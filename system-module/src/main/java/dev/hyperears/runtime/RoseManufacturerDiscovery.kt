package dev.hyperears.runtime

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.util.size
import dev.hyperears.integration.EarbudIdentity
import dev.hyperears.protocol.rose.RoseRsV2IdentityCodec
import dev.hyperears.protocol.rose.RoseManufacturerIdentityCodec
import java.util.Locale

/** One bounded observation per physical connection; never associates different Bluetooth addresses. */
@SuppressLint("MissingPermission")
internal class RoseManufacturerDiscovery(context:Context) : AutoCloseable {
    private val handler=Handler(Looper.getMainLooper())
    private val manager=context.getSystemService(BluetoothManager::class.java)
    private val attempts=mutableMapOf<String,Any>()
    private val active=mutableMapOf<String,()->Unit>()
    private var closed=false
    fun observe(device:BluetoothDevice, identity:EarbudIdentity, connection:Any,
        eligible:()->Boolean, accepted:(Map<Int,List<Int>>)->Unit) {
        if(!candidate(identity)) return
        handler.post {
            val address=runCatching { device.address.uppercase(Locale.ROOT) }.getOrNull() ?: return@post
            if(closed || attempts[address]==connection || !eligible()) return@post
            active.remove(address)?.invoke(); attempts[address]=connection
            val scanner=runCatching { if(device.bondState==BluetoothDevice.BOND_BONDED) manager?.adapter?.bluetoothLeScanner else null }.getOrNull() ?: return@post
            val startedAt=SystemClock.elapsedRealtimeNanos()
            val lease = RoseDiscoveryLease(address, startedAt)
            var finished=false
            lateinit var stop:()->Unit
            val callback=object:ScanCallback() {
                override fun onScanResult(callbackType:Int,result:ScanResult) = consume(result)
                override fun onBatchScanResults(results:MutableList<ScanResult>) { if(results.size<=32) results.forEach(::consume) }
                override fun onScanFailed(errorCode:Int) { handler.post { stop() } }
                private fun consume(result:ScanResult) { handler.post {
                    if(finished || closed || attempts[address]!=connection || !eligible()) { stop();return@post }
                    val observed=runCatching { result.device.address }.getOrNull() ?: return@post
                    val bonded=runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
                    if(!bonded){stop();return@post}
                    if(!lease.accepts(observed,result.timestampNanos,SystemClock.elapsedRealtimeNanos(),bonded,eligible())) return@post
                    val raw=result.scanRecord?.manufacturerSpecificData ?: return@post
                    if(raw.size !in 1..8) return@post
                    if ((0 until raw.size).any { raw.valueAt(it).size !in 1..512 }) return@post
                    val data=buildMap { for(i in 0 until raw.size) {
                        val value=raw.valueAt(i); put(raw.keyAt(i),value.map { it.toInt() and 255 })
                    } }
                    if(RoseManufacturerIdentityCodec.modelId(data)==null) return@post
                    stop()
                    if(eligible()) accepted(data.mapValues { it.value.toList() }.toMap())
                } }
            }
            val timeout=Runnable { stop() }
            stop={ if(!finished) { finished=true;lease.close();handler.removeCallbacks(timeout);runCatching { scanner.stopScan(callback) };active.remove(address) } }
            active[address]=stop
            runCatching { scanner.startScan(listOf(ScanFilter.Builder().setDeviceAddress(address).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),callback) }
                .onSuccess { handler.postDelayed(timeout,8_000) }.onFailure { stop() }
        }
    }
    fun cancel(address:String?, forget:Boolean=false) { handler.post {
        val keys=if(address==null) attempts.keys.toList() else listOf(address.uppercase(Locale.ROOT))
        keys.forEach { active.remove(it)?.invoke();if(forget)attempts.remove(it) }
    } }
    override fun close(){handler.post {closed=true;active.values.toList().forEach {it()};active.clear();attempts.clear()}}
    companion object {
        fun observationMatches(expected:String,observed:String,ageMs:Long)=expected.equals(observed,true)&&ageMs in 0..15_000
        fun candidate(identity:EarbudIdentity)=identity.standardHeadset && !identity.nativeSystemEarbud && identity.manufacturerData.isEmpty() &&
            (RoseManufacturerIdentityCodec.candidateName(identity.deviceName.orEmpty()) || identity.serviceUuids.any { it.equals(RoseRsV2IdentityCodec.SERVICE_UUID,true) } ||
                identity.deviceName.orEmpty().lowercase(Locale.ROOT).let { it.startsWith("rose ") || it.startsWith("roseselsa ") })
    }
}

/** Complete callback admission is shared with deterministic lifecycle tests. */
internal class RoseDiscoveryLease(private val address:String,private val startedAtNanos:Long) {
    private var closed=false
    fun accepts(observed:String,timestampNanos:Long,nowNanos:Long,bonded:Boolean,eligible:Boolean):Boolean =
        !closed && bonded && eligible && address.equals(observed,true) && timestampNanos>=startedAtNanos &&
            nowNanos-startedAtNanos in 0..8_000_000_000L && nowNanos-timestampNanos in 0..15_000_000_000L
    fun close(){closed=true}
}
