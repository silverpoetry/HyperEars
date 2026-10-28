package dev.hyperears.runtime

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import dev.hyperears.bridge.AppleCaseDiscoveryContract
import dev.hyperears.bridge.AppleNativeAdmissionContract
import dev.hyperears.bridge.ModuleContract
import dev.hyperears.bridge.StateBroadcaster
import dev.hyperears.bridge.BroadcastIdentity
import dev.hyperears.bridge.NativePresentationContract
import dev.hyperears.hook.ModuleLog
import dev.hyperears.integration.EarbudAdapter
import dev.hyperears.integration.EarbudIdentity
import dev.hyperears.integration.EarbudState
import dev.hyperears.settings.ModuleSettings
import dev.hyperears.settings.ModuleSettingsRuntime
import java.io.Closeable

/**
 * Service-lifecycle facade installed in the Bluetooth process.
 *
 * Hook code calls only this API. The facade deliberately mirrors the native service shape:
 * create, register/unregister a device, disconnect all devices, and destroy.
 */
internal object EarbudSessionService {
    private val lock = Any()

    // This is the Bluetooth process application context, and onDestroy clears the
    // reference after synchronously unregistering the receiver and closing all sessions.
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var runtime: Runtime? = null

    fun onCreate(context: Context?) {
        if (context == null || runtime != null) return
        synchronized(lock) {
            if (runtime != null) return
            runtime = Runtime(context.applicationContext ?: context)
        }
        ModuleLog.debug(COMPONENT, "created")
    }

    fun registerDevice(
        device: BluetoothDevice,
        identity: EarbudIdentity,
        adapter: EarbudAdapter,
    ): Boolean = runtime?.connectionManager?.registerDevice(device, identity, adapter) == true

    fun onPhysicalConnected(address: String) {
        runtime?.connectionManager?.onPhysicalConnected(address)
    }

    fun observeDevice(
        device: BluetoothDevice,
        identity: EarbudIdentity,
    ): Boolean = runtime?.connectionManager?.observeDevice(device, identity) == true

    fun unregisterDevice(device: BluetoothDevice?): Boolean =
        runtime?.connectionManager?.unregisterDevice(device) == true

    fun disconnectAllDevices() {
        runtime?.connectionManager?.unregisterDevice(null)
    }

    fun onDestroy() {
        val oldRuntime = synchronized(lock) {
            runtime.also { runtime = null }
        } ?: return
        oldRuntime.close()
        ModuleLog.debug(COMPONENT, "destroyed")
    }

    private class Runtime(
        private val context: Context,
    ) : Closeable {
        val connectionManager = EarbudConnectionManager(context)
        private val settingsSubscription = ModuleSettingsRuntime.observe(::applySettings)
        private val controlAppPresence = ControlAppPresenceRegistry(
            context = context,
            onChanged = connectionManager::updateControlAppPresence,
        )

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (context == null || intent == null) return
                when (intent.action) {
                    AppleCaseDiscoveryContract.SCANS, AppleCaseDiscoveryContract.READY -> {
                        if (!BroadcastIdentity.accepts(context, sentFromPackage, sentFromUid,
                                ModuleContract.NATIVE_HEADSET_PACKAGE)) return
                        if (intent.action == AppleCaseDiscoveryContract.READY) connectionManager.announceAppleDiscovery()
                        else connectionManager.observeAppleDiscovery(AppleCaseDiscoveryContract.readScans(intent))
                    }
                    AppleNativeAdmissionContract.READY, AppleNativeAdmissionContract.DECISION -> {
                        if (!BroadcastIdentity.accepts(context, sentFromPackage, sentFromUid,
                                ModuleContract.NATIVE_HEADSET_PACKAGE)) return
                        if (intent.action == AppleNativeAdmissionContract.READY) connectionManager.requestAppleNativeDecisions()
                        else {
                            val decision = AppleNativeAdmissionContract.readDecision(intent) ?: return
                            connectionManager.acceptAppleNativeDecision(decision.request, decision.accepted, decision.revision)
                        }
                    }
                    BluetoothDevice.ACTION_UUID -> {
                        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                        connectionManager.refreshIdentity(device)
                    }
                    BluetoothSystemBattery.ACTION_LEVEL_CHANGED -> {
                        val device = BluetoothSystemBattery.device(intent) ?: return
                        connectionManager.updateSystemBattery(
                            device,
                            BluetoothSystemBattery.level(intent),
                        )
                    }

                    ModuleContract.ACTION_REQUEST_STATE -> {
                        val target = with(ModuleContract) { intent.readReplyPackage() }
                            ?: return
                        val snapshots = connectionManager.snapshots()
                        if (snapshots.isEmpty()) {
                            StateBroadcaster.reply(
                                context = context,
                                targetPackage = target,
                                state = EarbudState(),
                                sessionToken = "",
                            )
                        } else {
                            snapshots.forEach { snapshot ->
                                StateBroadcaster.reply(
                                    context = context,
                                    targetPackage = target,
                                    state = snapshot.state,
                                    sessionToken = snapshot.sessionToken,
                                    presentation = snapshot.presentation,
                                    producer = connectionManager.presentationProducer,
                                )
                            }
                        }
                    }

                    ModuleContract.ACTION_CONTROL -> {
                        val request = with(ModuleContract) { intent.readControl() } ?: return
                        val token = with(ModuleContract) {
                            intent.readSessionToken()
                        } ?: return
                        val address = with(ModuleContract) { intent.readAddress() } ?: return
                        if (!connectionManager.execute(request, address, token)) {
                            ModuleLog.warn(
                                COMPONENT,
                                "rejected stale, disconnected, or unauthenticated control",
                            )
                        }
                    }

                    ModuleContract.ACTION_CONTROL_APP_REGISTER -> {
                        val fields = with(ModuleContract) {
                            intent.controlAppRegistrationFields()
                        }
                        val registration = with(ModuleContract) {
                            intent.readControlAppRegistration()
                        }
                        if (registration == null) {
                            ModuleLog.warn(
                                COMPONENT,
                                "malformed control-app registration " +
                                    "package=${fields.packageName} process=${fields.processName} " +
                                    "tokenPresent=${fields.tokenPresent}",
                            )
                            return
                        }
                        ModuleLog.debug(
                            COMPONENT,
                            "received control-app registration sender=$sentFromPackage " +
                                "uid=$sentFromUid " +
                                "package=${registration.packageName} process=${registration.processName}",
                        )
                        if (
                            !controlAppPresence.register(
                                registration = registration,
                                senderPackage = sentFromPackage,
                                senderUid = sentFromUid,
                            )
                        ) {
                            ModuleLog.warn(
                                COMPONENT,
                                "rejected unauthenticated control-app registration",
                            )
                        }
                    }

                    NativePresentationContract.ACTION_ADMISSION -> {
                        if (!BroadcastIdentity.accepts(context, sentFromPackage, sentFromUid,
                                ModuleContract.MILINK_PACKAGE)) return
                        val (address, token) = NativePresentationContract.readAdmission(intent) ?: return
                        connectionManager.confirmNativeAdmission(address, token)
                    }

                    ModuleContract.ACTION_SYSTEM_OWNERSHIP_CLAIMED -> {
                        val address = with(ModuleContract) {
                            intent.readSystemOwnershipClaimAddress()
                        } ?: return
                        if (!isAuthenticatedMiLinkSender(context)) {
                            ModuleLog.warn(
                                COMPONENT,
                                "rejected unauthenticated system-ownership claim",
                            )
                            return
                        }
                        connectionManager.claimSystemOwnership(address)
                    }

                }
            }

            private fun isAuthenticatedMiLinkSender(context: Context): Boolean {
                val senderPackage = sentFromPackage
                val senderUid = sentFromUid
                if (senderPackage.isNullOrBlank() && senderUid < 0) return false
                if (!senderPackage.isNullOrBlank() &&
                    senderPackage != ModuleContract.MILINK_PACKAGE
                ) return false
                if (senderUid >= 0) {
                    val packages = context.packageManager.getPackagesForUid(senderUid).orEmpty()
                    if (ModuleContract.MILINK_PACKAGE !in packages) return false
                }
                return true
            }
        }

        init {
            val filter = IntentFilter().apply {
                addAction(AppleCaseDiscoveryContract.SCANS)
                addAction(AppleCaseDiscoveryContract.READY)
                addAction(AppleNativeAdmissionContract.READY)
                addAction(AppleNativeAdmissionContract.DECISION)
                addAction(BluetoothDevice.ACTION_UUID)
                addAction(ModuleContract.ACTION_REQUEST_STATE)
                addAction(ModuleContract.ACTION_CONTROL)
                addAction(ModuleContract.ACTION_CONTROL_APP_REGISTER)
                addAction(ModuleContract.ACTION_SYSTEM_OWNERSHIP_CLAIMED)
                addAction(NativePresentationContract.ACTION_ADMISSION)
                addAction(BluetoothSystemBattery.ACTION_LEVEL_CHANGED)
            }
            context.registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_EXPORTED,
            )
            controlAppPresence.requestAnnouncements()
            ModuleLog.debug(COMPONENT, "control receiver registered")
        }

        override fun close() {
            connectionManager.close()
            controlAppPresence.close()
            settingsSubscription.close()
            runCatching { context.unregisterReceiver(receiver) }
                .onFailure {
                    ModuleLog.debug(COMPONENT, "control receiver already unregistered")
                }
        }

        private fun applySettings(settings: ModuleSettings) {
            connectionManager.setModulePaused(settings.modulePaused)
            connectionManager.updateExternalControlEnabled(settings.yieldToVendorControlApp)
            connectionManager.updateDisabledAdapters(settings.disabledAdapterIds)
        }
    }

    private const val COMPONENT = "SessionService"
}
