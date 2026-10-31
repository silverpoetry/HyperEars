package dev.hyperears.bridge

import android.content.Context
import android.content.Intent
import android.os.IBinder
import dev.hyperears.integration.EarbudState
import dev.hyperears.presentation.NativePresentationMetadata

object StateBroadcaster {
    fun publish(
        context: Context,
        state: EarbudState,
        sessionToken: String,
        presentation: NativePresentationMetadata? = null,
        producer: IBinder? = null,
    ) {
        ModuleContract.stateConsumerPackages.forEach { targetPackage ->
            BroadcastIdentity.send(context,
                ModuleContract.stateChanged(
                    state = state,
                    sessionToken = sessionToken,
                    targetPackage = targetPackage,
                ).addFlags(Intent.FLAG_RECEIVER_FOREGROUND).apply {
                    if (presentation != null && producer != null) {
                        NativePresentationContract.attach(this, presentation, producer)
                    }
                },
            )
        }
    }

    fun reply(
        context: Context,
        targetPackage: String,
        state: EarbudState,
        sessionToken: String,
        presentation: NativePresentationMetadata? = null,
        producer: IBinder? = null,
    ) {
        if (targetPackage !in ModuleContract.stateConsumerPackages) return
        BroadcastIdentity.send(context,
            ModuleContract.stateChanged(state, sessionToken, targetPackage)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND).apply {
                    if (presentation != null && producer != null) {
                        NativePresentationContract.attach(this, presentation.copy(replay = true), producer)
                    }
                },
        )
        if (targetPackage == ModuleContract.MODULE_PACKAGE) {
        }
    }
}
