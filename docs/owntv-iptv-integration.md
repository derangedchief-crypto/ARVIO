# OwnTV IPTV integration status

## Active route

`AppNavigation` continues to enter `LiveTvScreen`, retaining ARVIO's account,
profile, activation and navigation contracts. Its `TvGuideViewport` now resolves
to `OwnTvGuideViewport.kt`, not `LegacyTvGuideViewport`.

The guide adapts OwnTV's canvas strips and wall-clock cursor navigation and uses
OwnTV Core's `EpgProgrammeEntity` and `EpgDedupe`. Vertical navigation retains the
cursor time. A programme is not a separate Compose focus node. Ruler and row
geometry share a viewport; offscreen programmes are not measured or drawn.

Live and catch-up URLs resolve through ARVIO's existing authenticated source
configuration, then play through the actual `OwnTVPlayer` from Player Core.
ARVIO's DRM path remains available for compatibility, including PlayReady (which
OwnTV's DRM model does not support). Movies and shows keep their existing player.

## Isolation and upgrade safety

- `OwnTvIptvRuntime` constructs a private Koin graph, not the global context.
- ARVIO's Hilt graph, login and profile stores are unchanged.
- OwnTV settings/database instances are process-long to avoid duplicate DataStore
  instances. Native playback stops when the TV route is disposed.
- ARVIO's source, favourites, history and SQLite EPG stores remain the data bridge;
  this is **not** a migration of those records into OwnTV's catalog database.
- Surface ownership follows the mini/fullscreen destination. The player layout
  uses SurfaceView, and OwnTV's identity-aware detach protects the newer surface.
- Native commands, retries, decoder selection and buffer budgets are delegated
  to upstream OwnTVPlayer rather than reimplemented in the ARVIO host.
- Catch-up pause/seek and timeline state use OwnTV's engine; URL-anchor changes
  still use ARVIO's provider-specific catch-up resolver.
- OwnTV's reconnect callback resolves a fresh URL through the same authenticated
  source adapter; it never reuses a previous channel's Stalker callback.
- Coroutines runtime/test libraries align with OwnTV's 1.11.0 requirement rather
  than downgrading its runtime to ARVIO's previous 1.7.3 pin.

## Validation completed

- `:app:compileSideloadInternalTestKotlin` succeeds.
- Targeted JVM/Robolectric suite: 27 tests pass, zero failures.
- Tests include OwnTV's real dependency-graph construction without starting a
  stream, global Koin isolation, programme navigation/gaps/bounds, duplicate
  broadcasts versus genuine repeats, existing guide viewport and source tests,
  and the update-version comparison against the activation-login build.
- `git diff --check` succeeds.

Those results cover the guide/player port before the final reconnect callback
and coroutines-runtime alignment. The same validation suite and optimized APK
build are being rerun for that last change; do not interpret a previous green
result as validation of a newer unbuilt revision.

## Still required before publishing

- Assemble the optimized internal-test APK after these changes.
- Device checks for native playback, SurfaceView mini/fullscreen transitions,
  channel zapping, background/resume and error recovery.
- Authorized provider checks for M3U/Xtream/Stalker sources, guide population,
  missing-guide rows and archive seeking.
- Check the ported guide layout and touch/remote focus on target screen sizes.

There is no connected Android device in this environment. Unit tests and a
successful build do not demonstrate smooth native playback or correct focus on
hardware. The testing workflow must not be dispatched as a substitute for the
remaining checks or represented as a complete migration of OwnTV's entire app.

## Release version

Version code remains `391`. Version name is `2.0.075`, following the actual
activation branch's `2.0.074` format. `2.0.0.75` would sort **below** `2.0.074`
with ARVIO's numeric-component comparator and would not be offered as an update.

See `THIRD_PARTY_NOTICES.md` for upstream attribution and GPL requirements.
