# VOD and Extreme Cloud restoration after updates

User report: after an update, movie/series sources sometimes omit VOD or take too
long; clearing app data and signing in again restored both VOD and Extreme Cloud.
Exact affected version and whether waiting/restarting restored it are not yet
confirmed. Do not recommend clearing data or treat reactivation as the repair.

## Confirmed code defects

1. `XtreamEntitlementsGuard` unconditionally removed a previously granted Cloud
   add-on and its tracker BEFORE ensuring the replacement. A failed/timed-out
   manifest fetch therefore destroyed a working local grant.
2. It wrote `last_check_at` before resolution/installation succeeded. A failed
   repair could then be skipped for the 30-minute foreground interval.
3. Its startup credential read accepted the first activation-config emission,
   which could be empty while the saved primary vault was still loading. That
   check silently stopped instead of awaiting the saved account.
4. An open Details Sources request that had already observed Cloud unavailable
   did not automatically retry when the background entitlement check restored it.

## Staging changes

- Ensure/install each granted add-on first. Preserve the existing tracker/add-on
  on fetch failure; retire an old ID only after its replacement is available.
- Existing, deliberately disabled add-ons are still respected by ensureCustomAddons.
- Keep confirmed revocation removal; unknown provider/manifest responses do not
  uninstall a grant. The primary and extra-playlist access guards are unchanged.
- Record successful-check time only after reconciliation completes. Retry an
  inconclusive check up to three times with 30-second gaps, never a tight loop.
- Wait (within the existing timeout) for actual saved activation credentials.
- On Cloud restoration, refresh only an already-open Sources/Play request, retaining
  visible VOD hits and the same item, episode and profile. Idle browsing does not
  trigger source requests. Do not restart an already-playing video.

This does not delete caches, playlists, settings, favourites, activation bindings,
accounts or add-on preferences. No website/worker changes or new APK publication.

## VOD investigation and boundaries

The existing source path still waits for VOD verification before Cloud discovery.
An uncached provider catalogue can genuinely take seconds; that ordering is an
existing tested policy and has NOT been bypassed to hide latency. Positive cached
VOD links are searched before network, and cached Cloud results are merged with
current VOD links rather than replacing them. Cold catalogue misses, provider
outages, malformed rows and false/incomplete package state must be distinguished.

The Sentry work also makes a valid exact episode usable when an unrelated series
row is malformed, without treating an incomplete catalogue as proof of absence.
This is not proof of the user's standalone VOD omission's exact cause. An affected
device's version and pre-reset diagnostics are still needed for that attribution.

## Acceptance

Regression fixtures cover existing grant retention, failed-manifest retry without
30-minute throttling, install-before-remove ordering, cancellation, startup empty
state followed by restored credentials, authoritative revocation and unknown reads.
Source recovery policy tests exclude idle/initial/repeated signals. Existing VOD+
Cloud coexistence, primary revocation and VOD-before-backup tests remain required.
Final validation passed: **1,007 native tests, zero failures/errors, one existing
skip**, plus testing/customer Kotlin variant compilation. Log:
`/tmp/opencode/sentry-vod-staging-final-validation.log`.
No physical update/restart/source-retention test has been performed here.
