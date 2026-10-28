package dev.hyperears.integration

import dev.hyperears.protocol.rose.RoseLegacyIdentityCodec.Model
import org.junit.Assert.*
import org.junit.Test

class RoseLegacyAdapterTest {
    private fun report(type:Int,value:Int):ByteArray {
        val body=byteArrayOf(0xdd.toByte(),0,2,type.toByte(),value.toByte())
        return body+byteArrayOf((body.sumOf {it.toInt() and 255} and 255).toByte(),0xaa.toByte())
    }
    @Test fun endpointAndReportContractsRemainModelSpecific() {
        Model.entries.forEach { model->
            val adapter=RoseLegacyAdapter(model)
            assertEquals(model.spp,adapter.transports.first() is RfcommEndpointSpec.ServiceUuid)
            assertFalse(adapter.effectiveCapabilities().noiseControl)
            if(!model.spp) {
                assertEquals(listOf(GattWriteMode.WITHOUT_RESPONSE,GattWriteMode.WITH_RESPONSE),adapter.transports.map {(it as GattTransportSpec).writeMode})
                assertEquals(2,adapter.transports.map {it.id}.distinct().size)
            }
            if(!model.f8){
                adapter.receive(report(14,1))
                val request=RoseLegacyControlRequest.SetValue(model.name,RoseLegacySetting.GAME,0)
                assertTrue(adapter.executeControl(request).accepted)
                assertFalse(adapter.executeControl(request.copy(expectedModel="foreign")).accepted)
                assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
                adapter.receive(report(9,1))
                assertEquals(model in setOf(Model.BUDSFREE,Model.CAMBRIAN),adapter.effectiveCapabilities().noiseControl)
                adapter.resetProtocolSession();assertFalse(adapter.executeControl(request).accepted)
            }
        }
    }
    @Test fun explorerOffUsesRawZeroAndNeverCeramicsRawThreeOrWind() {
        val adapter=RoseLegacyAdapter(Model.EXPLORER)
        adapter.receive(byteArrayOf(0,0x27,2,0,3,12,1,3))
        assertFalse(adapter.effectiveCapabilities().noiseControl)
        adapter.receive(byteArrayOf(0,0x27,2,0,3,12,1,1))
        assertEquals(NoiseMode.ANC,adapter.runtimeState().noiseMode)
        assertArrayEquals(byteArrayOf(0,0x2c,1,0,1,0),adapter.protocolSession.encode(StandardControlRequest.SetNoiseMode(NoiseMode.OFF)).single())
        assertTrue(adapter.protocolSession.encode(StandardControlRequest.SetNoiseMode(NoiseMode.WIND)).isEmpty())
        adapter.receive(byteArrayOf(0,0x27,2,0,4,4,2,1,2))
        assertNull(adapter.runtimeState().features.get<RoseLegacySettingsFeatureState>())
        adapter.receive(byteArrayOf(0,0x27,2,0,4,4,2,0,2))
        assertEquals(2,adapter.runtimeState().features.get<RoseLegacySettingsFeatureState>()!!.values[RoseLegacySetting.EQ])
    }
    @Test fun batteryUsesThirdComponentForHeadphonesAndChargingBitsOnlyForEarbuds() {
        fun battery(left:Int,right:Int,third:Int):ByteArray {
            val body=byteArrayOf(0xdd.toByte(),0,4,12,left.toByte(),right.toByte(),third.toByte())
            return body+byteArrayOf((body.sumOf {it.toInt() and 255} and 255).toByte(),0xaa.toByte())
        }
        for(model in listOf(Model.SWIFT,Model.CAMBRIAN)) {
            val adapter=RoseLegacyAdapter(model)
            adapter.receive(battery(128+70,128+60,50))
            val result=adapter.runtimeState().battery
            assertEquals(50,result.overall.percent);assertFalse(result.overall.charging)
            assertFalse(result.left.available);assertFalse(result.case.available)
            adapter.receive(battery(70,60,255))
            assertEquals(50,adapter.runtimeState().battery.overall.percent)
        }
        val tws=RoseLegacyAdapter(Model.OPENFEEL)
        tws.receive(battery(128+70,128+60,50))
        val result=tws.runtimeState().battery
        assertEquals(70,result.left.percent);assertTrue(result.left.charging)
        assertEquals(60,result.right.percent);assertTrue(result.right.charging)
        assertEquals(50,result.case.percent);assertFalse(result.case.charging)
    }

}
