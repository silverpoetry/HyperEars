package dev.hyperears.bridge

import android.app.Application
import android.content.pm.PackageInfo
import android.os.Binder
import dev.hyperears.presentation.HeadsetConnection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class AppleNativeAdmissionContractTest {
    private val request = AppleNativeAdmissionContract.Request("02:00:00:00:00:01", HeadsetConnection("connection", 10), Binder())
    @Test fun malformedDecisionNeverBecomesDecline() {
        val valid = AppleNativeAdmissionContract.decision(request, false, 1)
        assertEquals(false, AppleNativeAdmissionContract.readDecision(valid)?.accepted)
        assertNull(AppleNativeAdmissionContract.readDecision(valid.putExtra("accepted", "false")))
        assertNull(AppleNativeAdmissionContract.readDecision(valid.putExtra("accepted", 0)))
        assertNull(AppleNativeAdmissionContract.readDecision(AppleNativeAdmissionContract.decision(request, false, 0)))
        assertNull(AppleNativeAdmissionContract.readDecision(AppleNativeAdmissionContract.decision(request, false, 2).putExtra("revision", "2")))
        assertNotNull(AppleNativeAdmissionContract.readDecision(AppleNativeAdmissionContract.decision(request, null, 3)))
    }
    @Test fun bothPlatformSenderPackageAndUidMustAuthenticate() {
        val context = RuntimeEnvironment.getApplication()
        val pm = shadowOf(context.packageManager)
        val expected = ModuleContract.NATIVE_HEADSET_PACKAGE
        pm.installPackage(PackageInfo().apply { packageName = expected })
        pm.setPackagesForUid(12345, expected)
        assertTrue(BroadcastIdentity.accepts(context, expected, 12345, expected))
        assertFalse(BroadcastIdentity.accepts(context, "other", 12345, expected))
        assertFalse(BroadcastIdentity.accepts(context, expected, 54321, expected))
        assertFalse(BroadcastIdentity.accepts(context, null, -1, expected))
    }
}
