# Device management 0.2.0 — validation

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
