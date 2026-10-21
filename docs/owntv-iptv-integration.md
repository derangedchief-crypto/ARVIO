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
configuration. Normal live playback starts on OwnTV's `LivePreviewEngine` under
`LiveTuneController`, with its automatic `OwnTVPlayer` compatibility fallback;
catch-up stays on `OwnTVPlayer` from Player Core.
ARVIO's DRM path remains available for compatibility, including PlayReady (which
OwnTV's DRM model does not support). Movies and shows keep their existing player.

## Isolation and upgrade safety

- `OwnTvIptvRuntime` constructs a private Koin graph, not the global context.
- ARVIO's Hilt graph, login and profile stores are unchanged.
- OwnTV settings/database instances are process-long to avoid duplicate DataStore
  instances. Native playback stops when the TV route is disposed.
- ARVIO's source, favourites, history and SQLite EPG stores remain the data bridge;
  this is **not** a migration of those records into OwnTV's catalog database.
- A persistent video view resizes between preview/fullscreen. The player layout
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

Current development version code is `422`. Version name is `2.0.106`, following the actual
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

## Playback popup lifecycle in 2.0.083 / 399

The startup notification previously cleared only when the engine's playing,
buffering or error StateFlow changed. Channel changes can keep `isPlaying=true`,
leaving a newly posted startup message stuck over working video. A shared
diagnostic lifecycle now checks every new message against the active player's
playing state, covering both playback paths. Startup/retry messages dismiss on
playback; terminal errors remain visible for three seconds then dismiss. Timer
cancellation prevents an old failure from clearing a newer channel's message.
Engine-error notifications are observed separately from buffering changes so a
dismissed failure is not repeatedly re-posted when buffering toggles.

The startup popup uses the channel name rather than OwnTV branding, and the
compatibility retry popup is also engine-neutral. Required third-party notices
and the underlying playback integration remain intact. Compose regressions cover
startup dismissal, unchanged-playing-state channel switches, failure expiry,
failure while the previous channel still reports playing, timer cancellation and
retry dismissal. The server-cache-first fix from 2.0.082 remains included.

## Shared background loading and stable channel rows in 2.0.084 / 400

Full guide work now belongs to an application-scoped repository coordinator.
Home requests it immediately, without the old 6–8 minute warmup delay; activity
startup and WorkManager request the same job. TV observes shared progress instead
of owning the bulk import and provider sweep in its ViewModel. Leaving any tab
cancels only UI observation, not the import. Cache-first import and missing-channel
checking are phases of one progress session; completion is reported only after
both finish. Successful completion is stored per profile/source for six hours,
so absent provider listings or repeated TV/worker requests do not restart a
finished import. Explicit guide refresh bypasses that freshness check.

The channel LazyColumn now retains all loaded category metadata with stable IDs,
appending pages rather than replacing its backing list with a sliding slice.
Only programme hydration moves with the visible rows and remains bounded. This
avoids stranding a fling/focus position outside a replacement page and blanking
the category. Both compact/mobile and TV layouts use the same stable list path.

Tests cover concurrent Home/TV/worker requests sharing one job, screen-waiter
cancellation leaving work alive, subsequent scheduled requests, real-repository
completion freshness (including a channel without provider listings), and scrolling
a 350-row category across former window boundaries and back up. Physical-device
navigation and timing still require confirmation.

## Launch-time provider catalogue refresh in 2.0.085 / 401

Activity/Home startup now requests an application-owned content check per
profile/configuration. Returning to a retained activity can recheck after a
15-minute debounce; tab navigation does not repeat it. Existing cached content renders immediately;
the check survives tab changes and is independent of the six-hour EPG freshness
window. Previously startup prefetch only fetched a playlist when no cached channels
existed, so a warm launch could skip new/removed provider content entirely.

Live playlists are refreshed source by source. Successful nonempty snapshots
replace that provider's rows (additions, removals and metadata/category updates),
while failed, timed-out or ambiguous empty live responses retain the prior source.
Unchanged snapshots retain ordering; stable channel IDs preserve favourites and
session references. Commits check the active profile/config under the load mutex,
then notify TV to read the refreshed in-memory snapshot without an empty-list phase.

Enabled Xtream providers also refresh `get_vod_streams` and `get_series` lists,
bypassing the normal catalogue TTL for this one launch check. These are metadata
requests, not episode-tree/media downloads. Valid snapshots replace credential-
scoped disk caches; the currently owned in-memory catalogue and derived source
indices are updated/invalidated as appropriate. Valid empty movie/series arrays
remove old entries; failed/malformed responses retain the previous cache. Related
provider resolver bindings are invalidated so removed series do not survive solely
through a stale lookup. Other profiles' bindings are retained. Providers without a
delta/version endpoint still require downloading their full metadata lists; no
fixed wall-clock speedup is claimed. Stalker live lists remain supported; this
change does not introduce a new Stalker VOD catalogue adapter.

The live-list check precedes automatic EPG startup, avoiding an import against an
outdated channel list followed by an immediate second import. Channel identity
changes invalidate the six-hour guide completion marker once; metadata-only and
unchanged checks do not. Cached episode information for the refreshed provider
expires so opening a show can discover new episodes without a launch-time crawl.

Regression tests exercise real-repository channel/movie/series updates, repeated
launch requests, HTTP failures, malformed and empty catalogue responses, profile
switch rejection, absence of per-episode requests, source-safe channel merging,
ordering and separate Stalker portal ownership. Existing guide/popup/scroll/startup
regressions remain in the release validation set.

## Movie/episode VOD verification before scraper fallback in 2.0.086 / 402

Movie source discovery previously appended VOD in parallel with addons/plugins;
scraper links could win autoplay and spend points before the VOD lookup finished.
VOD matching also resolved credentials only from the legacy single-provider URLs,
ignoring enabled multi-playlist providers, and its cold lookup used the short
playback-lookup timeout rather than a catalogue budget.

All enabled Xtream playlists are now checked for movie VOD. Every local catalogue
is searched before waiting on any provider network request, allowing a cached hit
on a secondary provider to win even if the first provider is unavailable. On a
miss, strict fallback verification refreshes each required catalogue with a
45-second per-provider network budget inside a 90-second overall check. Only
complete, valid responses can establish absence; failures, malformed responses,
profile/config changes and timeout remain verification errors, not empty results.
A hit from another provider still wins despite one failed provider. Confirmed
misses are coalesced for 60 seconds, scoped to profile/config/movie identity, and
invalidated by catalogue updates.

Both progressive and non-progressive movie source resolution check VOD before
addon caches or external requests. Details checks the same gate before launching
plugin scrapers and returns VOD sources directly when found. Failed verification
stops automatic scraper fallback and asks the user to retry. Player metadata is
hydrated before title-dependent movie discovery, and progressive movie requests
are structured children so cancellation cannot leave a paid fallback running
after a cancelled VOD check. Explicitly selected playback URLs remain playable.

The same gate covers progressive/non-progressive episode discovery and the
Details plugin path. Native series lists and exact season/episode records are
checked across enabled providers, including matching variants and individual
episode files in movie catalogues. A missing episode in an existing show permits
fallback only after all necessary requests complete successfully. Failed series
info, ambiguous numbering/season data and malformed payloads remain verification
errors. Strict checks never use legacy closest-season/episode-only guesses.
Verified episode hits are cached per profile/provider/series; launch catalogue
refresh invalidates them. Automatic parallel supplemental probes were removed
from normal movie and episode discovery so they cannot bypass the gate.

Title normalization now folds diacritics. Exact short-title fallback covers names
discarded by the token index (e.g. Up/It), including a trailing release year.
Explicitly conflicting remake years are rejected in title-based matches; strong
TMDB/IMDb matches retain priority. Complete compact canonical keys cover fused
versus spaced spellings (Masterplan / Master Plan), with numeric sequel checks.
No broad lowering of fuzzy-match thresholds was introduced.

Regression fixtures verify multi-provider and stale-cache matching, short and
accented titles, remake rejection, valid empty catalogues, failure versus absence,
and the actual StreamRepository paths with a mocked points-consuming source:
zero scraper calls on VOD hits/errors/cancellation, and no scraper call before
verification completes. The user's reported Masterplan (2026) is covered by
exact-title and spaced-title fixtures, plus rejection of Master Plan 2. This is
not a claim of direct access to their authenticated live provider catalogue.
Episode fixtures cover exact availability, wrong-season rejection, a missing
episode in an existing show, absent shows, provider failures, malformed numbering,
secondary providers and episodes stored in movie lists. Autoplay planner tests
were aligned with the existing local-library grace window (no planner timing code
was changed), and verified VOD sources are explicitly tested not to wait for
remote scraper results.

## Authorized manual Extreme Cloud backup search in 2.0.087 / 403

VOD-first remains the default for movies and exact episodes. A separate,
request-scoped `ManualScraperFallback` search mode lets a user explicitly request
backup links even when VOD exists or its verification failed. It is not saved as
a default and does not silently enable paid fallback on later playback requests.

Both Details and Player source pickers expose **Search Extreme Cloud** only when
the existing Xtream Cloud Stream entitlement source is installed and enabled.
Access follows the configured entitlement manifest or the addon identity recorded
by `XtreamEntitlementsGuard` (`granted_addon_id:cloud_stream`); arbitrary addon names
or unrelated installed scrapers do not grant it. This reuses the existing package-
driven addon grant/revocation path rather than adding a separate entitlement rule.
Access is observed reactively and rechecked at each manual repository entry point.
Unknown/unreadable state fails closed. Unauthorized calls make no external source
requests and do not show an upsell or permission message.

The action opens a confirmation explaining that Extreme Cloud lookup/resolution
may use points. Merely focusing the TV rail action, opening the dialog or cancelling
it does not start a search. Confirmation keeps VOD/home-server sources available,
fetches configured backup links and leaves source selection to the user; it does
not automatically replace a playing VOD stream. Pending automatic discovery is
cancelled when the user requests backups, preventing late automatic results from
overwriting that choice. Details also cancels old plugin/prewarm work. Revocation
hides the action, closes an open confirmation and cancels manual lookups; VOD
sources and the active playback selection remain intact. Generic VOD verification
errors do not advertise Extreme Cloud to users without access.

Regression coverage includes authorized and denied movie/episode requests for
both progressive and non-progressive discovery, return to VOD-first after manual
search, unreadable access state, live entitlement revocation observation, grant
identity/URL matching and spoofed/disabled sources. Compose phone/TV tests exercise
the requested label, explicit confirmation, cancellation, remote focus without a
request, VOD-row retention, hidden unauthorized UI and revocation while a dialog
is open. Device playback and remote timing still require user confirmation.

## Simple authorized automatic Cloud fallback in 2.0.088 / 404

This supersedes 2.0.087's manual-search UI. There is no search action or permission
confirmation. The single flow is now: check movie/exact-episode VOD; return VOD
when found; after a verified absence, automatically query Extreme Cloud only if
the existing Cloud Stream entitlement permits it. Unavailable/unfinished VOD
checks are still errors, not proof of absence. Unauthorized users make no Cloud
requests and receive no Cloud popup. The authorization check now covers automatic
fallback and cached-result reuse, not just a manual override. Details plugin
discovery is gated by the same check. Personal media-server lookups remain
independent of Cloud entitlement.

Cloud results display as **Extreme Cloud** instead of Torrentio PM. This applies
to fresh and cached links, the source picker and source-info display. A separate
nullable presentation label preserves raw addon names/IDs, links, debrid handling,
cache keys and source identity. Multiple underlying Cloud sources share a single
display group without deduplicating their actual provider identities. VOD and
personal-server labels are unchanged. The former manual mode, callbacks, action,
permission popup and warning strings were removed.

Regressions cover automatic authorized fallback after a verified movie/episode
miss, VOD hits without Cloud requests, unauthorized automatic requests (both
progressive and non-progressive), cancelled/failed verification, cached branding,
revoked authorization preventing cached-link reuse and touch/TV direct selection
without a permission dialog. Existing Masterplan matching, EPG retention,
background loading, startup and channel-scroll tests remain in validation.

## Guide-first television layout in 2.0.089 / 405

TV guide mode no longer reserves the 82dp top navigation header. Profile, Search,
Home, Library, Live TV and Settings are in a normally hidden drawer on the far
right. The small upper-right menu control, the remote Menu key, or Up from the
category search/provider selector opens it. Up/Down selects an entry; Enter
activates it. Back/Left closes it and returns focus to the previous guide zone.
The drawer overlays the guide instead of resizing it, and normal Right-key EPG
time navigation remains unchanged. Menu-key repeats and active search/variant
modals are guarded so a held button or another modal cannot steal navigation.

Only the TV preview uses a compact guide-mode layout: the video is capped at
200dp wide, keeps its 16:9 plate and FIT aspect handling, and sits beside compact
one-line programme information/actions. The EPG loading indicator is a single
thin status row, and guide-heading padding is reduced. Channel/programme row
fonts and heights are unchanged. Mobile/tablet preview layouts, fullscreen
playback, source resolution and application-owned guide loading are unchanged.

Compose layout checks compare the old and new arrangements at 960×540 and
1280×720 logical TV sizes, including the loading status. They require at least
150dp more guide height, at least 60% of the screen for the guide viewport, at
least 8/12 visible rows respectively, and a 16:9 preview no larger than 200×113dp.
Opening the drawer must not change the measured guide height. Further tests
exercise hidden-by-default navigation, all destinations/profile, remote movement
without activation, direct selection and Back/Left dismissal. Fixture screenshots
have a black preview surface because no live stream is loaded; they validate
layout, not physical 65-inch hardware playback.

The same update fixes a live/fullscreen buffering-wheel race. A fast native
channel start can emit buffering true/false within a frame, leaving a separate
mutable preparation flag stuck true even though the active engine's collected
buffering value is false. The HUD now reads the active engine directly, ignores
inactive Media3 preparation flags and stops loading on native terminal errors.
Actual cache rebuffering still displays the indicator even when `isPlaying`
remains true. The live PlayerView's independent native buffering wheel is
explicitly disabled so the HUD is the single loading-indicator owner. Explicit
fullscreen playback also cancels pending guide/VOD action lookups rather than
letting their busy indicator cover the playing channel.

Regression tests exercise unchanged/conflated native values across repeated
starts, genuine rebuffering after HUD controls hide, recovery, engine switching,
Media3/DRM buffering, terminal failure and inflated PlayerView configuration.

960×540 logical TV fixture screenshots:
[before](screenshots/tv-guide-before.png),
[guide-first layout](screenshots/tv-guide-after.png),
[right navigation open](screenshots/tv-guide-menu.png).

## Persistent fast server cache and non-destructive refresh in 2.0.090 / 406

Three client-side problems caused slow refreshes and apparent guide loss: the
server SQLite file was downloaded to a disposable temp file and copied through
one Kotlin object/delete/insert per programme; a partial successful cache could
trigger a second full provider XMLTV download; and both an unknown/changed EPG
signature and Settings → Refresh IPTV could delete the existing index.

Server snapshots now live in app files storage, verified against the manifest's
SHA-256, gzip checksum and SQLite integrity/schema. An unchanged manifest reuses
the verified SQLite file without another payload download. New files are promoted
only after validation; a previous verified file remains available. Failed or
corrupt downloads keep the last good file, and an offline fallback never overwrites
a usable local index with an older artifact. Files are immutable by checksum and
bounded to the selected snapshot plus one previous snapshot.

Programme copying now uses an attached SQLite source and native INSERT SELECT
in batches of 64 feed channels. Timestamp corrections commit atomically with
their replacements; unreturned channels/future listings remain intact. Readers
can use the active generation between commits and cancelled/failed batches do
not empty it. No index schema-version bump or destructive migration is required.
The local regression fixture imports 96,000 programmes in 16 commits (about 1.1s
in the development test run; not a physical-TV timing claim). Rechecks of the same
artifact/mapping skip existing rows and extend only the newly entering future
window. Cosmetic logo/raw-M3U changes do not invalidate guide mappings.

With a usable shared cache, uncovered Xtream channels use their short-guide API
instead of another whole XMLTV feed. Plain-M3U sources that require XMLTV still
have that fallback. The missing-channel sweep reads coverage once, uses pages of
48, avoids network delays for non-requestable channels and prunes history once
rather than scanning the whole source after every page. Foreground row requests
re-read SQLite after a short-EPG miss, and cannot restart an authoritative shared
import or a recently completed whole guide just because one channel has no data.

Missing upgrade signatures and EPG URL changes now mark the same source stale
without deleting its guide. Settings Refresh preserves channel/guide storage,
expires only resolver/catalogue caches, and joins the same application-owned guide
loader instead of its old broad XMLTV path. Settings reports persisted guide
coverage, not only the small in-memory snapshot. Old completion checkpoints are
rechecked once using a new checkpoint key; their programmes are not removed.

The TV update indicator is again a visible full-width progress bar, with byte,
validation and indexing phase information. The route observes shared progress
directly, so stale UI-state copies cannot hide it; progress is monotonic across
phases. A failed refresh shows that saved listings are retained, not an endless
loading wheel. Partial/empty viewport reads retain valid loaded broadcasts rather
than replacing a whole channel schedule. The compact TV layout remains intact.

Tests cover durable reuse across cache instances, offline/corrupt updates, disk
corruption fallback, native bulk copying/cancellation/rollback, future-window
extension, old checkpoint recovery, missing signature/EPG-edit/manual-refresh
retention, targeted Xtream fallback, prevention of repeated visible-row XMLTV
downloads, and full-width progress alongside saved programme rows. First-ever
installs still need source data, and no client can invent listings a provider
does not publish; the guarantee here is not discarding usable saved guide data
merely to refresh or retry it.

## DTS MKV linkage repair in 2.0.106 / 422

The supplied Sentry event `8ad423a7` (trace `5463f8a1b34c48aea566a14899d587ed`)
reports a fatal `DtsUtil.isSampleDtsHd(ExtractorInput, int)` call from the vendored
MatroskaExtractor.writeSampleData, first seen in 2.0.100 and reproduced in
2.0.103-test. No canonical issue URL was supplied. Trakt's earlier handled HTTP
errors are not on the failing extractor/loader stack.

Dependency inspection confirmed host compile Media3 1.9.0 versus packaged Media3
1.11.1 (OwnTV's implementation dependencies upgrade runtime only). Inspection of
the actual cached AAR classes confirmed isSampleDtsHd exists in 1.9.0 but not
1.11.1, which provides updateFormatWithDtsHdInfo instead. Host declarations now
match the existing 1.11.1 runtime; this is not a decoder/library downgrade or an
OwnTV engine change. A per-variant pre-build verification task rejects mismatched
Media3 compile/runtime modules and mixed Media3 module versions. A negative fixture
deliberately forcing compile extractor 1.9.0 correctly failed with the new ABI
mismatch diagnostic (fixture/log stay outside the repository).

The vendored extractor now uses the current format-aware DTS-HD probe and skips
probing samples shorter than the four-byte sync word. Its unused private legacy
probe is removed. Dolby Vision P7 hooks, source selection, DRM, the fast live
engine, normal Back behavior and the update/relaunch implementation are unchanged.
The fork remains based on 1.9.0 with explicit current-API adapters, not a claimed
full rebase to 1.11.1. Source/package documentation reflects that distinction.

Regression fixtures exercise the actual private writeSampleData call site against
the real runtime using synthetic DTS core/HD extension headers, plus factory
replacement/toggle tests for existing DV hooks. These are extractor/API tests,
not hardware DTS decoding tests. The affected movie/stream still needs a device
retest after a fixed testing APK is published. Main/customer remains 2.0.101;
2.0.105's separate relaunch exercise does not contain this repair.

Validation: 146 targeted JVM/Robolectric tests passed, zero failures/errors/skips,
including the 10 DTS/DV regressions plus live presentation/tuning, updater/relaunch,
activation, source preference and actual application startup checks. Internal-test
Java/Kotlin and Android-instrumentation Kotlin compilation passed. The customer
staging Media3 alignment guard also passed. Final log:
`/tmp/opencode/dts-media3-final-validation.log`; negative guard log:
`/tmp/opencode/dts-media3-negative-guard.log`. No hardware DTS decoding or playback
of the customer's affected stream was performed locally. The user authorized
publishing 2.0.106-test through the existing testing workflow. Device checks should
use the in-app updater, confirm reopening, and replay the affected DTS MKV.

## Relaunch device exercise in 2.0.105 / 421

The user confirmed 2.0.104-test is installed and open. This release changes only
version fields and documentation, preserving the tested relaunch implementation.
Leave 104 open until its mandatory update prompt detects 105, then install through
that prompt and confirm whether the app reopens automatically on 105. Do not
manually sideload 105 for this exercise: it must be initiated by 104's updater.
Main/customer release remains 2.0.101. Native relaunch results are not yet known.

## Post-update relaunch in 2.0.104 / 420

The user confirmed mandatory detection/enforcement works in the 2.0.102 ->
2.0.103 exercise, but the installed app did not reopen. Existing completion
callbacks were broadcast PendingIntents; both success and MY_PACKAGE_REPLACED
then attempted a background activity start with CLEAR_TASK. Modern Android can
silently block that attempt, even when startActivity does not throw.

New sessions use an explicit, session-scoped mutable activity PendingIntent to
the non-exported UpdateInstallActivity. The system can fill confirmation/status
extras and retain the callback across package/process replacement. Android 15+
creator BAL opt-in (Android 16's ALLOW_ALWAYS mode) is scoped to this updater token.
The activity handles results after foreground resume, still opens Android's
required confirmation UI, and opens MainActivity directly on success. Failures
return to the existing mandatory gate for retry. Main relaunch reuses its task
with CLEAR_TOP/SINGLE_TOP rather than wiping it with CLEAR_TASK. No generic
launcher lookup, alarm, overlay permission, forced sign-out or engine change is
introduced. Old broadcast sessions/package replacement remain best-effort fallbacks
using an explicit activity token with creator/sender opt-ins where available.

Android/OEM background-start policy can still refuse an automatic foreground
launch. Opt-ins do not bypass that policy; the system installer's Open button
(where provided) or the app icon remains the fallback. Installing this build from
2.0.103 still starts with the old version's broadcast callback; the decisive test
of the new handoff requires an update initiated from 2.0.104 to a later testing
version. Automated callback/intent tests do not prove real package-replacement
or Android 15/16 BAL behavior. Main/customer release remains 2.0.101.

Validation: internal-test and Android-instrumentation Kotlin compilation passed;
37 targeted JVM/Robolectric tests passed with zero failures/errors/skips. Tests
cover explicit activity callbacks, per-session tokens, mutable result delivery on
API 34, foreground/legacy launch intent construction, no CLEAR_TASK, confirmation,
success/failure handling, mandatory gate retention, activation and persistent live
output regressions. Final log: `/tmp/opencode/update-relaunch-final-validation.log`.
Testing uses the existing Publish Testing APK workflow. Install 2.0.104-test first,
then publish a later version-only testing update to exercise the new callback.

## Mandatory-update device exercise in 2.0.103 / 419

This testing release changes only the version fields and documentation, keeping
the 2.0.102 implementation intact. Install and leave 2.0.102-test running before
2.0.103 is published to staging-testing. Its next foreground poll should require
the update, dispose playback/navigation, and prevent Back or installer cancellation
from bypassing the gate. Completing installation should reopen 2.0.103 without
another requirement for that same version. Cold launch is an alternative check.
Customer/main remains 2.0.101; no customer publication is part of this exercise.

## Mandatory update gate in 2.0.102 / 418

Development stays on `iptv-core-replacement`; this change is not a customer
publication. `main` and the published customer 2.0.101 remain unchanged.

An app-wide RequiredUpdateController checks the existing release channel before
mounting navigation on launch, on foreground return (30-second debounce), and
every five minutes while the activity is started. Checks have a dedicated
12-second network ceiling. The testing package continues to use staging-testing;
customer sideload builds use the latest non-prerelease customer release. This
is polling, not a push-notification service; detection while continuously open
normally occurs within the next poll plus network time. Unauthenticated GitHub
API throttling/network outages may delay discovery.

Once a valid newer APK release is known, its requirement is persisted independently
of ignored tags and transient download/installer status. A root gate removes app
navigation and player compositions, intercepts Back, and offers only update,
permission and retry actions. It sits outside maintenance so an active maintenance
gate cannot prevent installing a required update. Android installation approval
is still required. Cancelling/failing installation or resetting the old update
status never unlocks navigation; only the actually installed version can satisfy
the known requirement. Downloaded APKs are checked for valid package metadata,
package identity, required version and signature conflict before the existing
installer is invoked. Failed installer launches now report a retryable error.

A failed initial check without a known newer version allows normal use rather
than treating an outage as an update. Known requirements survive subsequent
failures, older remote responses and process restarts. Downloads survive navigation
and configuration changes. They do not change login/profile/guide data. Existing
live-engine and Back actions outside the gate are untouched.

Important rollout limitation: this behavior only exists after a customer installs
the first build containing it. Already installed 2.0.101 cannot retroactively be
made mandatory by this client change. Device verification of permission approval,
installer cancel/retry, restart retention and an incoming newer release is still
required; automated tests do not execute Android's real package installer.

Validation: internal-test and Android-instrumentation Kotlin compilation passed,
with 113 targeted JVM/Robolectric tests and zero failures/errors/skips. Tests cover
startup/in-session detection, persisted requirements, cancellation/retry policy,
actual installed-version checks, ignored tags, foreground debounce, playback
composition disposal, Back interception, activation, maintenance and existing
live/source-preference regressions. Log:
`/tmp/opencode/required-update-final-validation.log`.

## Source discovery reuse in 2.0.101 / 417

The user confirmed 2.0.100 fixed preview/fullscreen transitions, then reported
movie/show Play and Sources taking up to 45 seconds. VOD verification could
re-download a complete catalogue before Cloud discovery, even after Home had
just refreshed it. Pressing Play/Sources also cancelled details-page prefetch
and restarted its outstanding VOD/addon requests.

Complete, structurally valid provider catalogue generations verified in this
process are reusable for five minutes, scoped by profile/configuration/provider
and exact generation timestamp. Arbitrary disk-cache timestamps do not count as
verification. Cached show identities can fetch their small exact episode tree
before another full catalogue download; missing episodes still require the full
verification path, including episode files in the VOD catalogue. Failed checks
remain unverified and do not become confirmed misses.

Shared lookups transfer an outstanding VOD/addon request from prefetch to the
foreground with a 500 ms cancellation handover grace. Unobserved work is cancelled
after that grace; failures are not retained. Keys include source identity and
request metadata. VOD scope and Cloud entitlement/profile checks still protect
returned results. Cloud discovery remains behind VOD verification, both source
families remain visible to authorized users, and automatic link prewarm retains
the existing library-first restriction. No Cloud playback URL resolution is added.

This reduces redundant network work, not provider response times. A genuinely
cold/expired catalogue or slow Cloud provider can still take longer; request
timeouts are not shortened to claim a speedup. Actual device lookup timings
remain to be confirmed. Live playback, original Back handling, activation and
the signed testing-publication workflow are unchanged.

Validation: internal-test and Android-instrumentation Kotlin compilation passed.
The final targeted JVM/Robolectric run passed 276 tests, zero failures/errors/skips,
including Home-refresh catalogue reuse, exact-episode fast lookup, shared-request
handover/cancellation, Cloud revocation/coexistence, autoplay preference, guide,
maintenance, live presentation and activation regressions. Logs are
`/tmp/opencode/source-lookup-final-validation.log` and
`/tmp/opencode/source-lookup-final-tests.log`.

## One persistent video output in 2.0.100 / 416

The user reports both transition directions still freeze/rebuffer with the prior
ready-view fix. That fix retained views but still switched the decoder between
two output surfaces. The Live TV route now creates one `PersistentLiveVideoLayer`
outside both guide and fullscreen-control subtrees. Guide preview stages report
their window bounds and render logical slots/editorial controls, not a second
PlayerView. The persistent view occupies that slot or the full Live TV root by
changing its bounds; it is not reparented, keyed by fullscreen, or placed inside
the fullscreen fade/scale layer. Badges and channel-name decorations follow the
same view. The full control overlay no longer paints an opaque background over it.

The binding effect no longer depends on fullscreen state. Presentation changes
do not call switchTargetView, change the video target, re-resolve URLs, stop or
prepare the stream. Only a genuine engine-family change or explicit upstream
surface-recovery token may require a new binding/view. The initial recovery key
uses the channel's engine family rather than the later preparation flag, avoiding
an unnecessary first-tune view replacement. Retained guide/focus/time behavior
and the existing Back handlers remain; hold-Back fullscreen is not restored.

Tests resize through 20 presentation changes with one PlayerView, one holder,
one modeled output binding and no view release/detach. The guide slot is tested
not to create another view. Explicit recovery remains able to replace a view;
geometry tests cover outer navigation offsets, portrait fullscreen and initially
unknown bounds. Robolectric has no native decoder surface, so modeled bindings
use holder identity rather than claiming native frame continuity. Actual device
transition timing remains to be verified. The fast-engine, activation and backend
code and the publishing workflow are unchanged.
Internal-test and Android-instrumentation Kotlin compilation passed, with 420
targeted JVM/Robolectric tests and zero failures/errors/skips before publication.

## Ready preview return and retired TV-prefetch removal in 2.0.099 / 415

The user confirms 2.0.098 fixed the Back crash and the fast engine works well,
but reports a long frozen preview after returning from fullscreen. The visited
guide and mini PlayerView are now retained rather than disposed on fullscreen
entry and reconstructed on Back. While hidden, guide controls, programme strips,
scroll containers and preview focus are disabled; menus close, accessibility is
hidden, pointer/key input is blocked, and channel-name marquees stop. Direct
fullscreen entry still defers the guide until first shown, avoiding extra initial
startup work. On return the channel scroll window survives and the time cursor
returns to current programming, matching the former fresh-guide time anchor.

Player binding waits for a real destination surface. A lifecycle observer wakes
the binding effect when surface availability changes without changing the
PlayerView reference. The outgoing fullscreen layer remains mounted until the
preview owns the output, even if the cosmetic transition has finished; an
invisible retained layer cannot take focus or touch input. No stop, prepare,
source-resolution, engine change or watchdog retune is added to presentation.
These changes remove the identified UI/surface teardown and gap; actual TV
transition latency is not measured locally and still needs device confirmation.

The separately reported `LazyLayoutPrefetchState.schedulePrefetch-0kLqBqw(IJ)`
NoSuchMethodError is addressed by removing the retired `tv-foundation` alpha
widgets. Remaining TV rows/columns/grids in source selection, details, collections,
person modals and skeletons now use aligned Compose Foundation implementations.
Import aliases retain existing TV layout call names; there are no legacy widget
classes or data/catalog/scraper changes. Existing D-pad focus and scroll helpers,
item keys, spacing and callbacks remain. The unused TV Foundation opt-in is removed.
The variant Compose ABI gate now also rejects any reintroduced `androidx.tv:
tv-foundation*` artifact on compile/runtime classpaths instead of checking only
Compose module version equality. Scrolling gesture tests exercise prefetch, and
a classpath regression verifies the obsolete TV lazy class is absent.

The fast engine, activation fix, guide Left/Right contract and restored Back
actions are unchanged; hold-Back fullscreen is not reintroduced. Native runtime
instrumentation remains compile-only here, not an executed decoder test. Existing
testing publication workflow is unchanged and both version fields advance.
Final checks compiled app and Android-instrumentation Kotlin and passed 418
targeted JVM/Robolectric tests with zero failures/errors/skips. The fast-engine,
surface-adapter, activation and publishing-workflow files are unchanged from
2.0.098. No claim of measured zero-pause video output on physical TVs is made.

## Back-button-only rollback in 2.0.098 / 414

The user reports the crash persisted and explicitly requests reverting **only**
Back behavior while retaining the now-fast playback engine. Removed the held-Back
preview-to-fullscreen shortcut, its custom short/long key tracking, and the added
fullscreen Back-release guard. The pre-2.0.096 BackHandler/key branches are restored;
Back has no custom hold-to-fullscreen action. The existing Watch fullscreen action
remains available.

Fast OwnTV live-first playback, mpv fallback, surface ownership code, activation
fix, channel marquee and future-to-current EPG navigation are unchanged from
2.0.097. No engine rollback, tuning changes or additional crash experiment is
included. Regression coverage retains the original short Back from EPG to the
same channel without starting/changing playback. The earlier native crash is not
claimed as physically confirmed resolved by this behavioral rollback.
Internal-test/instrumentation Kotlin compilation and 408 targeted unit tests
passed with zero failures/errors/skips. The fast-engine, surface adapter,
activation and EPG-navigation source files were checked unchanged against 2.0.097.

## Single-action activation and surface ownership in 2.0.097 / 413

Activation keyboard Done now invokes the same submit action as Activate rather
than only hiding the keyboard and focusing the button. Submission hides the IME
and clears input focus after the gesture completes. GateButton no longer adds a
second explicit focus target ahead of clickable's own focus/key handling. The
existing synchronous ViewModel submitting guard remains, so queued IME/click
actions cannot issue duplicate activation requests. Cancellation is rethrown
rather than shown as a sign-in error. Pointer, IME and D-pad tests cover the actual
state-hoisted gate content; ViewModel tests cover a successful one-submit fresh
profile flow, profile-before-playlist ordering, validation and duplicate actions.
No activation code or credentials are logged.

The reported fullscreen-to-guide SIGSEGV trace contains only an ART trampoline
frame, so it does not identify the faulting native library. Code inspection found
unsafe surface paths in the 2.0.096 adapter: engine choice followed an asynchronous
initially-false flow, and lifecycle callbacks could reach both engines or a view
that had already been replaced. Surface ownership now starts at NONE, selects
fast live synchronously, and switches to mpv only for an actual archive/fallback.
An inactive mpv core is never attached/resized/detached by fast-live view events.
The real upstream controller notifies the adapter when it actually starts an
engine; a flow's default value is no longer a native surface-routing decision.

`OwnTvSurfaceHandoff` distinguishes the requested view from the surface actually
bound. A ready replacement binds first; old created/changed/destroyed/release
callbacks cannot reclaim or clear it. While a replacement is not ready, the
outgoing observer stays registered so its real destroy callback can detach the
still-bound decoder before the framework frees the surface. Only the active
engine receives native calls; route release clears ownership before late events.
Reset tokens/aspect/error/play signals are scoped to the actual engine. Explicit
new playback and stop discard stale background restores. No fast-playback rollback,
buffer reduction, guide reset or server-side maintenance change is required.

Fullscreen Back key-down now guards its repeat/release across the logical return
to the guide, preventing that same physical press from navigating again during
the view handoff. Hold-Back promotion and normal short-Back behavior remain.
Privacy-safe breadcrumbs record engine/presentation/surface operations and submit
start/completion, without URLs, codes, account identifiers or provider credentials.

Tests model repeated preview/fullscreen/preview handoffs, queued old-view callbacks,
an unready replacement, actual engine changes, resize ownership and disposal, plus
actual Compose Back down/up with an enabled parent BackHandler. These demonstrate
the repaired ownership/event invariants, not reproduction or confirmation of the
user's native decoder crash. Device fullscreen-return testing is still necessary;
the supplied ART-only report cannot establish a confirmed native root cause.
Internal-test and Android-instrumentation Kotlin compilation passed, together
with 416 targeted JVM/Robolectric tests, zero failures/errors/skips, before the
signed testing build was requested. No live activation credentials or native
device playback were used in the automated tests.

## Live boundary, fast live engine and held Back in 2.0.096 / 412

Left within future EPG listings now steps through prior programmes until the
current programme is selected. Only the next Left returns to channel names; a
further Left reaches categories. Empty/gapped rows clamp at the current clock
instead of drifting into history. A cursor later within the currently airing
programme is still treated as the current programme, including after moving to
another row. Explicit Rewind history and short Back remain available. Compose
key-input tests verify the intermediate programme selections, not only focus.

The earlier tuning changes removed host-side waits, but every non-DRM live stream
still went straight to mpv's full demuxer. OwnTV documents a 3–5 second HLS/unknown
container startup floor there. Normal live playback now starts on the actual
OwnTV `LivePreviewEngine` (Media3) using upstream `LiveTuneController` and
`EnginePair`. Upstream owns opening, response sniffing, retries, failure watches,
single-session handling, decoder-release waits and automatic mpv fallback. The
existing mpv probe is not shortened or patched. Catch-up continues through mpv;
DRM, including PlayReady, continues through ARVIO's existing Media3 configuration.

The adapter supplies already-authorized URLs to the first tune without another
Stalker resolution, preserves headers/UA/HLS declarations, and scopes reconnect
callbacks to that request. Per-tune entities are in memory only, not a second
catalog import. The private Koin graph now retains the process-long repositories
and mpv player while constructing a route-owned fast live engine. The mini player
and fullscreen share the active engine; promoting to fullscreen does not retune.
The active engine controls play/buffer/error/aspect state, and both engines detach
surfaces by identity. Native surface reset/generation tokens recreate the actual
PlayerView, including for subsequent UHD tunes. Disposal stops both engines and
clears their background restores; stale tunes and delayed archives are cancelled.

On TV, holding Back for about 600 ms while a preview channel exists promotes that
**playing** stream to fullscreen from categories, channel rows, EPG or the hidden
navigation menu. It does not select the currently browsed row or programme. The
held press consumes repeat events and its release even after fullscreen changes
the shortcut's enabled state, preventing an immediate bounce back out. Short Back
keeps its prior zone-specific action; search/variant modals and touch/system Back
keep their existing handlers. Pending holds cancel on route disposal or app stop.

Tests cover the real upstream tune controller against fake engine ports (fast
first, no duplicate resolution, headers, mpv fallback, superseded requests and
archive handover), actual Compose Back key holds/releases in all browse zones,
surface recreation and current-programme boundaries. The production Koin runtime
probe was moved to Android instrumentation because its real native SQLite and
WorkManager background work cannot run correctly in a plain JVM Application.
Instrumentation Kotlin is compiled here; device probes and authenticated-stream
startup timing are not claimed as executed. First-frame speed still depends on
the provider, segments/keyframes, network and device decoder.
Final validation compiled internal-test and Android-instrumentation Kotlin, and
passed 387 targeted JVM/Robolectric tests with zero failures/errors/skips. The
internal-test runtime dependency check resolves Media3 to 1.11.1, matching OwnTV
Player Core, rather than loading an older host-declared Media3 runtime.

## Directional guide columns and pending repairs in 2.0.095 / 411

Left from any EPG programme position now returns immediately to that same
channel's name column, rather than traversing historical programmes until the
far-left time-window boundary. Another Left reaches categories; Right returns to
the remembered channel and then the EPG. Horizontal column navigation does not
start playback, change the selected category or require Back/OK. Up/Down preserve
the EPG time cursor across rows as before; Right still advances through future
programmes. Explicit Media Rewind moves to previous/historical programmes, and
Media Fast Forward advances them. Back remains a supported alternative, not a
requirement. Touch programme selection and catch-up actions remain unchanged.

Real Compose key-input regressions exercise round-trips through the actual
category panel, channel labels and programme strips with no Back key; future and
empty-guide exits; row restoration after vertical movement; and selecting an
earlier programme using explicit Rewind. Test tags identify the actual focus
targets. This validates focus behavior in fixtures, not a physical remote session.

This user-requested testing build also includes the previously unpublished
dashboard service intro repair and long-channel-name/startup improvements:
`dashboard-service-intros.md` and `live-channel-names-startup.md`. Both version
fields advance for this testing build, and the existing publishing workflow is
unchanged. No maintenance event or server EPG rebuild is triggered by publishing.
Internal-test Kotlin compilation and 373 targeted JVM/Robolectric tests passed,
with zero failures/errors/skips, before requesting the signed testing publication.

## Expired-cache recovery and protected EPG controls in 2.0.094 / 410

Inspection of the public cache on 2026-10-20 at about 21:13 UTC found 4,075
programme channels but only 58 with current/six-hour listings. AAJ TAK SD, ATN
Aastha SD and CA - APTN had cached programmes ending at 00:00 UTC that day. A
matching artifact checksum proves download integrity, not current guide coverage.

The client now measures current versus expired **matched** channels. When at least
eight known channels have expired and outnumber current ones, it retains available
server listings but performs a bulk provider/XMLTV recovery for uncovered rows.
A healthy partial cache with unpublished/missing channels still skips the slow
per-channel sweep. Failed recovery retains saved listings and cannot claim a fresh
server-only completion. All completion-marker consumers now use v3, forcing one
non-destructive coverage check after upgrade instead of honoring v2's partial
completion for another six hours. Tests reproduce one current and 23 expired
Xtream channels and verify one bulk XMLTV request with zero short-EPG probes.

The PHP admin website adds authenticated EPG status and rebuild controls. Its
server-to-server bridge calls new protected worker endpoints using a shared secret
stored only in website config.php and worker environment, never JavaScript/APK.
The worker reads actual child-process progress, deduplicates active jobs, pulls
automatically every six hours, honours XMLTV timezone offsets, validates coverage
before publication and atomically replaces the gzip only after success. A failed
or mostly expired upstream pull keeps the prior artifact and reports an error.
No live rebuild was triggered during tests. The new controls require installation
of the worker update and matching private connection configuration on both servers.
See `epg-worker/ADMIN-CONTROL-README.txt` for SSH and aaPanel deployment instructions.
The website update ZIP intentionally omits config.php and private state so accounts,
maintenance mode, setup keys and existing settings survive extraction.

## Server-first completion and maintenance integration in 2.0.093 / 409

A successful shared-server guide import now completes the shared refresh
without probing every unlisted Xtream channel using short-EPG requests. The
previous "Checking remaining channel
listings" phase could make hundreds/thousands of network requests for channels
whose guide is not published; it is no longer run after a successful cache import.
The same six-hour freshness checkpoint, source guards, last-good retention and
saved coverage remain in place. Separately configured XMLTV sources not represented
in the shared cache still import normally. Provider/XMLTV fallback remains available
when no usable shared cache is available. Unpublished programmes are not fabricated.

`MaintenanceRepository` polls `https://hubmanager.extremeiptv.net/api/maintenance`
at launch, on activity re-entry and every 10 seconds while STARTED. A separate
four-second-timeout client keeps this independent of login, provider/guide work
and playback. `MaintenanceGate` replaces the entire app navigation subtree, not
just an overlay; existing player disposal releases internal VOD and OwnTV/Media3
live playback. Back cannot dismiss the gate. Auth/profile data are untouched.
An explicit off state remounts the app but does not automatically restart video.
Known-active status persists through process death/outages until the server
explicitly clears it; a service outage before receiving an active flag does not
invent a global lockout. Offline devices cannot receive new commands, older APKs
without this support cannot be blocked, and external third-party players cannot
be closed by this app. Normal connected propagation is about 10–15 seconds.

The standalone `hub-manager/` service provides a protected admin page with two
independently provisioned accounts, salted scrypt password hashes, Secure/HttpOnly
session cookies, login rate limiting, strict-origin admin POSTs, transactional
revision conflict protection and an administrator audit trail. No admin credentials
are shipped in the APK. SQLite preserves the flag across server restarts. The
aaPanel reverse-proxy/systemd setup is documented in `hub-manager/README.md`.
The installer does not enable maintenance or create default admin passwords.
The service must be deployed with HTTPS on the new subdomain before this feature
is live; DNS/site creation alone is not a backend deployment. No live global
maintenance event was enabled for validation. The APK publishing workflow is unchanged.

## Stop automatic IPTV shortcuts in 2.0.092 / 408

Removed the app-generated `g-4k` (4K | Ultra HD) and `adult` shortcuts from
all four category-tree construction paths: raw, enriched, fast startup and paged
startup. Neither shortcut is a user favourite or a provider-created group.
Paged startup also incorrectly used the total count of all hidden groups as its
Adult shortcut count, including hidden sports groups. That shortcut and incorrect
count are removed rather than repurposing hidden groups as Adult content.

Actual provider groups, including any genuinely named Adult or 4K | Ultra HD,
remain under PLAYLIST in provider/configured order. Hidden groups and favourites
are not rewritten. Existing selection validation resets a removed shortcut to
All Channels. Channel classification and adult safety filtering remain unchanged.
Regression tests cover all loading paths, hidden sports/adult groups, empty
favourites, original group ordering and preservation of identically named provider
groups. No guide data, VOD, Cloud entitlement or publishing-workflow changes.

## Authorized Cloud backups alongside VOD in 2.0.091 / 407

This supersedes the VOD-hit early return in 2.0.088. Authorized customers now get
both IPTV VOD and Extreme Cloud choices, even when VOD is found. There is no
permission popup or extra search action. VOD lookup remains first, and progressive
discovery publishes its playable links immediately while Cloud metadata loads.
Every subsequent cache/progress/final response retains those VOD links instead of
replacing them. Non-progressive discovery also returns the merged choices. The
Cloud result cache stores only Cloud metadata, not a stale VOD lookup result.

Details shares its initial VOD lookup with repository discovery, continues into
authorized addon/plugin discovery instead of returning, and retains VOD on Cloud
failure. Authorized users can still receive backup choices during a VOD catalogue
outage; callers without Cloud access retain the original retry/error behavior.
Existing entitlement checks still guard every Cloud request and cached result.
Revocation removes Cloud results without removing VOD. Fresh and cached backup
links remain branded Extreme Cloud with original provider identities intact.

VOD/library sources are preferred for autoplay and source-list ordering, including
when Cloud offers a larger 4K file. Automatic list/focus prewarming is restricted
to local library links whenever such links exist: merely listing or focusing an
unused Cloud backup must not open/resolve it automatically. Explicit user source
selection still opens the chosen Cloud link. Background source population respects
the current playback URL, so it cannot revert a user-selected source to an earlier
provided link. Source menus show ongoing discovery while retaining playable links.

Tests cover movie and exact-episode coexistence, immediate VOD emission before a
slow Cloud lookup, fresh/cached merged results, Cloud/VOD lookup outages, shared
lookups, cancellation, unauthorized callers, revocation, local-source autoplay and
prewarm policy, and phone/TV selection of both choices. The Beekeeper (2024) picker
fixture demonstrates the reported case with both tabs/cards present; it is not a
claim of direct access to the customer's live authenticated catalogue.

[Phone source-picker fixture](screenshots/extreme-cloud-vod-backups-phone.png).
