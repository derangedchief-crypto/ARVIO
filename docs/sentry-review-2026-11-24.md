# Sentry review — 2026-11-24

Scope: Extreme Hub customer Android app, `staging/testing`.

## Changes

- ANDROID-20: channel lookup for catch-up history was waiting for SQLite on the
  UI thread. Make the lookup suspending and execute both database access and
  large-list scans on IO; retain refresh throttling/state updates on main.
- ANDROID-2G / ANDROID-2D: move the installed-addons and catalogue observation
  pipelines (including JSON parsing and catalogue equality) onto Default rather
  than inheriting the UI collector's dispatcher. These were directly visible
  in the reported main-thread ANR stacks.
- ANDROID-1X: handle a missing system image picker on Fire TV and keep the
  profile dialog available with a message directing users to built-in avatars.
- ANDROID-21: replace the close-a-temporary-socket/free-port probe with CIO's
  atomic port-zero bind on loopback. Serialize startup and wait for the resolved
  connector before exposing a stream URL. Start only when Telegram playback
  requests a URL, off main, rather than on repository construction. This also
  avoids keeping an unused server and its resources alive for non-Telegram users.
- Memory-pressure mitigation: forward Android's low/trim-memory callbacks to
  OwnTV's existing native player cache-trimming API while the live adapter is
  alive; unregister on release. Player-core 1.0.68 exposes this API but the
  application adapter previously never called it. This is not a proven fix for
  the thread-allocation crash.

## Still requires evidence / release verification

- ANDROID-2P: newest fatal `pthread_create (1040KB stack) failed`, branded
  2.0.136.1/45200001, 32-bit RockChip device after roughly 13 hours. Surface
  breadcrumbs show FAST_LIVE/MPV transitions immediately beforehand, but the
  failing thread-creation caller is obfuscated and no full thread dump was
  supplied. Matching branded mapping plus a long-running device reproduction
  are needed before identifying a thread leak or claiming resolution.
- ANDROID-2B's socket permission error is not proven to share the port race.
- ANDROID-27 / ANDROID-2A: map-equality and guide-composition ANRs still need
  device profiling; do not replace native surface calls with off-main calls.
- ANDROID-A's inspected event is from 2.0.023-beta/339, well behind current
  staging; its recent ingestion is not evidence of a current-code regression.

Keep these reports open pending delivery and affected-device verification.
Saving source to staging does not update existing customer APKs.
