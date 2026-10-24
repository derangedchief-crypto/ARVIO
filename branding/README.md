# One-brand central build workflow

## Current runner option

See **STANDALONE_RUNNER.md**: a dedicated-host adapter now connects the queue to
isolated builds, central signing, durable retained bytes and per-brand publication
without changing/adding GitHub workflows. It is source-tested, not installed.
The owner confirmed manual revision1 -> 2 updates preserve branding and data.
Automatic queue/publication/updater validation still needs a connected-runner pilot.
Trials stay OFF. Earlier milestone descriptions below are historical.

Source now includes private uploads, immutable inputs, central queue pickup/leases,
resource generation, a central signed-build runner prototype, actual APK/package/
certificate verification, immutable brand-channel publication and uncertain-publication
reconciliation. See **CENTRAL_BUILDER.md** for setup, recovery and explicit limits.
All builder/publication/trial rollout settings default OFF. No live deployment,
workflow dispatch or new signed APK publication has been performed. This is not an
already-running unattended APK service; queued work waits until a trusted runner runs.
The proposed extra GitHub workflow was removed at the user's request; all existing
workflows remain unchanged. Its branded-runner/retention adapter is deferred, not
connected to the normal testing publication workflow. Test normal114 first, then
take up the separate reseller APK without replacing the normal testing channel.

## Operator flow (local/offline preparation only)

1. Apply website migration014 after all earlier migrations. Open **My app -> My app
   brand**, choose the verified workspace, set app name and upload loading/logo/icon
   (static PNG/JPEG/WebP,1 MB maximum each). Save and request a build. Each workspace
   has ONE persistent identity. Edits reuse its package; no delete/clone/transfer API.
2. On the website host, export that queued build privately:
   `php cli/console.php brand-export BUILD_ID /private/path/brand-input.json`.
   Export excludes signing/worker/update secrets. It refuses cancelled/suspended or
   unlinked workspaces and checks immutable image hashes. Transfer this bundle to
   the owner-controlled isolated builder, not a public downloads directory.
3. Install `branding/requirements.txt` in the builder's isolated Python environment.
4. Run `python branding/prepare.py --bundle /private/path/brand-input.json --output
   /private/path/new-brand-resources`. Output must not already exist. It re-encodes
   bounded images (no uploaded filenames/SVG/scripts), generates all launcher/banner
   densities, replaces default/localized brand strings and creates public identity
   properties. Base app resources/source are not rewritten.
5. Resource/Kotlin compilation can be checked with
   `./gradlew -PbrandDirectory=/private/path/new-brand-resources
   :app:processSideloadDebugResources :app:compileSideloadDebugKotlin`.
   This does NOT create an installable signed reseller release.

Name/icon/loading/logo only: no colour customization. Public `BRAND_ID` is cosmetic,
not proof of app authenticity or permission to provision a line. Internal backend,
canonical source IDs, signing policy, provider URLs and entitlement identifiers are
not renamed. Default unbranded builds retain their original resources/identity.

The optional Gradle input uses a generated package tied to brand UUID, not a reseller
provided application ID. Brand-specific updater tags are derived from that UUID in
OUR existing release repository, with isolated testing tags. Different packages
never fall back to a generic APK. Distributable release/internal-test builds refuse
missing centrally managed signing, rather than falling back to a debug key.

## Before shipping reseller APKs

- Deploy the restricted SSH entry point and protected runner environments only after
  separate authorization, pin the reviewed full source commit and verify signing/
  publication with an approved testing brand. No generic/customer channel changes.
- Perform a real signed brand-preserving installation/update and ownership-enrollment
  test. Cosmetic revisions now have monotonic four-part version names/newer Android
  version codes; exact-version immutable APK selection prevents old/generic fallback.
- Validate optional external/deep-link/Discord callback behaviour with side-by-side
  packages before claiming those features supported. HTTPS activation is unchanged;
  generated package-scoped providers are checked on actual APK output.
- User chose broad-compatible PUBLIC brand-scoped signup, not hardware attestation.
  Package/hash/embedded ID are not APK authentication. Website0.13 includes the broker,
  request-bound key possession, quotas, native form/handoff and explicit publication
  controls. Keep rollout off until the separately approved device/panel pilot verifies
  real activation, note/contact persistence, recovery and repeat-trial refusal.

No automatically enabled trial checkbox is shipped ahead of these backend/native
capabilities. A user-visible name is not a panel username, authorization or ownership.
One-brand enforcement prevents minting extra identities through our service, not
APK sharing, off-platform resale or arbitrary repackaging.

## Storage increment

Settings -> provider device management -> **Storage usage** performs an explicit
local read-only scan of fixed byte counters. No contents/paths/identifiers are
uploaded. Totals are approximate, exclude installed APK/external/OS-managed space,
and show a partial warning if unreadable/bounded. Compare fresh install, first sync
and repeated sync on a real owner device before selecting broader cache budgets.

Saved Xtream VOD/series/category catalogues use transparent gzip JSON. Legacy plain
JSON remains readable; successfully read legacy files over256 KiB compact once.
Same schema/timestamps, TTL and offline stale fallback; atomic synced replacement
keeps the good cache on failure. No credential/preferences/favourites/guide/cache
purge or playback-buffer change. Synthetic compression results are not a claim of
real-device1.55 GB savings or hardware latency. The user has separately approved the
regular113 APK for isolated testing; no reseller-branded APK publication is included.
