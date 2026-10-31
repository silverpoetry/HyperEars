package dev.hyperears.protocol.sony

/** SonyPods 3f701ff GATT preparation, isolated from Android and any connection ownership. */
class SonyGattSetup {
    enum class Action { PROBE, WAIT_PROBE, READ_LENGTH, SUBSCRIBE_DATA, READY, FAILED }
    private var stage:Action?=null
    private var closed=false
    private var probePresent:Boolean?=null
    private var mtu:Int?=null
    private var earlyProbeReady=false
    var fragmentLimit:Int?=null
        private set

    fun services(hasProbe:Boolean):Action? {
        if(closed || stage!=null)return null
        probePresent=hasProbe
        return start()
    }
    fun mtuSettled(value:Int):Action? {
        if(closed || stage!=null)return null
        if(value !in 23..517)return fail()
        mtu=value
        return start()
    }
    private fun start():Action? {
        if(mtu==null || probePresent==null)return null
        return move(if(probePresent==true)Action.PROBE else Action.READ_LENGTH)
    }
    fun probeSubscribed(success:Boolean):Action? {
        if(closed || stage!=Action.PROBE)return null
        return move(if(!success || earlyProbeReady)Action.READ_LENGTH else Action.WAIT_PROBE)
    }
    fun probeNotification(value:ByteArray):Action? {
        if(closed || stage !in setOf(Action.PROBE,Action.WAIT_PROBE) || !value.contentEquals(byteArrayOf(1)))return null
        earlyProbeReady=true
        return if(stage==Action.WAIT_PROBE)move(Action.READ_LENGTH) else null
    }
    fun timeout(expected:Action):Action? {
        if(closed || stage!=expected)return null
        return when(expected) {
            Action.PROBE,Action.WAIT_PROBE -> move(Action.READ_LENGTH)
            Action.READ_LENGTH,Action.SUBSCRIBE_DATA -> fail()
            else -> null
        }
    }
    fun length(value:ByteArray):Action? {
        if(closed || stage!=Action.READ_LENGTH)return null
        if(value.size!=2)return fail()
        val length=((value[0].toInt() and 255) shl 8) or (value[1].toInt() and 255)
        // Out-of-window positive values are advisory; cap fragments at the actual ATT payload.
        // Zero cannot form a progressing write, so fail instead of looping or dividing by zero.
        if(length==0)return fail()
        fragmentLimit=minOf(length,requireNotNull(mtu)-3)
        return move(Action.SUBSCRIBE_DATA)
    }
    fun dataSubscribed(success:Boolean):Action? {
        if(closed || stage!=Action.SUBSCRIBE_DATA)return null
        return if(success)move(Action.READY) else fail()
    }
    fun fragments(frame:ByteArray):List<ByteArray> {
        check(!closed && stage==Action.READY) { "Sony GATT is not ready" }
        val limit=requireNotNull(fragmentLimit)
        return frame.asList().chunked(limit).map {it.toByteArray()}
    }
    fun close() {closed=true;fragmentLimit=null}
    private fun move(next:Action):Action {stage=next;return next}
    private fun fail():Action {closed=true;fragmentLimit=null;return move(Action.FAILED)}
}
