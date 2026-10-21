# Live channel names and startup — included in 2.0.095

Originally saved unpublished, like the dashboard intro repair. The user later
requested a testing build after fixing guide column navigation. These changes
are included in 2.0.095 / 411; publishing workflows remain unchanged.

## Channel names

The OwnTV guide channel label and legacy channel row now marquee long names when
focused or actively playing. They wait 1.2 seconds, scroll at 28 dp/second, and
repeat while selected. Short names do not move; inactive rows retain ellipsis and
do not all animate. This preserves row dimensions, typography and remote focus.
The Text semantics retain the entire channel name for accessibility.

## Startup

- Removed the fixed 250 ms wait from first tunes and normal deliberate selections.
  Requests within 650 ms of another request still debounce 250 ms, preserving
  cancellation/coalescing during held-key zapping and limiting codec churn.
- Non-DRM OwnTV tunes skip the redundant format/redirect preflight entirely: the
  native engine follows redirects and detects the container itself. Stalker link
  resolution, signed catch-up URL generation, headers and forced reconnect URL
  refreshes still run normally. This avoids opening an extra stream connection
  ahead of playback on providers with restrictive connection limits.
- Optional DRM/Media3 redirect preflight for extensionless slug URLs now uses asynchronous,
  cancellable OkHttp calls with a **single 1.5-second budget across HEAD and GET**.
  Previously each blocking call could spend five seconds before native playback.
  A timed-out probe hands the original URL to the player; inconclusive/error
  responses are not cached as authoritative targets. Explicit recovery retains a
  five-second total budget. Known TS, numeric Xtream and HLS URLs still bypass
  this optional preflight, as they did before.
- Cancelling an obsolete tune closes its outstanding preflight call. The screen
  rethrows cancellation rather than displaying a stale-channel error.
- OwnTV live playback explicitly disables an inherited pre-roll override. Its
  normal cache depth, network recovery, probes, decoding and catch-up behavior
  remain upstream-owned. No raw native mpv option injection is used.
- The DRM Media3 path starts with 750 ms rather than 1,500 ms of buffered media.
  Steady-state buffers remain 20–45 seconds, 48/64 MiB heap targets remain, and
  recovery thresholds remain 3.5/4 seconds. No VOD/movie buffers are changed.

## Limits and validation

This does not promise a particular device/provider first-frame time. In particular,
OwnTV's full HLS/extensionless probe is intentionally preserved: upstream documents
that shortening it can wedge HLS playback, and that it can impose a 3–5 second
startup floor. Provider response time, segment/keyframe availability and decoder
startup still apply. We have not measured the user's authenticated TV streams.

The follow-up 2.0.096 starts normal live streams on OwnTV's fast live engine,
with the full-probe mpv engine as the compatibility fallback instead of the
mandatory first step. See `owntv-iptv-integration.md` for routing, handover and
surface-lifetime details. Catch-up/DRM paths are kept distinct.

Native Compose image tests verify that long selected text moves, inactive long
names stay still, and short selected names stay still. Policy tests verify debounce
and preserved recovery/heap budgets. Resolver tests verify deadline cancellation,
no cache poisoning, slower explicit recovery, redirect/HLS detection and caller
cancellation. Compile and broader TV, buffering, guide, startup, maintenance and
dashboard intro regressions were validated before requesting the testing build.
