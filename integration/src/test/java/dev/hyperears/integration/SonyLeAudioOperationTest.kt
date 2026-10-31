package dev.hyperears.integration
import org.junit.Assert.*
import org.junit.Test
class SonyLeAudioOperationTest {
    @Test fun operationRoundTripPreservesPendingAndNeverImpliesLinkSuccess() {
        for(phase in SonyLeAudioPhase.entries) {
            val value=SonyLeAudioOperation(phase,true,true,"test")
            val decoded=FeatureStateTransport.decode(FeatureStateTransport.encode(DeviceFeatureSnapshot(listOf(value))))?.get<SonyLeAudioOperation>()
            assertEquals(value,decoded)
            assertEquals(phase in setOf(SonyLeAudioPhase.WAITING_PROFILE,SonyLeAudioPhase.WAITING_HISTORY,SonyLeAudioPhase.WAITING_CONFIRMATION),decoded?.pending)
        }
    }
}
