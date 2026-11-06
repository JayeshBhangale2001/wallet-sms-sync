# Validation status

Verified on 2026-11-06 using JDK 17, Android SDK 35, and the existing Pixel C API 28 emulator (Android 9).

| Check | Result |
| --- | --- |
| `:core:test` | 21 tests passed: SMS templates, ignores, dates, decimals, stable fingerprints, Wallet payloads and write outcomes |
| `:app:testDebugUnitTest` | 6 tests passed against MockWebServer: pagination, serialization, throttling, reconciliation, redirects and disconnected creates |
| `:app:assembleDebug` | Passed; installable debug APK generated |
| `:app:lintDebug` | Passed with zero errors; non-blocking warnings remain for pinned dependency/target versions, durable preference commits and kapt |
| `:app:connectedDebugAndroidTest` | 4 tests passed on API 28: Room duplicate imports, approval/transfer evidence, interrupted upload recovery, automatic account/review gates, Keystore/auth-pause preferences and Compose navigation |
| Actual SMS receiver smoke check | APK installed, SMS permissions granted, synthetic financial SMS injected twice through emulator telephony; exactly one INR 42.00 review row persisted; non-transaction alert ignored |
| UI screenshot | Empty history rendered and visually inspected; navigation assertions passed |
| Source hygiene | Only synthetic fixtures; local SDK settings, full fetched API schema, APKs and build caches excluded from Git |

All 31 automated tests passed on the final verification run. No live Wallet financial records were created and no user-supplied token was used.

The initial Windows build encountered Gradle 8.9 transform-cache directory rename failures. Completed transform entries were recovered locally and the full checks then passed; the project does not depend on that local workaround. GitHub Actions uses Linux.

## Remaining device/integration checks

Physical phone/OEM background restrictions, permission denial/revocation, reboot behavior, and other Android versions still need coverage. Live Wallet account/category retrieval, real API entitlement and paired-transfer visibility were validated against the public schema and mock server, not against a live financial account. The API intentionally has no create idempotency guarantee; uncertain writes need reconciliation. See [device checks](DEVICE_CHECKS.md) before expanding personal use or distribution.
