# Client update continuity audit

## Scope and conclusion

Reviewed staging `f179dde` against released-source baseline
`32fa37a7dc24c6244707780757b4d950764dcb17`. The only Android changes are
MainActivity's refresh trigger, repository playlist refresh, the process-owned
five-minute scheduler, and their tests. No device identity, package, signing,
activation vault, preference-store or database migration code changed.

A normal signed in-place update of the same app/account should retain the existing
server device record and should not create an identity-mismatch recovery hold.
This is a code-level conclusion, not a guarantee for every physical device or a
completed acceptance test of an unreleased APK.

## Identity path checked

- `ManagementAccount.scope` depends on the normalized account username, not the
  app version, playlist contents, model or profile ID.
- `ManagementVault` retains the `device-management-v1` directory, encrypted state
  format and signing/encryption key aliases. Existing installation and server
  device IDs are loaded from that state.
- The AtomicFile repair remains present: legacy `.bak` files are restored;
  unfinished `.new` and corrupt records cause retry instead of being mistaken
  for a fresh installation. Existing data is not erased as a repair.
- `DeviceManagementAgent` creates a new installation only for absent saved state
  or a changed signing-key fingerprint. App-version changes do neither. A 401
  clears the expired credential for re-registration, retaining installation ID.
- Server enrollment matches installation, key, account and package before
  inspecting recovery hints. Existing approved devices retain their record.
- The app's displayed installation ID and the site's database device ID are
  different identifiers by design; different text alone does not establish a
  failed association.
- Recovery hint derivation is version-independent and package-scoped. Signed
  check-ins backfill it for existing installations.
- Activation, favourites, settings and management storage remain separate from
  image/HTTP/catalogue caches. The update installer uses Android package replacement;
  its uninstall helper has no callers in the app.

## Remaining boundaries

An actual data wipe, missing signing key, a different package/signature, or an
ambiguous historical identity can still require recovery. Restricted identities
must remain restricted. The additional-device server repair admits eligible new
TVs without treating an active hintless legacy TV as sufficient reason for a hold;
it does not claim that every new installation is an old physical device.

The public live endpoint reports website 0.40.0. This confirms the reported live
version, not private backend file hashes or successful check-ins for every device.
Previously repaired Amazon/X88Pro records still require their actual check-in
confirmation.

No ADB device is attached for a physical in-place acceptance test. Before release,
the actual signed candidate must have the same customer package and signer and
an advancing version code. Staging still declares 2.0.131 / 447; the next candidate
must receive its new version during release preparation. Reseller builds must
retain each brand's package/signing identity. No APK has been published by this
audit.

## Verification

The isolated control-service suite passes all **348 tests**, including the added
in-place update regression. The full Android unit/Robolectric suite reports
**1,053 tests: 1,052 passed, one skipped, zero failures/errors**, including all
**95 management tests** passing. Compilation also succeeds.
The added server regression uses both a hintless legacy identity and
an identity with a recovery hint, updates the online device's reported version,
then re-registers the same signed identity. It asserts the same device/request,
no recovery hold, no duplicate device and retained assignment, notes and policy.

These synthetic tests do not simulate the Android package manager replacing a
signed production APK on an actual customer's hardware.
