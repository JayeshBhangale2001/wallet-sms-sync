# Architecture

`core` is a JVM library with deterministic template parsing, exact decimal amounts, normalized fingerprints, Wallet request serialization, and write-result classification. `app` owns Android permissions, Compose UI, Room persistence, Keystore credentials, OkHttp, and WorkManager.

## Data path

1. Incoming multipart SMS is assembled by `SmsReceiver`; historical SMS is queried from the inbox by receipt date.
2. `SmsParser` returns a structured transaction or discards the message. Bodies are not stored. Unsupported financial texts yield an incomplete review candidate rather than guessed amounts.
3. `Repository` applies account mappings and category rules, then inserts under a unique fingerprint. Historical candidates always enter Review. New mapped, unambiguous expenses can enter Queued when automatic sync is enabled.
4. Review validates amount, type, timestamp, INR accounts and category. Confirmed same-currency transfers may link one matching review candidate as evidence in a Room transaction.
5. A single unique WorkManager chain atomically claims one queued item. It saves Sending before the HTTP call, then handles individual response outcomes. The app writes one record per request to simplify partial-result correlation.

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

Token ciphertext is AES-GCM with a non-exportable Keystore key. No token is shipped in the APK. Only required Wallet transaction fields and a random local correlation marker leave the device. Wallet has a fixed HTTPS host; redirects and automatic connection retries are disabled. Logs contain neither SMS nor request bodies. App backups and cleartext HTTP are disabled. Financial data stays in app-private Room storage; uninstall removes local mappings and deduplication history.

## Extension points

Add bank-specific templates and synthetic regression tests in `core`. Add parser versions when semantics change. Introduce Room migrations for later schema changes; never use destructive migration for transaction history. Automatic income parsing, broad bank coverage, cross-currency transfers, and public-store distribution are future work.
