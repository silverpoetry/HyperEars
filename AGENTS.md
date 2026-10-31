# Maintainer workflow

Read `CONTRIBUTING.md` and `docs/project-scope.md` before changing device adapters, adding
features or processing contribution PRs. HyperEars provides core HyperOS headset integration;
detailed device configuration belongs in vendor control apps through the existing settings
navigation and runtime-yield paths. Protocol extensibility is not approval to add comprehensive
vendor-control interfaces, Settings-process hooks or features outside that scope.

When preparing a maintenance version of a device PR, preserve the contributor's commits and
keep both the original PR and the maintenance PR open until the final maintenance version has
received device revalidation. Keep the maintenance PR in Draft while awaiting that feedback.
Original-version device reports, unit tests, CI and signed builds do not replace revalidation
of changed protocol, lifecycle or card behavior. Do not use closing keywords for the original
PR while revalidation is pending. An explicit maintainer exception applies only to that case.

The premature #72 merge and #62 closure are recorded in `CONTRIBUTING.md`. On 2026-09-08 the
maintainer chose to retain that merge; this does not authorize repeating the exception.
