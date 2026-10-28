# Sentry review — last 48 hours, 2026-10-28

## Scope and restrictions

Read-only review of `extreme-us/android`, across all environments and releases.
Sentry labels the customer `.beta` build's environment `staging`; do not mistake
that label for the generic testing app. Search: `lastSeen:-48h`, plus a 48-hour
error-event aggregation. Eleven error events across ten groups; one additional
resolved metric alert (`ANDROID-4`) is not an application exception.

All code changes are staging-only. No main promotion, APK publication, issue
resolution/assignment, alert changes, service deployment or customer data edits.
Do not use automatic-closing commit keywords to mark these production issues fixed.

## Findings and candidate fixes

| Issues | Events in 48h | Finding | Staging treatment |
| --- | ---: | --- | --- |
| ANDROID-Z, ANDROID-10, ANDROID-11, ANDROID-15, ANDROID-16 | 6 | Main thread inside APK createSession/copy/fsync/commit | Installer is internally IO-dispatched; all callers adjusted. Session handles close, unsuccessful sessions abandon, cancellation propagates, accepted commits never open a second installer on close failure. Relaunch callback unchanged. |
| ANDROID-13 | 1 | ANR during update-gate recomposition/StringBuilder | Downloader emitted progress for each 8KB (~16,000 updates per APK). Throttle to 4Hz, retain initial/final progress, remember static version text. This reduces a concrete hot path; the single stack snapshot is not proof of the entire ANR cause. |
| ANDROID-12 | 1 | Player startup sampled inside optional renderer Class.forName/ClassNotFoundException | Probe only bundled FFmpeg renderers, cache their constructors once, retain hardware-first ordering and software fallback. Do not change Media3 versions or mpv. Still requires affected-device timing validation. |
| ANDROID-14 | 1 | Handled VodVerificationException, not an unhandled process crash | Preserve fail-closed verification. A malformed unrelated catalogue row no longer hides a separately verified exact episode. Incomplete catalogues still cannot prove absence or authorize fallback. Actual report could also be provider outage/timeout; that cause is not established. |
| ANDROID-2 | 1 | Fire TV Android 9 native SIGSEGV in WebPRescalerImport | Guard Coil against entering the legacy Amazon native WebP decoder; affected WebP images may use placeholders. Other platforms and image formats unchanged. Native root cause and device coverage are unproven; a Java try/catch cannot catch SIGSEGV. |
| ANDROID-D | 1 | Background ANR without a stack trace, updater activity present | Insufficient evidence for a definitive fix. Installer work is hardened, but do not claim this group resolved. |

Known release tags: three events explicitly on customer 117, three on customer
123, five without a release tag. This does not establish that the latest release
is unaffected, or that all events describe current code.

## Validation

The first run compiled but reported 6 failures in the newly added renderer/WebP
Robolectric fixtures because their Conscrypt mode did not match this repository's
native-free test harness. Those fixtures now explicitly disable Conscrypt, as the
existing Android tests do, and use an explicit non-Hilt test application. This is
not evidence that the native Fire TV crash was reproduced or fixed. Final validation,
including the subsequently requested post-update source restoration work: **1,007
tests, zero failures/errors, one existing skip**, with both testing/customer Kotlin
variants compiled. Log: `/tmp/opencode/sentry-vod-staging-final-validation.log`.
Added regression
coverage exercises installer worker-thread execution, session cleanup/cancellation,
download completion/throttling, truncated responses, hardware-first FFmpeg fallback,
WebP header rejection without consuming the source, and VOD positive/negative gates.

No physical Android device or native Fire TV reproduction has been performed.
ANR traces are samples, not complete timings. Do not claim all reports eliminated.

## Source comparison

At task start main was `dadbeb064eb7ac7b8a994aaa3f0d31a33ec947d4` and staging was
`6dd64f5`. Staging retains temporary artifact-only customer workflow differences;
do not merge them into production as part of a later app-fix promotion. Version
bump and signed testing APK require the user's separate request/approval.

Sentry dashboard:
https://extreme-us.sentry.io/issues/?project=android&query=lastSeen%3A-48h
