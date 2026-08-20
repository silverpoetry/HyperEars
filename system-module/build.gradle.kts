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
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.hyperears"
        minSdk = 35
        targetSdk = 36
        versionCode = 20302
        versionName = "2.3.2"
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
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
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.dexkit)

    testImplementation(libs.junit)
}
