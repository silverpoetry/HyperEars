package dev.hyperears.integration
import dev.hyperears.protocol.sony.SonyHeadphonesWireCodec as Wire
import dev.hyperears.protocol.sony.SonyVendorToolsCodec
import org.junit.Assert.*
import org.junit.Test
class SonyVendorToolsTest {
    private fun b(vararg p:Int)=p.map(Int::toByte).toByteArray()
    @Test fun actualV1FunctionOnlyAnnotatesWithoutQueriesOrPrivateCapabilities() {
        val a=requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName="WH-1000XM4",standardHeadset=true)))
        a.beginHandshake()
        fun report(table2:Boolean=false,vararg p:Int)=a.receive(Wire.encode(if(table2)Wire.MessageType.COMMAND_2 else Wire.MessageType.COMMAND_1,0,b(*p)))
        report(p=intArrayOf(1,0,1,0));report(true,7,0,1,0x81)
        assertNull(a.runtimeState().features.get<SonyVendorToolsFeatureState>())
        report(p=intArrayOf(7,0,1,0x81))
        assertEquals(SonyVendorToolsFeatureState(1,true),a.runtimeState().features.get<SonyVendorToolsFeatureState>())
        assertNull(a.miLinkCardPresentationId);assertFalse(a.effectiveCapabilities().noiseControl)
        var ack=1
        val commands=buildList<Wire.Frame>{repeat(25){val r=a.receive(Wire.encode(Wire.MessageType.ACK,ack));ack=1-ack;r.commands.forEach{addAll(Wire.Decoder().offer(it))}}}
        assertFalse(commands.any{it.payload.firstOrNull()?.toInt()?.and(255) in 0x80..0x89})
        report(p=intArrayOf(7,0,2,0x81));assertTrue(a.runtimeState().features.get<SonyVendorToolsFeatureState>()!!.optimizerAvailable)
        report(p=intArrayOf(7,0,0));assertFalse(a.runtimeState().features.get<SonyVendorToolsFeatureState>()!!.optimizerAvailable)
        a.resetProtocolSession();assertNull(a.runtimeState().features.get<SonyVendorToolsFeatureState>())
        a.beginHandshake();report(p=intArrayOf(1,0,3,0,0,0,0,0));report(p=intArrayOf(7,0,1,0x81,0))
        assertNull(a.runtimeState().features.get<SonyVendorToolsFeatureState>())
    }
    @Test fun byte81IsNotDecimal81AndMalformedCountDoesNotRevokeEvidence() {
        assertEquals(true,SonyVendorToolsCodec.optimizerFunction(b(7,0,1,0x81)))
        assertEquals(false,SonyVendorToolsCodec.optimizerFunction(b(7,0,1,81)))
        assertNull(SonyVendorToolsCodec.optimizerFunction(b(7,0,2,0x81)))
        assertNull(SonyVendorToolsCodec.optimizerFunction(b(7,0,1,0x81,0)))
    }
}
