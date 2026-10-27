# Client 2.0.119: background verification and Cloud playback guards

Validation: 943 full native tests, zero failures/errors, one existing skip; both
testing/customer Kotlin variants compile. All70 focused primary/Cloud/fallback
checks and79 branding/builder checks pass. Real-device acceptance is still needed
on a separately approved signed testing build.

The activation/recovery screen no longer exposes a primary-verification retry or
sync button. The existing background heartbeat, foreground check and successful
activation refresh remain responsible for verification. The activation-code form
is still available when confirmation is actually needed.

Cloud access now requires BOTH the existing Cloud entitlement and a valid primary
lease. Movie/episode discovery, progressive results, in-memory/persisted source
cache reads, resolved-URL cache reads, prewarming and final stream resolution all
check primary access. Results completing after expiry or a binding change are not
published as playable links. Resolved-URL entries are scoped to the binding.

Primary access denial is a distinct policy error. Player selection, provided URLs
and alternative-source failover do not turn that error into raw-URL playback or
another Cloud attempt. Normal Cloud fallback remains available while primary
access and the customer's Cloud entitlement are valid.

Existing ongoing-player shutdown and bounded 15-minute network-outage grace are
preserved. Known primary expiry/invalidity still ends access immediately; caches,
alternate URLs and installed addons cannot create a new grant. Nothing deletes
saved playlists, installed Cloud addons, favourites or trial history.

This is a client-source update (2.0.119 / 435). It requires no new website/worker
installation beyond the already installed 0.25 services. Publishing a new testing
or customer APK remains a separate operator action. Existing installed118 APKs
cannot pick up a native UI/code correction just by reloading the website.
