# Backend foundation 0.1.0 — validation

Validated locally on Linux with PHP 8.3.6 and real MariaDB 10.11.14.

- **66 passing backend/database/HTTP cases**, zero failures: setup locking, typed
  input, authentication/CSRF/origin checks, tenant/resource isolation, private-file
  protection, rate limits, device policy, credential revocation, queue capabilities,
  leases/retries/expiry, idempotency, redaction/encryption and retention.
- Two-process concurrency exercises enforce device allocations and deduplicate
  simultaneous command requests against actual InnoDB transactions.
- Native MariaDB dump/restore into a separate generated test database verifies
  schema, records, migration checksums and decryption with the original private key.
- Missing encryption keys fail closed instead of being silently regenerated.
  Filesystem-authorized owner recovery revokes sessions without deleting data.
- **7 passing real-browser checks** with Playwright 1.64.1 / Chromium: owner and
  reseller login, hostile customer text rendered literally, pending enrollment,
  unsupported controls, actual queue visibility, scoped APIs and mobile layout.
  No uncaught JavaScript errors. Desktop and 390px-wide screenshots inspected.
- PHP, JavaScript and shell syntax checks pass. The ZIP is deterministic, its
  manifest matches contents, private/test files are excluded, and extracting it
  over an initialized local copy preserves configuration and encryption keys.

This is **not** a claim of completed Android device management or production fleet
capacity. Devices in preview screenshots are explicitly synthetic test simulators.
No live aaPanel site/database was contacted or deployed, and no supplied database
password was used. Production MySQL 8 compatibility, remote SQL certificate/TLS
handshake, real host permissions/cron and load capacity still require deployment
verification. Local MariaDB results are not represented as tests on those systems.

Existing APKs, activation, maintenance, app updates, fast live playback and original
Back handling are unchanged. App enrollment/ownership integration, configuration
sync and diagnostic/screenshot permissions remain subsequent plan stages. No
custom branding, reseller APK pipeline or reseller-controlled update channel is
included.
