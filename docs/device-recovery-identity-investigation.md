# Standalone device recovery hold investigation

## Confirmed incident evidence

The owner supplied read-only website query results for one standalone customer
installation on 2.0.126-beta. They show a pending `unidentified` enrollment, one
historical approved enrollment on 2.0.124-beta, an active historical device and
workspace, and no historical recovery hint. The pending request supplied a hint.
An owner explicitly authorized reconnecting that one customer; a guarded CLI
transaction recorded the association and an audit event. Subsequent completion
has not yet been confirmed. No production APK or global recovery-policy change
is authorized by this investigation.

These records establish WHY the server held recovery: account proof alone does
not establish that a new installation is the same physical device. They do not
establish WHY the installation identity changed. Do not attribute this to an app
update, reinstall, data clearing, keystore loss or a second device without evidence.
The displayed device ID is the app's installation ID, not the database device ID.

`last_request_at` is enrollment registration time, NOT a log of every poll, failed
request, foreground session or network attempt. An old timestamp establishes no
fresh registration, not necessarily no network activity. Foreground-only management
also stops when the Activity is stopped. Network/access logs and on-device evidence
are needed before claiming that the retry loop stopped or why.

## Reproduced defect and prevention

ManagementVault.load checked only AtomicFile.baseFile.exists before readFully.
On Android versions using legacy AtomicFile backup replacement, a process death
can leave the old valid record only in `.bak`. The early check skipped Android's
backup restoration and returned null. The agent interprets null as first
enrollment and generates a new installation ID. Modern interrupted first writes
may leave `.new`; these too were incorrectly treated as a clean first enrollment.

ManagementVaultRecoveryTest reproduced both silent-null conditions before the
patch (two failures, one genuine-first-install success). The patched reader lets
AtomicFile restore a legacy backup and refuses an unfinished-only write rather
than generate/overwrite identity. Invalid existing encrypted state still fails
closed; this does not weaken encryption or recreate keys to decrypt old state.
No backup deletion, app-data clearing, package, signing or storage-directory change
is used as a repair.

This is a confirmed implementation defect, not proven incident causation. There
is no captured on-device file/keystore evidence from before the owner's repair.
The patch also does not reconstruct state that was actually erased or transfer
hardware identity based on a username.

## Recovery UX

Unidentified/ambiguous recovery now says provider approval/review is needed,
instead of implying a permanent hold resolves itself within minutes. Busy and
restricted holds remain distinct. The recovery gate offers Retry connection,
using the existing management wake/check-in method; it does not clear credentials,
authorize a match, bypass policy or grant playback. Restricted non-recovery screens
remain restricted. Saved identities, accounts and financial/subscription state
are not modified by these UI changes.

## Evidence still needed for this incident

Compare the pending request's key fingerprint with the approved historical
device's current fingerprint (print only equality, not keys/tokens). Compare
installation equality, model/OS and request creation time. A surviving same key
with a changed installation supports a missing-session-file hypothesis; different
keys remain consistent with multiple scenarios and do not prove a reinstall.
Ask whether the customer used the same physical box; retrieve redacted device
diagnostics and endpoint status/timing logs if retries remain absent. Do not dump
signed proofs, poll tokens, credential ciphertext, private keys or passwords.

## Delivery boundary

Source-only fix for review. No live website update, worker restart, APK publication
or customer rollout. Existing installed 2.0.126 apps do not acquire this reader/UI
fix until a separately approved signed in-place app update. Keep production
rollout approval separate and do not reuse an existing release version.
