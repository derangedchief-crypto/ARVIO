# My Hub — native development build

This is a separate Kotlin / Jetpack Compose Android project. It contains no
WebView. It uses the licensed `/api/my-hub/` API on Hub Control Center.

Testing version 0.1.5-dev shows only the current build in App branding and
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
