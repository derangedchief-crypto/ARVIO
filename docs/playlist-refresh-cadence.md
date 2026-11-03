# Playlist refresh cadence — staging, unpublished

Every MainActivity foreground start requests a fresh TV/movie/series catalogue
check, including an immediate return to a retained activity. Previously a
15-minute launch debounce skipped those returns. Ordinary Home/TV tab requests
still reuse an already checked profile/source, and simultaneous requests share
the repository's in-flight job.

A single process-owned scheduler starts on the first foreground start and requests
another check every five minutes. It survives activity stops and recreation; a
slow in-flight request is reused, not queued repeatedly. Android process death,
sleep and network availability can defer checks. This is not an alarm, wake lock,
or promise of five-minute execution while force-stopped.

The existing provider refresh updates TV snapshots and the movie/series disk and
memory catalogues. It invalidates movie resolution caches and series episode
lookups; successful movie refresh now also clears negative movie lookup results
even if the series endpoint fails. Failed/malformed VOD responses retain the
last good catalogue; valid empty arrays remove entries. Profile/source checks
discard stale in-flight results. No per-series episode crawl is performed.

The user confirmed the global change was made in the provider IPTV panel. This
refreshes that provider's catalogues. The customer's installed version and actual
check-ins have not been inspected, so the individual incident's cause remains
unconfirmed. No APK or update feed is published by this change.

Focused verification: compilation and all 13 tests passed.

```sh
./gradlew :app:testSideloadDebugUnitTest \
  --tests '*LaunchContentRefreshIntegrationTest' \
  --tests '*PlaylistRefreshSchedulerTest' \
  --tests '*GuideRefreshCoordinatorTest'
```

Regression coverage includes rapid reopen with changed provider IDs, tab request
deduplication, source reuse, failed/empty/malformed catalogues, profile switching,
five-minute virtual-time cadence, duplicate scheduler starts, and slow-request
coalescing.
