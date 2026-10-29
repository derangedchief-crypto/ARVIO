# Initial connection: offered packages only

This bundle is for the EXISTING reseller AUTH worker host (38.190.133.171),
`/opt/iptv-auth-worker`, service `iptv-auth-worker.service`, user `resellerworker`.
It is NOT the website host (38.190.133.176) or the separate activation/EPG worker.

## What it changes

The installer accepts only the reviewed original main.py SHA256
`c5a93d1a4d98f214a4427e8ac0ca7dbdcb03fb5d59632ad4212740a188a7e8d1`,
optionally with the exact established trial hook. Unknown source is preserved and
refused. It appends only a catalogue route hook, adds two read-only modules and a
separate systemd EnvironmentFile. No original main/config/private credentials or
trial module/ledger are supplied, replaced or reset. The native portal and existing
trial policy are unchanged; only the auth-worker service is restarted. Failure of
original health, trial-route auth or signed catalogue readiness restores original
worker code/service override. Preserve the printed private backup directory.

The new bridge exports signed offered package definitions and CREDIT costs under
the reseller's existing panel group/override restrictions. It uses a transaction
started READ ONLY, always rolled back, and contains no native renewal/debit/save
calls. It does not install the paid adapter or ledger. `/renew` and `/eligibility`
under `/branded-renewals/` are ALWAYS denied, even if an environment write flag is
changed. Existing ordinary portal/trial operations are not disabled or modified.

## On the AUTH worker host

Extract the verified bundle into a new root-private directory, then:

```sh
bash install-catalogue.sh
```

A new separate random catalogue key is retained root0600 in
`/etc/extreme-renewal-catalogue.env`; repeat installation reuses it or refuses a
conflict, never silently rotates keys. A private connection export is written to
`/root/brand-renewal-website-connection.json` (root0600).

## Private transfer between hosts

Transfer that JSON file directly to `/root/` on the WEBSITE host using your normal
SSH/SFTP access. For example, from the AUTH host if root SSH is available:

```sh
scp -p /root/brand-renewal-website-connection.json root@38.190.133.176:/root/
```

Use established host-key trust; do not disable host-key checking. If SSH root login
is unavailable, use your existing privileged SFTP/file-transfer process. Do not put
the file in the website folder/public/, attach it in chat, or print its contents.
No new account/login method or authentication bypass is installed here.

## On the WEBSITE host

Install website0.27.3 UPDATE, preserving its private config/storage. Run:

```sh
chmod 600 /root/brand-renewal-website-connection.json
bash /www/wwwroot/control.fecxbrfzku.net/deploy/connect-renewal-catalogue.sh
```

The importer requires a verified owner-reseller workspace. It checks nonce-bound
signed read-only status and its REAL offered package list before installing the
connection. It preserves existing secrets and refuses a conflicting prior key.
Payment rollout is off; live Rampex API calls/simulation are disabled, and the
website itself refuses checkout/renew calls in catalogue-only mode. Other config,
trial/builder policies and application keys remain unchanged. No broad PHP restart.

Send only the safe JSON result (`connected`, `catalogue_only`, `writes_enabled`,
`payments_enabled`, `package_count` and package names/credit costs), never the file.
Hard-refresh Customer renewals: provider packages should populate for pricing.

If the public `/branded-renewals/` path is not proxied, reuse the existing reseller
worker's upstream for that location only. Never point it at the website PHP server
or change working portal/trial/activation/EPG routing to work around a failed check.

This initial connection authorizes NO charge, account creation or line renewal.
Paid fulfilment still needs separately tested/approved atomic integration and live
Rampex contract acceptance. Changing a flag cannot turn this bundle into it.
