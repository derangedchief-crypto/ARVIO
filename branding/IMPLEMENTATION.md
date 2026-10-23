# Storage and reseller branding — first development increment

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
4. Admin-approved trial-plan choices, reseller default policy, structured protected
   contact records/follow-up detail UI, native first-name/phone trial form and secure
   single-operation activation handoff. Contact notes work in the worker extension;
   the end-to-end control-system contact view and app flow are not implemented yet.
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
