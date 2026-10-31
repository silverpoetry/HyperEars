package dev.hyperears.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class RayNeoAppLabelsTest {
    @Test fun installedAppsUseTheirActualNames() {
        assertEquals("SmartTube", RayNeoAppLabels.label("app.smarttube.rayneo"))
        assertEquals("蓝莓投屏", RayNeoAppLabels.label("com.bluberry.aircast"))
        assertEquals("阅读·D", RayNeoAppLabels.label("io.legado.app.debug"))
    }
    @Test fun unknownPackagesArePreservedRatherThanGuessed() {
        assertEquals("org.example.newapp", RayNeoAppLabels.label("org.example.newapp"))
        assertEquals("com.mb.android.beta", RayNeoAppLabels.label("com.mb.android.beta"))
    }
}
