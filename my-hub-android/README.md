# My Hub — native development build

This is a separate Kotlin / Jetpack Compose Android project. It contains no
WebView. It uses the licensed `/api/my-hub/` API on Hub Control Center.

Testing version 0.1.10-dev checks for My Hub updates on launch and when returning
to the foreground (at most once per hour per running process). More and the
sign-in screen offer a manual check. Updates come from the existing published
staging-testing release; upload `My-Hub-X.Y.Z-testing.apk` with increasing Android
versionCode and the same package/signer. APK size, GitHub SHA-256, package,
version and installed signing certificate are verified before Android installation.
Users approve the unknown-app permission and Android installer; there is no
silent install. Install 0.1.10 manually once to enable checks for future versions.
This is the existing sideload/testing channel, not a Play Store updater.
It creates category layouts from the shared master
catalogue without requiring a customer device. The website owner configures
the private master playlist in Saved setups → Category layouts.
The layout editor uses a full-screen category list for arranging larger catalogues.
It correctly reads numeric and boolean branding-enabled
flags so enabled resellers can save artwork and request builds. Disabled brands
remain blocked with an explanation; edits must be saved before building.
It makes the whole Connected devices card tappable and
opens the server-filtered online-only device list. Search and pagination retain
the connection filter, with a Show all action to clear it.
It displays actual IPTV panel line notes on both customer
and device cards. Install the IPTV Panel Notes worker and control 0.42.0 updates
first; missing or unavailable panel notes never fall back to Hub-local notes.
It shows only the current build in App branding and
displays verified customer usernames instead of panel
placeholders and retains real customer names. It includes lifecycle-scoped automatic device inspection and the navy/mint native redesign: persistent
phone navigation, tablet rail, visual dashboard, polished sign-in, customer/device
cards, renewal tabs and consistent workflow controls. It signs in with the existing reseller-panel username and
password. Deploy the matching username-login backend update first. The verified
panel account must already be linked to an active Hub workspace. With the owner
backend update (migration035), owner-linked panel accounts use this same APK with
access scoped to their existing owner workspace, without global owner privileges.

**Development build; live/device acceptance remains outstanding.** Native screens
cover accounts/licensing, customers/devices, renewals/merchant settings, saved
setups/defaults/jobs, source and preference previews, category layouts, favourites
recovery, support/downloads, contacts/recovery holds, branding and trial settings.
Uncertain writes have an encrypted recovery journal and guarded idempotent retries.
See `../docs/my-hub-native.md` for coverage and release prerequisites.

Build with JDK 17 and Android SDK 36:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The current package is `com.extreme.myhub.dev`, signed only as a development
build. Production package, signing, repository and update distribution are not
yet selected. Do not publish this APK as the completed My Hub app.

Backend prerequisites: Hub migration `034_my_hub_sessions.sql`, the My Hub
panel update, matching private bridge keys and a verified Hub-workspace to panel
reseller link. License administration stays alongside My TV. App passwords and
activation keys are never persisted; session tokens use Android Keystore-backed
AES-GCM storage, with backups disabled.

Queued device operations are not reported as applied. Leave the managed app open;
receipts and supported data update automatically. Rejected stale revisions require a fresh read and
review. Source credentials entered into preview forms are not journaled. Public
branded APK downloads are SHA-256 checked; private support downloads authenticate
against the native API and use Android's document picker.
