# Control Center validation

## 0.28.0 separate full worker and Rampex account setup

Release validation: 287 isolated website/service cases, nine browser cases and
29 standalone worker cases (including17 real MariaDB transaction cases) pass.
The existing auth/trial/bridge regression suite also passes all71 checks. PHP/JS,
shell/embedded-Python syntax and actual delivery ZIP/manifest checks pass.

The new service has its own folder, port, unprivileged user, Python environment
and anchored state. Dependency version discovery reads the old environment's
metadata without executing its packages or the private config; old files/services
are untouched. Bundles omit private config/env and all old portal/trial routes.

Real isolated MariaDB cases execute the supplied native date/locking/credit-journal
helpers: one extension/debit, current-package and expired-revision fences, credit
trigger/no-trigger paths, missing-note rollback, duplicate/concurrent/cross-reseller
reference fencing, lost commit replies, funding deferral, refresh-only repair,
preserved credentials/notes/bouquets/connection overrides and unlimited catalogue
entries not blocking renewable products. HTTP checks cover separate service auth,
capabilities, original state loss and absence of legacy auth/create/delete routes.

Website contract checks cover full-worker imports, normal owner controls, real
merchant-key validation adapters, HTTP201 creation, exact37-currency minor units,
gross invoice vs net payout, ignored signed setup notifications, same-merchant key
rotation evidence, lost creation replies, unpaid expiry/retry, late accepted money
and manual/other renewal protection. Asynchronous jobs retain the same operation.

These checks are isolated/synthetic: no live Rampex request with an account key,
card charge, real line renewal or actual Overcast refresh was performed. Root
installation/proxy reload and the specific owner test-line/provider/device
acceptance must be confirmed on the servers; no global production-readiness or
provider/KYC/chargeback/instant-unlock guarantee follows from local tests.
Logs: /tmp/opencode/renewal-worker-028-release-validation.log and
/tmp/opencode/rampex-028-release-validation.log.

## 0.27.3 catalogue-only connection

283 isolated service checks, eight focused browser checks and71 auth-worker
offline/HTTP/deployment checks pass. New coverage proves signed read-only status,
owned offered packages/credit costs, READ ONLY transactions with rollback,
unconditional refusal of renewal/checkout even with a write environment flag,
preserved original health/trial routes and exact additive source-hook fencing.
Configuration merge preserves unrelated credentials/builder/trial settings and
refuses key conflicts; website mode blocks outbound renewal and enabling payment
rollout. The catalogue-only UI shows explicit OFF status without enablement controls.
Both archives are allowlisted, manifest-checked and exclude original configs/keys.

Live root installation, service restart/rollback and the private host-to-host file
transfer still require the user's server execution. No live connection/package
count, charge, account creation or renewal is claimed from these offline checks.

## 0.27.2 current-package-only pricing

280 isolated service checks and seven focused browser checks pass. Reseller
money prices are accepted only for currently offered panel packages; custom names,
terms, connections, credit overrides, duplicate prices and retargeting are denied.
Both verified owner and reseller checkout flows show only the current package,
reject another offered/priced package, hold package changes after payment without
renewing, and repair the original paid order after review without another charge.
The phone has no package picker. The operator edits money price/currency only.
All 61 existing/new reseller-worker offline/HTTP checks pass, including native
current-package locking and refusal of old/non-current-package-only contracts.
ZIP/manifest checks preserve private config/storage and existing order history.
These tests use synthetic provider/panel data; real-panel transaction acceptance
and live Rampex contract verification remain open.

## 0.27.1 verified owner reseller correction

279 isolated service checks and six focused browser checks pass. These include
the real null-tenant owner login resolving its existing verified workspace,
owner signed checkout/payment/demo same-line fulfilment, missing-link rejection,
cross-reseller denial and the owner settings screen rendering with payments OFF.
No native change, new migration, real payment or panel write is part of this fix.
Delivery ZIP checks preserve config/storage/keys and validate archive manifests.

## 0.27.0 renewal settings preview

Prior staging integration passed 276 isolated service checks, 59 worker tests,
68 baseline and five focused browser checks, 1,018 native tests (one skip) and
33 final targeted access/checkout tests plus both Kotlin variants. Payment cases
use synthetic merchant data and demo targets, never real charges/panel writes.
Delivery packaging preserves private config/storage and verifies every manifest.
The additional one-brand staging queue passes the expanded 277-case isolated
service suite: immutable staging source registration, same-job retries, preserved
testing artwork/package/revision and refusal to claim that source for production.
See `deploy/UPDATE-0.27.0.txt` for the
required migration and explicit testing-only queue command. No live payment or
production publication is part of this preview.

## Device management 0.26.0 recovery and fresh check-ins

Final local checks: 1,016 Android tests (zero failures/errors, one existing skip),
both testing/customer Kotlin variants compiled; 261 isolated MariaDB/service checks,
5 focused browser checks, 83 branding checks and delivery-package checks passed.
Logs are `/tmp/opencode/device-recovery-native-acceptance.log` and
`/tmp/opencode/device-recovery-web-acceptance.log`.

Checks cover unique inactive recovery, two simultaneous reset claims, preserved
notes/assignment/restrictions, old credential retirement, same allocation slot,
owner-unassigned retention, signed hint backfill, collision/legacy holds, tenant
boundaries and fresh-list visibility with no deletion/logout. Browser checks verify
cancelled confirmation, same-ID return after check-in, owner-only controls, retained
record access and mobile/desktop layout. Matching hints are not hardware proof.

No physical reinstall/reset test or live device-list reset has been performed.
Automatic recovery defaults OFF and requires a compatible APK plus prior backfill.
Existing125 apps support the server-only fresh visibility window but not hints.
Follow `deploy/DEVICE-RECOVERY-AAPANEL.txt`; update ZIP excludes config/storage.

## Historical 0.25.0 primary safeguards validation

Final local validation: 927 Android tests (zero failures/errors, one existing skip),
including all20 new primary-access tests; both testing/customer Kotlin variants
compile. All245 service/database/HTTP checks,68 browser checks,30 activation-worker
checks and79 branding/builder checks pass. Package and syntax checks also pass.

New native tests cover authenticated private binding storage, legacy confirmation
without dropping sources, no gate teardown during submission, pinned identity,
expired-trial/extra fallback, actual expiry despite auth=1, server-time bounds,
monotonic grace, explicit-negative invalidation, no restart-created grace and
primary-only access when the optional module is absent. Existing EPG/catalogue
tests use explicit access fixtures rather than contacting a live validation host.

Worker coverage checks read-only line status, nonce-bound request/response HMACs,
credentials/expiry negatives and the reversible/idempotent targeted installer.
PHP checks isolate localhost simulators from production: a production origin must
never receive the test-credential primary-validation exemption. Config, .env and
trial-history preservation are asserted by installer fixtures.

Local Kotlin compilation skips only local cloud-secret validation. Release gates
and existing GitHub workflows are not changed. Real-device expiry/renewal, PiP,
background shutdown and retained data remain separate acceptance evidence before
an approved customer release. No installed worker, live flag or APK is changed
by these local tests. See the current run reports before claiming test counts.

## Previous 0.24.1 archive/cache compatibility

Package regression tests compare two adjacent versions: unchanged PHP file bytes
must have strictly advancing ZIP timestamps, while repeated packaging of the same
version stays byte-identical. Private config/storage and every manifest remain
protected. This does not bypass PHP installations with timestamp checks disabled;
their operator must restart PHP. The live failure investigation confirmed frontend
0.24.0 while the new public device add-on policy route fell through to login,
consistent with an older router still executing (cache or incomplete extraction).

## Previous 0.24.0 monthly Add-ons

Full Android regression run: 907 tests, zero failures/errors, one existing skip;
all 13 add-on policy/authorization/persistence tests pass. Testing and customer
Kotlin variants compile. The 79 branding/builder regression checks pass.
All 242 isolated service/database/HTTP checks and 68 complete browser checks pass,
including simultaneous redemption and the live UI lifecycle on local fixtures.
Deterministic package tests preserve installed private config, storage and keys.

New local acceptance coverage tests workspace key issuance/replay/privacy, monthly
UTC boundaries, suspension/expiry/renewal/revocation, HTTP roles/CSRF/scope, fleet
membership, direct source previews and stale queued contexts. Browser checks cover
one-time key display, reseller redemption, owner controls and mobile layouts.

Native tests exercise paid-source classification and atomic denial of local,
Stalker, cloud-profile and managed-source additions while retaining saved state.
Local compilation does not validate production cloud credentials; that check is
skipped only for local compilation, not changed in source. No client APK or live
restriction is published by validation. Real-device acceptance remains a separate
step before enabling enforcement.

## Previous 0.23.2 current activity

234 service/database/HTTP checks and 67 full browser checks pass, plus the focused
current-activity mobile browser check. PHP/JavaScript syntax and package checks pass.

Current-view regressions cover old final results being hidden, recent final
results being limited to one, active requests taking priority regardless of age,
five-request pagination, reseller isolation, and retained full command/audit
history. Setup activity retains active/paused jobs and the latest review-needed
job. Support report tests distinguish current display from retained downloads.

The focused mobile browser check verifies that ten obsolete playlist command
rows never render, the current status links to active requests, and an empty
status disappears. Deterministic update ZIP tests preserve config and storage.
All fixtures are local/synthetic; no live trial, build or device action is created.

## Previous 0.23.1 visible device access actions

230 service/database/HTTP checks and 67 complete browser checks pass. The focused
owner-removal test also passes independently. Update-package preservation checks
confirm private config/storage are excluded. No backend/native source changed.

The new owner browser check verifies both applicable removal choices are visible
without expanding details, remain44px touch targets on mobile, and cancelled
confirmations leave policy, reseller assignment and command history unchanged.
Existing service coverage continues to enforce owner-only detachment/revocation,
kept viewing/data on unassignment, revoked-token recovery constraints and tenant
isolation. This is a visibility/wording fix, not permanent inventory deletion or
a new backend removal mode. No live device was altered to validate the controls.

## Previous0.23.0 compact workflow interface validation

230 isolated service/database/HTTP cases and 66 complete browser checks pass.
Focused before/after capture also passes: desktop heading34 ->25px, panel padding
24 ->16px and sidebar282 ->236px, with mobile action targets kept at least44px.
No business-service PHP or native APK source changes are part of this interface
update. A normal0.22 ->0.23 installation needs only extraction and browser reload.

Browser coverage additionally catches the settings-form race (Create waits for
field definitions), no-op/incomplete branding saves, private connection-method
switching without mixed URL/Xtream payloads, protected activation fields and the
separate trial-settings/contact routes. All provider/receipt tests remain local
synthetic fixtures, not new live accounts or device-install evidence.

The presentation-only sweep preserves the existing service contracts. Browser tests
use the new task labels while still proving tenant scope, local-only edits, reviewed
atomic device application, private-value redaction, unsupported-device states, stale
form refusal, draft preservation, global trial override and reseller opt-outs.
Playlist connection-method controls hide and clear irrelevant private fields and
keep the protected activation playlist enabled. Source/private payload schemas and
server-side guards are unchanged. No command is sent by changing a form field or
saving a reusable setup.

Dedicated layout checks cover320/390/768/1440px, mobile44px targets, no horizontal
page overflow, grouped artwork and saved-setup actions. An optional immutable0.22
baseline capture compares actual computed heading/card/sidebar dimensions and
screenshots against local synthetic fixtures. Static helper text is removed from
the primary workspace or moved into optional details; actual status/errors and
privacy/destructive-action boundaries stay explicit. Screenshot fixtures contain no
real customer information or credentials. No storage/playback saving is inferred
from smaller website widgets.

## Previous0.22.0 global packages and reseller self-service validation

230 isolated database/service/HTTP cases, 63 full browser checks and 79 builder
checks pass. The corrected self-service UI also passes its focused browser run.
Global-upgrade rerun/seed preservation, package/private-file checks and shell syntax
are verified. No live trial account, APK, worker deployment or customer update was
created for these checks.

New coverage verifies global approval applied once, actual per-panel package
availability, reseller ON with no grant row, stale per-reseller denials/caps ignored,
independent self-chosen quotas, global pause retaining each opt-out, stale global
reviews/fingerprints rejected, closed CSRF/role/scope contracts and removal of old
per-reseller enable/default/publication HTTP steps. Owner legacy offer HMAC and
Unlimited survive transition without a new approval or quota reset. No settings
action calls provisioning. Helper tests verify owner-only initial import, rerun
preservation and no private-config writes. No live panel trial or APK is created.

The new UI is exercised separately from cached0.20/0.21 compatibility: global
package approval has no tenant/daily field, reseller owns an editable daily limit
and app switch, admin views another reseller's settings read-only, and global
pause/resume retains saved settings. Old models are inactive after the explicit
global upgrade. Historical checks below describe earlier versions, not current UX.

## Previous0.21.0 per-reseller model validation (superseded)

224 isolated database/service/HTTP cases and 60 full browser checks pass, alongside
79 builder tests, package/private-file preservation, publication-input unit checks,
owner/pilot-helper compatibility and upgrade-shell syntax checks. The owner one-save
standing-terms adoption is covered separately from preserving its existing approval
on upgrade. These checks create no live panel account or distributable APK.

224 isolated database/service/HTTP cases, 60 full browser checks and 79 builder
tests pass. Package/private-file preservation, publication-input unit checks,
owner-helper/pilot-helper compatibility and upgrade-shell syntax also pass. No
native source change, live trial account or new APK was needed for this update.

New coverage exercises explicit migration-default-OFF reseller permission, the
owner-only pilot no longer vetoing an explicitly approved reseller, per-workspace
daily caps instead of the legacy pilot cap, typed/closed/role/CSRF-scoped APIs and
atomic default+offer rollback when a verified APK is missing. No settings action
calls provisioning. No-op saves, ordinary verified APK updates, cosmetic brand
edits and daily-limit changes preserve unchanged managed trial terms. The live
artifact registry and actual approved plan fingerprint remain independently checked.

Automatic signed metadata refresh, cooldown on failures, real-term changes failing
closed, stale client fingerprints/revisions, retained app/global/admin opt-outs,
unchanged owner Unlimited and trial-history preservation are covered. OFF remains
usable despite worker failure or unfinished package/quota edits. Existing legacy
controls are still tested as a cached-client compatibility path, not the new UI.

Browser checks cover the one-save reseller flow, no publication/default-step cards,
no reseller admin access, clear paused state, retained opt-outs, owner Unlimited,
mobile overflow, contact privacy and stale-form refusal. Publication/worker receipts
in local tests are synthetic: no live trial account or APK was created for this work.

Deployment remains a one-time website update plus migration022/PHP reload. The ZIP
excludes private config and storage; it never broadly enables already-approved
resellers or changes the existing owner preference, consumed trial history or keys.

## Previous0.20.0 owner unlimited daily trials validation

216 isolated database/service/HTTP cases, 57 browser checks and 79 builder checks
pass. Publication-input unit checks, private-config/opt-out preservation tests for
the owner setup helper, package-preservation, pilot-helper and shell-syntax checks
also pass. No live trial account or new APK was created for this change.

Owner unlimited policy is an explicit private opt-in for one verified owner brand.
It removes only the owner daily total and the spent pilot ceiling; original pilot
records, customer/device eligibility, idempotent recovery, contacts and budget rows
are retained. Device/IP anti-abuse limits, owner/app/global switches, approved plans
and active verified workspace admission still apply. Reseller daily caps/opt-outs
are not changed, and a reseller brand cannot obtain this exception even if named
in the private setting. Zero is a server-owned owner-unlimited publication sentinel,
never a public/reseller request to bypass a quota.

The isolated service tests cover two distinct eligible owner signups despite daily
cap1, same-key recovery without another worker call, duplicate identity rejection,
retained device/IP quotas, global/app pause, history preservation and role isolation.
Synthetic worker responses are not live account creation evidence. Browser coverage
checks an Unlimited owner view with no numeric input/hidden daily-limit POST field,
typed revision, explicit permitted ranges for resellers and invalid inputs rejected
before HTTP. Backend validation names the failing revision/daily field rather than
returning the same generic numeric error. The prior live browser failure's exact
request payload was not captured; CLI publication was a workaround, not its diagnosis.

No APK or worker update is required. Deployment remains a separate explicit helper
execution on the website; installing files alone never opts an owner into unlimited
trials. The helper preserves original private config/keys and verifies history before
atomically installing the owner-only opt-in. No account is created by setup.

## Previous0.19.0 normal branded releases validation

214 isolated MariaDB/service/HTTP cases pass. New coverage verifies separate-channel
deduplication, clean normal package/version/filename, explicit production opt-in,
immutable main-source queue routing, testing artifact/history preservation and
published rebrand catch-up without artwork/name rollback. 77 Python builder tests
pass. 24 activation-worker tests pass using its isolated dependency environment,
including the narrow source patch and repeat/unknown-policy safety checks.
39 trial-worker tests, 55 browser checks and 44 native trial/updater unit tests
pass. Android main/UI compilation succeeds at2.0.116/code432. Workflow lint and
shell syntax, update-package preservation and pilot-helper checks pass. The website
configuration test confirms the original database/bridge/trial secrets, custom
settings and exhausted pilot limit are retained while normal releases are selected.

These are local checks, not evidence of a deployed production configuration or
published normal APK. The first normal package is a separate installation from
the historical .test identity; no data transfer is promised. Existing pilot history
is retained. Live receipt/download verification and hardware evidence remain separate.

## Previous0.18.0 build progress and ETA validation

210 isolated MariaDB/service/HTTP cases pass. New coverage verifies fixed stage
milestones, negotiated capability, state-compatible/monotonic leased reports, invalid
phase/token rejection, sanitized tenant-scoped public data, measured bootstrap and
learned remaining-stage ranges, stale/overdue handling, stopped/retried attempts,
fresh retry tokens, prepared wait without ETA and completion only after the existing
verified publication finish. Queue time is not invented and clocks are server-owned.
73 builder/resource/dispatcher tests pass, including old-server negotiation, exact
leased phase payloads, phase-report suppression around receipt freeze and failure
of invalid stages/lost leases. No actual job was dispatched to measure this feature.

55 full browser checks pass with no uncaught errors. The focused UI checks also pass
stage/ETA refresh with no timer-driven percentage, one
latest row, preserved unsaved branding and build-ID focus, suppressed ETA during a
network failure, paused prepared status, automatic verified-download appearance and
mobile overflow checks. Publication responses in these tests are synthetic, not
evidence of a newly signed APK or live build.

The calibration file records read-only existing GitHub step measurements; it contains
no keys/tokens or reseller data. Normal UI requests do not query GitHub or renew a
lease. Old pinned runners remain coarse/indeterminate during compilation; fine phases
require updated negotiated runner code on a reviewed source. Pins, approval/signing/
byte-verification gates and production remain unchanged. Native app code did not change.

## Previous0.17.0 global and per-app trial controls validation

207 isolated MariaDB/service/HTTP cases pass. Coverage includes migration preservation
of explicit published offers/fresh OFF defaults; owner-only, CSRF/revision-guarded
global changes; seller isolation; global OFF overriding saved seller ON; retained
seller opt-outs on resume; stale forms refused without a worker call; preserved
grants/enrollment/quota/history; and uncertain original requests pausing/resuming as
the SAME operation. Prepared activation/ack remain usable while OFF. Current locking
reads serialize new dispatch authorization with both controls; already-authorized
remote work may finish and is never falsely claimed recalled.

52 browser checks pass with no uncaught errors. The focused trial-controls suite
also passes reseller-only app control/no admin access, global override (including
seller preferences saved during pause), and retained opt-outs/private safety gates.
Publication responses in those UI cases are synthetic; master mutations and role
checks use the isolated local server. No live panel account is created.

Android sideload-debug main/UI compilation and13 trial-client/visibility tests pass:
optional-plus canonical identity, unchanged signing/bindings, backward-compatible
paused status, hidden new signup, retained original recovery/activation and work
already underway. Native refresh enhancement awaits a signed APK; no APK version,
build/publication, shared-source repin or main/customer merge was performed.
Package/private-config preservation and pilot-helper checks pass. No deployed
switches, worker flags, pilot history or private keys were changed by development.

## Previous0.16.0 owner workspace throughout the site validation

202 isolated MariaDB/service/HTTP cases pass, including authoritative owner workspace
resolution without a reseller tenant_id, implicit owner creation of layouts/presets/
settings/device profiles, real owner overview/audit scopes, authenticated self context,
reseller cross-tenant refusal, editable owner allocation without self-suspension,
unchanged records/roles/panel links/brands/trial state during reads, and HTML/header/
asset/footer versions following VERSION rather than hard-coded old cache keys.
Pilot tests additionally verify remaining-slot reporting and refusal to expose another
brand's publish action; the existing one-operation admission/recovery guard still passes.

48 full browser checks pass. The focused owner browser suite also passes six scenarios
and has no uncaught errors:
owner resource creation/default visibility without a reseller login; owner recovery from
a deliberately incomplete roster plus server-paginated search; dashboard/customer/
device/audit scopes alongside aggregate administration; directory owner shortcuts and
allocation controls; explicit suspended-workspace selection without a silent scope
change; update notification/reload with unsaved-edit protection. Screenshots
use only synthetic local data. Package/private-config preservation and pilot-script
checks pass. No live panel trial, worker deployment, APK build or private-config change
was performed for this increment. Old user-stopped local services were not restarted.

## Previous0.15.3 one-account pilot validation

197 isolated MariaDB/service/HTTP cases pass. The pilot regression exercises first
signup, a different customer/device being refused, original-session retry and fresh
same-key recovery, a two-day rollover, released daily quota and every operation state
(reserved/sending/retry_wait/failed/needs_review/cancelled/prepared/delivered). Exactly
one fake worker provision occurs. Other-brand publication/admission is refused and
malformed pilot configuration fails closed. Existing normal multi-operation daily
budget and uncertain-response recovery tests still pass without the pilot flag.

Two setup-script checks pass: shell/embedded PHP syntax plus fail-closed reviewed-
identity validation without touching private config, and preservation of every
unrelated private value while adding only the testing/brand-only/one-per-day settings.
Package-preservation checks pass. The root setup script has not been executed on the
live website; no real trial account has been created by development/testing.
No Android/frontend/worker code or migration changed; the previous43 browser checks
remain applicable to the unchanged UI. The shared app/build ref is not advanced.

## Previous0.15.2 owner-workspace trials validation

196 isolated MariaDB/service/HTTP cases pass. New owner coverage verifies that owning
or branding a workspace alone cannot refresh panel plans, signed exact panel lookup
is required, conflicting permanent links cannot be transferred, reseller actors cannot
read/change owner trial policy or contacts, current owner plan/default preparation
does not publish an offer or call provisioning, and removing the panel link invalidates
readiness. Confirmed synthetic contacts remain owner-scoped and reject wrong panel
ownership. No live panel account, credit change, offer or trial has been created.
The private CLI rejects a missing exact username before any worker request. Package
preservation and PHP/JavaScript syntax checks pass. No worker, Android, migration,
central signing or source-pin change is part of this website-only increment.
43 isolated browser checks pass, including the owner branding-to-trial navigation,
owner workspace selection and disabled publication while its link/default is missing.
The existing compact latest-build/collapsed-editor and reseller isolation checks pass.

## Previous0.15.1 compact branding validation

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
