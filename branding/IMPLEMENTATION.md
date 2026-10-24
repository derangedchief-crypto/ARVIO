# Storage, reseller branding and trials — development progress

## Standalone builder integration — source only

Manual signed owner revision1 -> 2 update passed on-device; owner confirmed retained
branding/login/favourites/settings. Normal testing114 was restored; customer/latest
remainsv2.0.101. No GitHub workflow changes are needed for the new dedicated-host
adapter (standalone_runner.py). It uses disposable exact-commit checkouts, owner-only
signing inputs, restricted SSH queue pickup, durable local immutable APK/provenance
retention BEFORE preparation, reverified prepared-byte reuse, per-brand-only release
publication, download verification and website registry completion. Systemd examples
default to build-only; publication and timers need explicit staged operator setup.

See STANDALONE_RUNNER.md. Deployment remains BLOCKED on a trusted host with the
owner's original central keystore/backup and restricted website SSH configuration.
GitHub secrets cannot be read back; no secret extraction or replacement key is used.
Automatic builder/registry and brand updater pilot remain unverified, not operational.
Trials/workers stay OFF; no new website migration or ZIP is required by this adapter.
Validation:34 Python builder/resource/standalone cases pass, including durable
retention-before-prepared, missing/tampered bytes, tenant/job isolation, disk-full
and uncertain-publication fencing. PHP regression rerun was blocked because the
private test MariaDB socket is unavailable; no PHP/backend code changed here.
Historical milestone descriptions below do not supersede this status.

## Owner-workspace hotfix — website0.13.2

User uploaded0.13.1 and reported that their admin account could not select itself,
with a non-JSON error on the selected reseller. Confirmed code defect: the dropdown
AND backend admitted only kind=reseller and excluded the existing owner workspace.
0.13.2 adds one owner-only brand in that existing workspace without a second reseller
login/panel link, keeps its identity stable and supports the same private export and
restricted builder/registry preparation. It does not rename the normal APK, enable
financial trial policy or relax verified-reseller/device ownership rules.

The owner page checks pending migrations via the existing system-status API BEFORE
requesting branding data. Unexpected HTML responses now show safe HTTP status/request
ID instead of blaming PHP/Nginx, and link directly to the upgrade screen. No raw HTML
is displayed. The actual authenticated non-JSON failure has NOT been reproduced;
read-only signed-out API checks return proper JSON. No new migration beyond017,
configuration/key replacement, workflow change, worker deployment or APK publication.

## Latest site delivery — 0.13.1

Normal testing114/430 from50cc240 is published and its downloaded package/version/
central signer/GitHub digest verified (run40031882645). No existing workflow changed.
User now requests the site update followed by a separate reseller test APK. Live
website status was read-only checked at0.10.3; this session cannot drive aaPanel or
apply files/migrations on that host. The update ZIP preserves private configuration/
storage/keys, includes migrations through017 and adds OWNER-only browser download of
immutable brand inputs, so the first actual reseller name/artwork/identity can be
supplied without an SSH export.184 DB/37 browser checks and package preservation pass.
No website/worker installation, live trial or real reseller APK is claimed completed.
The isolated centrally signed reseller-build adapter remains pending; normal testing
publication must not be reused to overwrite its channel with a different package.

## In-progress broad-compatible signup integration (not published)

USER CORRECTION: do not change/add GitHub workflows. The extra builder workflow was
withdrawn before pushing. Existing normal testing/release workflows remain unchanged.
User now authorizes a NORMAL testing114 build first; reseller APK follows only after
that test works. The central branded runner/retained-artifact adapter is not connected
to an installed workflow; its CLI/queue/verifier code remains prepared but deferred.
Do not describe unattended branded builds as operational or send branded APKs to the
generic staging-testing channel. The no-new-workflow check replaces the old workflow
structure unit test; the runner unit case count is unchanged.

The user selected public brand-scoped signup offers, not mandatory hardware
attestation. Source now includes migration016, explicit opt-in offer publication,
centrally approved artifact prerequisites, server-owned plan/reseller selection,
request-bound P-256 key-possession sessions, encrypted operation recovery, quotas
and idempotent worker provisioning. Brand/device claims remain spoofable; no APK
or permanent physical-device authenticity claim is made.

Native source targets114/430 (NOT published). Only branded builds can render the
first-name/phone form; no OTP, Google dependency or new identifier permission.
Prepared codes use the existing activation/sign-in path. Secure pending-request
storage must succeed before financial submission; normal activation remains present.
The worker now checks activated state, code uniqueness and actual trial limits
before returning an activation-ready result. Branding-only revisions use a fourth
version component and a newer Android version code without changing package identity.

All rollout configuration remains OFF. Central builder pickup/lease, isolated
signed-build/verification/publication runner, immutable brand-specific updater assets,
publication reconciliation and explicit trial-offer UI are implemented in source.
See CENTRAL_BUILDER.md for deployment/approval/recovery limits. No live builder has
been configured or dispatched; registration in local tests uses synthetic receipts.
Real signed installation/update, deployment and device/panel pilot remain unfinished.
Website0.13 reports prepared versus published artifacts and owner-disabled trial
rollout explicitly. No website/worker deployment, real trial or APK publication performed;
testing113 remains the installed-test target. Do not call this end-to-end ready.
Current local checks:175 DB/service/HTTP cases pass, including lost-response
same-operation signed-poll recovery, quota preservation and artifact-metadata tamper
rejection;28 worker unit cases,4 worker HTTP cases and6 branding-resource cases pass.
Package configuration/key-preservation checks pass. Full Android unit run:882 tests,
zero failures/errors,1 skipped (881 passed), and internal-test Kotlin compilation
passed. Four older launch-refresh assertions were updated to read catalogue files
through the gzip/legacy-compatible reader; production cache/playback code unchanged.
Builder increment:183 DB/service/HTTP checks pass;the last full browser run passes36 workflows;17 offline runner/
publication checks pass, including reuse of frozen prepared Actions artifacts rather
than rebuilding a version. Branded sample resources/Kotlin compile and28 unbranded
updater tests/internal-test Kotlin compile pass. Actual SDK verification rejects the
published generic testing113 APK as a branded artifact despite its matching central
signer.18 activation-worker enrollment checks pass, including branded format handling;
the website requires the exact centrally registered package/channel. Generic Python/
PHP enrollment-proof interoperability also passes. Real branded
signing/publication and device/panel validation remain pending. No signed build timing
or on-device update/performance claim is inferred from resource compilation.

## Second increment: policy and contact layer (website0.12 / migration015)

Implemented owner approval of a worker-verified trial-plan subset, reseller default
selection, immutable plan-revision checks and stale-draft review. Read-only catalog
and status requests use separate signature domains and cannot create/renew accounts.
The worker rechecks approved metadata under locks and verifies actual created limits.
Numeric panel bouquet strings/numbers normalize to the same approved rights.

The control system now has encrypted, record/tenant-bound contact records and a
Trials & contacts screen: masked lists, authorized full-number/dial links after
expiry, explicit panel-status refresh, original trial expiry on conversion, private
view audits and deliberate local contact removal. Replays cannot restore removed
PII or reset eligibility. Default local contact retention90 days is owner-configurable
(7–365); panel notes remain separate. Late detail responses cannot replace unrelated
dialogs; drafts/PII are cleared at sign-out. No verification/SMS/automated outreach.

Validated in local service/browser tests; worker cases are offline/mocked. No live
panel writes/deployment or new APK publication in this increment. Published regular
testing113 remains the storage test, source367cb04. Native trial activation, trusted
branded-install validation and full builder/version/publication integration remain
unfinished, so app offers stay OFF and no public provisioning endpoint is exposed.
Check results:168 DB/service/HTTP cases,36 browser workflows and31 worker/HTTP cases
passed. Native139-test/storage baseline belongs to the first increment and was not
rerun or republished for these website/worker-only changes.

## First increment

## Implemented and locally validated

- Native development **2.0.113 /429**: explicit local **Storage usage** measurement
  in Settings, fixed byte categories including retained XML guides, no uploaded
  filenames/content, no deletion. Totals are approximate and flag incomplete scans.
- Transparent, atomic gzip storage for saved Xtream movie/series/category catalogues.
  Existing plain JSON still reads and compacts once after successful decoding. Cache
  schema, saved timestamps, TTL and offline fallback are unchanged. No guide purge,
  image-cache budget reduction, credential/profile/favourite or playback-engine change.
- Website development **0.11.0 / migration014**: verified reseller self-service name,
  loading image, logo and launcher icon, authenticated private images, fixed one-brand
  identity/package per reseller, version guards and pinned/deduplicated build inputs.
  Cancel/requeue unstarted jobs; owner export checks workspace/link/image integrity.
- Deterministic brand resource preparation: localized visible brand strings, dynamic
  Kotlin UI labels and all launcher/banner/loading variants. Same site/app colours.
  Branded compile checked with a sample name containing both `&` and an apostrophe.
  Brand/package/platform-version identities are validated; user signing/update fields
  are forbidden. Actual release/test signing remains central; missing signing and
  unsupported Play/beta distributions fail closed.
- Separate auth-worker extension foundation: protected trial-only operation, first
  name/phone in reseller notes, reseller/customer/device history, durable request
  reservation, concurrent/repeated request protection and exact same-line recovery
  after lost commit responses/registration failures. Cryptographic new credentials,
  current owner/plan checks and result isolation. No SMS or phone verification.

## Check results

- **159** real local MariaDB/service/HTTP checks: zero failures.
- **35** browser workflows: zero uncaught browser errors; includes actual reseller
  uploads/save/version/build-preparation flow and responsive phone rendering.
- **139** selected Android JVM/Robolectric tests: zero failures/errors/skips, including
  new storage/codec and existing updater/management/guide/source regressions.
- Default internal-test and instrumentation Kotlin compilation passed; sample branded
  resources and Kotlin compile passed. No signed branded APK was produced.
- **6** deterministic branding/resource tests; **22** offline worker/HTTP tests.
- Missing central signing and unsupported brand distribution negative checks passed.
- Website packaging/config/key-preservation checks and syntax/diff checks passed.

Tests/driver data are local and synthetic. This does not establish hardware storage
savings, first-tune latency or a successfully provisioned live IPTV trial.

## Deliberately NOT enabled / still to complete

1. Physical fresh-install/first-sync/repeat-sync measurements. Screenshot1.55 GB is
   not claimed fixed. Image/guide retention budgets need evidence; retained guide XML
   may be significant and must not be deleted blindly. Native changes need a later
   testing APK before the owner can measure them on hardware. The user has now
   authorized regular113 publication to the isolated testing channel only.
2. Automatic central builder pickup/leases/finish/artifact verification/publication;
   branding-only revision version allocation; package/certificate enrollment and
   deep-link compatibility; actual brand-preserving update/rollback pilot. The
   website currently says **Waiting for central builder**, not APK ready.
3. A validated trusted-install policy for the sideload devices. A public brand ID,
   reported package/hash or APK-wide secret is NOT proof of app authenticity. One-brand
   enforcement prevents extra identities through our system, not external APK resale.
4. Admin-approved trial plans/defaults and protected contact/follow-up UI are now
   implemented in0.12 above. Still pending: native first-name/phone form, validated
   branded-install session and secure single-operation activation handoff/provisioning
   broker. Contact receipt ingestion is internal-only, not an unauthenticated API.
5. Authorized real-panel pilot, including pending/started expiry semantics, package
   charges, registration repair and trial-to-paid contact/history retention.

The control server therefore returns trial_creation_enabled=false and exposes NO
public app trial-provisioning route. The worker extension is NOT installed or enabled
live. No live accounts, website/worker deployment or main/customer merge. Regular113
testing publication is separately authorized; no reseller-branded APK publication.

## Files

- `branding/README.md`, `branding/prepare.py`: private input export -> generated resources.
- `reseller-auth-worker/README.md`, `branded_trials.py`: separate worker integration.
- `control-center/test-results/app-brand-self-service-desktop.png` and phone PNG:
  actual rendered local website with clearly labelled synthetic data.
- Source branch remains `reseller-control-center`; testing publication uses an exact
  committed source revision, with its CI run/result recorded in the session.
