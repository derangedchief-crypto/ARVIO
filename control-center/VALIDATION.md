# Device management 0.7.0 — validation

## Favourites recovery and verified line usernames

- **125 real MariaDB/service/HTTP cases passed**, zero failures. New tests verify
  encrypted nonempty immutable backups, first-good protection, empty-state status
  updates WITHOUT replacing/deleting the good version, deduplication, pin quotas,
  protected retention, same-device context authorization, tenant isolation and
  former-reseller denial after unassignment. Recovery previews bind lease/backup/
  profile/source/counts; stale/expired previews and partial destructive Replace are
  rejected. Browser views never expose original favourite identities/ciphertexts.
- Username tests recompute the activation account HMAC from the candidate and
  verify it belongs to the device's approved key/package. Spoofed labels do not
  populate names. Correct names appear/search in customer/device lists, remain
  available to the owner after detachment, and stay hidden from the old reseller.
  Existing schema-upgrade and concurrency checks remain green; idempotency reads
  are locking/current reads even when username projection queries run first.
- **21 browser checks passed**, including signed-account username display and
  removal of Add customer UI, a protected2-channel/1-group version retained after
  a simulated empty live state, fresh recovery preview, default merge queue and
  separate receipt/live report before restored counts are shown. Native protocol
  drivers here are explicitly SYNTHETIC, not physical Android restoration proof.
- **130 Android JVM/Robolectric cases passed**, zero failures. New cases cover
  empty-vs-good content/source identity, one-call merge recovery preserving current
  order, explicit Replace, missing/duplicate/foreign identities, stale previews,
  source/profile changes and no writes during previews. Previous activation,
  update/relaunch, live output/codec and whole-layout regressions remain green.
- App/internal-test/instrumentation Kotlin compilation passed. Native favourite
  restore/automatic capture and actual phone/TV storage behavior require the111
  controlled pilot. No data-reset/reinstall migration or timing guarantee claimed.
- Migrations008/009 add encrypted history/live status/short-lived previews and
  verified username mappings. Earlier migrations/worker source are unchanged.
  No additional worker patch or Android permission. Website0.7.0/migrations must
  precede111. Config.php, private keys and approved inventory are preserved.

The test disk filled during the first Android rerun. Only completed isolated test
DB data and this variant's truncated generated reports were removed; source/user
previews were preserved. Clean rerun passed. The temporary DB harness now removes
its own stopped database data (or retains it with --keep-db), preventing buildup.

Recovery requires a good version captured BEFORE loss. Empty can never become a
backup; protected versions never auto-expire. Automatic capture is foreground/
check-in based, not continuous/offline, and cannot reconstruct uncaptured changes.
Source/profile/installation changes are not guessed as valid restore targets.

## Previous whole-layout increment (0.6.0)

- **119 real MariaDB/service/HTTP cases passed**, zero failures. New coverage
  checks separate bounded whole-layout snapshots, latest-three cache retention,
  command-history/ACK size, atomic-push idempotency/capability/scope checks,
  exclusive large delivery, complete reference validation, authenticated large
  uploads, safe portable names, versioned template CRUD/copy/tenant boundaries
  and CSRF. Worst-case2000-row Unicode reads upload under2MiB and ACK only small
  metadata;2000-ref order/hidden pushes fit the client's512KiB response limit.
- **18 headless browser checks passed**, including many local position/filter/
  bulk-visibility edits with ZERO native commands, saving a custom ordered
  template without pushing, one complete mutation command plus explicit success/
  fresh confirmation read, and cross-device exact-name template preview/application
  that remains LOCAL until Push. Native protocol receipts in this E2E test are
  explicitly SYNTHETIC drivers, not actual Android execution.
- **6 pure browser-draft tests passed** for complete payloads, cross-device ref
  mapping, missing/unmatchable/ambiguous names, provider mode and explicit rebase
  preserving edited values while adopting untouched fresh values. Source/profile
  changes cannot rebase by guessing. Drafts are cleared on logout/session expiry.
- **123 Android JVM/Robolectric cases passed**, zero failures. Full-read tests
  verify complete data, provider positions, portable-name eligibility and scoped
  transient retry targets. Whole-layout cases reject partial/duplicate/foreign
  sets, preserve other/unavailable keys, and verify ONE guarded repository call,
  not a per-category loop. Previous activation/update/live/codec tests remain green.
- App/internal-test/instrumentation Kotlin compilation passed. Real full-layout
  SQLite/DataStore/Keystore behavior and phone/TV UI changes still need the110
  pilot. No physical latency/fleet capacity or automatic offline waking claimed.
- Migration007 preserves configuration/approved devices and adds caches/templates.
  Large body parsing is authenticated first; normal endpoints remain32KiB.
  Nginx snippet changes64k ->2m for whole-layout traffic. No worker, player,
  Android permission, Back handler or central updater/workflow changes.

This is custom Live TV CATEGORY order/visibility, not arbitrary channel-row order,
full source URL editing, movie/series category editing, payment processing or
automatic fleet-wide rollout. Templates use exact safe complete names with an
explicit preview; shortened/redacted names never become guessed portable IDs.

## Previous source-scoped category increment (0.5.0)

- **113 real MariaDB/service/HTTP cases passed**, zero failures. New cases check
  closed opaque-reference operation payloads, profile/source/page response matching,
  capability/lease/tenant enforcement, duplicate/malformed row rejection, title
  redaction, precise read chronology/idempotency and separation of read catalogs from mutation receipts. A worst-case
  Unicode50-row/32-source page fits BOTH the actual32KiB HTTP upload limit and
  Android client's64KiB response limit. Previous enrollment, cancellation,
  unassignment, migration, backup/restore and service-isolation suites are included.
- **15 headless browser checks passed**, including source category rendering with
  literal hostile text (no injected image element), real queue creation with
  profile/source/revision refs, pending-action controls and cancellation. Existing
  preset/unassignment/isolation/mobile checks remain green. The inspected category
  screenshot is an explicitly SYNTHETIC local fixture, not real customer/app data.
- **119 Android JVM/Robolectric cases passed**, zero failures. New cases cover
  source-only visibility/order/reset planning, preservation of other/unknown keys,
  profile/source/catalog/preferences conflicts, legacy-order refusal, per-device
  keyed refs, Unicode/redacted labels, paging and no raw source identities on wire.
  Read retries retain their original target; full pages stay out of receipt JSON.
  Executor tests reject wrong/stale refs before writes, discard a read after a
  profile switch and time out a stalled catalog wait. Previous activation/update/
  relaunch/live/codec regressions remain green.
- Internal-test and Android-instrumentation Kotlin compilation passed. Native
  SQLite/DataStore/Keystore behavior and actual category UI changes still need a
  controlled109 device pilot; mocks/helper tests are not hardware measurements.
- All existing migration files and worker source are unchanged. There is no NEW
  database migration for0.5.0, no new Android permission and no player/Back/updater
  modification. Install website0.5.0 BEFORE the109 testing APK.

Reads are cached paged snapshots, not provider freshness guarantees. A successful
mutation receipt is not a fresh read; request another page to confirm. Missing or
ambiguous legacy state is not remotely guessed/migrated. Playback timing/fleet
capacity and full automatic cross-device category/favourite sync are not claimed.

## Previous linked-only admission/control increment (0.4.0)

- **107 real MariaDB/service/HTTP cases passed**, zero failures. New admission
  checks prove missing/unlinked/suspended/legacy-proof resellers create no request,
  customer/device, proof journal or per-device audit row. A created-but-unlinked
  workspace is insufficient; linking permits a fresh proof later. HTTP ignore
  responses are200 with no request/poll credential. Legacy unclaimed rows are hidden,
  expired/pruned while approved inventory is retained. Owner-unassigned renewal
  does not grant new unknown installations an owner exception.
- Server tests cover closed profile/revision settings payloads, updated typed
  receipts, preset tenant isolation/CSRF/edit/archive/quota/retention, cancellation
  requester/owner permissions, invalidated delivery leases and truthful rejection
  of running/finished recall. The complete previous DB/HTTP suite is included.
- **14 headless browser checks passed** including real preset creation with literal
  hostile text, applying a revision-bound preset as a QUEUED action, actual queued
  cancellation, existing unassignment, scoped reseller reads and mobile layout.
  Preset/setting screenshots use explicitly SYNTHETIC simulator response fixtures;
  they are not represented as real Android data or executed settings writes.
- **105 Android JVM/Robolectric cases passed**, zero failures. The atomic mutable-
  preference helper changes only the selected sort key and rejects stale/profile/
  account guards. Executor cases check revision/profile validation, compare-and-set
  conflict results, updated snapshot revisions and no source writes. Tests also
  cover persisted ignore-state serialization, manual retry UI, original first-code
  lifetime fix, updater/relaunch, activation and live/codec regressions.
- Internal-test and Android-instrumentation Kotlin compilation passed. Real Android
  DataStore/Keystore operation, manual retry, sort UI updates and fresh-device code
  entry still require the combined108 pilot. Tests do not assert live hardware
  performance, fleet capacity or implemented billing/payment processing.
- Applied migrations001–004 are unchanged;005/006 add linked request metadata and
  workspace presets. The worker source, updater source/workflows and live engine/
  Back handlers are unchanged. Public worker health reported ownership_schema_version1
  via a read-only check; no live SQL/worker deployment/customer action was executed.

## Previous unassignment/action increment (0.3.0)

- **98 real MariaDB/service/HTTP cases passed**, zero failures. Covers owner-only
  CSRF-protected detachment, composite-FK command history migration, cancellation
  and old reseller read/command denial, preserved working credentials, owner-only
  proof renewal, revoked-token recovery (including an already-pending re-enrollment),
  original panel ownership for sibling installations and independence from former
  reseller suspension. Typed leased snapshots reject secret/URL/raw-log fields,
  missing results, foreign tenant reads and invalid values. Full previous suites
  and upgrade/backup/restore/concurrency checks are included.
- **12 browser checks passed**, including actual unassignment with active policy,
  requested/not-fabricated snapshot UI, new labels and existing reseller isolation.
  `test-results/device-unassigned-desktop.png` is an inspected SYNTHETIC fixture.
- **96 Android JVM/Robolectric cases passed**, zero failures. New transition and
  composition-lifetime tests retain the onboarding tree during the first activated
  playlist's management check. Existing Xtream submit/duplicate-click, updater,
  live output and codec tests remain green. Action tests cover read-only safe
  settings, no cache/config purge on reload, shared guide-job request semantics,
  profile changes and generic non-secret failures.
- Internal-test and Android-instrumentation Kotlin compilation passed. Native
  Keystore/device tests remain compile-only locally. Cold first-code entry,
  actual provider reload and snapshot delivery still need the next owner-device
  pilot; mocks/Compose tests are not physical Android/network measurements.
- **19 unchanged activation-worker/interoperability cases passed**. No worker
  code, central updater source, manifest permissions, player engine or Back handler
  changed. Update the website BEFORE publishing/installing 108 testing.
- Applied migrations 001–003 are unchanged. Migration 004 adds the device marker
  and cascade relation without dropping history or initialized private storage.

Full remote settings/playlist editing, category/favourite sync, raw logs and
screenshots are not implemented or advertised by this increment. Detachment does
not rewrite external panel ownership or guarantee an already-running operation
can be recalled; it removes management access and cancels further delivery/results.

## Previous automatic panel ownership update (0.2.1)

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
