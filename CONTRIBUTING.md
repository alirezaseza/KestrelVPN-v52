# Contributing
- Build: `bash scripts/build-libbox.sh`, then `./gradlew testDebugUnitTest lintDebug`.
- Gradle wrapper: add `distributionSha256Sum` (from gradle.org) to `gradle/wrapper/gradle-wrapper.properties`; then set `KESTREL_REQUIRE_SHA: "1"` in the CI env so unverified downloads fail.
- After a verified libbox build, pin `SINGBOX_COMMIT` and `LIBBOX_SHA256` in CI env.
- Keep `values/` and `values-fa/` strings in sync (`python3 scripts/check_i18n.py`).
- Never log IPs, UUIDs or links; use `DiagnosticsLog` (it redacts).
- Any change to the sing-box JSON needs a test in `engine/core` and a device check.
- Report security issues privately (see SECURITY.md).
