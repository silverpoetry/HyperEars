package dev.hyperears.runtime

import android.app.Application
import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class AppleVerifiedModelCacheTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val address = "02:00:00:00:00:01"
    private fun confirmed(model: String) = EarbudState(address = address,
        lifecycle = DeviceLifecycle(SystemProfileState.CONNECTED, PrivateTransportState.CONNECTED, ProtocolHandshakeState.CONFIRMED))
        .withFeature(AppleDeviceInformationFeatureState("AirPods", model, "firmware"))

    @Test fun confirmedIdentitySurvivesManagerRecreationButUnpairForgetsIt() {
        val cache = AppleVerifiedModelCache(context)
        cache.observe(confirmed("A3441").copy(lifecycle = DeviceLifecycle(systemProfile = SystemProfileState.CONNECTING)))
        assertNull(cache.type(address))
        cache.observe(confirmed("A3441"))
        assertEquals("3020", AppleVerifiedModelCache(context).type(address))
        cache.forget(address)
        assertNull(AppleVerifiedModelCache(context).type(address))
    }

    @Test fun nativeModelBytesMatchStockModelsAndIgnoreFriendlyNames() {
        val cache = AppleVerifiedModelCache(context)
        cache.observe(confirmed("unknown").withFeature(AppleDeviceInformationFeatureState("A3441", "unknown", "firmware")))
        assertNull(cache.type(address))
        cache.observe(confirmed("A2084")); assertEquals("0E20", cache.type(address))
        cache.observe(confirmed("A3063")); assertEquals("2720", cache.type(address))
        cache.observe(confirmed("A3056")); assertEquals("1B20", cache.type(address))
    }
}
