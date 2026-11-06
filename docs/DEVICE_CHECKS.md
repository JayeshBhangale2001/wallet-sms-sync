# Device acceptance checks

Use a test emulator or dedicated device and synthetic SMS only. Do not put credentials or financial messages in screenshots or CI logs.

1. Install and launch the debug APK. Verify setup, empty history and navigation, grant/deny SMS permissions, and confirm denial explains the next action.
2. Save a test token, restart the app, verify persistence, and remove the token. Confirm there is no token in logcat or plaintext preferences.
3. On a 64-bit emulator/device, send synthetic SMS. Test IDFC purchase, HDFC multiline UPI, repayment, limit alert, OTP and unrelated text. Confirm multipart messages assemble once and sender/heading appear in the review title/Wallet note.
4. Import an inbox date range. Verify preview creates no entries or HTTP requests. Import twice: existing fingerprints are skipped. Imported entries always require approval.
5. With a disposable Wallet test account, map INR accounts. Verify grounded expenses/income queue directly only with automatic sync enabled and previously confirmed account ownership. Uncertain amounts, unmapped accounts, repayments and transfers stay in Review. Historical imports remain reviewed.
6. Verify edits, category overrides, manual income and ignored candidates. Confirm date-only timestamps and precision in JSON.
7. Confirm a transfer between two owned accounts, link matching evidence, and verify one paired transfer in Wallet. Check both returned IDs.
8. Go offline before a queued upload. It must remain queued. Reconnect and verify WorkManager resumes. Exercise HTTP 401/403, 409 and 429 with a controlled test server/build rather than real throttling.
9. Interrupt a request after it may have reached the server, or kill the process during Sending. On restart confirm Uncertain, marker lookup, and no automatic repeat create. Verify missing mirrors also require reconciliation.
10. Check app backup is disabled, permission revocation is handled, screen layout on a small phone, and resume after device reboot/background restrictions.
11. Import a compatible `.litertlm` model in Setup. Run the sample and measure cold/warm time, GPU-to-CPU fallback and CPU selection. Compare output to held-out redacted SMS; do not infer accuracy from a model's self-reported confidence.
12. Enable AI, process a new template, apply/correct suggestions, and approve with remembering enabled. Restart and verify mappings, merchant categories and templates persist. Confirm future matched messages bypass inference; conflicting formats stay uncertain.
13. Verify a direct AI upload uses grounded transaction amount rather than balance, only uses existing confirmed ownership, caches an AI-labeled category, proposes a disabled template and removes encrypted original evidence. New model-suggested account IDs alone must not authorize direct sync.
14. Review/activate a proposed template in Learn. Disable/forget it and merchant/account mappings. Correct a classification and verify its previous trust is revoked. Check the uncertainty/correction history and ciphertext removal after ignore/linking/approval.

These manual checks complement the instrumented Room/Keystore tests and local parser/mock HTTP tests. See VALIDATION.md for checks actually run; this list is not a claim that device checks passed.
