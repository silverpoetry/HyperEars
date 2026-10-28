# Apple controller coexistence (B8)

## Identity evidence

- AndPods: archived `.analysis/andpods-4.3.8/decompiled/resources/AndroidManifest.xml`
  lines 3-7 declares versionCode 277, versionName 4.3.8 and package
  `cn.xiaolongonly.andpodsop`. The same application's `BuildConfig.java:5` confirms
  APPLICATION_ID. The prior installed-package report is recorded in
  `apple-device-identity-followup.md`; this implementation performs no phone query.
- LibrePods: pinned upstream `89356c08cea3db0b6b54792d9329d09a87cc85fa`,
  `android/app/build.gradle.kts:51`, declares `me.kavishdevar.librepods`.
  This is the existing GPL-3.0-or-later upstream reference; no controller implementation
  or application assets are copied for this catalog addition.

## Bounded integration

Apple family, Pro and Max adapters declare AndPods first and LibrePods second.
The existing navigator uses this default order and preserves a currently active
explicit controller as the first navigation candidate. Missing applications still
fall back through the existing device-settings route. The catalog drives optional
scope metadata, package visibility and existing process-presence hooks; Settings
UI reads accepted adapter controller lists, so no Apple-specific selection UI is added.

A package identity is a navigation/presence boundary, never headphone identity or
proof that a controller owns a particular address. Automatic yield remains governed
by the existing runtime-yield preference. An explicit launch uses the existing
bounded VendorHandoffLease, including presence confirmation and death/expiry cleanup.
No new per-address controller claim, connection attempt or polling loop is introduced.

## Existing Apple cleanup and official ownership

`EarbudConnectionManager.updateControlAppPresence` refreshes Apple retention and
forwards the snapshot to each session. Its retention eligibility already checks
`ControlAppCatalog.activeOwner(adapter.controlApps, activeControlAppPackages)`.
`EarbudDeviceSession.reconcileExternalControlOwner` calls `yieldToControlApp`, marks
transport stopping, cancels connection work and invokes graceful transport cleanup.
That cleanup detaches private key callbacks, closes Apple BLE registration/proximity,
clears long-press behavior, closes spatial/motion handling, restores owned device
settings where possible, closes the AAP channel, clears stale queues and resets the
protocol generation before publishing external ownership. Existing phone wear and
conversation runtimes remove their actions when MODULE ownership is lost.

Native Apple admission remains upstream of adapter registration: SYSTEM and UNKNOWN
never become module ownership due to an application launch or presence report.
Controller exit does not undo a native-positive connection decision. No protocol or
native admission implementation changed in this slice.

## Verification boundary

Regression coverage uses the real catalog/adapter list, existing navigation and
handoff lease, Bluetooth manager native admission path, and wear/conversation runtime
cleanup. Existing OwnedChannelCleanup/AppleSpatial tests cover shared cleanup ordering
and stale work. Scope/doc validation checks all declared controller metadata.
Tests are scheduled in the root verification batch; no live controller launch, scope
configuration, device restart, installation or earphone claim is part of this work.
