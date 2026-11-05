# My Hub live workspace 0.42.0

This update targets the deployed 0.41.0 My Hub-compatible control backend. The
installer verifies the released Application/Auth and category-layout baselines,
backs up replaced files, and publishes versioned frontend assets. It retains
existing credentials, device identities, My Hub licensing, database contents,
live relay configuration and worker services.

## Installation on the control-panel host

Download `Extreme-Control-Live-Workspace-0.42.0-MyHub.zip` from the `staging-testing`
release of `derangedchief-crypto/ARVIO`. Check the release SHA-256, then extract
into a new private directory under `/root`, not the website document root.

```sh
sha256sum -c MANIFEST.sha256
python3 install-live-workspace.py "$PWD/Extreme-Control-Live-Workspace-0.42.0-MyHub.zip"
/etc/init.d/php-fpm-84 reload
```

Keep the printed `/root/control-0420-before-*` backup. If the baseline check
rejects the installation, collect the error and compare the deployed source;
do not bypass the check. Hard-refresh the website after installation.

## Testing app

Install `My-Hub-0.1.3-testing.apk` over the existing My Hub testing app. It keeps
`com.extreme.myhub.dev`, increments version code to 4, and uses the same testing
certificate as the previously distributed 0.1.2 APK. It is a development/testing
build, not a production-signed Play release. Keep the managed customer app on
the previously released 2.0.134 testing build or a compatible newer build.

## Acceptance on the existing phone

1. Open its device workspace on the website and My Hub. Playback, preferences,
   playlists, channels and diagnostics should load without manual fetches.
2. Change a setting in the managed app; check automatic readback in the open
   workspace. Edit a field remotely and confirm a background response preserves
   the unsaved value until an explicit save/discard.
3. Apply a reviewed change; confirm the app's receipt and subsequent fresh data.
4. Select another playlist source; only that source's layout should be shown.
5. Leave playback active without input, then test offline/reconnect. Last-known
   data must be labelled rather than presented as a fresh device response.

## Rollback

The backup ZIP contains the previous Application.php, CategoryLayouts.php,
index.html and VERSION. Restore those files with their original website owner,
then reload PHP 8.4. `added-files.json` lists newly added files. The versioned
0.42.0 asset directory can remain unused after restoring the old index. No
database rollback or app-data reset is required.

## Local verification

Backend integration includes inspection deduplication, queue bounds, tenant
isolation, offline/restricted states, failure backoff and source isolation.
Browser checks cover owner/reseller routes at 320–1440px, draft preservation,
revoked access, drawer focus and theme persistence. Installer sandbox tests
cover installation/backups, unexpected backend modifications and symlinks.
Android validation runs unit tests, assembly and lint. Physical-device
acceptance remains a separate post-install step.
