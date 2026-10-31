package dev.hyperears.runtime

import android.bluetooth.*
import android.content.Context
import dev.hyperears.integration.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.yield
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothGatt
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], manifest=Config.NONE)
class GattMtuNegotiationTest {
    private class Fixture(requested: Int? = 220, minimum: Int = 48) {
        private val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("AA:BB:CC:DD:EE:02")
        private fun uuid(short: String) = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
        private val service = BluetoothGattService(uuid("4d45"),BluetoothGattService.SERVICE_TYPE_PRIMARY)
        private val tx = BluetoothGattCharacteristic(uuid("b611"),BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,BluetoothGattCharacteristic.PERMISSION_WRITE)
        private val rx = BluetoothGattCharacteristic(uuid("b612"),BluetoothGattCharacteristic.PROPERTY_NOTIFY,BluetoothGattCharacteristic.PERMISSION_READ)
        private val spec = GattTransportSpec(serviceUuid=service.uuid.toString(),writeCharacteristicUuid=tx.uuid.toString(),
            notifyCharacteristicUuid=rx.uuid.toString(),writeMode=GattWriteMode.WITHOUT_RESPONSE,
            notificationsRequired=true,requestedMtu=requested,minimumMtu=minimum,id="test-mtu")
        private val type = Class.forName("dev.hyperears.runtime.AndroidGattChannel")
        val channel = type.getDeclaredConstructor(Context::class.java,BluetoothDevice::class.java,GattTransportSpec::class.java)
            .apply { isAccessible=true }.newInstance(RuntimeEnvironment.getApplication(),device,spec) as EarbudChannel
        fun field(name:String) = type.getDeclaredField(name).apply { isAccessible=true }
        val callback = field("callback").get(channel) as BluetoothGattCallback
        val gatt = ShadowBluetoothGatt.newInstance(device)
        val completion = field("connectCompletion").get(channel) as CompletableDeferred<*>
        init {
            rx.addDescriptor(BluetoothGattDescriptor(uuid("2902"),BluetoothGattDescriptor.PERMISSION_WRITE))
            service.addCharacteristic(tx);service.addCharacteristic(rx)
            field("gatt").set(channel,gatt)
            shadowOf(gatt).apply { setGattCallback(callback);addDiscoverableService(service);allowCharacteristicNotification(rx) }
        }
        fun connect() = callback.onConnectionStateChange(gatt,BluetoothGatt.GATT_SUCCESS,BluetoothProfile.STATE_CONNECTED)
    }
    @Test fun tozoNegotiatesBeforeCompletingNotifications() {
        val f=Fixture();f.connect()
        assertEquals(220,f.field("settledMtu").get(f.channel))
        assertTrue(f.completion.isCompleted);assertFalse(f.completion.isCancelled)
        f.channel.close()
    }
    @Test fun atomicEqWriteFitsButOversizedWriteCannotBeSilentlyTruncated() = runBlocking {
        val f=Fixture();f.connect()
        val frame=ByteArray(45) { it.toByte() }
        f.channel.write(frame)
        assertArrayEquals(frame,shadowOf(f.gatt).latestWrittenBytes)
        var rejected=false
        try { f.channel.write(ByteArray(218)) } catch (_:java.io.IOException) { rejected=true }
        assertTrue(rejected)
        assertArrayEquals(frame,shadowOf(f.gatt).latestWrittenBytes)
        f.channel.close()
    }
    @Test fun oldAdaptersKeepTheNoMtuRequestPath() {
        val f=Fixture(null,23);f.connect()
        assertNull(f.field("settledMtu").get(f.channel))
        assertTrue(f.completion.isCompleted);assertFalse(f.completion.isCancelled)
        f.channel.close()
    }
    @Test fun undersizedMtuFailsBeforeDiscoveryAndClosedCallbacksDoNotRestart() {
        val f=Fixture()
        f.callback.onMtuChanged(f.gatt,23,BluetoothGatt.GATT_SUCCESS)
        assertTrue(f.completion.isCancelled);assertTrue(shadowOf(f.gatt).isClosed)
        f.callback.onMtuChanged(f.gatt,220,BluetoothGatt.GATT_SUCCESS)
        f.connect()
        assertFalse((f.field("discoveryStarted").get(f.channel) as java.util.concurrent.atomic.AtomicBoolean).get())
    }
}
