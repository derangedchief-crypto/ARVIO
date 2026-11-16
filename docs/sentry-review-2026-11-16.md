# Sentry review — 2026-11-16

Scope: staging/testing, no customer APK publication. Sentry access restored through
the public HTTPS OAuth callback; issue evidence was read live on November 16.

## New reports

- ANDROID-1S: background startup ANR in Application Hilt injection while building
  OkHttpProvider. Release metadata missing; onn Android 14.
- ANDROID-1R: startup injection constructs IptvRepository/its dispatcher on main;
  reported release 2.0.126/442, onn Android 14.
- ANDROID-1Q: startup injection reaches TelegramRepository and File.isDirectory;
  Crystal Streamz 2.0.117.2/43300002, onn Android 14.
- These expose eager construction of the application repository graph. Application
  now injects lazy background-service dependencies, resolves cloud push wiring
  inside its worker coroutine, and the entitlement guard registers lifecycle
  callbacks without constructing its HTTP/IPTV/entitlements dependencies. Existing
  delayed checks resolve those dependencies on IO. This removes the observed eager
  path; keep ANRs open until affected-device startup traces verify recovery.
- ANDROID-1P: current 2.0.134/450 blocks main in nativeSetPropertyString while
  OwnTvSurfaceHandoff detaches MPV during a fast-live switch.
- ANDROID-1N: archive handoff reaches nativeCreate/ART wait on main. No release tag.
  Both are from the same onn device. OwnTV player-core 1.0.68 source explicitly
  requires attach/detach/resize on main because SurfaceHolder lifetime is owned by
  the UI thread. Moving adapter calls onto a generic coroutine could race surface
  destruction or break ExoPlayer thread confinement. These remain open pending a
  lifecycle-safe native player change and affected-device playback verification.
- ANDROID-1T: SSL_free/BIO_free refcount abort on Amazon Android 7.1.2, release
  2.0.023/339. No demonstrated current-source fix; remains open.
- ANDROID-2 recurrence: SkCodec native crash on 2.0.023/339. Current legacy WebP
  guard is a mitigation, not proof that every native codec crash is resolved.

## Earlier fixes checked against current evidence

- ANDROID-1M: latest November 15 event still runs 2.0.134/450 and the exact eager
  decodeGzipLayers/readBytes allocation removed by 3f554c6 + 0a49441. Streaming
  regression passed GitHub Build Check 42635304768 and 42638912545. Fix exists in
  staging/main but is not yet shipped. Resolve in next release, not as deployed.
- ANDROID-1D: ISO offset watch-history timestamp handling and regression already
  shipped with 2.0.134. Latest event remains 2.0.131/447 on November 2.
- ANDROID-1F: protected-primary cloud merge and persistence regression already
  shipped with 2.0.134. Latest event remains 2.0.131/447 on November 2.
- ANDROID-X: playback-progress IO containment passed, but the obfuscated event
  does not identify every possible preference writer and the device had zero free
  space. Retention reduces avoidable accumulation; it does not guarantee writes
  succeed. Keep open rather than assert all ENOSPC paths are fixed.
- ANDROID-1J: rename failure on 2.0.023/339; singleton DataStore ownership in current
  code is not enough evidence to prove this particular filesystem failure fixed.
- Other unexplained ANRs retain their status and affected-device trace follow-up.

## Validation

GitHub Build Check includes actual Hilt application startup and entitlement recovery
tests, including lifecycle registration with dependency factories that fail if
evaluated during startup. Existing timestamp/cloud merge, gzip/storage, retention,
playlist/category and management regressions remain in the same workflow.
