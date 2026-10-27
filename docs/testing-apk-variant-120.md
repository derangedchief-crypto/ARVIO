# Testing update selection correction — 2.0.120 / 436

Validation: 950 full native tests, zero failures/errors, one existing skip; both
testing/customer Kotlin variants compile. Seven new updater-selection checks and
all79 branding/builder checks pass. This source correction is staging-only;
APK publication remains separately approved and no main push is permitted.

The 118 and119 generic testing APKs were built by Publish Testing APK from
staging/testing. Their manifests both use com.arvio.tv.test and the same central
signing certificate, with increasing Android version codes.

The missed failure was the IN-APP asset-selection path. The rolling testing
release also retains two historical owner-branded testing APKs. Generic builds
previously passed every asset to the ABI selector, which picks the first APK
without an ABI marker. In the actual release order this is an old branded APK,
not ExtremeTV-Test-latest.apk. The installer's variant check correctly rejects it.

The fix selects exact app/channel identity BEFORE ABI selection:
- Generic testing: only ExtremeTV-Test-latest.apk.
- Generic customer: only ExtremeTV-VERSION.apk for the current normal release.
- Branded apps: only their exact immutable Brand-VERSION.apk on their own channel.
- Missing correct asset: fail closed; never substitute another app's APK.

Regression tests reproduce the actual mixed release ordering and assert the fixed
selection, customer/testing separation and branded version isolation. No existing
release assets are deleted/renamed, no keystore/package identity is changed, and
no main push is authorized by this correction.

An affected OLD generic updater cannot repair its own selection before an update.
Use the verified direct generic testing APK to update the same installed generic
package without uninstalling or clearing data. Do not use it to replace a branded
testing or customer package. If direct installation also rejects the file, inspect
the actual installed variant and exact installer error before changing anything.

All future testing builds must be sourced from staging/testing and published with
the testing workflow. Verify the selected in-app asset, not only a manually
downloaded APK, before promising an in-place update.
