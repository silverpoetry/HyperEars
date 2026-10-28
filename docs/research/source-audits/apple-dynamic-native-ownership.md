# Apple dynamic native ownership — connection-time design

## Scope and evidence boundary

Read-only review of existing ROM extracts under D:/Backup/Desktop/HyperEars/.analysis/andpods-4.3.8 and current HyperEars-full source. No device calls, builds, production edits or remote operations. All runtime behavior below is static source evidence or explicitly identified design inference; provider permissions, plugin loading and complete negative support decisions have not been tested on device in this pass. Decompiled methods contain JADX warnings, so exception/finally details need bytecode confirmation before implementing those exact paths.

User clarification supersedes live-update design: Xiaomi resource updates restart Bluetooth. Recompute native support on each fresh physical connection/new Bluetooth producer; no resource file watcher, periodic polling, immediate in-session migration or persisted support allowlist. AndPods remains primary Apple protocol reference; original HyperPods is supplemental. This document concerns ownership, not selecting alternate protocol sources.

## Exact native boundaries

Paths in this section are relative to `.analysis/andpods-4.3.8/`.

1. `xiaomi-feature/sources/com/android/bluetooth/ble/app/fastconnect/airpods/MiuiAirPodsFastConnectHelper.java:1123`: public boolean checkSupport(String productType). Returns false for missing type OR null mSupportAirPodsAll; otherwise checks native product list. Public void parseAirPodsJson() at1139 loads `files/fc_resources/00000101/fc_support_airpods.json` into mSupportAirPodsAll (AirPodsSupport array). checkSupport callsites at350/626 gate native handling. A false result before list initialization is UNKNOWN, not unsupported.
2. `xiaomi-bluetooth/sources/com/android/bluetooth/ble/app/headset/miuibluetoothprovider/MiuiBluetoothContentProvider.java:432`: public boolean checkSupportAirPods(String productType). Reads current fc_support_airpods.json, then additional cloud support via public boolean checkSupportAirPodsFromCloud(Context,String) at591, which reads `fc_support_airpods_additional_2_extended.json` AirPodsSupport. Native call handler onAirPodsProductId at262-275 writes address-type only if that method accepts product_id. This is a mutation endpoint; do not invoke it as an ownership query or populate native identity for HyperEars fallback.
3. `xiaomi-bluetooth/sources/com/xiaomi/bluetooth/modules/airlibrary/db/AirLocalStorage.java:193`: public static boolean isFeatureSupport(Context,BluetoothDevice,String). The actual official private-connection gate uses feature `ConnectL2cap`; callsite `a2/a.java:235-253` (AirCoreManager.onDeviceConnected, obfuscated name i) returns without opening native channel if false. It gets stored address `type`, applies isFeatureCloudSupport(Context,String,String), then Config.isSupportConnectL2cap(String) only if cloud feature absent. Model inclusion in cloud is authoritative; nonempty cloud list excluding model disables fallback-to-built-in. Missing context/device/type also returns false: negative alone is ambiguous.
4. `AirLocalStorage.java:174-191`: cloud result codes0=parse/input error,1=feature absent,2=model included,3=feature list excludes model. `loadFeatureCloudSupport(Context)` at243 caches once using sCloudConfigState; missing file stays-1, parsed0, error1. Bluetooth restart resets static cache under user's stated update behavior. Do not force-reset this vendor cache or add a watcher.
5. `utils/Config.java:15-27` holds native built-in model arrays and boolean isSupportConnectL2cap(String), isSupportBasicNoise(String), isSupportEarDetection(String). Query current loaded native semantics, never copy those arrays as HyperEars ownership allowlist.

## Existing cross-process read-only provider surface

Bluetooth process can call the official provider without loading Xiaomi classes:

- Authority `com.android.bluetooth.ble.app.headsetdata.provider`.
- ContentResolver.call(authority, "airpodsRepository", "get_state", extras): extras `extra_key="type"`, `android.bluetooth.device.extra.DEVICE=BluetoothDevice`; returns `extra_value`.
- Same method with arg `check_feature_support`, extra_key="ConnectL2cap": returns boolean `extra_feature_support`.
- Implementation `xiaomi-bluetooth/sources/h1/a.java:20-36,40-55,58-100`; stable protocol mirrored by `settings-plugin/sources/plugin/settings/java/airpods/DbHelper.java:12-125`.
- Provider outer permission check atMiuiBluetoothContentProvider:155,188; specific repository package/signature check at335-339. `permission/PermissionChecker.java:23-31` includes com.android.bluetooth, com.xiaomi.bluetooth, Settings and device center. Therefore query from injected actual Bluetooth context is supported by static evidence. Query from module app identity is not equivalent. Preserve null/missing-key/exception as UNKNOWN.

This surface is sufficient for positive native transport evidence when a real native product type exists. It is NOT sufficient for authoritative unsupported-model discovery: rejected products may never have native address-type stored (onAirPodsProductId explicitly filters), so blank type+false cannot distinguish unsupported from not yet learned.

Minimal missing bridge for negative evidence: a narrow read-only query in the already-scoped Xiaomi Bluetooth host, accepting a verified current-connection product type/address and returning SUPPORTED / UNSUPPORTED / UNKNOWN with query generation. It should call current native checkSupportAirPods and official feature-policy semantics in their own host, verify resource/helper initialization, and never write AirpodsModel or override vendor answers. Unsupported requires known identity AND readable initialized native decision source AND negative native product support AND no positive native ConnectL2cap/ownership evidence. Missing resources or failed parsing remain UNKNOWN. An instance of the provider/helper can be captured at its semantic lifecycle; avoid independently loading APK classes into Bluetooth process, which creates a separate stale static cache and wrong filesDir.

Public read-only native provider call directly accepting productType was not found. A new authenticated module-owned bridge is therefore a concrete gap, unless further host API investigation discovers a suitable equivalent. Keep bridge transport in system integration, not Codec/CardAdapter. Use real host caller identity and existing module authentication conventions; no blanket permission-hook override.

## MiLink is not a complete Apple classifier

`milink/sources/com/xiaomi/mxbluetoothsdk/service/MxBluetoothService.java`:

- public int checkIsMiTWS(BluetoothDevice), line733: positive1, rejected0, unavailable2/3/4/-1. This checks Xiaomi path, not Apple coverage.
- public boolean checkIsAirPods(String address), line707: false also represents Bluetooth off, service not bound and exceptions.
- It invokes native Binder checkIsAirPods; `BluetoothHeadsetService.java:1720-1734` delegates to T0(String) at5993. T0 -> `offlinefind/OfflineFindProvider.java:311` isAppleHeadset(String,Context): enabled native Apple module and nonempty stored address-type. It does NOT freshly check support resources.
- `MxBluetoothService.java:668` isAirPods(BluetoothDevice) uses getAirpodsState/modelName for Settings navigation. A picture/name or stored type is not a complete negative ownership decision.

Thus original checkIsMiTWS=0 must not grant Apple transport when official Apple ownership is positive or unknown. Existing presentation check may remain a UI boundary after the Apple transport owner has already been decided.

## HyperEars current lifecycle gaps

Paths relative to HyperEars-full:

- `hook/BluetoothProcessHook.kt:68-114`: physical connect recorded, then global PlatformReserved policy short-circuits Apple; otherwise adapter is immediately registered.
- `integration/PlatformReservedHeadsetPolicy.kt`: Apple name/AAP UUID currently reserve all Apple. Apple adapters are also absent from default registry. A controlled Apple path must replace blanket reservation only after native-ownership decision, while keeping all other native protections.
- `runtime/EarbudConnectionManager.kt:155-208`: registerDevice publishes candidate then calls session.start(), which starts transport before MiLink presentation admission. Merely adding Apple to registry and awaiting NativeAdmission is too late to prevent dual L2CAP owners.
- `runtime/EarbudConnectionManager.kt:94-106`: confirmNativeAdmission is presentation authorization for exact session; not transport-owner permission. Keep those concepts separate.
- `claimSystemOwnership` at256-280 closes session and stores systemOwnedAddresses for process lifetime. Safe for current process; do not persist negative/positive product lists across restarts.
- `hook/MiLinkDeviceOwnershipRegistry.kt:28-48`: owner sticky per address for entire MiLink process.
- `hook/MiLinkServiceHook.kt:498-550`: system claim broadcast only when systemOwnershipNewlyClaimed. queryNativeAdmission at783 skips already SYSTEM-owned addresses. handleStateChanged at741-769 also skips fresh query for those addresses.
- `clearModuleState()` at728 is only called on module pause (line87), not on Bluetooth producer replacement. If Bluetooth restarts but MiLink remains alive, MiLink can retain SYSTEM, skip query, and never resend claim to the new Bluetooth manager. This is a real source-path delivery gap, not a demonstrated device failure.

Fix design: separate sticky ownership decision from receipt delivery. On authenticated new Bluetooth producer/session/connection for an address, re-evaluate applicable native query and re-deliver SYSTEM claim for that epoch even if owner was SYSTEM previously. Include session/generation in new claim/query contract; dedupe per epoch, not owner transition. Do not accept late responses from old generation. A plain address-only claim should not permanently decide a newly registered Apple connection based solely on stale host state.

## Minimal connection-time flow

1. Fresh physical connect creates connection token; normalize candidate Apple evidence from actual UUID/product identification, not name-only ownership. No private Apple socket yet.
2. Query official host current support. Positive check from native owning path => SYSTEM, keep original native handling unchanged; drop module candidate work. For missing identity/readiness return UNKNOWN and retain standard system behavior without Apple private transport or custom card.
3. Only explicit initialized unsupported result for verified model admits Apple Adapter fallback. Choose AndPods-backed model/profile; run existing ProtocolSession/WireCodec; legal responses independently open each feature. Native support and feature existence are separate facts.
4. MiLink CardAdapter and native Settings presentation receive only confirmed typed state and go through existing controlSender/ControlRequest. No protocol writes or hand-built ownership extras in CardAdapter.
5. Disconnect/Bluetooth process restart removes pending query and connection-scoped decision. Next connect queries current provider/helper and updated native resources. HyperEars binary need not update when Xiaomi adds support. No disk support cache or updater needed.
6. For asynchronous native identity learned during initial connection, allow one event-triggered re-query from native type/helper-ready notification; use bounded work and current token. If no such event can be observed reliably, UNKNOWN stays official/default rather than a timeout becoming unsupported. This is initial readiness, not live resource monitoring.

## Verification needed before implementation claims

- Confirm two provider calls from injected Bluetooth context yield real type/feature value and distinguish denied/missing response.
- Confirm unsupported Apple has verified productType available independently of native filtering; establish trustworthy address association without opening conflicting AAP socket first.
- Validate helper/provider captured instance and initialized resources in current loaded plugin; no guesses from version alone. Restart naturally invalidates classloader references; store only process-local host objects, no reflected methods across new loader.
- Test positive native after Xiaomi resource update and Bluetooth restart while MiLink survives; ensure no module socket before decision and claim replay works.
- Test unsupported -> fallback; missing resource/type/error ->UNKNOWN/no private transport; stale response ignored; exact model doesn't imply all controls; native Apple coexistence and standard fallback UI unaffected.

All above tests remain unperformed in this read-only pass. No claim that false native boolean alone proves unsupported, and no requirement for active-session hot takeover remains after user clarification.

## Follow-up: observe the actual native connection decision

The earlier product-type query design has a bootstrap limitation: a new device may not yet have
native type or AACP-derived identity. Further static source tracing identified a better primary
boundary. `a2/a.java:235-260` is the native AirCoreManager `onDeviceConnected` implementation:
after valid context/device checks it calls `AirLocalStorage.isFeatureSupport(..., "ConnectL2cap")`;
false returns without opening a native channel, true schedules the native transport. HFP connected
and HFP service-rebind paths schedule this native decision (the native delay is 2 seconds).

An observed false in THIS actual connection call can mean NATIVE_DECLINED for that connection,
including a successfully read empty native type. It must not be described as MODEL_UNSUPPORTED.
An arbitrary provider false, elapsed wait, missing hook, failed initialization or failed storage read
remains UNKNOWN. A true native decision means SYSTEM even if the subsequent socket attempt fails.
This resolves the first-connection circular dependency without requiring a copied model allowlist.

Discover the outer method by unique semantic strings and signature `void(BluetoothDevice)` plus
its call to the stable feature method, not by hardcoding `a2.a.i`. Observe the original call using a
thread-local scope, matching address and exact feature name, and preserve return values/exceptions.
Only a real observed gate plus successful enclosing completion permits a negative result. Validate
storage-read health separately because native storage methods can swallow errors as empty strings.

Bind evidence to the current Bluetooth producer and physical-connection token; invalidate on
disconnect/restart and reject stale callbacks. The Apple candidate must remain transport-pending
until this decision. Native acceptance always wins; later real native acceptance uses existing
session close/reset and presentation cleanup. An asynchronous yield broadcast alone does not prove
zero socket overlap. Do not block native behavior to hide that limitation. Resource update watchers,
permanent polling and persistent support tables remain unnecessary.

This is static design evidence, not implemented or device-verified behavior. The earlier public
provider query remains useful positive evidence, but the mandatory product-type negative-query
bridge is superseded as the primary bootstrap plan by actual connection-scoped native admission.

## Direct DEX verification during implementation

The parent verified the extracted `nezha-BluetoothExtension.apk` directly with androguard in an
isolated local environment. APK SHA-256:
`b61f58508256528f7dfbb2166c2e8c352fb343a433a051bc1ab75b039eee3544`.
Scanning every DEX for nonstatic `void(BluetoothDevice)`, the five native connection strings and
exactly one call to the stable `AirLocalStorage.isFeatureSupport` signature yielded one candidate.
The observed obfuscated names are audit evidence only; production discovery must not hardcode them.

In `classes.dex`, the candidate calls the feature gate at byte offset 52; its false branch ends at
120 after the not-target message, while the true branch calls the transport scheduler at 84.
The outer exception handler prints and returns at 130. The feature method's own exception handler
returns false at 298, confirming a return that JADX omitted from its Java output. Consequently,
normal outer return and arbitrary false alone remain insufficient: scoped gate execution and
read-health evidence are required by the implementation.

Raw instruction and unique-match records plus reproducible scripts are retained under the ignored
`.analysis/verification/native-dex/` directory in the original checkout. The DEX inspection did not install a module or restart a phone process; live hook installation
remains a separate acceptance step.

A subsequent read-only ADB refresh re-enumerated the target and confirmed `nezha`, model
`25128PNA1C`, build `OS4.0.5.0.XPACNXM`. The currently installed `com.xiaomi.bluetooth` APK hash
matches the audited DEX hash above exactly. A Bluetooth dump contains the published AAP service
UUID, but that aggregate dump observation is not a per-model or active-headset validation. Raw
names, addresses, logs and identity keys were not added to the report. No installation, settings
change, connection command or restart was performed.

The direct DEX check also confirms one outer BluetoothHeadset field, one inner Handler with one
outer-owner field and one handleMessage(Message) method, retained MemberClasses/InnerClass/
EnclosingClass annotations, and the exact cloud-state field plus built-in feature method consumed
by the health observer. This validates discovery prerequisites in the installed binary; it still
does not execute Android reflection or LSPosed interception on the phone.
