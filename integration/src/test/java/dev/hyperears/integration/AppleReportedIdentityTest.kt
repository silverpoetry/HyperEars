package dev.hyperears.integration

import org.junit.Assert.*
import org.junit.Test

class AppleReportedIdentityTest {
    @Test fun reportedIdentityIsInformationalAndResetRemovesIt() {
        val adapter=AppleAirPodsAdapter()
        adapter.receive(info("My earbuds","A3441"))
        val feature=adapter.runtimeState().features.get<AppleDeviceInformationFeatureState>()!!
        assertEquals("My earbuds",feature.reportedName)
        assertEquals("AIRPODS5_WIRELESS",feature.catalogModel!!.key)
        assertFalse(adapter.effectiveCapabilities().noiseControl)
        assertNull(adapter.miLinkCardPresentationId)
        val encoded=FeatureStateTransport.encode(adapter.runtimeState().features)
        assertEquals(feature,FeatureStateTransport.decode(encoded)!!.get<AppleDeviceInformationFeatureState>())
        adapter.receive(info("Unknown","unmapped-model"))
        assertNull(adapter.runtimeState().features.get<AppleDeviceInformationFeatureState>()!!.catalogModel)
        adapter.resetProtocolSession()
        assertNull(adapter.runtimeState().features.get<AppleDeviceInformationFeatureState>())
    }
    @Test fun malformedTailCannotPublishOrReplaceName() {
        val adapter=AppleAirPodsAdapter()
        val frame=info("Reported","A3441")
        adapter.receive(frame.dropLast(1).toByteArray())
        assertNull(adapter.runtimeState().features.get<AppleDeviceInformationFeatureState>())
        adapter.receive(frame.takeLast(1).toByteArray())
        assertEquals("Reported",adapter.runtimeState().features.get<AppleDeviceInformationFeatureState>()!!.reportedName)
    }
    private fun info(name:String,model:String):ByteArray {
        val fields=listOf(name,model,"","","9A1")+List(6){""}
        val body=fields.flatMap { (it.toByteArray(Charsets.UTF_8)+byteArrayOf(0)).toList() }.toByteArray()+ByteArray(34)+byteArrayOf(0,0)
        val size=body.size+2
        return byteArrayOf(4,0,4,0,0x1d,0,2,size.toByte(),(size shr 8).toByte(),4,0)+body
    }
}
