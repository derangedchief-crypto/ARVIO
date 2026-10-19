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

The final validation for implementation revision `5fae955`, including the
reconnect callback and coroutines-runtime alignment, completed successfully:
optimized `assembleSideloadInternalTest` plus the targeted 27-test suite, zero
failures/errors/skips. Build log: `/tmp/opencode/owntv-5fae955-validation.log`.

## Still required before publishing

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

TV UI replacement version code is `395`. Version name is `2.0.079`, following the actual
activation branch's `2.0.074` format. `2.0.0.75` would sort **below** `2.0.074`
with ARVIO's numeric-component comparator and would not be offered as an update.

See `THIRD_PARTY_NOTICES.md` for upstream attribution and GPL requirements.

## Startup regression in 2.0.076 / 392

The published build crashed before the UI opened. A new Robolectric regression
test using ARVIO's actual `ArflixApplication` reproduced this dependency failure:

```
NoSuchMethodError: kotlinx.coroutines.internal.LockFreeLinkedListHead.addLast
  at io.ktor.events.Events.subscribe
  at io.ktor.server.engine.BaseApplicationEngine.<init>
  at TelegramStreamingProxy.start
  at TelegramRepository.<init>
  at Hilt_ArflixApplication.onCreate
```

The old Ktor 2.3.7 events implementation calls a coroutines-internal method
removed in coroutines 1.9+. OwnTV's runtime alignment to coroutines 1.11 exposed
that incompatibility in ARVIO's eagerly initialized Telegram streaming proxy.
The previous isolated OwnTV graph test did not exercise this startup path.

Ktor fixed this exact failure in 2.3.12 (KTOR-7054). The recovery aligns Ktor's
client/server modules with the 2.3.13 BOM, preserving the 2.x API used by ARVIO
and Supabase while keeping the coroutines version required by OwnTV. The real
application test fails on 2.3.7 and passes on 2.3.13. Recovery validation includes
internal-test Kotlin compilation and 28 targeted tests: zero failures/errors/skips.

Reference: https://ktor.io/changelog/2.3/

The headless emulator could not complete a boot without accessible KVM in this
environment and was stopped. No successful Android 16 device launch is claimed.
The reproduced failure and fix above are from the real-application Robolectric
startup test, not an emulator crash log or a crash report from the user's phone.

## Phone carousel linkage failure in 2.0.077 / 393

The phone crash report then identified a missing `HorizontalPager` method. Gradle
dependency inspection confirmed Foundation 1.6.8 on the app's compile classpath
but 1.12.0 on its runtime classpath: OwnTV's implementation dependencies upgraded
the packaged libraries without upgrading ARVIO's compile-time BOM.

The recovery uses OwnTV's Compose BOM 2026.08.00 for app compilation and UI tests,
updates TV Material to 1.1.0 and uses the pager's renamed
`beyondViewportPageCount` argument. The real populated `MobileHeroCarousel` is
rendered by a new Robolectric Compose UI test; the existing TV lazy row and grid
also have rendering smoke tests. These tests supplement the application-startup
test, which cannot detect a failure that occurs only when UI content is rendered.

`verify<Variant>ComposeAlignment` compares all shared Compose module versions
between compile/runtime configurations and runs from the variant's pre-build
task. A negative test forcing Foundation 1.6.8 onto only the compile classpath
fails with the exact `compiled=1.6.8, packaged=1.12.0` mismatch, before compiling
or packaging another incompatible APK. The publishing workflow is unchanged.

## Visible TV interface replacement in 2.0.079 / 395

The TV route no longer calls the old `MiniPlayerRow`, `CategorySidebar`,
`TouchCategoryRail`, `ProviderSelector`, `AppTopBar` or `FullscreenHud` components.
The new TV-only Stage chrome provides:

- A new desktop/TV header and provider/folder navigation panel.
- A floating preview plate and programme detail stage, adapting to phone width.
- A category-picker dialog on phones instead of the old scrolling country chips.
- New channel-label rows coordinated with the OwnTV-derived canvas programme strips.
- A new fullscreen player overlay, with existing live/catch-up command callbacks,
  scrubbing, guide/zap actions, focus and automatic-hide behaviour retained.

This is an ARVIO-adapted Stage interface, not a verbatim copy of OwnTV's entire
application shell. Non-TV routes and login are unchanged by this UI revision.
Filtering, favorites, hidden groups, ordering and playback retain their existing
source/profile adapters.

Validation: 39 targeted tests pass, including actual application startup, populated
phone home, TV widget compatibility, new phone category selection, category
management callbacks, preview fullscreen action, fullscreen controls/visibility,
desktop and phone rendering, and the previous guide/player tests. The renderer
tests save actual native-rendered screenshots using fixture programme data to
`/tmp/opencode/tv-stage-desktop.png` and `/tmp/opencode/tv-stage-phone.png`.
These screenshots do not demonstrate native video playback: the preview plate
has no active test stream. No physical-device playback validation is claimed.
