# Separate IPTV renewal worker

Recovery update: installation now runs a read-only startup preflight as the
renewal user before launching systemd, and retains credential-free stage/error
evidence in the original private state directory. It allows up to180 seconds for
native SSH/pool startup instead of the original short polling window. A failed
preflight never submits renewals or modifies panel schema; it reports which
prerequisite failed and retains the existing key/ledger/config. This improves
diagnosis/recovery; it is not proof that an unknown server failure is resolved.

This is a NEW service, not a patch to `iptv-auth-worker` and not the earlier
catalogue-only extension. Do not run the old catalogue installer for this setup.

## Reuse without altering the auth worker

- Own folder `/opt/iptv-renewal-worker`, port `127.0.0.1:9093`, systemd unit
  `iptv-renewal-worker.service`, and state `/var/lib/iptv-renewal-worker`.
- Own unprivileged `iptvrenewal` account and own Python environment. Initial
  dependencies are constrained to the working auth environment's versions, read
  as metadata only; nothing is installed into/changed in the auth environment.
  No original ACL changes or auth-service restart. The auth process need not run
  for the new service to run after setup.
- Initial setup copies the existing auth worker's server-local `config.py` and
  `.env` into the NEW private folder, root-owned/group-readable only. Existing
  copies are preserved on updates. Private values never enter this public ZIP,
  the website, APKs or chat. The old source/config/env/trial ledger stay untouched.
- Frozen native helpers come from the supplied reviewed source (SHA256
  `c5a93d1a4d98f214a4427e8ac0ca7dbdcb03fb5d59632ad4212740a188a7e8d1`).
  No legacy login/create/delete/change-package/trial routes are included. Native
  calendar calculation, owner/line locks, credit journal and panel refresh are
  reused, not guessed REST/billing APIs. Native logs omit private response details.

## Paid renewal contract

Only signed server requests can renew. The caller must prove a verified payment;
the website performs Rampex signature + merchant status/amount/currency checks.
The worker independently verifies the real reseller, primary fingerprint, current
package assignment, enabled/unrestricted status, package eligibility/fingerprint
and credits under its native DB locks. It never switches packages. The same line's
expiry extends while credentials, activation code, bouquets, notes and connection
overrides stay unchanged. There are no public account creation/deletion routes.

The line extension, native credit movement and `BRN1` payment-operation marker
commit together. The involved tables must support transactions and the journal
must fit the full marker. Duplicate/lost replies reconcile the same operation;
native refresh failure retries refresh only. One provider reference cannot be
reused by another reseller. The worker's independent private ledger key is anchored
in its environment; missing/corrupt state is held, never reset automatically.
Never restore an older ledger over newer committed history or recreate keys to
work around a review state. Back up the original state/env together privately.

## Installation on the existing worker HOST (not website host)

Extract the verified new-worker ZIP in a new root-private directory and run:

```sh
bash install.sh
```

Only the NEW renewal service starts/restarts. Its source/unit are backed up and
rolled back on readiness failure. New ledger/key state is preserved, never rolled
back over committed operations. Auth-worker files are hash-checked unchanged.
Startup verifies the DB journal/transaction prerequisites and authenticated status;
it does not renew a line, charge a card, create a trial or alter panel schema.

The new private website connection file is
`/root/renewal-worker-website-connection.json` (root0600). Transfer it DIRECTLY to
`/root/` on the website host by your trusted SSH/SFTP access. Never paste it into
chat, place it under public/, or reuse the portal/trial/Rampex secrets for this key.

## Public routing

The installer attempts narrow Nginx discovery automatically. It chooses the unique
existing HTTPS server, or the existing HTTP auth origin behind your established
TLS proxy, without changing SSL settings. Ambiguous/shared/custom renewal routes
are retained for review. For an explicit site path, run as root:

```sh
/opt/iptv-renewal-worker/venv/bin/python /opt/iptv-renewal-worker/configure_proxy.py --site /PATH/TO/EXISTING/WORKER-SITE.conf
```

Alternatively use `nginx-location.conf` INSIDE that existing service-bearing server,
on the machine that actually proxies `resellerworker.extremeiptv.net`.
It routes ONLY `/branded-renewals/` to the new loopback port9093. Existing auth,
portal and trial paths remain on their current upstream. Back up the relevant
Nginx file, validate with `nginx -t`, then reload Nginx (not any other worker).
Do not assume loopback refers to the worker if the proxy is on another host;
use your established private upstream in that case, never expose port9093 publicly.

The full signed `/status`, `/catalog`, `/eligibility` and `/renew` routes are
available under that prefix. There is no catalogue-only flag blocking the service.
The website import verifies full capabilities and its offered packages. Connecting
Rampex verifies each reseller's own merchant key; customer checkout still requires
the normal owner-enabled rollout, current package pricing and genuine expiry.

## Acceptance and operations

The real-SQL suite uses a new socket-only MariaDB and synthetic rows; no live
panel credentials/tunnel/HTTP are imported. It tests native credit triggers,
atomic rollback, concurrency, duplicate/cross-reseller payment fencing, current
package changes, credential/data preservation and lost-reply/refresh-only repair.
HTTP checks cover server signatures, capability/status and loss of original state.
Actual Overcast refresh, Rampex transaction and device unlock must still be checked
on the owner's specifically chosen test line before a customer rollout is claimed.

Safe logs: `journalctl -u iptv-renewal-worker.service`. Do not send private config,
env/export files, ledger keys or raw customer records. No customer APK publication
or main promotion is part of this worker deployment.
