# Weekend Sentry triage: 7–8 November 2026 (UTC)

Reviewed on staging/testing on 9 November. Customer release remains 2.0.134;
these changes require validation and a separately approved publication.

## Confirmed current-release crashes

- ANDROID-1M: three 2.0.134/450 events. The network interceptor used unbounded
  `GZIPInputStream.readBytes()` after bounding only compressed input. The latest
  crash attempted a 64MB array on a 256MB heap. Replaced eager copies with streaming
  Okio gzip decoding, preserving plain JSON with an incorrect gzip header and up
  to three nested layers. Invalid/truncated gzip fails as an IOException. Added
  an 80MB expansion regression that consumes data incrementally, plus nesting,
  malformed input and close-propagation coverage. Downstream callers that request
  entire response strings still have their own allocation costs.
- ANDROID-X: three 2.0.134/450 events on a Formuler Z10 with exhausted storage.
  Both settings and Trakt preference writes failed with ENOSPC. Latest breadcrumbs
  show periodic playback progress immediately before failure; that path allowed
  the local Continue Watching write exception to escape its launch. Added an IO
  boundary around the progress-save job, with a once-per-player storage warning.
  The failed path stops before the subsequent cloud snapshot; a later progress
  tick can retry. No preferences are deleted or replaced with defaults. A real
  DataStore atomic-write regression checks retained on-disk state and recovery.
  Cloud pull already returns FAILED when apply throws; storage exhaustion itself
  still requires freeing space on the device. This is not a claim that all IO
  failures at every preference-writing call site are now handled.

## Freeze reports and older releases

- ANDROID-1K: 2.0.131/447, main waiting in Android rendering/ART. A runnable
  background thread creates a new ManagementApi/OkHttpClient on each extra-playlist
  decision. Reuse one lazily initialized API in the singleton licence service,
  retaining fresh server checks and unchanged authorization rules. This reduces
  churn; the stack sample does not prove it resolves the ANR.
- ANDROID-1B: weekend events on 2.0.131 and 2.0.126. Latest main thread is waiting
  in native event dispatch; APK installation is already on a background thread.
- ANDROID-C: release not recorded, UpdateInstallActivity visible. Main is waiting
  in native input dispatch; a worker is hashing guide data. Insufficient evidence
  to attribute this to one app operation or claim a fix.
- ANDROID-Y: 2.0.134/450 TCL ANR, no stack trace. Needs a trace/reproduction on
  affected hardware; remains unresolved.
- ANDROID-A: all four weekend focus-coordinate crashes are 2.0.023/339, not 134.
  No new current-release focus regression established.
- ANDROID-1J: preference rename failure on 2.0.023/339. Current source defines one
  settings DataStore delegate; no duplicate factory for that file was found.
- ANDROID-4 is an error-count metric alert, not a separate application defect.

## Verification

The GitHub Build Check workflow includes the new network and persistence tests,
existing Sentry regressions, category/menu checks, refresh and management checks.
No Sentry issues are marked resolved merely because staging code was changed.
