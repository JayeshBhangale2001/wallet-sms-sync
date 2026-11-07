# Architecture

`core` is a JVM library with deterministic template parsing, exact decimals, fingerprints, Wallet serialization, AI evidence validation, prompt construction and reviewed-template compilation. `app` owns Android permissions, Compose UI, Room persistence, Keystore encryption, OkHttp, and WorkManager.

## Data path

1. Incoming multipart SMS is assembled by `SmsReceiver`; historical SMS is queried from the inbox by receipt date.
2. `SmsParser` handles known formats. A bounded financial filter catches unfamiliar banks while excluding OTPs/unrelated texts. Reviewed templates are tried before inference. Conflicting matches remain uncertain.
3. `Repository` applies mappings/category rules and inserts under unique fingerprints plus a retained message/reference ledger. Historical candidates always enter Review. New trusted expenses may queue automatically; new merchants/missing mappings can request AI when enabled.
4. Review validates amount, type, timestamp, INR accounts and category. Confirmed same-currency transfers may link one matching review candidate as evidence in a Room transaction.
5. A single unique WorkManager chain atomically claims one queued item. It saves Sending before the HTTP call, then handles individual response outcomes. The app writes one record per request to simplify partial-result correlation.

## Cloud AI and local learning

A network-constrained WorkManager chain sends one eligible SMS at a time with the local extraction and aliased Wallet account and category choices. Groq uses GPT-OSS 20B Chat Completions. xAI uses Grok 4.3 Responses with storage disabled. The app validates structured fields and choice aliases locally. An AI suggestion has no authority to establish account ownership.


AI produces JSON suggestions, not executable parsers. Values must be evidenced in the SMS, dates use a fixed format allowlist, and Wallet IDs must belong to the current catalog. Known parser facts are preserved. Uncertain suggestions stay separate until applied in Review. For new messages with automatic sync enabled, the application can queue a transaction only when its amount matches an explicit direction phrase (or trusted built-in parsing) and its account mapping was previously confirmed. Unknown accounts, conflicting formats, transfer/repayment types, failure messages, ambiguous amounts and historical imports stay reviewed. The model has no API execution access.

Reviewed approval can save account ownership mappings, exact merchant categories and a template. Repayment account identifiers map to the confirmed destination card, not the paying account. Templates use escaped literals and application-generated bounded field slots, with unique/overlap checks and an extraction round trip. Model-supplied regex/code is never evaluated. Ambiguous evidence saves no template. Redacted template examples replace extracted fields, numeric metadata and links.

Templates proposed by automatic AI processing start disabled, with origin AI and zero user confirmations. Manual corrections create enabled rules; conflicting classification changes disable the old rule until confirmed again. Enabled rules still pass the direct-sync gate. AI category choices are cached with AI provenance, while human corrections take precedence. Users can disable/forget templates and mappings/category rules. Original uncertainty, before/after fields and rule ID are retained for automatic and reviewed decisions; this is a knowledge library, not model fine-tuning.

Room schema 2 adds encrypted evidence, learned templates and decisions. `MIGRATION_1_2` preserves existing schema-1 history and mappings. Original SMS lookup uses the encrypted evidence or checks the inbox against stored message identity. Older imports without an evidence hash use their inbox ID and a verified fingerprint. Users can attach a copy locally when recovery is unavailable. Evidence hashes prevent re-importing an SMS when parser identity changes. Dateless messages include a timestamp in their identity to distinguish identical receipts.

## States

`REVIEW → QUEUED → SENDING → SYNCED`

- `REVIEW → IGNORED`: user dismisses candidate.
- `REVIEW → LINKED`: user identifies a second SMS as evidence for another transfer.
- `SENDING → REVIEW`: definitive client validation error.
- `SENDING → QUEUED`: authentication, throttling or initial-sync refusal. Authentication waits for a user-triggered resume; server-specified cooldowns schedule delayed work.
- `SENDING → UNCERTAIN`: transport failure, server write error, unexpected response, missing mirror, or process interruption.
- `UNCERTAIN → SYNCED`: exact-marker reconciliation for a regular record, or user-confirmed remote IDs.
- `UNCERTAIN → REVIEW`: user explicitly verifies absence in Wallet; linked evidence is released for review as part of the same database transaction.

Interrupted Sending rows are recovered before the next worker processes its queue. There is no claim of exactly-once delivery across an API without create idempotency keys. Uncertain writes are intentionally held for reconciliation.

## Deduplication boundaries

Reference-bearing SMS uses bank/account key, reference, kind and amount. Other supported templates use normalized body (including the bank's written date/time) and account, so a phone timezone change does not change their keys. Incomplete messages additionally use receipt time. Fingerprints work across broadcast receipt and historical re-imports. Equal non-reference messages at the same precision may collapse; ambiguous/manual entries require human review. Deduplication is local to this installation and does not match transactions imported by Wallet's bank integration or other clients.

## Security

The app encrypts Wallet, Groq, and xAI credentials with separate Android Keystore keys. Changing the AI provider disables cloud processing until the user enables it for that provider. Original SMS awaiting review uses its own key, including with cloud AI off. The encrypted copy is removed on approval, ignore, direct sync or transfer-evidence linking. Wallet receives the transaction fields, SMS title and import marker. API clients use fixed HTTPS hosts and disable redirects.

Inbox imports use the SMS sender timestamp when available to match live-receiver evidence while keeping the receipt timestamp for review and date-range filtering. The ledger and unique constraints also check legacy receipt hashes, transaction fingerprints, and bank references.

Local backups export a consistent Room snapshot to a user-selected document. A passphrase-derived key (PBKDF2-HMAC-SHA256) encrypts and authenticates the versioned file with AES-GCM. Restoring imports the snapshot in one database transaction on a fresh installation. Pending uploads become uncertain and retain their original IDs for reconciliation. Backup documents omit credentials and retained SMS ciphertext.

## Extension points

Add bank/date-specific formats and synthetic regression tests in `core`. Add parser versions when semantics change and explicit Room migrations for schema updates. Cross-currency transfers, model fine-tuning and public-store distribution remain future work.
