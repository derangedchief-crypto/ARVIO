# Automatic device recovery and fresh check-in lists — staging

## User decisions

User approved the best-effort automatic approach, not a guarantee after factory
reset. For the existing inventory the user explicitly selected **fresh check-ins**
for **owner and all resellers**, NOT deletion/archive/revocation. Development goes
to staging; main promotion and APK publication require separate approval.

## Recovery protocol

- Android 8+ scoped Android ID is validated (including rejection of known broken
  constants), then SHA256-namespaced by package before transmission over TLS.
  It is not stored as raw Android ID, and is not a secret or hardware attestation.
  No new identifier permission, MAC/serial access, OTP or embedded shared secret.
- Server advertises capability version 1. New apps send no new fields to old
  servers. Existing installations backfill the hint on authenticated check-in,
  with a signature from their EXISTING management key; the first response advertises
  support and the following check-in sends the proof. New registrations bind the
  hint into the ECENROLL2 signature plus the existing fresh signed account proof.
- The server HMAC-scopes the matching value to verified account, package and panel
  reseller using its existing private secret. No raw/client matching hash in the
  public listing, audit metadata or logs. Brands/test/customer variants do not
  silently merge into one another.
- Only a unique historical match in the same verified ownership scope is eligible.
  It reuses the original database device ID, customer/workspace assignment, custom
  label, notes, enrollment time and history. Owner-unassigned records stay owner
  managed. Suspended/blocked restrictions remain; removed records never auto-revive.
- A recent check-in OR hint proof gives the old installation a five-minute quiet
  window. Do not kick off a currently checking-in TV because somebody copied its
  non-secret hint. Pending clients retry enrollment with fresh account proof after
  five minutes. Original credentials are retained until a recovery commits.
- Recovery retires old credentials, rejects superseded old-key enrollment delivery,
  cancels old queued/leased commands and active setup jobs, then issues the new key
  a new credential. Completed history and saved server records are not deleted.
- Same-account multiple TVs with different established hints remain separate.
  Multiple matches, conflicting ownership or unidentified legacy records are held
  outside the main device list. Never guess from model, label, IP or account alone.
  Compatible apps temporarily hold ordinary navigation while verifying a recovery
  so that a lost key cannot bypass a known restriction. They display a dedicated
  reconnection notice, not the normal activation form or a false expiry message.
- Per-account/package database locks serialize competing claims. They are not
  global fleet locks. Recovery reuses the existing allocation slot.

## Limits and rollout order

These values can be missing, spoofed, copied, changed by OEM software or reset with
the Android user/factory state. Hashing does not make them authentication. Fresh
proof/key possession and policy fencing are still mandatory. Physical identity
cannot be guaranteed without surviving trusted evidence.

Previously released apps (including 125) did not send this hint. An installation
must run the new compatible app and check in BEFORE a later reset/reinstall can
automatically match it. Lost historical identity cannot be retroactively recovered.
Ambiguous/legacy exceptions remain retained pending requests, not silent merges.
They may still require exceptional owner investigation; do not promise zero manual
work for every possible reset. Normal app updates retain existing keys without
needing the new recovery path.

Website 0.26.0 / migration025 collects supported proofs but automatic reassignment
defaults OFF. Deploy website support first; test the new app's backfill/reinstall
path before enabling **Automatic reconnection**. This feature is independent of
primary activation: it does not bypass account expiry, change primary playlists,
grant add-ons, revive blocked devices or reset trial history.

## Fresh list semantics

Owner-only reset increments a global visible-list generation. Records, credentials,
last-seen history, quotas and restrictions are unchanged. Existing apps need no
update for visibility: on a successful ordinary check-in the existing row records
the current generation and reappears once. Offline ghosts stay hidden by default.
Both the default device listing and overview device counts honor the generation.
All retained records remain accessible through the explicit view and existing
authorized detail/history links, with the same tenant boundaries.

This does NOT free device allocation or delete old rows. Capacity accounting still
includes retained records. Do not silently turn a visibility reset into billing,
hard deletion, bulk unassignment or unrestricted fresh enrollment.

## aaPanel website deployment (after validation)

1. Back up the website/database using the owner's normal aaPanel process.
2. Upload/extract `Extreme-Control-Center-0.26.0-Update.zip` into the existing
   `/www/wwwroot/control.fecxbrfzku.net` website folder. The update excludes private
   config.php/storage. Do NOT upload to the activation/management worker host.
3. In the WEBSITE console:

   ```sh
   cd /www/wwwroot/control.fecxbrfzku.net
   /www/server/php/84/bin/php -d opcache.enable_cli=0 cli/console.php migrate
   /www/server/php/84/bin/php -d opcache.enable_cli=0 cli/console.php fresh-checkins --all-workspaces
   ```

4. Reload this website's PHP handler in aaPanel if opcode caching retains old code;
   hard-refresh the console. Devices appear as they next open/check in, not merely
   because the owner visits the page. No reinstall or clearing app data is needed.
5. Leave Automatic reconnection OFF until the new compatible app has passed the
   device test and deployed clients have established hints. It is an owner setting,
   not something each reseller must approve for normal unique recovery.

None of these deployment/reset commands have been run on the live website during
development. No worker/proxy/panel credentials or signing keys are changed.

## Acceptance evidence

All 261 service checks and 5 focused browser checks pass, including simultaneous
claims, same-slot recovery at capacity, owner-unassigned retention, preserved
restrictions, old-credential retirement, signed backfill, no-delete fresh-list
reset and return of the SAME device after check-in. Mobile/desktop overflow and
reseller cross-tenant/owner-only control checks pass. Final native validation passed
1,016 tests (zero failures/errors, one existing skip), plus both variants compiled.
83 branding checks and package reproducibility/private-storage preservation checks
also pass. Source baseline metadata is aligned to the released125/441 platform;
this does not publish the feature or produce an APK. Any new signed APK must receive
a higher version/code before publication.
Logs: `/tmp/opencode/device-recovery-web-acceptance.log` and
`/tmp/opencode/device-recovery-native-acceptance.log`.

Required physical test: existing app -> compatible update -> at least two check-ins
-> record hint established -> controlled reinstall/reset of that dedicated test
installation -> same server device ID after quiet window, without duplicating it.
This is NOT a promise to recover local playlists/favourites erased by the user;
device inventory recovery and local app-data recovery are different operations.
