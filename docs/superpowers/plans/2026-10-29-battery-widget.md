# Battery Widget Implementation Plan

**Goal:** Implement the approved D1 battery-only resizable desktop widget.
**Architecture:** Explicit reduced authenticated state delivery; bounded pure projection and existing identity guards; display-only persistence and RemoteViews lifecycle. No transport, control, worker, permission or scope additions.
**Tech Stack:** Kotlin, Android AppWidget/RemoteViews, existing bridge contracts, JUnit/Robolectric.

- [x] Add projection/dedup tests, then pure projection and timestamp policy.
- [x] Add explicit publisher and root-owned StateBroadcaster publish/reply hooks.
- [x] Add authenticated receiver, producer lifetime, bounded display persistence and rejection tests.
- [x] Add provider, resize layouts, selection/configuration and existing Settings click routing.
- [x] Add manifest/resources and actual RemoteViews/provider lifecycle tests.
- [x] Verify spoof/stale/dead-producer/system-owned/removal/forced replay paths.
- [x] Update documentation and CHANGELOG.
- [ ] Root completes final combined Gradle/lint/release verification.

Design: ../specs/2026-10-29-battery-widget-design.md. Root technical approval received; no new user approval needed. Root owns Gradle and commits; preserve full-build source freezes.

Verification: root-run focused widget tests passed with packaged Android resources enabled. Root inspected Robolectric native-rendered compact, wide and dark/large-font images. The final combined suite and explicit minimum-size visual cases are pending at this update; no phone or launcher acceptance is claimed.
