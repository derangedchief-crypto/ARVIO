# Dashboard service intro repair — unreleased

The user requested this repair without publishing another testing APK. The APK
version and publishing workflows are unchanged; no release is requested here.

## Cause

The original `mrtxiv/networks-video-collection` repository and the pinned Netflix
MP4 URL returned HTTP 404 on 2026-10-20. The Home hero mounted its video layer as
soon as a URL existed, without a playback-error fallback or first-frame gate.
The old SurfaceView could therefore obscure the service artwork with black.

## Repair

- Bundled service videos use the surviving `PacerguyDon/networks-video-collection`
  copy pinned to `c5802b081ccc945bbac8d74621c687e711627ce2`.
- Saved and cloud-restored collection video/artwork URLs referencing the exact
  retired asset prefix are repaired during catalog normalization. Custom URLs,
  catalog titles, visibility, order and account/profile storage are not rewritten.
- The existing eight intro-enabled services remain Netflix, Disney+, Apple TV+,
  Prime Video, HBO Max, Hulu, Paramount+ and Crunchyroll. Other services retain
  their existing static-artwork behavior; no substitute clips are invented.
- Home uses the existing texture-backed trailer PlayerView and keeps its artwork
  visible until a frame actually renders. Playback errors hide the failed video
  for that focus visit; late events from a different media URI are ignored.
- Leaving/returning to a service resets the played-once state and preparation key,
  so completed/failed previews can replay on a later visit. Short MP4s no longer
  receive live-stream configuration. Preview audio pauses while backgrounded;
  normal disposal, including the maintenance gate, releases the Home player.

## Validation

All eight pinned remote MP4 URLs were fetched successfully and returned an MP4
`ftyp` header. Regression tests cover stored-catalog migration, preserving custom
URLs/settings, bundled service definitions, first-frame visibility, stale events,
playback failure fallback, and inflation of the actual texture-backed layout.
Home/startup/catalog and maintenance regressions are included in the validation
run. This is not physical-device playback verification or a guarantee that an
external asset host will never go offline again; failures now retain artwork.
