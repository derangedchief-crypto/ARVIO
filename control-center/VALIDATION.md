# Device management0.15.1 compact branding — validation

42 isolated browser checks pass, including a synthetic eight-build response rendering
only its newest row, the existing-brand editor starting collapsed on desktop/mobile,
explicit opening/closing, first-brand setup and saving still working, latest-build
actions/downloads remaining available, and no page overflow or uncaught JavaScript
errors. Build history is hidden only in presentation, never deleted or rewritten.
Update-package preservation checks and JavaScript syntax checks pass. Asset URLs are
versioned0.15.1 to avoid retaining old cached JS/CSS after installation. No Android,
build identity, signing, updater or trial-policy changes in this UI increment.

## Previous0.15.0 main fanout/trial bridge validation

193 isolated MariaDB/service/HTTP cases pass, including new coverage for default-off
fanout, newer exact source identities, immutable registrations/idempotent retries,
published branding versus drafts, no mutation of existing brands/artifacts/production,
dynamic source/tag polling, retry/backoff/uncertain-publication fencing, rejection of
production claims, unchanged central-signer receipt enforcement and subsequent branding
requests using latest main.69 builder/resource/dispatcher checks and35 trial-worker/
HTTP/deployment checks pass. Trial tests use fake panel callbacks, never real accounts.
Private website import is tested to preserve every unrelated config value and refuse
silent rotation of an existing different secret. Default-off trial creation leaves
signed read-only catalogue access working. Package checks pass; actionlint and shell
syntax checks pass. No Android/playback code or app version was changed.

The additive installer is syntax/package checked and contains original-hash/service
gates plus backup/readiness/rollback. It has NOT been run against the live auth worker;
offline checks are not proof of actual service sandbox/Overcast behaviour. Website,
dispatcher, workflow main registration and environment/tag protections still require
deployment. Main still has older unsupported application source; no main-source APK
or real panel trial was produced. Hardware storage savings remain unmeasured.

## Previous0.14.3 automatic testing publication validation

Owner-opted automatic publication remains testing-only and requires site AND worker
policy. Tests reject publish hints without explicit server mode/owner opt-in and
preserve the default build-only worker behavior. Healthy prepared work may reuse
retained bytes; publishing/review/published/cancelled and artifact-review states do
not enter automatic pickup. Historical-source approval is confined to explicit
frozen-publication reconciliation and does NOT allow old-source claims/new builds.
The finalizer's private-config transformation is tested to preserve EVERY unrelated
value (database credentials, custom settings, trial OFF, queue hash) while changing
only approved source/automation/reconciliation policy.63 Python tests and189
DB/service/HTTP cases pass. Package tests require update ZIPs to omit ALL storage
entries, preserving initialized permissions/ACLs as well as original key contents.
Live automatic-policy deployment and the revision8 mirror recovery remain pending.

## Previous0.14.2 validation

The authenticated testing queue hint is read-only, source-pinned and disabled by
default. Service tests cover missing/wrong token, production refusal, source
mismatch, repeated hints without row mutations, active/expired leases, prepared,
uncertain/published/cancelled states, frozen receipts, retry cap and failure backoff.
The anonymous HTTP endpoint is404 when disabled. No trial/device data is returned.
Python tests cover empty/error queues making zero GitHub calls, bounded HTTPS
responses, header-only tokens, redirect refusal, exact-job dispatch, durable repeat
fencing, forbidden production/publication polling and preservation of the existing
GitHub token during private queue-token provisioning.189 isolated DB/service/HTTP
checks,41 browser checks,60 builder/resource/dispatcher tests,11 configuration checks
and update-package preservation tests pass. The first browser run hit the external
120second command timeout; a complete isolated rerun with adequate time passed all
checks. Live deployment is pending.

## Previous0.14.1 validation

## Existing encryption keys are read-only inputs

11 standalone configuration checks pass, including0400 original-key reads,
encryption/decryption with that same read-only key, preservation of operator read
permissions, exclusive0600 initial creation, and refusal to regenerate an empty
existing key.188 isolated DB/service/HTTP checks still pass. No key content,
database migration, trial history or live configuration was replaced.

The owner confirmed website PHP8.4 at /www/server/php/84/bin/php has all required
extensions; system PHP8.3 is missing three and is not used. The new owner bridge
setup defaults to the confirmed site path, uses diagnostics on stderr/CLI opcache
off (no php.ini edits), saves pre-change ACL metadata and provisions only a
restricted account/root-owned command plus narrow read/write boundaries. Bundle
tests verify public-key-only packaging and rejection of private/malformed key input.
Root account/ACL setup has NOT run on the live server; actual SSH authorization,
read access and host-key pinning still require the owner's installer output.

## Previous website0.14.0 validation

## Site-owned branded download/update delivery

188 isolated MariaDB/service/HTTP checks and41 browser checks pass. New coverage:
size/hash-bounded restricted-CLI APK ingestion, wrong/stale leases, short/excess/
tampered bytes, immutable reuse, pre-registry local copy requirement, transfer temp
cleanup and0640 group-readable APK files, no upstream/source/run fields in reseller
responses, metadata/full downloads/HEAD/single Range/suffix/416/wrong-version paths,
and no public builder upload route. A synthetic browser publication response verifies
site-only links and refusal of injected external provider URLs; it is not a real APK.
An earlier combined browser run timed out at the unchanged clock-format UI scenario;
the subsequent complete isolated browser rerun passed all41 cases with no JS errors.

46 Python builder/resource/dispatcher checks pass; workflow actionlint and installer
bash syntax pass.31 Android updater tests pass and internal-test Kotlin compilation
succeeds for115/431. Generic update routing is unchanged. Deterministic fresh/update
package tests preserve private config, initialized storage, original keys and migrations.

No new migration beyond017. New delivery/builder/trial settings stay default-OFF.
Live setup still needs restricted website SSH/secrets, source pin and worker dispatcher.
No live website install, worker install, APK publication, automatic update or panel
trial is established by these local checks. The earlier manual owner114 revision1->2
install-over-existing/data test passed; the new115 site updater still needs its pilot.

## Previous 0.13.2 owner-branding hotfix

## Owner branding and error diagnosis

Owner-branding tests start with the existing administrator workspace and NO panel
reseller link. They verify default/explicit owner selection, image/name save, a single
stable brand/package across revisions, owner-only pinned export, builder preparation
and synthetic artifact registration without creating/relinking a reseller account.
Resellers cannot access owner branding; ordinary reseller verification is unchanged.
Owner branding still cannot enable financial trial policy/public offers.

The browser suite tests the mobile owner selector/form/queue, then a pending-upgrade
response that must prevent branding-table requests entirely. A separate injected HTML
503 verifies safe HTTP-status/request-ID display, no raw page leakage and a working
upgrade-screen link. Mobile build rows use the existing stacked record layout.

Live read-only checks show version0.13.1 and JSON401 on unauthenticated API routes.
They do NOT reproduce or establish the cause of the authenticated non-JSON response
in the user screenshot. Pending migrations are a possibility, not a proven diagnosis.
No Nginx configuration, live database, account mapping, signing/workflow, APK or worker
was changed by this hotfix. No migration beyond017 is added.
Verified locally:186 DB/service/HTTP cases and40 browser workflows pass with zero
failures/browser errors. Owner branding, pending-upgrade preflight, safe HTML-error
diagnostics and the mobile build-row actions are covered. Package preservation and
manifest/migration checksum checks pass; existing migration files are byte-identical.

## Previous website0.13.1 delivery increment


Adds the owner-only build-input attachment route and download link. Service/HTTP
cases enforce owner authentication, reject reseller/anonymous access, preserve exact
pinned input bytes/schema and use a fixed server-generated JSON filename. Browser
coverage downloads the file as owner, checks the original brand revision after later
edits, verifies the name/artwork-only schema and confirms resellers have no link.
Update ZIP omits live config.php/runtime storage/keys and includes ordered migrations
through017. No GitHub workflow or Android source is changed in this site-only increment.
Verified locally:184 DB/service/HTTP cases and37 browser workflows pass with zero
failures/browser errors, including the actual attachment download. Package preservation,
archive manifest hashes, migration inclusion and JavaScript syntax checks also pass.

## Central builder/public offer increment

User correction: no GitHub workflow is changed or added. The proposed extra workflow
was withdrawn; its old YAML check is superseded by an assertion that the file is absent.
The runner prototype is tested offline, not connected to an installed branded workflow.

Real-DB cases cover immutable source/channel/version/signing policy, active verified
reseller ownership, hashed30-minute leases, heartbeat and expiry/stale-token fencing,
ready-versus-published status, explicit publication opt-in, same-byte publication
reconciliation, idempotent finish, package-specific enrollment, retry limits and
maintenance/cancellation without discarding uncertain publication history.

Public signup cases cover explicit public-mode publication, artifact integrity,
request-bound P-256 possession, approved default terms, per-reseller combination
history, daily budgets, lost-response same-operation signed-poll recovery, encrypted
activation handoff, unpublishing without revoking prepared results and default-OFF
anonymous routes. Branded identifiers are claims, not cryptographic APK authentication.

Browser cases include the default-disabled publication action and explicit rollout
status, alongside existing scoped defaults/contact details, stale drafts, mobile
layout and redaction. Builder runner tests mock remote commands/GitHub: immutable
asset upload ordering, never-latest release policy, uncertain failures, refusal to
overwrite mismatching bytes, post-upload provenance verification and private input
handling. A later publish run retrieves the original prepared Actions artifact and
must match its frozen receipt; missing/mismatching bytes cannot trigger a same-version
rebuild. All simulated publication messages are captured, not live publication.

SDK read-only negative test runs real apksigner/apkanalyzer against the existing
signed generic testing113 APK: its central signer is valid but the branded package/
version policy correctly rejects it. Sample branded resources/Kotlin compile locally;
updater-only native tests and internal-test Kotlin compile pass. YAML is parsed and
safety/default/trigger structure checked; no actionlint execution is claimed.

No new workflow was dispatched, no SSH builder deployed, no real branded APK signed/
published and no panel trial created. Actual signed installation/update/enrollment,
optional callback interoperability and panel pilot require separate authorization.

Local results:183 real-DB/service/HTTP cases;36 browser workflows in the most recent
full run;17 offline builder tests;6 branding-resource tests;28 targeted native updater
tests;18 activation-worker enrollment tests plus Python/PHP proof interoperability.
Branded sample resources/Kotlin and unbranded internal-test Kotlin compile. Package
config/storage/key-preservation and syntax/YAML safety checks pass. The preceding
full Android run (before the builder-only updater selector increment) was882 tests,
zero failures/errors,1 skipped; it is not presented as a new post-builder full run.

## Previous increment — 0.12.0

## Trial policy/contact increment

Real-DB/HTTP cases exercise signed scoped worker catalog parsing, owner-only grants,
approved reseller defaults, price/limit revision changes, stale admin/default writes,
disabling stale policies, encrypted resource/tenant-bound contacts, phone masking,
post-expiry access, guarded status updates, preserved original trial expiry, delayed
status responses, scoped read/removal, retention and non-restoration of erased PII.
Catalog/policy/contact operations are asserted never to call worker provisioning.

Browser cases use an isolated synthetic reseller to check owner approvals, rejected
stale default drafts, permitted choices, full contact/dial link after expiry, masked
lists, mobile layout, explicit redaction and no phone storage. Delayed detail responses
must not replace a newer unrelated dialog or revive phone data after navigation.

Worker offline cases cover domain-separated catalog/status/provision signatures,
read-only scoped status, metadata fingerprints and actual created limits, including
quoted numeric panel bouquet IDs. No real worker/panel connection or live line creation.
Native app and published testing113 are unchanged by this increment. App offers stay off.

Verified local results: **168 DB/service/HTTP checks,36 browser workflows and31
offline worker/HTTP checks passed**, with zero failures/browser errors. Package
configuration/key preservation and syntax checks also pass. All panel adapters,
catalogs and trial receipts in tests are synthetic; no live trial or note was written.

## Previous increment — 0.11.0

## Self-service brand registry / storage / worker preparation

New real-DB cases cover the unique reseller identity and stable package, optimistic
versions, verified-link enforcement, scoped images, forbidden signing/update/package
inputs, immutable pinned build exports, pending-build deduplication and cancellation,
suspended workspace refusal, image validation and HTTP authentication/CSRF/isolation.
Browser coverage uses an independent verified reseller to exercise actual self-service
uploads/save/build-request/revision workflows without relaxing production rate limits.
Device command polling also stops permanently when its view token changes, preventing
old watchers from resuming when the user reopens a section.

Android catalogue compression/legacy migration/storage counters and selected updater,
management/guide/source regressions are tested locally. Resource generation validates
images/text/identity and all launcher/banner densities, including actual branded
resource/Kotlin compilation and missing-signing negative checks. These are NOT APK
publication, a real hardware storage reduction benchmark or a branded update pilot.

Offline auth-worker tests cover server signatures/body bounds, concurrent/repeated
requests, scoped trial eligibility, preserved contact notes, post-commit uncertainty,
same-line registration repair and missing-key fail-closed behaviour. No panel connection
or live account creation was performed. Trial policy/contact UI and native activation
integration remain disabled/unfinished, not a successful end-to-end trial result.

First increment verified: **159 backend/HTTP checks,35 browser workflows,139 selected
native tests,6 brand-resource tests and22 offline trial-worker/HTTP tests passed**.
Default internal-test/instrumentation Kotlin and sample branded resource/Kotlin
compilation passed, as did negative signing/distribution and package-preservation
checks. Browser scenarios respect the unchanged production actor request budget.
See source `branding/IMPLEMENTATION.md` for explicit unfinished work and pilot limits.

## Previous milestone — 0.10.3

## Consistent sidebar theme

The0.10.3 menu uses shared palette variables instead of fixed charcoal/red colours.
Browser assertions compare the actual sidebar surface and active row foreground/
background to the site's palette in BOTH light and dark modes. Prior responsive,
keyboard/drawer, device-management and tenant-isolation workflows remain covered.
No backend/native changes; the153-check backend baseline below is unchanged.
0.10.3 browser validation: **34 workflows passed, zero browser errors**. Actual
light/dark menu colours were checked against shared theme variables; delivery
config/key-preservation checks also passed.

## Cleaner device directory

0.10.2 reduces the directory to five columns, with a real scoped matching count,
existing server-side app-access filtering and a confirmed compatible playlist-refresh
shortcut. Browser coverage exercises search/count/filter results alongside all prior
device workflows, mobile cards, responsive widths, navigation and tenant isolation.
The existing search query now compares ASCII IDs in UTF-8 so Unicode labels do not
cause a collation failure; a real-DB regression checks Unicode names, ID searches
and tenant isolation. No native changes or additional migration beyond013.

Combined0.10.2 validation: **153 database/HTTP checks and33 browser workflows
passed**, zero failures/browser errors. All6 draft-helper tests and delivery
config/key-preservation checks passed. Actual screenshots are
`test-results/clean-devices-desktop-dark.png` and `test-results/clean-devices-mobile.png`.
Data/protocol drivers are synthetic; no live deployment or physical-device pilot.

## Grouped portal menu

0.10.1 adds the requested reference-style charcoal grouped sidebar, direct setup
links and red active selection, with a mobile drawer instead of duplicate bottom
navigation. Browser validation checks section grouping, one active/current row,
visible setup links, drawer width/scrolling, Escape/focus restoration and responsive
content. Owner-only entries remain absent for resellers. Backend code is unchanged
from the152-check0.10 baseline below; no new migration or native capability.

0.10.1 browser run: **32 workflows passed, zero browser errors**. Actual desktop
and phone screenshots are `test-results/portal-menu-desktop.png` and
`test-results/portal-menu-mobile.png`. Delivery/config/key-preservation checks pass.

## Task-based rebuild / new-device setup profiles

The0.10 release gates cover the five-section workspace, friendly Playback controls,
one-click read/result tracking, role-based More/administration, scoped customer ->
device discovery and the Library maker/settings/recipe/default-policy flows.

New real-DB cases exercise immutable versions/policy pins, new-admission-only
creation (including first verified owner review), no unmatched-attempt jobs,
no renewal/check-in reapplication, explicit existing-device selection, every
required receipt, multi-stage preference/sort/buffer/layout/final confirmation,
preserved explicit overrides/target-only groups, missing-name notes, ambiguous/
unsafe/all-hidden refusal, local conflict stopping, unassignment cancellation,
paused expiry, retention, nested transaction rollback and scoped action tracking.

Browser workflows retain the existing sorting/layout/template/favourites/source/
support/CSRF/auth/isolation checks, adapted to the new actual navigation (not hidden
legacy controls). Added checks demonstrate one Load action without manual refresh,
maker saves without native commands, explicit default activation without selecting
existing devices and template edits not changing pinned recipes. Responsive checks
cover320–1440px and source/private input cleanup. Native app code is unchanged;
automation reuses the existing112 operations, not a new arbitrary control channel.

Final combined local run: **152 database/HTTP checks and31 browser workflows passed**,
with zero failures/browser errors. All6 draft-helper tests,42 PHP syntax checks and
delivery ZIP/config/key-preservation checks passed. Android/worker/workflow code is
unchanged. Local protocol drivers are synthetic, not a real automatic customer-device
setup or TV playback pilot. No live website deployment or APK publication was performed.

## Previous visual milestone — 0.9.0

## Mobile / modern visual increment

- **142 real database/service/HTTP checks and28 browser checks passed**, zero
  failures/uncaught browser errors; all6 layout-draft helper checks also passed.
- Chromium viewport checks cover320,390,768,1024 and1440px, theme restoration in a
  second authenticated browser page, mobile record layouts, modal bounds/touch
  targets, drawer focus/Escape/background inertness and page overflow.
- Actual local desktop/device inventory was visually reviewed: desktop inventory
  fits its1070px region without horizontal overflow;390px phone records remain
  readable at390px page width. Wide editors scroll inside their own regions.
- Package/config-preservation, JS syntax and diff checks passed. Android/worker
  code and database migrations are unchanged by this increment; the prior170
  Android/19 worker results below are not claimed as a new hardware run.

Website-only changes; no Android/worker/SQL/API permission modifications. Existing
functional validation below refers to the0.8/112 increment. The0.9 browser rerun
also exercises drawer open/close/Escape/focus restoration, inert background,
readable mobile device records,44px dialog buttons, light/dark persistence and
page-width containment at320/390/768/1024/1440px. Existing complete-source drafts,
guarded pushes/rollback, overrides, private support, authentication/tenant scope
and literal text rendering remain part of the same real HTTP/browser suite.

Screenshots in test-results/ are generated from LOCAL SYNTHETIC fixtures, never
customer data. They are not evidence of a live deployment or Android pilot.

## Previous functional milestone — 0.8.0

## Complete managed-control increment / client112

The release-gating results below are recorded after the final combined rerun.
Physical owner-device acceptance is separate and remains outstanding.

- **142 real MariaDB/service/HTTP checks passed**, zero failures.
- **26 browser checks passed**, zero uncaught browser errors.
- **170 Android JVM/Robolectric checks passed**, zero failures/errors/skips;
  internal-test and instrumentation Kotlin compilation passed.
- **19 activation-worker regression tests and6 draft-helper tests passed**;
  package contract, PHP/JS syntax, earlier-migration immutability and diff checks passed.
- These are local/synthetic tests, NOT a physical-device pilot or a guarantee of
  improved customer-stream buffering, actual process restart or OEM behavior.

### Implemented and exercised safeguards

- Maintenance uses image/HTTP cache-owner APIs only. No recursive directory clear,
  source purge, guide deletion, credentials/profile reset, update-file removal or
  playback-buffer clearing. Owner failures are independently reported. Foreground
  reopening is consumed before ACK/effect, never replayed on duplicate delivery,
  blocks while updates are active, and has a five-minute persisted cooldown. It
  reopens the activity/task, not the Android process or device.
- Source snapshots/previews/checkpoints are encrypted server-side. Browser views
  contain opaque refs/labels/counts only; replacements are write-only, password
  controls are cleared on modal close and pending drafts freeze while previewing.
  Actual source credentials/MACs inside labels are masked without rewriting an
  unchanged private label. Credential/topology edits require nonsensitive labels
  where keeping a private label could expose an old credential.
- One source push requires a current device lease, source/profile/account revision,
  successful safety capture, atomic explicit-profile DataStore CAS and exact
  uploaded confirmation. Native URL/guide normalization mismatches are refused
  BEFORE mutation. Canonical activation identity stays first/protected. At least
  one M3U source remains enabled because the legacy fallback is not changed.
- URL/credential/MAC replacement creates new supplementary source IDs; old
  favourite/category IDs cannot silently rebound to unrelated channels. Explicit
  compatible checkpoint restoration can restore historical IDs, and legacy empty-
  playlist storage shape is preserved instead of forcing a migration. Protected
  initial source checkpoints survive command-log retention. Existing guides and
  favourites history are not purged; changed-source recovery is not guessed.
- Broader settings use a fixed typed inventory, versioned workspace base profiles,
  explicit device/profile override precedence and actual effective-value revisions.
  Source/credential/update/entitlement/arbitrary preference keys are rejected.
  Clearing policy preserves newer local values. Cloud exports strip device overlays;
  imports update underlying choices and reapply the overlay in ONE transaction.
  Profile-player preferences do not claim to control the embedded live engine's
  independent audio settings; consumers/application timing are listed in README.
- Support history accepts fixed event codes/timestamps and closed diagnostic fields
  only. No raw exceptions/logcat/URLs/headers/credentials or channel/profile names.
  Events are device/account scoped; the snapshot identifies its active profile.
  One-time consent captures only a dedicated safe app panel. Decline, expiry,
  changed eligibility, backgrounding, profile/account changes and updater activity
  refuse it. No full-device/current-page/video capture or new OS permission.
- Private support artifacts are encrypted, rate/count/retention bounded and every
  download is tenant-authorized/audited/no-store. PNG checks bound encoded and
  decoded bytes/pixels, CRCs, chunks, zlib stream length and row format; text,
  animation, trailing content and oversized payloads are rejected. PHP zlib is
  checked in CLI preflight. No artifacts are placed under public/.
- Real HTTP cases cover new source/profile/override/support routes, CSRF, scoped
  bearer leases, anonymous/foreign downloads, scalar/array injection and secret-free
  browser responses. Browser source/policy/support receipts are explicitly driven
  by SYNTHETIC fixtures, not presented as Android restoration/restart proof.
- New migrations010–012 only;001–009 unchanged. Manifest permissions, activation
  worker, updater code and signed publication workflows unchanged.112 publication
  and live installation require separate authorization; main/customer untouched.

### Owner-device acceptance still required

Use only an owner-approved test device/account after website/migrations are installed
and112 is separately published. First verify a protected good favourites backup
and source checkpoint. Compare Off/2/5/10 on the SAME affected stream/network; test
both fast engine and mpv fallback, low-RAM/high-bitrate cases, channel changes and
normal Back. A larger buffer is not proof of a provider/network fix.

Verify image/HTTP cleanup preserves logins, profiles, current playback, saved guides,
favourites and update files. Request one foreground reopen, verify actual app opening
without loops/data loss, and confirm another request within five minutes is refused.
Do not claim all process services restarted or OEM background launching succeeded.

On an owner-controlled supplementary source, test rename/order/visibility/guide edits,
URL replacement/new source identity, stale preview conflict and explicit checkpoint
rollback without canonical credential changes. Verify retained guides/favourites
and deliberately unavailable old-source IDs rather than guessed migration.

Apply a base settings profile plus one override, verify stored values/origins and the
correct consumer/timing, edit locally, then reset without overwriting that local
change. Test Cloud import/export with another owner test installation to verify
that device overrides do not leak. Templates must never auto-push on edit.

Request a support bundle; inspect the authenticated download for fixed codes only.
Decline/expire/background a panel request and verify NO image/repeated prompt.
Accept a fresh request and verify only the safe panel is included. Test session-
less/former-reseller downloads fail, retention removes expired artifacts, and
unassigning reseller management does not block ordinary app viewing.

## Earlier buffer/network diagnostics slice


- **128 real MariaDB/service/HTTP cases passed**, zero failures. New cases cover
  closed diagnostics enums/counts, missing/partial payload rejection, impossible
  heap counts, denied/unknown network states, private SSID/address/log rejection,
  leased receipt enforcement and reseller isolation after device unassignment.
- Playback buffer commands are capability gated and accept ONLY0/2/5/10 seconds.
  Unknown fields/arbitrary byte caps are rejected. A success requires a typed
  settings receipt with the requested profile and amount. Queueing does not claim
  saved/applied settings. Old basic snapshots remain accepted for111 compatibility.
- **23 browser checks passed**, including redacted diagnostic fields only after
  a leased synthetic receipt, Android-validation caveats, Off default, bounded
  pre-buffer selection/confirmation and a saved amount displayed after a matching
  receipt. Synthetic fixtures are NOT physical Android playback/buffering proof.
- Website0.8.0 supports112; no migration or worker patch in this increment.
  Existing008/009 still apply when upgrading from0.6. Update ZIP preserves config
  and private storage; source/signing/update workflows and manifest permissions
  are unchanged.112 publication remains separate authorization.

The existing OwnTV1.0.68 binary API accepts SourceEntity.livePrerollSecs and carries
it into fast-engine and mpv LiveRequest. This increment uses that supported field,
not reflection, a dependency upgrade or a new player. Off remains0; decoder,
surface ownership, original Back, provider resolution and engine routing are
retained. Memory safety caps remain with the existing engines. The stored choice
is read once per new standard live tune and does not observe/rebuild active video.
DRM, catch-up and VOD paths are unchanged. Higher values intentionally trade startup/
live delay for headroom and may not be achievable for every feed/device.

Physical pilot still required: compare Off/2/5/10 on the same affected channel/
network/device, including repeated stalls, low-RAM/high-bitrate feeds, channel
switches and mpv fallback. No measured startup/continuity improvement or diagnosed
root cause is claimed from local tests. Provider/network problems may remain.

## Previous favourites increment (0.7.0)

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
