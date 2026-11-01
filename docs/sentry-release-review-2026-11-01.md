# Sentry review for the 2.0.131 release candidate

Reviewed extreme-us/android before promotion. Initial query: all unresolved
issues active in the last 30 days (7 groups), plus every issue first seen in the
last 7 days regardless of status (13 groups; 3 overlap). Sentry Seer returned
HTTP 402/no Autofix budget; review was performed manually from event details,
stack traces and current source. No subscription/budget setting was changed.

## Findings and dispositions

| Issue(s) | Evidence | Source action / boundary |
| --- | --- | --- |
| ANDROID-18 | 2.0.126 details navigation enters ViewModel construction on the main thread | Defer streaming/scraper dependency construction; resolve the access flow on IO. A behavioral test asserts the provider is not called by navigation construction and is created off that thread. The allocation stack alone does not prove the entire ANR cause. |
| ANDROID-19 | 2.0.126 main thread waiting in Android 14 IME Binder show | Coalesce repeated editor/Compose requests, reject detached/background/unfocused callbacks, remove native SHOW_FORCED second calls. This reduces app-generated pressure but cannot guarantee an OEM Binder service never stalls. |
| ANDROID-17 (already marked resolved) | 2.0.126 Application.onCreate synchronously initializes Sentry | Move diagnostics SDK initialization to IO after installing the local crash handler. Move settings SDK toggles to IO; beforeSend still enforces the stored privacy choice. Do not suppress ANRs to make the issue disappear. |
| ANDROID-E | 2.0.023 missing public suffix resource | Narrow custom-DNS fallback for the exact resource failure; do not swallow unrelated exceptions or alter TLS. Customer and brand APK validators require a nonempty PSL resource. Support both OkHttp resource layouts; current 2.0.130 testing APK already includes the Android asset. |
| ANDROID-6, ANDROID-B | Older synchronous EPG count/read on the TV main path | Current source already replaced that start path with background refresh requests. Add a regression guard, retain existing guide behaviour. |
| ANDROID-1A | Newly reported event actually from 2.0.023, sampled in lazy home-card composition | Current cards use remembered, pixel-bounded image/logo requests. Add regression guards; do not claim an old single-sample composition stack proves a new current-version root cause. |
| ANDROID-C | 2.0.010 main thread sampled idle in nativePollOnce | No app-owned blocking frame captured. No speculative patch or telemetry suppression; keep issue unresolved for better evidence. |
| ANDROID-16, ANDROID-15, ANDROID-11, ANDROID-10, ANDROID-Z | Old installer session creation/copy/commit on main | Current installer launch is suspend/withContext(IO), with only activity launch on Main. Preserve and guard the existing fix. |
| ANDROID-13 | Old required-update progress sample in string building | Existing updater responsiveness changes are retained. No evidence warrants replacing the updater or changing update identity. |
| ANDROID-12 | 2.0.123 optional codec class lookup in renderer construction | Existing HardwareFirstRenderersFactory disables generic extension probing and adds only known bundled fallbacks. Retain existing renderer safeguards. |
| ANDROID-14 | 2.0.123 handled VOD catalogue verification failure | Preserve existing verified-catalogue/fallback guards and tests; never skip paid-account verification or interpret network failure as catalogue absence. |
| ANDROID-Y | Older 2.0.101 ANR without actionable app-owned frame | Reviewed; no unsupported claim of a new causal fix. |

These are fixes and evidence-backed mitigations, not a guarantee that every ANR
group is solved. Do not auto-resolve old native/idle reports or promise all events
will stop. Monitor the new version after rollout and collect better diagnostics
if a group recurs.

## Release boundaries

Recovery fixes were merged into staging via PR #13 before this work, as directed.
Sentry changes are developed directly on staging/testing. Candidate identity is
2.0.131/code447, advancing past existing Hub 1 2.0.130 testing artifacts without
reusing their version. Standalone package/signing/feed identity remains unchanged;
branded production fanout retains each published brand snapshot and identity.

The requested scheduled time is 2026-11-01 03:30 America/Toronto (EST, UTC-05),
which is 08:30 UTC. This is a workflow-start target, not instantaneous APK
availability. Publication must stay conditional on completed validation, exact
approved main source, compatible live website, correct signing/cloud inputs and
absence of an already-published version. No publication or recurring annual cron
is authorized merely by writing this review. Use a one-time guarded dispatch.
