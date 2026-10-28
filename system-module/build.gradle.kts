plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseStorePath = providers.environmentVariable("HYPEREARS_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("HYPEREARS_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("HYPEREARS_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("HYPEREARS_KEY_PASSWORD").orNull
val releaseSigningConfigured = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "dev.hyperears"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.hyperears"
        minSdk = 35
        targetSdk = 36
        versionCode = 30100
        versionName = "3.1.0"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(releaseStorePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    packaging.resources.excludes += "/DebugProbesKt.bin"

    lint {
        // Android 16 is the deliberate deployment target for the current HyperOS device.
        disable += setOf("OldTargetApi", "ObsoleteSdkInt")
        warningsAsErrors = true
    }
}

dependencies {
    implementation(project(":integration"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.blur)
    implementation(libs.kotlinx.coroutines.android)
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.dexkit)

    // Debug-only host fixture: AGP generates real Preference/AppCompat resources for JVM tests.
    // Release remains reflection-only; these host UI libraries are not shipped in Release.
    debugImplementation(libs.androidx.preference.test.host)
    debugImplementation(libs.androidx.appcompat.test.host)
    debugImplementation(libs.androidx.fragment.test.host)
    testImplementation(libs.junit)
    // Exercise native card rollback with real ViewGroup attachment semantics on the JVM.
    testImplementation(libs.robolectric)
    testRuntimeOnly(libs.libxposed.api)
}
