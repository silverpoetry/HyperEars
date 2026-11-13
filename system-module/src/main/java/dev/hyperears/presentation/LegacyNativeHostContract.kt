package dev.hyperears.presentation

import android.content.Context
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** The SDK-35 ABI was audited from this exact official host and its bundled feature plugin. */
internal object LegacyNativeHostContract {
    const val HOST_SHA256 = "5d6945fbbed73be58ad0408097fc10bb1266083f8ad79fe1baa3ab88f5145a72"
    const val PLUGIN_SHA256 = "d817a82f933951b21413b5c4477bddc636766e154d580bda7941af97adc69a3b"

    @Suppress("DEPRECATION")
    fun isCandidate(context: Context): Boolean = Build.VERSION.SDK_INT == 35 &&
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode == 35L

    /** Runs only on the host resolver worker, never on a rendering or protocol hot path. */
    data class Verification(val verified: Boolean, val digest: String? = null)

    fun inspect(context: Context): Verification {
        if (!isCandidate(context)) return Verification(false)
        val digest = sha256(File(context.applicationInfo.sourceDir))
        return Verification(isVerified(35, 35, digest), digest)
    }

    fun isVerified(sdk: Int, version: Long, digest: String): Boolean =
        sdk == 35 && version == 35L && digest == HOST_SHA256

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
