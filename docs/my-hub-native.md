# My Hub native Android

## Confirmed scope

### Reseller-facing showcase

The user confirmed My Hub's reseller-panel page should match the My TV showcase.
`server/panel/my-hub.php` in the panel worktree now delegates presentation to
`my-hub-showcase.php`, with dedicated CSS, an SVG identity and two native sample
screenshots. It uses the My TV hero/license-card, paired-phone preview, benefits
and feature-grid layout with My Hub mint accents. Existing authentication,
active-account verification, CSRF and license redemption remain in the controller.
Active licenses show the 0.1.2 testing APK link; other states show license status
and the existing redemption flow. Login copy now says reseller-panel username
and password. Preview data is labelled and testing-build status is explicit.

Panel integration/security and Playwright desktop/mobile checks passed, as did
PHP syntax, the public-content privacy scan and three installer tests. Upgrading
from the confirmed workspace-access-130 bundle changes exactly six panel page/
asset files and is idempotent. No Hub PHP or My TV file changes are planned by
that upgrade. Screenshots: `/tmp/opencode/my-hub-showcase-{desktop,mobile}.png`.

### Approved native redesign (0.1.2-dev)

The owner approved the interactive screen render on November 5, 2026. The native
Compose implementation uses deep navy surfaces, mint accents, line icons, rounded
cards and Home / Customers / Devices / Renewals / More navigation. Wider screens
use a navigation rail. Dashboard metrics come from the existing scoped API;
preview records are confined to UI tests. Login, lists, device controls, renewal
tabs, library menus and shared forms/results use the same design system.
Top-level navigation clears detail history and refreshing retains the current
screen while loading. This APK uses the existing username/owner backend update;
no additional Website-host or Auth-worker deployment is needed for the redesign.
Real-device acceptance remains required before production distribution.

Testing APK: https://github.com/derangedchief-crypto/ARVIO/releases/download/my-hub-ui-test-20261105/My-Hub-0.1.2-testing.apk

- VersionCode 3; package `com.extreme.myhub.dev`; signer matches 0.1.1.
- SHA-256: `455f8507af5e55741e20a11fbec7d44af4ce6449db0d3ca9efa86b287b27f61e` (anonymous download verified).
- 18 tests passed, including five native Compose rendering/interaction tests;
  assembly and lint passed. Phone dashboard/login/devices/renewals and tablet
  dashboard captures are generated in `my-hub-android/app/build/ui-preview/`.
- UI captures use Robolectric native graphics with sample data. Hardware emulator
  access is unavailable; software emulation also failed on missing host graphics
  libraries. Physical-device acceptance is still pending.
- Attached APK includes worktree changes; generated baseline-tag source archives
  do not. No production signing/distribution decision is implied by the prerelease.

My Hub is a separate, fully native Android reseller application for
`https://control.fecxbrfzku.net`. All existing reseller controls are in scope.
The reseller panel must show My Hub directly below My TV, with a showcase and
account-bound license activation page. Website-hosted screens are not a substitute
for native workflows.

Development is isolated on `feature/my-hub`. The original Hub checkout's website
edits remain in its existing working tree. Neither publication nor a production
signing identity has been selected for My Hub.

The owner chose license administration alongside My TV in the existing reseller
website. Panel-side work is isolated in `/home/studio/my-hub-panel-workspace`,
also on `feature/my-hub`. The Website host is the entitlement authority; Hub must
verify access through a signed server-to-server bridge.

## Authentication and entitlement boundary

### Owner feature access correction

The initial owner-session update allowed login and ordinary management but left
three owner-kind feature gates checking the global owner role. This caused the
renewals “active workspace with a verified panel reseller link” error and blocked
branding/trial screens despite a valid link. `Auth::nativeOwner()` now attaches a
request-local `_native_owner_workspace` capability after validating the stored
session binding, active installation owner, active workspace and exact panel link.
Only the local gates in RenewalSettings, AppBrands and TrialPolicies accept that
capability for the same workspace. The actor remains reseller-scoped; global
owner operations and other workspaces remain denied. Existing sessions receive
the correction on their next authenticated request. No APK update is required.
Regression coverage exercises renewals, branding, trial policies, both trial
setup implementations, rejection without the capability, cross-workspace denial
and continued denial of installation-wide owner operations.

Published backend bundle:
https://github.com/derangedchief-crypto/ARVIO/releases/download/my-hub-workspace-access-20261105/My-Hub-workspace-access-backend.zip

SHA-256: `e067b12cf0ae42507c797139c4def2fef9e46476d5476f5f90a4ec85827045bd`.
All 352 integration cases passed, including the expanded owner-session regression;
two deployment tests, PHP syntax checks, exact prior-owner-bundle upgrade and
repeat-install checks passed. Anonymous release download verified. Live deployment
and user confirmation remain pending; only four Hub PHP files differ from the
previous owner bundle.

Live deployment preflight then refused AppBrands.php without writing anything.
The user's complete source diff confirmed the live branding baseline is 2.0.130
(446), with no 2.0.130 historical fallback entry, rather than this checkout's
2.0.131 (447). Published `My-Hub-workspace-access-130-backend.zip` on the same
release, preserving those live constants/pins and changing only its owner gate.
SHA-256: `f0b7e8146bdf0fcc1a8f842c660590ded643afa235376b54602f263b765935be`.
Build with `--brand-platform 2.0.130 --baseline /tmp/opencode/My-Hub-owner-backend.zip`.
Three deployment tests, reconstructed live four-file upgrade, idempotency, refusal
of a newer baseline, PHP syntax and anonymous-download hash checks passed.
Await deployment of this corrected variant; the original failed preflight did
not apply the access fix.

- Hub website authentication remains email/password. My Hub authenticates the
  existing reseller-panel username/password through the signed Website-host bridge,
  which calls the existing Auth-worker `/verify-login`. Only its verified numeric
  reseller ID resolves the existing `panel_resellers` mapping. One active local
  reseller user and an active linked workspace are required for reseller workspaces.
  An owner-linked panel account instead maps to the installation's active owner,
  with a native-only workspace binding (migration035). Native owner requests are
  presented to management services as a scoped workspace operator, never a global
  owner. No existing user role or panel mapping is changed. Disabled owners,
  suspended owner workspaces and removed/changed panel links invalidate access.
  Ambiguous/unlinked reseller workspaces are denied. Passwords are never stored or audited.
- Native sessions remain hashed and expiring. My Hub password management directs
  users to the reseller website rather than modifying their unrelated Hub password.
- Web requests currently use a session cookie plus CSRF and Origin validation.
- Native sessions need a distinct audience and bearer transport. A native token
  must not become an unlicensed web session by copying it into a cookie.
- License checks must run server-side on every native management request,
  including reads. Activation and logout must remain accessible when unlicensed.
- Disabled users, suspended tenants, password changes and logout must invalidate
  native access as they do website access.
- Use the same management service methods to retain tenant isolation, revisions,
  idempotent command retries and owner-only restrictions.
- Store tokens in Android Keystore-backed encrypted storage; never persist the
  password or an activation key. Never log credentials or customer payloads.

## Reseller-panel identity

Hub already has `panel_resellers(panel_id, tenant_id, username, linked_by)`.
`Enrollment::linkPanelReseller()` verifies panel identity through `PanelBridge`,
and enforces a permanent one-to-one mapping. Panel IDs and Hub IDs are different
identifiers. Any panel-side activation must resolve this verified link on the
server; an email match or submitted tenant ID is not ownership proof.

My TV licenses live in a separate SQLite database and cannot simply be queried
as Hub's MariaDB `module_licences`. That existing Hub module grants monthly Extra
Playlists access, not My Hub access. My Hub needs its own entitlement lifecycle,
with one-use keys, optional expiry, suspension, restoration, permanent revocation
and replacement, following My TV's behavior.

## Native workflow coverage

The following is an implementation inventory, not a claim of completed screens.

| Area | Existing service/API families | Required native workflows |
| --- | --- | --- |
| Account | Auth; `/me`, `/password`, `/logout` | Login, activation, license status, password change, logout |
| Overview | Management; `/overview`, `/workspace-context` | Workspace summary and current activity |
| Customers | Management; `/customers` | Search, pagination, create, detail, edit, notes |
| Devices | Management; `/devices` | Search/filter/pagination, create, detail, edit, permitted policy changes |
| Device actions | Commands; `/devices/{id}/commands` | Supported commands, confirmation, retry with stable key, status/history/cancel |
| Connection requests | Enrollment, RecoveryHolds | List and perform reseller-permitted actions; preserve owner-only recovery restrictions |
| Sources | Sources; `/devices/{id}/sources` | Snapshots, source editing, preview, apply through existing commands |
| Preferences | Preferences; `/preference-profiles`, device preferences | Profiles, snapshots, preview/apply |
| Categories | CategoryLayouts; `/category-layout-templates`, device category layout | Template editing, ordering, visibility and application |
| Favourites | Favourites; device favourites/backups | Capture, protect, preview and restore |
| Support | Support; device support | Diagnostic requests, artifact listing/download |
| Saved setups | DeviceProfiles; `/device-profiles`, `/device-defaults`, `/device-setups` | Create/edit profiles, defaults, apply, job status/control |
| Settings presets | SettingsPresets; `/settings-presets` | List/create/edit/archive |
| Branding | AppBrands; `/app-brand`, assets, builds | Branding fields/assets, build requests/status/cancel, permitted downloads |
| Trials | TrialSelfService, TrialSetup, TrialContacts | Available packages, app trial settings, contacts/detail/refresh/redact |
| Renewals | RenewalSettings, RenewalPayments | Merchant settings/setup, routing, availability, pricing, trial conversion, orders/retry |
| Add-ons | ModuleLicences; `/addons` | Extra Playlists status and redemption independent of My Hub entitlement |
| Activity | Management; `/audit` | Tenant-scoped audit/history and pagination |

Owner-only actions must stay owner-only even when listed in the same API family.
Native forms must follow the website's field validation and conditional controls,
not expose raw JSON editors as the finished reseller interface.

## Release acceptance

Before a release: complete the coverage above, native license enforcement and
tenant-isolation tests, panel linkage/activation tests, Android build/lint/tests,
and device checks for login, rotation, process recreation, pagination, retries,
image selection and downloads. Confirm repository, package ID, signing and update
channel with the owner before publishing. Host instructions must use Website host
and Auth-worker host labels and must not expose host IP addresses.

## Development checkpoint

Implemented locally:

- Separate My Hub license database and `MH-` one-use keys, using the existing
  admin license lifecycle, alongside My TV. Existing `AM-` keys cannot grant
  My Hub access.
- Reseller showcase/activation page and an idempotent menu installer that places
  My Hub directly beneath My TV, plus a My Hub license admin entry.
- Nonce-bound signed status/redemption bridge with replay rejection and fresh
  active-panel-account verification. Hub resolves panel identity only through
  its trusted workspace link. Bridge failures deny native management access.
- Native bearer session audience, migration and HTTP dispatch into the existing
  management services. Cookie and native sessions cannot be interchanged.
- A separately buildable Android project with native login/activation, overview,
  customer/device create/edit/list/search/filter, remote commands and history,
  activity history, branding edits/image uploads/build controls, current trial
  settings and Extra Playlists redemption.
- Native renewals: country pricing, trial conversion, Stripe/Square/Rampex merchant
  setup/replacement, gateway availability/routing and order reconciliation.
- Native library: versioned saved setups, automatic defaults, manual setup apply,
  job details/resume/cancel, preference profiles, sorting presets and category templates.
- Device workflows: revision-bound playback settings, source edit/rollback previews,
  preference previews, category source selection/order/visibility/template matching,
  legacy paged categories, favourites protection and preview-bound merge/replace.
- Trial contact review/status refresh/redaction, revision-bound recovery allow/reject,
  reseller password-management guidance, command details/cancellation/messages and
  support collection.
- Private support exports and branded APK downloads use native document selection.
  Downloads reject redirects and arbitrary paths; public APK requests omit bearer
  credentials and verify the published SHA-256 before copying to the destination.
- Encrypted uncertain-operation journal survives process recreation. Only server-
  idempotent commands/setup/favourites requests retain replayable payloads and keys.
  Other writes retain review metadata without credentials or bodies. Further writes
  are blocked until the user retries or reviews and acknowledges the uncertain result.
- Back navigation preserves list search/page/filter state. Device requests remain
  visibly queued until an app receipt arrives; Refresh retrieves the latest state.

Still required before release:

- Real-device native UI acceptance: rotation/process death, interrupted writes,
  image/document pickers, large category lists, stale snapshots and downloads.
  Automated journal serialization/replay tests do not replace Android process-death
  acceptance. Emulator UI validation remains unavailable because of KVM permissions.
- End-to-end real-host bridge provisioning and license suspension acceptance.
  Provision the same 32-byte private `my-hub-bridge.key` from the panel's private
  state directory into Hub's private `storage/` directory, readable only by the
  corresponding server account. Never send it to the Android app or publish it.
- Production identity, signing, repository and update-channel decisions, then
  deployment packaging and release acceptance. The user confirmed installation of
  the first backend testing bundle (migration034 and HTTP422 login validation).
  Testing assets are published as prereleases; the APK is not a production release.

Verification at this checkpoint: all 352 Hub integration cases passed, including session
audience and signed bridge isolation; panel licensing/security/browser and
installer/menu checks passed. Native workflow tests cover original-key recovery,
secret omission, stale-revision rejection, source replacement semantics, template
matching, authenticated download denial/redirects and APK integrity. Android build,
unit tests (13 passing), APK assembly and lint (0 errors)
passed from the separate project. Real-device verification remains
outstanding. Changes in both worktrees remain uncommitted. The username-login
update is published at GitHub prerelease `my-hub-username-test-20261105` with
`My-Hub-username-backend.zip` and `My-Hub-0.1.1-testing.apk`; installation and live
panel-username login acceptance are awaiting the user. The backend upgrade was
checked against the exact first testing bundle and changes five backend files.

The user confirmed that `MarcPanel` authenticates successfully and is linked to
the active owner workspace with no reseller users. Owner-workspace support is now
published in `my-hub-owner-test-20261105` as `My-Hub-owner-backend.zip`, tested
against the username-login bundle. It updates Auth.php and adds migration035.
Existing APK 0.1.1-dev is compatible; live installation/login acceptance is pending.
