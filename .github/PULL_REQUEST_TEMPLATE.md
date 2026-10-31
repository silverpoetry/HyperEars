<!-- markdownlint-disable-file MD041 -->

## Summary

<!-- What changed and why? -->

## Scope and impact

<!-- Which adapter, protocol, process or HyperOS surface is affected? -->

<!-- Identify the core system capability covered by docs/project-scope.md.
     Keep detailed device configuration in the vendor app; explain any necessary shared changes. -->

- [ ] This change follows the project scope; detailed configuration remains in vendor control apps

## Evidence

<!-- Link protocol sources or provide redacted captures and device reports. -->

## Validation

- [ ] `testDebugUnitTest`
- [ ] `:system-module:lintRelease`
- [ ] `:system-module:assembleRelease`
- [ ] Hardware validation, when behavior changes on a physical headset
- [ ] For a maintained device PR: the final maintenance version has received device revalidation;
      keep this PR in Draft and the original PR open until that feedback arrives
- [ ] Logs, screenshots and Bluetooth addresses are redacted
- [ ] Documentation and compatibility status are updated
- [ ] If controller apps changed: catalog, Adapter declarations, `scope.list`, tests and
      `docs/control-apps.md` are synchronized

## Risks and rollback

<!-- Describe host-process, battery, compatibility and migration risks. -->
