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

Current testing version code is `398`. Version name is `2.0.082`, following the actual
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

## Guide retention and mobile aspect recovery in 2.0.080 / 396

Partial/short guide responses previously replaced whole channel rows, and final
provider imports could replace the whole source generation. They now merge
programme timestamps transactionally, preserving unreturned channels and future
listings, replacing corrected entries with matching start times, and pruning
history outside the existing 48-hour retention window.

The viewport now distinguishes "requested before" from "still resident" so an
evicted row is hydrated when scrolling back. Empty/unavailable reads do not erase
valid retained cells or enter the repository's window cache. A cache generation
guard prevents an in-flight old read from repopulating a freshly invalidated
cache. Local reads refresh each minute as well as on index revision; this does
not initiate network downloads. The current playing channel remains hydrated for
the preview's now/next panel even when browsing another category.

Preview plates now measure at 16:9 instead of fixed 154/180dp heights. While mpv
owns a PlayerView it has no Media3 player to supply automatic video-size events;
the adapter now forwards OwnTVPlayer.videoAspect to its aspect-ratio frame and
uses FIT scaling. This preserves 16:9 and 4:3 pictures in wide/Fold, portrait and
fullscreen containers instead of stretching the SurfaceView to arbitrary bounds.

New regressions cover short refresh preservation, corrected listings, interrupted
refresh/reopen, source isolation, expired-history pruning, viewport eviction and
empty-read retention, native-view aspect geometry and Fold preview dimensions.
Native stream playback still requires confirmation on the user's device.

## All-channel loading in 2.0.081 / 397

Previous loading policies deferred complete-guide work during playback, accepted
75% coverage for large lists (or merely some fresh data for smaller lists), and
limited large-list network requests to selected/favourite channels and tiny
visible subsets. Changing focus also cancelled the visible queue. A paged
background sweep existed but was not called.

The complete-guide path now uses OwnTV's actual streaming XMLTV parser, commits
at most 256 programme rows per batch directly to SQLite, and continues during
playback on the low-priority background dispatcher. It retains only channel
metadata and a bounded write batch, not the full programme map in memory.
Working Xtream feed aliases are not redundantly downloaded; explicitly
configured supplemental sources still run. Cancelled/failed imports preserve
committed rows and mark an incomplete import for retry.

After bulk indexing, a background pass walks every playlist channel in pages of
12, skipping listings already on disk and fetching missing short provider EPG
without expanding the in-memory UI cache or downloading XMLTV again per page.
Network pages are paced during playback; cache-only pages yield without a
750ms-per-page sleep. Progress is shown in the guide and is rate-limited to avoid
driving UI recomposition for every cached channel. The pass repeats periodically
and never requires selecting a channel. Visible rows remain prioritized but
their requests are no longer discarded when focus moves.

Coverage requirements use 98% rather than treating a partial cache as complete.
Providers can still publish no listings or fail requests: this change attempts
the entire playlist; it cannot create programme data the source does not supply.

Regression coverage includes an integration test importing 1,000 channels through
the real repository with playback marked active, one HTTP fixture feed, no channel
selection, 1,000 durable guide rows and no whole-guide memory-cache growth. Further
tests exercise the upstream parser's 256-row bound, all 10,025 channels in a paged
fallback pass, cache skips, empty responses, errors, cancellation and source
changes, alongside the previous startup/UI/retention/aspect checks.

## Server-cache-first restoration in 2.0.082 / 398

The 2.0.081 disk-first loader bypassed `importDailyWorkerGuide`, unintentionally
skipping the existing shared cache at
`https://activationworker.extremeiptv.net/epg/full.sqlite.gz`. The complete-guide
path now tries that cache before provider XMLTV, even without an explicit XMLTV
candidate. Gzip download/decompression streams to a temporary SQLite database;
matched programmes are copied in batches of at most 256 rows into the local
index, not a playlist-wide in-memory map. The legacy loader uses the same bounded
importer. Temporary database files and sidecars are removed afterwards.

Current/near-future cache coverage determines which channels need fallback.
Complete cache coverage skips XMLTV downloads; partial coverage keeps the server
listings and imports XMLTV only for uncovered channels. The existing paged
provider sweep then skips populated local rows. Expired, malformed and unavailable
cache responses fall back to provider loading. Cancellation still propagates and
previously committed guide rows remain queryable. The all-channel loading and
playback-independent indexing from 2.0.081 are retained.

Regression fixtures cover complete and partial gzipped SQLite caches, expired
listings, corrupt gzip, HTTP 503, and display-name matching with different XMLTV
IDs, using the actual repository while playback is active. Tests verify request
order, fallback contents, no whole-guide memory-cache growth and temporary-file
cleanup. A read-only download of the live server cache on 2026-10-19 confirmed
4,075 channel records, 283,638 programme rows and current listings. No on-device
2–5 minute timing claim has been validated here.
