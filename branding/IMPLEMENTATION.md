# Storage, reseller branding and trials — development progress

## Native trial phone entry — optional plus (awaiting a signed APK release)

The app accepts country-code-prefixed digits without requiring a typed `+`. Existing
`+` and `00` international prefixes remain supported. Display copy/placeholder explain
that the country code is still required; no country is guessed from locale/device/IP.
All accepted forms normalize to the SAME existing `+`-prefixed server representation
before session signing/hashing, submission or pending-request comparison. Server and
worker validation, panel notes, contact identity and duplicate-trial history stay
unchanged. No OTP, new permissions, trial reset or extra live trial is involved.

This is an Android source change, not a website/worker hotfix. No APK version bump,
build/publication, source repin, production release or shared-main merge is included.
Installed builds retain the old form until a centrally signed newer APK is released.
The app's sideload-debug main/UI compilation and all7 targeted Robolectric trial
client tests pass, including identical contact/request hashes for bare/+/00 forms,
canonical phone submission, malformed input rejection and unchanged session binding.

## Owner-authorized automatic TESTING publication — website0.14.3

Owner explicitly approved automatic verified testing publication. Both-host opt-ins
are required: site automatic_testing_publication=true + publication_enabled=true,
worker BRAND_AUTO_PUBLISH_TESTING=true. Authenticated/read-only testing hints must
explicitly return publish mode; build-only/legacy responses cannot authorize it.
The existing combined build/retain/verify/publish/mirror workflow is reused without
removing any APK verification. Healthy expired prepared jobs can reuse retained
bytes; review/uncertain states are excluded from automatic pickup. Default remains
build-only until opted in. Production/trial/main-fanout scope is unchanged.

Removed human review ONLY from GitHub brand-testing-publication under explicit
owner authorization, preserving branch restriction. Production environment was
read back unchanged. Revision8's deliberate publication reached release download
verification but failed the website mirror: rename from tempnam fallback /tmp into
storage/brand-apks was denied. Its frozen publication remains under review, not
blind-retried. The website finalizer repairs only bridge/config/key read and APK
write ACLs while preserving key bytes/private config values. Update ZIPs now omit
ALL storage entries to preserve initialized directory modes/ACLs. Ingestion refuses
non-writable directories and tempnam fallback. Explicit historical reconciliation
can finish ONLY the old job's frozen published bytes after a reviewed source move;
old sources cannot claim new builds. No signing key was copied or replaced.

Website finalizer and worker opt-in helper avoid manual credential/config editing.
Backend integration,63 builder/resource/dispatcher tests and package preservation
tests pass. Deployment and recovery of revision8's site mirror remain pending.

## Queue-aware testing pickup — website0.14.2

Owner requested approximately30second pickup WITHOUT empty GitHub runs. Implemented
a disabled-by-default authenticated read-only testing queue hint, sharing the CLI
candidate policy, with failed-job backoff and no frozen/uncertain receipt pickup.
Worker `--poll` checks the website before ANY GitHub call, validates exact source/
channel/job, then uses existing active-run checks plus lock/durable five-minute
dispatch-intent fence. Systemd service stays build-only/testing; timer30seconds.
Owner helper preserves existing GH_TOKEN and config backup, generates a separate
private queue token and prints only its SHA256 hash for existing website config.
No application/signing key, production channel or trial setting changes. Runtime
worker/site installation and empty-queue/exact-job pilot remain pending.

## Live pilot verifier compatibility fix

The revision5 pilot compiled successfully but the old fingerprint parser rejected
the newer GitHub Android SDK's `V2 Signer: certificate SHA-256 digest:` label.
Hash-pinned, previously installed revision2 APK reproduced the failure on GitHub;
the fixed parser verified the same APK successfully in run40125320781. It still
requires exactly one certificate digest record and the exact central fingerprint;
wrong, duplicate, extra and unrecognised records fail closed.53 builder tests pass.
Temporary reference-download diagnostics were removed after that check; existing
generic testing/customer workflows and default-branch application code are untouched.

GitHub bridge SSH authentication previously failed because the secret lacked the
OpenSSH key's required final newline. The SAME dedicated bridge key was re-stored
with its exact bytes through sealed GitHub secret API input. No signing key was
exported, replaced or regenerated. The workflow's signing preflight exports only
the PUBLIC certificate digest inside GitHub, never private key material.

Site source approval remains on the earlier e96049c commit until the owner updates
the private source pin. No pilot artifact has been retained or published yet;
publication and trials remain OFF. A fresh saved-brand revision is needed because
the failed revision5 request remains bound to its original source allocation.

## Restricted website bridge setup — website0.14.1

Owner console confirms website38.190.133.176:22, app folder
/www/wwwroot/control.fecxbrfzku.net, and complete PHP8.4 extensions at
/www/server/php/84/bin/php. Found existing Config.secret reopened keys c+ and
chmodded on every read, which prevented a truly read-only bridge account and
would erase named read ACLs.0.14.1 reads established keys rb/LOCK_SH without
chmod/write; new keys still create exclusively0600, corrupt/empty originals stop
without regeneration.11 config and188 DB/service/HTTP tests pass.

Owner-only website setup bundle provisions a dedicated public-key-only brandbridge
account/root-owned wrapper/authorization, source/config/key read-only ACLs, and writes
only to dedicated APK storage. It saves original ACL metadata, refuses unknown
accounts/keys/symlinks/masked ACLs and verifies original config/key content hashes.
No global PHP/SSH changes or source/key writes. It does not enable any rollout.
Generated a NEW SSH bridge key (not the signing key); private key and target set in
GitHub secrets. Host-key secret is NOT set until console fingerprint confirmation.
Live root installer/SSH login/queue pilot remain pending. No APK or trial published.

## Site-owned reseller downloads — website0.14.0 / app2.0.115

Reseller branding responses now allowlist only site download paths and readiness;
no upstream URL/source/run/provenance is returned. BrandDownloads implements bounded
private SSH ingress with frozen-receipt SHA256/size validation and post-stream lease
fencing, immutable content-addressed APK storage, published-registry-only metadata
and file delivery, HEAD/single Range/resume and optional internal Nginx offload.
There is no public upload or remote fetch/proxy endpoint. Public requests neither
contact nor redirect to GitHub. Downloads are intentionally shareable, not identity
proof or reseller contact data. Trials remain OFF and no viewing policy changed.

The publisher preflights site delivery before authorisation, mirrors verified bytes
before finish, and reconciliation repairs the same copy. Configuration stays private
and default-OFF. No new database migration beyond017; preserve config/storage/keys.
New platform source is115/431 rather than rewriting a previously built114 version.
New branded native update checks use only owner-site metadata and exact local APK
URLs with redirects disabled; generic updater routing is unchanged. Existing branded
114 tests need a first manual install-over-existing to adopt the new update endpoint.

This is not deployed: restricted website SSH secrets, GitHub environments, sourcepin
and worker dispatcher setup are still required. No APK, worker or panel trial has
been published/created by this increment. Site install instructions: UPDATE-0.14.0.txt.
Validation:188 isolated DB/service/HTTP cases,41 browser checks,46 Python builder/
resource/dispatcher checks and31 Android updater tests pass; internal-test Kotlin
compile, actionlint, package preservation and installer syntax pass. One earlier
browser attempt timed out on unchanged clock-format UI; full isolated rerun passes.
GitHub build/publication environments now allow only reseller-control-center;
testing/production publication requires owner approval. No build was dispatched.

## Authorised GitHub branded workflow — selected architecture

Owner confirmed the worker host will coordinate builds but the central key exists
only in GitHub, then explicitly authorised ONE additional branded-only workflow.
central-brand-builder.yml now signs inside GitHub using existing repository secrets;
dispatch_github.py/systemd examples coordinate from the worker host without signing
material. Existing testing/customer workflows are not edited. GitHub stages signed
output, uploads APK/provenance only, DOWNLOADS and verifies retention BEFORE freezing
the website prepared receipt, then optionally publishes/download-verifies/registers
the per-brand channel. Continuations are bound to source, Actions run and lease.

No live deployment or dispatch yet. Required gates: workflow push/registration,
protected environments, restricted website SSH bridge, owner-pinned source, worker
dispatcher setup, then a new-revision build-only/publish/updater pilot. No key export,
normal testing overwrite, website migration, trial enablement or panel trial creation.
See GITHUB_RUNNER.md. Earlier standalone signing-host instructions are not selected.
Validation:43 Python cases pass, including two-phase retention/receipt ordering,
failed retention, uncertain publication, stale Actions-run rejection, dispatcher
source pinning and active-run suppression. actionlint passes the new workflow.

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
