package dev.hyperears.runtime

import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import dev.hyperears.integration.*
import dev.hyperears.protocol.rose.RoseRsV2IdentityCodec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class)
class RoseManufacturerDiscoveryTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    private val address="02:00:00:00:00:11"
    private val adapter get()=context.getSystemService(BluetoothManager::class.java).adapter
    private fun device():BluetoothDevice {
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        return adapter.getRemoteDevice(address).also { shadowOf(it).setBondState(BluetoothDevice.BOND_BONDED) }
    }
    private val identity=EarbudIdentity("ROSE unclassified",true,deviceAddress=address)
    private fun result(device:BluetoothDevice,time:Long=SystemClock.elapsedRealtimeNanos()):ScanResult =
        ScanResult(device,ReflectionHelpers.callStaticMethod<ScanRecord>(ScanRecord::class.java,"parseFromBytes",ClassParameter.from(ByteArray::class.java,byteArrayOf(5,0xFF.toByte(),0x0B,0x0E,0x0A,0x0C,0))), -40,time)
    private fun idle(){shadowOf(Looper.getMainLooper()).idle()}
    @Test fun realScannerRejectsNearbyPreScanAndUnbondThenCancelsWithoutRepeating() {
        val device=device();var accepted=0
        val discovery=RoseManufacturerDiscovery(context)
        discovery.observe(device,identity,"connection",{true}){accepted++};idle()
        val scanner=shadowOf(adapter.bluetoothLeScanner)
        val callback=scanner.scanCallbacks.single()
        callback.onScanResult(1,result(adapter.getRemoteDevice("02:00:00:00:00:12")));idle();assertEquals(0,accepted)
        callback.onScanResult(1,result(device,-1));idle();assertEquals(0,accepted)
        shadowOf(device).setBondState(BluetoothDevice.BOND_NONE)
        callback.onScanResult(1,result(device));idle();assertEquals(0,accepted);assertTrue(scanner.scanCallbacks.isEmpty())
        shadowOf(device).setBondState(BluetoothDevice.BOND_BONDED)
        discovery.observe(device,identity,"connection",{true}){accepted++};idle();assertTrue(scanner.scanCallbacks.isEmpty())
        discovery.close();idle()
    }
    @Test fun actualManagerRefinesThenRetainsIdentityThroughRepeatObservationPolicyAndHandoff() {
        val device=device()
        EarbudConnectionManager(context).use { manager ->
            manager.onPhysicalConnected(address);manager.observeDevice(device,identity);idle()
            val scanner=shadowOf(adapter.bluetoothLeScanner)
            val callback=scanner.scanCallbacks.single()
            callback.onScanResult(1,result(device));idle()
            val expected=RoseRsV2IdentityCodec.Model.CERAMICS_MK2.id
            assertEquals(expected,manager.snapshots().single().state.adapter!!.id)
            manager.observeDevice(device,identity);idle()
            manager.updateDisabledAdapters(setOf("unrelated-adapter"));idle()
            assertEquals(expected,manager.snapshots().single().state.adapter!!.id)
            manager.updateControlAppPresence(setOf(ControlAppCatalog.roseLink.packageName));idle()
            manager.updateControlAppPresence(emptySet());idle()
            manager.updateDisabledAdapters(emptySet());idle()
            assertEquals(expected,manager.snapshots().single().state.adapter!!.id)
            assertTrue(scanner.scanCallbacks.isEmpty())
            manager.unregisterDevice(device);idle();manager.onPhysicalConnected(address);manager.observeDevice(device,identity);idle()
            assertNotEquals(expected,manager.snapshots().single().state.adapter!!.id)
            assertEquals(1,scanner.scanCallbacks.size)
            manager.claimSystemOwnership(address);idle();assertTrue(scanner.scanCallbacks.isEmpty())
            callback.onScanResult(1,result(device));idle()
            assertFalse(manager.snapshots().single().state.sessionActive)
        };idle()
    }
    @Test fun actualManagerRefinesThreeByteZtIdentityAndHonorsDisabledExactAdapter() {
        for (disabled in listOf(false, true)) {
            val device = device()
            EarbudConnectionManager(context).use { manager ->
                if (disabled) manager.updateDisabledAdapters(setOf("rose-zt-i3"))
                manager.onPhysicalConnected(address); manager.observeDevice(device, identity); idle()
                val scanner = shadowOf(adapter.bluetoothLeScanner)
                val callback = scanner.scanCallbacks.single()
                val malformed = ReflectionHelpers.callStaticMethod<ScanRecord>(ScanRecord::class.java, "parseFromBytes",
                    ClassParameter.from(ByteArray::class.java, byteArrayOf(4, 0xFF.toByte(), 0xF1.toByte(), 2, 3,
                        3, 0xFF.toByte(), 0x42, 0, 0)))
                callback.onScanResult(1, ScanResult(device, malformed, -40, SystemClock.elapsedRealtimeNanos())); idle()
                assertNotEquals("rose-zt-i3", manager.snapshots().single().state.adapter!!.id)
                assertEquals(1, scanner.scanCallbacks.size)
                val record = ReflectionHelpers.callStaticMethod<ScanRecord>(ScanRecord::class.java, "parseFromBytes",
                    ClassParameter.from(ByteArray::class.java, byteArrayOf(4, 0xFF.toByte(), 0xF1.toByte(), 2, 3, 0)))
                callback.onScanResult(1, ScanResult(device, record, -40, SystemClock.elapsedRealtimeNanos())); idle()
                if (disabled) assertNotEquals("rose-zt-i3", manager.snapshots().single().state.adapter!!.id)
                else {
                    assertEquals("rose-zt-i3", manager.snapshots().single().state.adapter!!.id)
                    manager.observeDevice(device, identity); idle()
                    assertEquals("rose-zt-i3", manager.snapshots().single().state.adapter!!.id)
                }
                assertTrue(scanner.scanCallbacks.isEmpty())
            }; idle()
        }
    }

    @Test fun sourceDialectRefinementReplacesSessionEvenWhenAdapterIdIsUnchanged() {
        val device=device(); val named=identity.copy(deviceName="ROSE Ceramics U")
        EarbudConnectionManager(context).use { manager ->
            manager.onPhysicalConnected(address); manager.observeDevice(device,named); idle()
            val before=manager.snapshots().single()
            assertEquals("rose-ceramics-u",before.state.adapter!!.id)
            val scanner=shadowOf(adapter.bluetoothLeScanner)
            val callback=scanner.scanCallbacks.single {it.javaClass.name.startsWith(RoseManufacturerDiscovery::class.java.name)}
            val record=ReflectionHelpers.callStaticMethod<ScanRecord>(ScanRecord::class.java,"parseFromBytes",
                ClassParameter.from(ByteArray::class.java,byteArrayOf(5,0xff.toByte(),0x0b,0x0d,0x0a,0x0b,0)))
            callback.onScanResult(1,ScanResult(device,record,-40,SystemClock.elapsedRealtimeNanos())); idle()
            val after=manager.snapshots().single()
            assertEquals(before.state.adapter!!.id,after.state.adapter!!.id)
            assertNotEquals(before.sessionToken,after.sessionToken)
            assertFalse(after.state.adapter!!.capabilities.noiseControl)
            manager.observeDevice(device,named); idle()
            assertEquals(after.sessionToken,manager.snapshots().single().sessionToken)
        }; idle()
    }

    @Test fun sharedCeramicsXNameCanRefineOriginalCandidateTo7034OnlyWithItsPrefix() {
        val device=device(); val sharedName=identity.copy(deviceName="ROSE CERAMICS X")
        EarbudConnectionManager(context).use { manager ->
            manager.onPhysicalConnected(address); manager.observeDevice(device,sharedName); idle()
            assertEquals("rose-luli-x-gatt",manager.snapshots().single().state.adapter!!.id)
            val scanner=shadowOf(adapter.bluetoothLeScanner)
            val callback=scanner.scanCallbacks.single { it.javaClass.name.startsWith(RoseManufacturerDiscovery::class.java.name) }
            val record=ReflectionHelpers.callStaticMethod<ScanRecord>(ScanRecord::class.java,"parseFromBytes",
                ClassParameter.from(ByteArray::class.java,byteArrayOf(5,0xff.toByte(),0x18,0x84.toByte(),1,0x12,0)))
            callback.onScanResult(1,ScanResult(device,record,-40,SystemClock.elapsedRealtimeNanos())); idle()
            assertEquals("rose-legacy-ceramics_x_7034",manager.snapshots().single().state.adapter!!.id)
            assertFalse(callback in scanner.scanCallbacks)
        }; idle()
    }

    @Test fun unbrandedKnownNameRequiresSameDeviceManufacturerEvidence() {
        val device=device()
        val unbranded=identity.copy(deviceName="SWIFT")
        assertTrue(RoseManufacturerDiscovery.candidate(unbranded))
        assertFalse(RoseManufacturerDiscovery.candidate(unbranded.copy(nativeSystemEarbud=true)))
        assertFalse(RoseManufacturerDiscovery.candidate(unbranded.copy(standardHeadset=false)))
        EarbudConnectionManager(context).use { manager ->
            manager.onPhysicalConnected(address); manager.observeDevice(device, unbranded); idle()
            assertNotEquals("rose-legacy-swift", manager.snapshots().single().state.adapter!!.id)
            val scanner=shadowOf(adapter.bluetoothLeScanner)
            val callback=scanner.scanCallbacks.single()
            val record=ReflectionHelpers.callStaticMethod<ScanRecord>(ScanRecord::class.java,"parseFromBytes",
                ClassParameter.from(ByteArray::class.java,byteArrayOf(5,0xff.toByte(),0x0b,0x0b,0x0a,0x0b,0)))
            callback.onScanResult(1,ScanResult(device,record,-40,SystemClock.elapsedRealtimeNanos())); idle()
            assertEquals("rose-legacy-swift", manager.snapshots().single().state.adapter!!.id)
            assertTrue(scanner.scanCallbacks.isEmpty())
        }; idle()
    }

    @Test fun timeoutAndOwnerCancellationStopRealScansAndRejectLateCallbacks() {
        val device=device();var eligible=true;var accepted=0
        val discovery=RoseManufacturerDiscovery(context)
        discovery.observe(device,identity,"one",{eligible}){accepted++};idle()
        val scanner=shadowOf(adapter.bluetoothLeScanner);val old=scanner.scanCallbacks.single()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        assertTrue(scanner.scanCallbacks.isEmpty());old.onScanResult(1,result(device));idle();assertEquals(0,accepted)
        discovery.observe(device,identity,"two",{eligible}){accepted++};idle()
        assertEquals(1,scanner.scanCallbacks.size);eligible=false;discovery.cancel(address);idle()
        assertTrue(scanner.scanCallbacks.isEmpty());discovery.close();idle()
    }
}
