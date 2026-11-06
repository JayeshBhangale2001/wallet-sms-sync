# Wallet SMS Sync

An Android companion for Wallet by BudgetBakers. Parse financial SMS on your phone, review uncertain transactions, and sync structured records directly to Wallet. No backend or cloud parsing service.

## Get started

Requires Android 8+, a Wallet account with REST API access, and your own Wallet API token. This MVP is intended for personal APK installation.

1. Build the debug APK, install it, and open **Wallet SMS Sync**.
2. In **Setup**, grant SMS permissions and enter a fresh Wallet API token. Tokens are encrypted with an Android Keystore key and never included in source code.
3. Fetch accounts and categories. Map bank/card identifiers to existing INR Wallet accounts, for example `IDFC:CARD:1234` or `HDFC:ACCOUNT:9012`.
4. Choose a default category and optional exact merchant category overrides.
5. Enable automatic sync if desired. New recognized card purchases on mapped accounts upload automatically. UPI payments, repayments, unsupported financial formats, and unmapped accounts require review.
6. For old SMS, open **Import**, select receipt dates, preview candidates, and add them to **Review**. Approve individual records to upload.

Use accounts without bank sync when possible: an SMS import and Wallet's own bank import can otherwise describe the same transaction. Existing records imported by another tool are not automatically deduplicated.

## Supported rules

| Format | Result |
| --- | --- |
| IDFC `INR … spent on … Card ending XX… at … on … at …` | Card expense; merchant and minute-precision date |
| HDFC `Sent Rs.… From … A/C *… To … On … Ref …` | UPI expense candidate; requires expense/transfer confirmation |
| HDFC `Spent Rs.… On … Card … At … On YYYY-MM-DD:HH:mm:ss` | Card expense; second-precision date |
| IDFC `Thank you for payment … towards … Credit Card …` | Repayment candidate; select paying account and confirm transfer |
| `you've used …% of the limit` | Ignored |

Balances and available credit limits are never used as transaction amounts. Known-bank financial messages that do not match supported templates enter manual review without storing their SMS body. Income can be entered manually or classified from an unsupported credit message; automatic income parsing awaits representative templates. OTPs and unrelated messages are discarded.

Dates use the phone's timezone. Date-only messages retain `DATE` precision and use local midnight for the API timestamp. Switch timezone only with awareness that date interpretation occurs when messages are parsed.

## Build and test

Install JDK 17 and Android SDK platform 35/build-tools 35.0.0. Set `ANDROID_HOME`, or create an untracked `local.properties` with `sdk.dir=...`.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

On Windows use `gradlew.bat`. APK: `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions runs the same checks and uploads the debug APK.

With an emulator or test device connected:

```sh
./gradlew :app:connectedDebugAndroidTest
```

Instrumentation tests use synthetic transactions and isolated test preferences; no real Wallet records are created. See [device checks](docs/DEVICE_CHECKS.md) and [validation status](docs/VALIDATION.md) for the verification performed for this version.

## Upload recovery

Uploads persist in Room and run through a network-constrained WorkManager queue. Requests are paced, honor `Retry-After`, and stop on token/entitlement errors. Use **Resume queued sync** after fixing setup. Android background restrictions can delay processing; this is not a real-time delivery guarantee.

Network interruptions, process death during upload, malformed responses, and missing transfer mirrors produce `UNCERTAIN`, not an automatic retry. Open the item to query its unique Wallet note marker. Wallet API synchronization can lag: a missing search result does **not** prove a write failed. Verify in Wallet and either enter confirmed remote IDs or explicitly confirm absence to return the item to Review.

A confirmed transfer creates a source and destination record in one Wallet operation. Link a second matching unsubmitted SMS in the review screen to prevent uploading that evidence separately. Cross-currency transfers are not supported in this MVP.

## Privacy and development

Only synthetic fixtures belong in this public repository. Never commit API tokens, SMS exports, account identifiers, signing keys, or `local.properties`. Raw SMS bodies are used in memory during parsing and never persisted. The database contains extracted financial data in the app's private storage; it is not independently encrypted. Android backups are disabled. The app has no analytics or HTTP body logging.

Architecture and API details: [architecture](docs/ARCHITECTURE.md), [Wallet contract](docs/WALLET_API.md). The app is unofficial and is not affiliated with BudgetBakers.

## License

MIT. BudgetBakers and Wallet names belong to their respective owners.
