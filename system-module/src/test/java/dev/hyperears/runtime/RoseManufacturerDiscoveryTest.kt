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
