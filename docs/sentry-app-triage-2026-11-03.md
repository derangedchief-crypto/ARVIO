# Sentry app triage — 2026-11-03

Reviewed the nine unresolved Android issues active in the last 14 days in
`extreme-us/android`. These changes are not a customer APK release. Leave issues
open until a deployed build and follow-up telemetry establish resolution.

## Verified defects

- **ANDROID-1D:** Android 9 rejected Supabase's
  `2026-10-29T03:44:57.690579+00:00` with `Instant.parse`, substituting epoch zero
  during watch-history conflict comparison. Use `OffsetDateTime` for ISO offset
  timestamps (including Z), retaining milliseconds and the existing invalid-date
  fallback. Regression covers the event value and equivalent nonzero offsets.
- **ANDROID-1F:** Cloud IPTV import passed a backup directly to primary-source
  protection. A missing, disabled or differently identified primary could reject
  the entire import. Reconcile against the locally saved primary inside the
  settings transaction, retain its credentials/ID and enabled state, and remap
  imported source references. A different playlist occupying the same ID is an
  extra with a distinct ID, still subject to existing extra-playlist authorization.
  Cloud import does not change the activation binding. Tests cover empty/legacy
  backups, renamed/disabled primaries, source ID collisions, favourites/recents,
  persistence and licence denial without saved-source mutation.

## Freeze reports — not proven resolved

- **ANDROID-1E:** Background Compose recomposition includes
  `PrimaryActivationGate.collectAsState`; a GC thread is also active. Changed the
  activation and renewal UI subscriptions to lifecycle-aware collection, avoiding
  continued state-driven UI updates while stopped. Playback enforcement remains
  in the access repository. This is a scoped mitigation, not proof of ANR cause.
- **ANDROID-1C / ANDROID-F:** Deep Compose text/layout/subcomposition snapshots.
  They do not identify the triggering screen workload or its duration.
- **ANDROID-1A:** Background Compose lazy-layout/change-list work in an older
  runtime snapshot. No verified first-party blocking operation in the snapshot.
- **ANDROID-1B:** Main thread is in native message-queue polling/ART wait, with a
  concurrent GC thread; no demonstrated application lock owner.
- **ANDROID-19:** Main thread blocked in Android's keyboard-show Binder call on
  2.0.126-beta. Removing keyboard functionality is not a justified fix.
- **ANDROID-18:** Details ViewModel construction sampled at `MutableStateFlow`.
  Current initial state construction does not show synchronous I/O. The sampled
  allocation alone does not identify a root cause.

Follow-up: reproduce navigation, Live TV category changes and background/resume on
the affected low-memory onn/Fire TV devices; collect frame/CPU/heap traces around
the stall. No physical device is attached to this session.

## Verification

Build Check now runs the focused watch-history, cloud-merge and protected-source
persistence regressions after compiling the debug APK. Verification uses GitHub
Actions rather than restarting local Android builds. Check the workflow result
before treating these changes as validated. No signing/package/version changes
or customer publication are part of this patch.
