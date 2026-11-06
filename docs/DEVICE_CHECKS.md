# Device acceptance checks

Use a test emulator or dedicated device and synthetic SMS only. Do not put credentials or financial messages in screenshots or CI logs.

1. Install and launch the debug APK. Verify setup, empty history and navigation, grant/deny SMS permissions, and confirm denial explains the next action.
2. Save a test token, restart the app, verify persistence, and remove the token. Confirm there is no token in logcat or plaintext preferences.
3. In an emulator, send synthetic SMS using Extended Controls → Phone. Test IDFC purchase, HDFC multiline UPI, repayment, limit alert, OTP and unrelated text. Confirm multipart messages are assembled once.
4. Import an inbox date range. Verify preview creates no entries or HTTP requests. Import twice: existing fingerprints are skipped. Imported entries always require approval.
5. With a valid disposable Wallet test account, map INR accounts. Verify new recognized card expenses queue only when automatic sync is enabled. Confirm UPI and repayments remain in Review.
6. Verify edits, category overrides, manual income and ignored candidates. Confirm date-only timestamps and precision in JSON.
7. Confirm a transfer between two owned accounts, link matching evidence, and verify one paired transfer in Wallet. Check both returned IDs.
8. Go offline before a queued upload. It must remain queued. Reconnect and verify WorkManager resumes. Exercise HTTP 401/403, 409 and 429 with a controlled test server/build rather than real throttling.
9. Interrupt a request after it may have reached the server, or kill the process during Sending. On restart confirm Uncertain, marker lookup, and no automatic repeat create. Verify missing mirrors also require reconciliation.
10. Check app backup is disabled, permission revocation is handled, screen layout on a small phone, and resume after device reboot/background restrictions.

These manual checks complement the instrumented Room/Keystore tests and local parser/mock HTTP tests. See VALIDATION.md for checks actually run; this list is not a claim that device checks passed.
