package dev.hyperears.integration

import dev.hyperears.protocol.rose.RoseRsV2IdentityCodec.Model
import org.junit.Assert.*
import org.junit.Test

class RoseSourceQualifiedAdaptersTest {
    @Test fun exactPrefixesQualifySameClassesAndIdsWithoutNameOrStaticControls() {
        for(m in Model.entries.filter { it.existingAdapterId != null }) {
            val id=identity(m)
            val resolved=EarbudAdapterRegistry.resolve(id)!!
            assertEquals(m.id,resolved.id)
            assertEquals(m,(resolved as RoseBudsFeelProtocolFamilyAdapter).sourceProfile)
            assertFalse(resolved.supportsControl(RoseRsV2ControlRequest.SetGame(true)))
            assertNull(resolved.miLinkCardPresentationId)
            resolved.receive(frame(14,0));assertTrue(resolved.executeControl(RoseRsV2ControlRequest.SetGame(true)).accepted)
            resolved.resetProtocolSession();assertFalse(resolved.executeControl(RoseRsV2ControlRequest.SetGame(true)).accepted)
        }
    }
    @Test fun sourceCeramicsUDistinguishesTouch07FromLanguage31AndLegacyKeepsOldMeaning() {
        val old=RoseCeramicsUAdapter();old.receive(legacyFrame(7,1))
        assertEquals(1,old.runtimeState().features.get<RoseRfcommSettingsFeatureState>()!!.values[RoseRfcommSetting.PROMPT_LANGUAGE])
        assertNull(old.runtimeState().features.get<RoseRsV2SettingsFeatureState>())
        val qualified=RoseSourceQualifiedAdapters.qualify(RoseCeramicsUAdapter(),identity(Model.CERAMICS_U_SOURCE))
        qualified.receive(legacyFrame(7,1))
        assertNull(qualified.runtimeState().features.get<RoseRsV2SettingsFeatureState>())
        qualified.receive(frame(7,1)+frame(0x31,0))
        val feature=qualified.runtimeState().features.get<RoseRsV2SettingsFeatureState>()!!
        assertEquals(true,feature.touchEnabled);assertEquals(0,feature.language)
        assertNull(qualified.runtimeState().features.get<RoseRfcommSettingsFeatureState>())
        assertFalse(qualified.executeControl(RoseRfcommControlRequest.SetValue(RoseRfcommSetting.PROMPT_LANGUAGE,0)).accepted)
    }
    @Test fun nameOnlyAndMalformedOrConflictingIdentityNeverSelectSourceDialect() {
        val old=RoseEarfeelI7Adapter()
        assertSame(old,RoseSourceQualifiedAdapters.qualify(old,EarbudIdentity("EarFeel i7",true)))
        val conflict=identity(Model.EARFEEL_I7_SOURCE).copy(manufacturerData=identity(Model.EARFEEL_I7_SOURCE).manufacturerData+identity(Model.FURINA_SOURCE).manufacturerData)
        assertFalse(old.matches(conflict));assertSame(old,RoseSourceQualifiedAdapters.qualify(old,conflict))
        assertFalse(old.matches(identity(Model.EARFEEL_I7_SOURCE).copy(nativeSystemEarbud=true)))
        assertNotEquals(old.id,EarbudAdapterRegistry.resolve(identity(Model.EARFEEL_I7_SOURCE),setOf(old.id))?.id)
        val source=RoseEarfeelI7Adapter(sourceQualified=true)
        source.receive(frame(9,1)+frame(0x2C,1)+frame(8,0))
        assertTrue(source.executeControl(RoseRsV2ControlRequest.SetNoiseLevel(Model.EARFEEL_I7_SOURCE.name,false,5)).accepted)
        assertTrue(source.executeControl(RoseRsV2ControlRequest.SetWearDetection(Model.EARFEEL_I7_SOURCE.name,false)).accepted)
        val mk2=RoseBudsFeelMk2Adapter(sourceQualified=true);mk2.receive(frame(9,1)+frame(0x2C,1))
        assertFalse(mk2.executeControl(RoseRsV2ControlRequest.SetNoiseLevel(Model.BUDSFEEL_MK2_SOURCE.name,false,5)).accepted)
    }
    private fun identity(m:Model):EarbudIdentity {
        val b=m.prefix.chunked(2).map { it.toInt(16) }
        return EarbudIdentity("renamed",true,manufacturerData=mapOf((b[0] or (b[1] shl 8)) to b.drop(2)))
    }
    private fun frame(type:Int,value:Int):ByteArray {
        val b=byteArrayOf(0xDD.toByte(),0,2,type.toByte(),value.toByte())
        return b+byteArrayOf(b.sumOf{it.toInt() and 255}.toByte(),0xAA.toByte())
    }
    private fun legacyFrame(type:Int,value:Int):ByteArray {
        val b=byteArrayOf(0xDD.toByte(),0,3,2,type.toByte(),value.toByte())
        return b+byteArrayOf(b.sumOf{it.toInt() and 255}.toByte(),0xAA.toByte())
    }

}
