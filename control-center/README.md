# Extreme Control Center

PHP 8.3 / MariaDB backend and responsive owner/reseller dashboard for
`https://control.fecxbrfzku.net`, delivered as an aaPanel Nginx upload/extract ZIP.
Work is isolated on `reseller-control-center`; no changes to the Android app,
activation/maintenance services, central updater, `main` or publishing workflows.

## Current milestone

- One-time owner setup, private generated keys, password hashing, secure sessions,
  password change/session revocation, origin/CSRF enforcement and rate limiting.
- Reseller workspaces with owner-controlled allocations/suspension; scoped customer
  records and pending device provisioning, paginated search, notes, activity audit.
- Independent presence versus authorization policy, explicit test-device labels,
  expiring allowlisted commands, idempotency keys, leased delivery and acknowledgments.
- Authenticated check-in/ack API contract, encryption/redaction primitives, checked
  SQL migrations and cron retention. No arbitrary commands, APK URLs or remote shell.

**Not yet connected to customer apps.** There is deliberately no public enrollment
endpoint before activation identity/reseller ownership is agreed. Existing apps
must gain the management client in a later testing build; provisioning a record
does not make an existing customer device online. Local simulations are marked as
test devices and issuance is CLI/test-only, loopback-environment-only, never HTTP.

Playlist editing, settings profiles, category/favourite sync, diagnostic uploads
and screenshots are later milestones. Their dashboard tabs explicitly show that
they are not available. Android permission/OEM limitations still apply. Rebranding,
launcher icons/names and branded APK generation are deferred. Every customer and
reseller continues to use **our existing centrally controlled update source**.

## Hosting

Read [aaPanel installation](deploy/INSTALL-AAPANEL.txt) and the Nginx snippet.
Serve **only `public/`**. All runtime secrets/data remain in the parent directory.
PHP extensions: PDO/pdo_mysql, mbstring, OpenSSL, cURL, fileinfo. No Composer, Node,
Python or permanent queue/WebSocket service is needed on the hosting server.
There are no third-party production PHP packages in this initial milestone.

Copy `config.example.php` to private `config.local.php` on the server. Never commit
it, publish an initialized archive or put secrets in URLs. The DB must be dedicated
and empty on first setup. Backup/restore instructions include both DB and private
keys; a new encryption key cannot decrypt a restored database's previous ciphertext.

## Local development / integration tests

The Ubuntu 24.04 helper extracts official distro PHP/MariaDB packages into a
user-owned directory. It does not need sudo, a Docker socket or system installs:

```sh
bash control-center/dev/bootstrap-ubuntu.sh
bash control-center/dev/start-db.sh
# In another terminal, while that PRIVATE Unix-socket-only DB is running:
bash control-center/dev/php.sh control-center/tests/run.php
node --check control-center/public/assets/app.js
python3 control-center/tools/package.py
```

The test runner creates only randomly named `control_test_*` databases through the
local Unix socket, tests actual MySQL/MariaDB constraints and real HTTP endpoints,
and drops only those generated databases. It creates an isolated application copy
under `/tmp/opencode`, never overwriting `config.local.php` in this checkout. Test
credentials are synthetic, not defaults, and tests/dev tools are excluded from ZIPs.
Keep generated logs/backups private. Package hashes are recorded by the tool helper.

Optional real-browser checks use pinned Playwright 1.64.1 / Chromium, installed
outside the website with `bash control-center/dev/bootstrap-browser.sh`.
`bash control-center/dev/php.sh control-center/dev/demo.php` creates a NEW private
local preview directory/database with explicitly synthetic customers/test devices;
it prints its directory, not its generated password. Start PHP on 127.0.0.1:8186
with that directory's `public/` root and `dev/router.php`, then run:

```sh
LD_LIBRARY_PATH=/tmp/opencode/control-center-browser-tools/root/usr/lib/x86_64-linux-gnu \
  node control-center/tests/browser.cjs /PRIVATE/PREVIEW/PATH/preview-credentials.json
```

The browser suite refuses non-loopback URLs and non-preview credentials. It checks
actual owner/reseller login, literal rendering of hostile customer text, device
provisioning, disabled unsupported actions, queue visibility and mobile overflow.
Preview data is not evidence that a real Android management client is connected.

Tests cover tenant boundaries at service and HTTP levels, cookie/CSRF/origin checks,
private-file exposure, tampered requests, allocation/command state, revoked keys,
retries, suspension, encryption, migrations, retention and database backup restore.
See `test-results/integration.json` for the latest actual run; unsupported/unrun
platforms and real Android behavior are not inferred from these backend tests.

Live aaPanel/SQL connectivity and remote-server TLS have not been tested. No live
database credentials or production secrets are needed for local development/tests.
Delivery includes the repository's Apache-2.0 LICENSE and the complete PHP/JS source.
