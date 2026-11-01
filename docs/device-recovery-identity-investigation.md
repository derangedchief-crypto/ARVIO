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

### Subsequent owner-provided history

The owner subsequently confirmed that app data had been wiped after update
problems, then the customer logged back in and playback initially worked. The
read-only historical comparison shows the same account/model/Android version
but a different signing key. A data reset explains a fresh management session
and key; the interrupted-save defect alone does not explain key replacement.

The delayed-lockout mechanism is visible in the code: management publishes local
state with no policy for a new session, independently of successful IPTV login;
network/enrollment unavailability is informational. A later authenticated
registration/poll response for an unidentified historical record supplies
`recovery_policy=suspended`. The agent persists that synthetic recovery hold and
the gate replaces playback. Therefore working playback after re-login was not
proof of completed device enrollment. The exact duration/reason for delayed
registration still requires endpoint/lifecycle evidence; do not invent a token
expiry, server outage or a second identity reset after playback resumed.

This exposes a legacy-reset migration/UX gap: the old record had no hint, so
fresh account proof plus a new key could not establish physical-device continuity.
The server's safety hold prevents guessing but the app misrepresents it as a
short automatic reconnection. PR #13 addresses the separate atomic-file defect
and honest retry/status UI, not a complete legacy-reset recovery workflow.
Prevention must also make pending enrollment explicit after re-login, verify
signed hint backfill on existing installations, and provide scoped audited
owner recovery for legacy records without weakening real device restrictions.

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

### Extended reset/recovery hardening (same PR)

An owner-only legacy recovery preview/approval is now implemented in Website
settings / Connection requests. A live unidentified hold must have exactly one
historical approved device for the account/package, matching verified panel
customer/reseller and customer/workspace binding. Restricted/recently active
devices, existing/foreign hints, expired delivery and stale review revisions are
refused. Approval records the association and `owner_authorized` state with the
real actor audit; it does not issue credentials or lift policy. Fresh signed
enrollment still performs recovery and retires the previous credential. The
browser sends only explicit confirmation plus a server-generated review revision.

Device detail now distinguishes recovery-association readiness from active access
and presence. Readiness is not proof of physical-device continuity or a guarantee
of recovery after every possible reset. Healthy existing installations still need
signed hint backfill; a legacy installation already reset without that evidence
needs owner review. Never automatically match by username/model/IP.

The foreground management runner now retries transient startup state/keystore
errors instead of becoming inert until a process restart/account emission. Retry
preserves known restrictions and lifecycle cancellation. Recovery UI can wake the
normal runner without wiping customer state. New enrollment audit metadata records
known-identity vs historical-recovery vs new-installation paths and signed-hint
presence without logging keys, tokens, passwords or raw device identifiers.

This hardens the failure modes but does not establish exactly why this incident's
first recovery registration occurred the night after its reset. Do not claim that
uncaptured network/lifecycle history has been reconstructed or that every future
reset can safely recover automatically.

The extended website changes require a new matching backend/frontend deployment;
the immutable fixed-file 0.36 installer is NOT suitable for deploying these changes.
No new schema migration is needed on an already-upgraded website. Customer app
reader/retry/UI changes still require a separately approved signed in-place APK.

Validation: 90 management tests pass with zero failures/errors/skips, including
four vault regressions and two recovery-status cases. Official customer-variant
Kotlin compilation also passes. Before the patch, both interrupted-state cases
failed while genuine first enrollment passed. These are synthetic regressions,
not evidence collected from the customer's device or signed in-place acceptance.

Source-only fix for review. No live website update, worker restart, APK publication
or customer rollout. Existing installed 2.0.126 apps do not acquire this reader/UI
fix until a separately approved signed in-place app update. Keep production
rollout approval separate and do not reuse an existing release version.
