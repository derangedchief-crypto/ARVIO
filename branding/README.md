# One-brand build preparation

This increment implements a **brand registry, private uploads, pinned build input
queue and Android resource preparation**. It is NOT yet an unattended APK service.
The website honestly shows Waiting for central builder; trial creation is disabled.

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

## Still required before shipping reseller APKs

- Owner-controlled builder pickup/lease/finish/artifact verification and publication,
  package/certificate enrollment registry, signed artifact provenance, compatible
  deep-link/auth callback review and an actual brand-preserving update test.
- Release/version allocation for branding-only revisions: changing artwork/name on
  an already-installed app must produce a monotonically newer installable/update
  version, not another APK with an indistinguishable version. Queue inputs already
  pin platform and brand versions separately; automatic publisher allocation and
  update metadata still need implementation before distribution.
- Trusted branded-install policy suitable for sideload TV devices; merely reporting
  a package/signing hash or extracting an embedded secret is NOT authentication.
- Trial admin allowlists/defaults and structured contact views are implemented in
  website0.12. Still needed: trusted branded-install session, public provisioning
  broker, protected single-operation result delivery and activation-screen integration.

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
