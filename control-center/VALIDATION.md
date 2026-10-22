# Device management 0.2.1 — validation

## Automatic panel ownership update

- **93 real MariaDB/service/HTTP cases passed**, zero failures. New cases verify
  signed panel-ID ownership, automatic customer/device creation without per-device
  approval, reuse of customer records, unlinked identities staying pending, unique
  owner-only reseller links, atomic new-reseller linking, exact signed lookups,
  allocation/suspension checks, rejected/removed/manual-assignment protection,
  and pending-app fresh-proof retries. The full existing suite is included.
- **19 worker tests passed**, including the unchanged six-digit activation route,
  database-only ownership assertions, unique active exact-username lookup, invalid/
  stale HMAC rejection, and Python/PHP interoperability for both ownership proofs
  and the nonce-bound request/result signatures.
- **10 real-browser checks passed**, including the new panel-link form's prefilled
  username, existing tenant isolation and enrollment/command UI coverage.
- The original 001/002 migrations and all Android/updater/playback code are unchanged.
  Existing 2.0.107 APKs use the same opaque proof exchange; no APK release is part
  of this update. The Android tests below are the previous 0.2.0 validation, not
  a claim they were rerun for this PHP/Python-only change.
- Read-only panel access uses the schema confirmed in the uploaded auth worker.
  No supplied panel credentials, live panel lookup, deployment or real ownership
  transfer was used as part of these local tests. Deployment verification of the
  worker's `users` read permission and the website's outbound HTTPS remains needed.

## Previous Android/enrollment baseline (0.2.0)

Validated locally with PHP 8.3.6, real MariaDB 10.11.14 and an isolated Python
environment matching the existing activation-worker requirements.

- **84 passing backend/database/HTTP cases**, zero failures. Includes prior auth,
  CSRF/tenant isolation, leases/idempotency, backup/restore and retention checks,
  plus account assertions, device-key possession, replay/audience/expiry checks,
  owner-only approval, identity/account binding, encrypted credential delivery,
  renewal without an extra device slot, and retained suspension/removal policy.
- A real 0.1-schema -> 0.2 migration test preserves existing owner sessions, records,
  and encryption keys. The original applied 001 migration is byte-for-byte unchanged.
- **14 passing activation-worker tests** cover original activation compatibility,
  active-account credential checks, generic failures without password echoes,
  bounded throttling, Python-issued assertions verified by actual PHP code, and
  merging only the two bridge fields while preserving the worker's existing `.env`.
- **9 passing real-browser checks** include protected bridge-file download and
  owner approval/assignment, as well as reseller isolation, literal hostile-text
  rendering, command history, unsupported controls and mobile layout.
- **87 passing targeted Android JVM/Robolectric tests** include protocol/account
  scoping, signatures, bounded duplicate journals, expiry, policy/message UI,
  activation, updater/relaunch, live output and DTS/DV regressions. Internal-test
  and Android-instrumentation Kotlin compilation passed.
- The native ManagementVault instrumentation smoke test is COMPILE-ONLY here.
  Android Keystore behavior, real package updates and physical-device enrollment
  have not been executed locally and remain part of the owner-device pilot.
- PHP/JS/installer syntax and install/update ZIP checks pass. Update ZIPs omit
  config.php and initialized storage; worker packages contain source only.

The database/browser validation starts a NEW private socket-only local DB and
temporary HTTP server for the test run and stops both afterward. It does not
restart the previous user-stopped preview services or contact live customer DBs.
Preview records and cryptographic issuer fixtures are explicitly synthetic.

This is not a claim of completed remote management or fleet capacity. Real worker
deployment/Nginx routing, APK Keystore operation, check-in/policy/message behavior,
host permissions and capacity still need a controlled owner-device pilot. No live
activation service or customer release was modified by these tests.

The fast live engine, original Back handlers, entitlement selection and central
update source remain unchanged. Branding and advanced remote refresh/settings/
playlists/diagnostic/screenshot capabilities remain deferred or subsequent stages.
