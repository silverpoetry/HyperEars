package dev.hyperears.hook
import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test
class SonyVendorToolsPolicyTest {
    @Test fun annotationRequiresConfirmedV1AndSonyAppAndOldAnnotationCannotBecomeGenericLaunch() {
        val base=EarbudState(adapter=StandardEarbudAdapter().snapshot().copy(controlApps=listOf(ControlAppCatalog.sonySoundConnect)))
        assertNull(SonyVendorToolsPolicy.annotation(base))
        val detected=base.withFeature(SonyVendorToolsFeatureState(1,true))
        val annotation=SonyVendorToolsPolicy.annotation(detected)!!
        assertTrue(annotation.title.contains("降噪优化"));assertTrue(SonyVendorToolsPolicy.current(detected,annotation))
        assertFalse(SonyVendorToolsPolicy.current(base,annotation))
        assertFalse(SonyVendorToolsPolicy.current(base.withFeature(SonyVendorToolsFeatureState(2,true)),annotation))
        assertFalse(SonyVendorToolsPolicy.current(detected.copy(adapter=base.adapter!!.copy(controlApps=listOf(ControlAppCatalog.huaweiSmartAudio))),annotation))
        assertTrue(SonyVendorToolsPolicy.current(base,null)) // Existing generic handoff remains usable.
    }
}
