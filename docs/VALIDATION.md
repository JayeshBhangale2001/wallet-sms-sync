# Validation status

Version 0.2 verified on 2026-11-07 using JDK 17, Kotlin 2.2.0, AGP 8.10.1, Gradle 8.14.3, Android SDK 35, and a dedicated x86_64 API 28 emulator (Android 9).

| Check | Result |
| --- | --- |
| `:core:test` | 31 tests passed: SMS rules, exact amounts/dates, AI evidence validation, safe template generation/reuse, ambiguity rejection, direct-sync policy, SMS-title notes, fingerprints, Wallet payloads and write outcomes |
| `:app:testDebugUnitTest` | 6 tests passed against MockWebServer: pagination, serialization, throttling, reconciliation, redirects and disconnected creates |
| `:app:assembleDebug` | Passed; installable debug APK generated |
| `:app:lintDebug` | Passed with zero errors; non-blocking warnings remain for pinned dependency/target versions, durable preference commits and kapt |
| `:app:connectedDebugAndroidTest` | 10 tests passed on x86_64 API 28: schema-1 history/mapping migration, native JNI loading without model/network, learned knowledge across restart, parser-identity duplicate prevention, conflict handling, revoked trust after corrections, repayment destination ownership, direct AI sync versus balance/unconfirmed-account mistakes, ciphertext cleanup, existing queue/recovery tests and Compose navigation |
| Actual SMS receiver smoke check | Version 0.1 previously passed synthetic SMS ingestion/deduplication. Version 0.2 checks include sender/heading payload generation and direct-sync gating; physical-phone telephony remains a manual acceptance check |
| UI screenshot | Phone-sized empty history rendered and visually inspected; History/Review/Import/Learn/Setup navigation assertions passed |
| Source hygiene | Only synthetic fixtures; local SDK settings, full fetched API schema, APKs and build caches excluded from Git |

All 47 automated tests passed on the verification run. No live Wallet financial records were created and no user-supplied token was used.

Windows Gradle transform-cache directory rename failures were recovered locally before successful full checks; the project does not depend on that local workaround. GitHub Actions uses Linux.

## Remaining device/integration checks

Actual model weights were not loaded during automated tests. AI extraction/learning tests use a deterministic fake engine, while a separate device test verifies that the bundled native inference library loads successfully. Import a compatible Gemma 3 1B INT4 `.litertlm` model on the S24 Ultra and use **Test model & measure speed** to verify inference, warm/cold latency, battery behavior and accuracy on held-out messages. No S24 performance numbers are claimed from emulator tests.

Physical phone/OEM background restrictions, permission denial/revocation, reboot behavior, model import/provider compatibility and other Android versions still need coverage. Live Wallet entitlement, account/category retrieval and paired-transfer visibility were validated against the public schema and mock server, not a live financial account. Uncertain writes require reconciliation. See [device checks](DEVICE_CHECKS.md) before expanding personal use or distribution.
