package dev.hyperears.runtime

import android.bluetooth.*
import android.content.Context
import dev.hyperears.integration.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
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
@Config(sdk=[35],manifest=Config.NONE)
class SonyGattChannelTest {
    private fun uuid(code:String)=UUID.fromString("5b833$code-6bc7-4802-8e9a-723ceca4bd8f")
    @Test fun actualChannelPreparesAndSplitsWritesBeforeCompletingConnection()=runBlocking {
        val device=BluetoothAdapter.getDefaultAdapter().getRemoteDevice("AA:BB:CC:DD:EE:01")
        val service=BluetoothGattService(uuid("e20"),BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val tx=BluetoothGattCharacteristic(uuid("c60"),BluetoothGattCharacteristic.PROPERTY_WRITE,BluetoothGattCharacteristic.PERMISSION_WRITE)
        val rx=BluetoothGattCharacteristic(uuid("c61"),BluetoothGattCharacteristic.PROPERTY_NOTIFY,BluetoothGattCharacteristic.PERMISSION_READ)
        rx.addDescriptor(BluetoothGattDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),BluetoothGattDescriptor.PERMISSION_WRITE))
        val length=BluetoothGattCharacteristic(uuid("c91"),BluetoothGattCharacteristic.PROPERTY_READ,BluetoothGattCharacteristic.PERMISSION_READ)
        @Suppress("DEPRECATION")
        length.value=byteArrayOf(0,7)
        service.addCharacteristic(tx);service.addCharacteristic(rx);service.addCharacteristic(length)
        val spec=GattTransportSpec(serviceUuid=service.uuid.toString(),writeCharacteristicUuid=tx.uuid.toString(),notifyCharacteristicUuid=rx.uuid.toString(),notificationsRequired=true,
            tandemPreparation=GattTandemPreparationSpec(service.uuid.toString(),length.uuid.toString(),uuid("c93").toString()),id="test-sony")
        val type=Class.forName("dev.hyperears.runtime.AndroidGattChannel")
        val channel=type.getDeclaredConstructor(Context::class.java,BluetoothDevice::class.java,GattTransportSpec::class.java).apply{isAccessible=true}.newInstance(RuntimeEnvironment.getApplication(),device,spec) as EarbudChannel
        fun field(name:String)=type.getDeclaredField(name).apply{isAccessible=true}
        val callback=field("callback").get(channel) as BluetoothGattCallback
        val gatt=ShadowBluetoothGatt.newInstance(device)
        field("gatt").set(channel,gatt)
        val shadow=shadowOf(gatt)
        shadow.setGattCallback(callback);shadow.addDiscoverableService(service);shadow.allowCharacteristicNotification(rx)
        val completion=field("connectCompletion").get(channel) as CompletableDeferred<*>
        callback.onConnectionStateChange(gatt,BluetoothGatt.GATT_SUCCESS,BluetoothProfile.STATE_CONNECTED)
        assertNotNull("Actual connection must initiate MTU negotiation",field("settledMtu").get(channel))
        assertTrue(completion.isCompleted)
        assertFalse(completion.isCancelled)
        val bytes=ByteArray(31){it.toByte()}
        channel.write(bytes)
        assertArrayEquals(bytes.takeLast(3).toByteArray(),shadow.latestWrittenBytes)
        @Suppress("UNCHECKED_CAST")
        val incoming=field("incoming").get(channel) as kotlinx.coroutines.channels.Channel<ByteArray>
        callback.onCharacteristicChanged(gatt,length,byteArrayOf(99))
        assertTrue(incoming.tryReceive().isFailure)
        callback.onCharacteristicChanged(gatt,rx,byteArrayOf(42))
        assertArrayEquals(byteArrayOf(42),incoming.tryReceive().getOrThrow())
        channel.close()
        assertTrue(shadow.isClosed)
    }
}
