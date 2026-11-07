# Wallet SMS Sync

An Android companion for Wallet by BudgetBakers. Parse financial SMS on your phone, review uncertain transactions, and sync structured records directly to Wallet. Optional cloud extraction uses Groq or Grok (xAI).

## Get started

Requires Android 8+ (including Galaxy S24 Ultra), a Wallet account with REST API access, and your own Wallet API token. This MVP is intended for personal APK installation.

1. Build the debug APK, install it, and open **Wallet SMS Sync**.
2. In **Setup**, grant SMS permissions and enter a fresh Wallet API token. Tokens are encrypted with an Android Keystore key and never included in source code.
3. Fetch accounts and categories. Map bank/card identifiers to existing INR Wallet accounts, for example `IDFC:CARD:1234` or `HDFC:ACCOUNT:9012`.
4. Choose a default category and optional exact merchant category overrides.
5. Automatic sync is enabled for new installs (an existing explicit off setting is preserved). New expenses/income can upload directly when the amount is grounded and the account has a confirmed mapping. Turn it off to approve every transaction. Transfers, repayments, unclear fields and unmapped accounts require review.
6. For old SMS, open **Import**, select receipt dates, preview candidates, and add them to **Review**. Previously processed and ignored messages are skipped. Approve individual records to upload.

## Keep your history across reinstalls

1. Before uninstalling, open **Setup → Local backup**, enter a passphrase of at least 12 characters, and tap **Save backup**. Use the Android picker to save to the phone's **Downloads** or **Documents** folder.
2. After reinstalling, return to **Local backup**, enter the same passphrase, and tap **Restore backup**. Select your latest file before granting SMS permissions or importing messages.
3. Re-enter your Wallet token and cloud AI key. The backup keeps transaction history, duplicate checks, Wallet record IDs, mappings, learned rules and preferences. It excludes credentials and raw SMS bodies. Verify restored pending uploads in Wallet before retrying.

Save a fresh backup after imports and completed syncs. Restore recovers the last saved snapshot. A normal app update using the same signing key keeps on-device data.

## Set up cloud AI

1. Open **Setup → Cloud AI & learning**. Select **Grok (xAI)** for a key issued by the xAI Console, or **Groq** for a key issued by the Groq Console. Save your key. The app encrypts each provider's key separately from your Wallet token.
2. Tap **Test with sample SMS**. This checks your key and model access with a synthetic message. HTTP 401 indicates an authentication problem. HTTP 403 indicates an access restriction.
3. Turn on **Enable cloud AI for financial SMS**. Each eligible SMS that needs AI sends the text with web links removed, the latest local extraction, and the cached same-currency account and category display labels. Temporary aliases stand in for Wallet IDs. Confirmed mappings stay on your phone. Re-enable cloud AI after upgrading to approve the expanded context.
4. For uncertain items, apply the suggestion in **Review**, check the fields and approve with **Remember these corrections** enabled.

Groq uses GPT-OSS 20B and is subject to Groq's free-tier limits. Grok uses the xAI Responses API with Grok 4.3 and may incur usage charges. The app sets `store: false` on xAI requests. Review your provider's data controls before enabling extraction for bank messages. [Groq data controls](https://console.groq.com/docs/your-data) · [Groq API limits](https://console.groq.com/docs/rate-limits) · [Grok 4.3 pricing](https://docs.x.ai/developers/models/grok-4.3)

Known parsers run first. An enabled learned template handles future matching formats locally. Approve an expense with **Remember category for this merchant** to reuse the category for future matching merchant names. Case and repeated spaces do not affect matching. Manage remembered categories in **Learn**. AI category choices are labeled **AI**, and manual corrections override them. Transfers and repayments always require review.

Use **Review → Recheck pending items** to refresh suggestions on existing items using retained SMS or matching messages still in the inbox. Inspect and apply the suggestions in the transaction. Rechecking preserves existing records and creates no uploads.

## Edit rules and organize Review

- In **Rules**, add a **Merchant equals** or **SMS contains** category rule using a fetched Wallet category. For example, match `Zomato` in the SMS and choose **Food & Drink**. Confirmed exact merchant rules take priority over keyword rules. Use **Move up** and **Move down** to order keyword rules.
- To create a bank parser, paste a sample, select its amount and account suffix, and tap the matching field buttons. You can also mark the merchant, date, and reference. Inspect the template and remove personal fixed text before saving. Test the template with **Preview parser**. Custom parser matches enter Review. To override an existing parser, choose the built-in format and use a sample that it recognizes. If the override does not match, the built-in parser handles the SMS.
- When choosing a category for an expense, **Apply to other uncategorized purchases from this merchant** lets you select existing matches. Applying the category leaves every selected expense in Review.
- **History** shows synced, ignored and linked records. **Review** separates items that need attention from queued or sending uploads. **Open Wallet** on synced details opens the installed Wallet app or Wallet in your browser. Use the displayed details to find the transaction.

Wallet notes include **both SMS sender label and heading** by default, followed by a stable import marker. You can choose sender-only/heading-only in Setup or edit the title of a review item. Notes are length-limited; reconciliation searches the marker within the note so added titles do not break interrupted-upload recovery.

Templates are generated by application code from unique, reviewed SMS field positions. Model-generated regex or executable code is never run. If a message cannot be safely generalized (for example, the same amount appears as both transaction and balance), account/category corrections can still be saved without a template. Merchant/category rules apply to exact names, ignoring case.

**View / attach original SMS** shows the full SMS in Review. The app stores an encrypted local copy while the item needs review, even with cloud AI off. Older or restored items can recover the SMS from the inbox with SMS permission. If the message is unavailable, you can paste a copy locally. Approval, direct validated AI sync, ignore, or transfer-evidence linking removes the encrypted copy. Cloud AI processes eligible pending requests only when enabled.

Use accounts without bank sync when possible: an SMS import and Wallet's own bank import can otherwise describe the same transaction. Existing records imported by another tool are not automatically deduplicated.

## Supported rules

| Format | Result |
| --- | --- |
| IDFC `INR … spent on … Card ending XX… at … on … at …` | Card expense; merchant and minute-precision date |
| HDFC `Sent Rs.… From … A/C *… To … On … Ref …` | UPI expense candidate; clear amount/confirmed account can sync, ambiguous transfers require review |
| HDFC `Spent Rs.… On … Card … At … On YYYY-MM-DD:HH:mm:ss` | Card expense; second-precision date |
| HDFC debit card `Spent/Withdrawn Rs.… From … Card x…` | Card expense or cash transfer candidate |
| HDFC `Credit Alert! … credited … from VPA … (UPI …)` | Incoming payment or owned-account transfer candidate |
| HDFC completed UPI mandate and successful AutoPay | Executed debit. Review investment destinations when unclear. |
| HDFC net-banking payment to a credit-card issuer | Bank-to-card transfer candidate. Confirm the actual destination card. |
| IDFC `Thank you for payment … towards … Credit Card …` | Repayment candidate; select paying account and confirm transfer |
| `you've used …% of the limit` | Ignored |

In transfer review, confirm **From** and **To**. When matching debit and credit alerts carry the same reference, approve the pair together. A late matching alert can be linked to an existing transfer. Wallet receives one transfer create request and returns the original and mirror record IDs.

In **Setup → Review notifications**, enable a heads-up alert for new outgoing payments that still need review, or enable a daily reminder for pending reviews and reconciliations. The reminder defaults to around 9 p.m. local time. Android controls heads-up presentation and can delay background work.

Balances and available credit limits are never used as transaction amounts by built-in parsers. Unfamiliar financial messages, including other banks, enter manual/AI review; AI suggestions must be checked because a model can select the wrong number despite valid JSON. Income can be entered manually or learned from reviewed templates. OTPs and unrelated messages are discarded before AI processing.

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

Only synthetic fixtures belong in this public repository. Never commit API tokens, SMS exports, account identifiers, signing keys, or `local.properties`. SMS awaiting review is kept encrypted on-device and excluded from backups. Extracted data and learned knowledge live in app-private Room storage; the whole database is not independently encrypted. Android cloud backups and device transfers are excluded. The app has no analytics or HTTP body logging. The selected AI provider receives eligible SMS text and category labels with request-specific aliases. Wallet credentials and account mappings are stored on the phone.

Architecture and API details: [architecture](docs/ARCHITECTURE.md), [Wallet contract](docs/WALLET_API.md). The app is unofficial and is not affiliated with BudgetBakers.

## License

App source is MIT licensed. Dependencies retain their own licenses. BudgetBakers and Wallet names belong to their respective owners.
