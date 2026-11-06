# Sentry follow-up — 2026-11-06

Reviewed `extreme-us/android` issues active since November 3, including resolved
and ignored issues. No matching issues were returned for `release:*2.0.134*` in
the seven-day search; this is not proof that the release has no defects.

## ANDROID-1H — update installation ANR

Event `42bae76099934dadaa64f46dcfb175f7` is from branded Crystal Streamz
2.0.117.2 / 43300002 on an onn Android 14 device. Its main thread is copying APK
bytes into PackageInstaller's FileBridge from `ApkInstaller.launchInstall`.
Current staging already wraps the session create/write/fsync/commit path in
`withContext(Dispatchers.IO)` (commit `3c7a4a7`). No new installer fix is needed
for this old-build report. Add the existing `ApkInstallerThreadingTest` to Build
Check so this behavior, cancellation and session cleanup are checked in CI.
The affected branded installation still needs its own authorized signed update;
the generic testing APK is not an update for that package.

## ANDROID-1G — Compose / GC freeze

Event `3fc1ed36b44e40f984ebbbc180794c41` is from 2.0.131-beta / 447.
The main thread is in Compose `drainPendingModificationsLocked`. Worker 73 is
waiting for GC during the episode-candidate title normalization pipeline; worker
86 is waiting for GC while `managementHash` allocates a Formatter per digest byte.

Scoped allocation reductions:
- Normalize the requested title once per episode verification. Reuse each
  provider title's already-normalized text when deriving its canonical tokens,
  instead of running the regex pipeline twice. Keep exact-ID precedence,
  contradictory-ID exclusion and exact-episode verification unchanged.
- Encode SHA-256 with direct lowercase hex digits instead of 32 Formatter calls.
  Compatibility tests verify known vectors, all byte values, UTF-8 and the
  persisted management account scope format. This must not change device identity.

These remove verified redundant allocations in the sampled paths; they do not
prove resolution of the Compose ANR. On-device profiling and post-deployment
telemetry remain necessary. Do not mark the issue resolved from this patch.

## Older-build recurrences

- **ANDROID-A:** All 11 returned events in the seven-day window are on
  2.0.023-beta / 339. The latest crash is Compose's internal detached-coordinate
  focus search on Amazon Android 7.1.2. Sentry currently marks it resolved; no
  evidence here establishes a regression on 2.0.134 or a specific new code fix.
- **ANDROID-T:** Latest event is Android's Binder GC watcher finalizer timeout
  on 2.0.023-beta / 339. No first-party blocking operation is present in that
  finalizer stack. Preserve its existing Sentry status and investigate recurrence
  on maintained builds before proposing a runtime workaround.
- **ANDROID-1C:** Last occurrence remains November 3 at 00:49 UTC, already covered
  by the earlier triage; no newer occurrence in this review.

## Validation and release boundary

GitHub Build Check compiles and runs existing installer threading regressions,
episode catalogue tests (including new normalized/canonical/contradictory-ID
cases), hash compatibility tests and the existing focused checks. No local
Android build, package/signing/version change, website deployment or APK
publication is part of this work. Check the workflow result before claiming
validation success.
