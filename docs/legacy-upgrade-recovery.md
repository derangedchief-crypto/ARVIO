# Legacy customer upgrade recovery — staging only

## Incident

Customer 117 stored the resolved activation credentials as ordinary profile IPTV
configuration. It did not create `verified-primary-v1.enc`. Customer 123 required
that new vault and treated its absence, and even read/decryption errors, as first
activation. Therefore package/signature compatibility did not prove continuity.
The user reports affected customers return to activation and lose access to data.
Actual deletion has not yet been established; do not describe that as disproved.

Customer 123 remains live at the user's explicit direction. This fix must stay on
staging. Main promotion and any customer APK build/publication each need separate
explicit approval. No server changes or enforcement changes are authorized.

## Implemented staging protections

- Existing valid activation vault: use it directly; never scan sources or rebind.
- Vault read/decryption failure: preserve it, show recovery (not activation), retry.
- AtomicFile backup-only state: allow Android's atomic recovery to restore it.
- Missing vault: read legacy source data across profiles without changing it.
- An unambiguous existing approved encrypted management identity may identify the
  account independently of playlist IDs, labels, order, enabled flags or selection.
  Examine historical management identities too, even if sources were removed.
- Pin that identity before checking activity. Expiry never selects an active extra.
- Migration writes only the new activation vault. It does not call activation,
  savePlaylists, profile selection, cache purging or onboarding completion.
- Missing/unreadable/ambiguous evidence: preserve data, show a recovery notice and
  retry; never pretend the saved installation is new or adopt an arbitrary source.
- Only a genuinely empty local account state goes to first-time activation.

## App-only recovery boundary

The user explicitly chose app-only investigation (no website recovery) and accepts
that this next update may have exceptions for some accounts, provided the rest
upgrade smoothly. Report those exceptions honestly; this does not authorize choosing
an arbitrary extra as primary or claiming universal activation continuity.

117 did not preserve an immutable activation receipt. Unmanaged installations,
multiple historical approved accounts, removed originals and lost encryption keys
cannot all be recovered safely from editable playlist data alone. The current
recovery page avoids misleading reactivation but still disrupts access. It is NOT
a universal seamless-upgrade fix and must not be presented as one. In these cases
saved data is retained, but activation/access is NOT automatically restored.

Authoritative account recovery for those exceptions remains necessary. The user
declined website recovery work for now. Do not weaken
the primary-binding requirement by trusting the first/only active source, a source
named Primary, `list_1`, imports, profile order or a sole official-provider account.
Do not deploy a new server endpoint or change the live release without approval.

## Required acceptance evidence

Final automated validation: 979 native tests, zero failures/errors, one existing
skip. Both `compileSideloadInternalTestKotlin` and `compileSideloadStagingKotlin`
passed. Log: `/tmp/opencode/legacy-upgrade-final-validation.log`.

The 117-format fixtures verify unchanged persisted preferences (including source
IDs, favourites, hidden groups and unrelated settings), migration from an existing
approved identity, persistence across simulated process restart, failed-write retry,
inactive-primary denial, outage recovery, ambiguous-identity refusal, unreadable
state handling and AtomicFile backup/interrupted-write behavior. Existing 123
bindings bypass migration entirely. Unmanaged fixtures explicitly assert retained
data but unresolved activation; this is not counted as seamless recovery.

No Android device was attached (`adb devices -l` returned an empty list). These are
automated state/migration fixtures, NOT an actual signed-APK install/update or
device playback/activation-retention acceptance test.

Run native regression and both variant compilation checks. Exercise authentic 117
state and process restart, extra playlists preceding the primary, multiple profiles,
inactive primary with active extra, network failure, failed storage writes, unreadable
keys and ambiguous/no identity evidence. Preserve raw preference contents and source
IDs; verify favourites/hidden groups/settings. On-device 117 and 123 upgrade tests
remain mandatory before claiming real-device success; signing alone is insufficient.
