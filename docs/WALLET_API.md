# Wallet API contract

Verified against the public [Wallet OpenAPI](https://rest.budgetbakers.com/wallet/openapi) and [reference](https://rest.budgetbakers.com/wallet/reference) on 2026-11-06. No authenticated live writes were used.

- Base: `https://rest.budgetbakers.com/wallet/v1/api/`
- Header: `Authorization: Bearer <user token>`; API access requires the appropriate Wallet entitlement.
- `GET accounts` and `GET categories`: named arrays and optional `nextOffset`; request `limit=200` and follow offsets. Archived/disabled items are excluded locally.
- `POST records`: JSON **array** of `CreateRecordRequest` objects. The app submits one at a time.
- Required fields: `accountId`, `amount: {value, currencyCode}`, `recordDate` (ISO instant). Expenses are negative; income is positive. Currency must match the account.
- Optional fields used: `categoryId`, `counterParty` (max 255), `note` (max 255). Notes include a sanitized SMS sender/heading (up to 120 characters) plus the complete `wallet-sms-sync:<UUID>` marker.
- Transfer: negative source amount plus `transfer: {pairingMode: "new", accountId: destination}`. Wallet creates the mirror; the app does not submit a second ordinary expense/income.
- Results: inspect `results` by `inputIndex`, not only HTTP status. A successful source has `id`; paired transfers also need `createdMirrorRecordId`. Mirror failures may still report source success.
- `200`, `207`, `400`, `500` can carry per-item envelopes. Authentication/entitlement failures are `401`/`403`; throttling is `429` with `Retry-After`. Initial data sync can return `409`.
- Current documented sustained allowance is 300 requests/hour, shared with MCP. The upload worker spaces writes by at least 20 seconds (at most 180 writes/hour before other calls), persists cooldowns, and honors longer retry delays. Setup/reconciliation calls also honor refusals; other clients share the same budget.
- Reconciliation queries `GET records?note=contains.wallet-sms-sync:<local UUID>` to find both old marker-only notes and new notes containing SMS titles. API data can lag behind Wallet writes. No absent-marker auto-retry is implemented.

The fetched full schema is a local ignored verification artifact, not part of the app. The public schema may evolve; update contract tests with integration changes.
