# Automatic multi-device enrollment and Recovery holds

## Incident evidence

The owner confirmed that an onn box, an Amazon device and an X88Pro should all
work on one account. An approved hintless 2.0.124 onn record caused new 2.0.131
installations on the other two models to be classified as unidentified recovery.
Two X88Pro requests shared a hint but had different installation IDs and keys.
This evidence establishes an additional-device enrollment problem, not that an
APK update itself erased an existing device's keys. The account-specific repair
retained onn, approved the other two devices and superseded the older X88Pro
request. Approval output alone did not confirm subsequent app check-ins.

## Shared server correction

An active legacy device lacking a hint is no longer sufficient to hold a new,
signed hinted installation. Ordinary panel/account/workspace and allocation
checks still decide whether to add that installation as a separate device. A
restricted legacy identity still requires review. Sites may opt into conservative
legacy handling with `automatic_additional_devices=false`; no installer edits
private configuration. Existing holds are reevaluated on fresh signed enrollment.
Recognized recovery retains the existing device record and policy; additional
admission never pretends to identify an old hintless device. This distinction is
necessary to preserve legitimately separate TVs. A legacy reinstall with no
continuity evidence cannot be deduplicated by guessing from model or username.

The existing request/device/hint tables hold history. On approval, older
unassigned pending requests for the same scoped hint become rejected/superseded;
other hints and existing device credentials are unaffected. Repeated registration
for the approved installation retains its device ID. No Android changes or new
APK are required for the observed multi-device case.

## Reseller recovery queue

`/api/recovery-holds` and the Recovery holds navigation page expose pending
recovery requests scoped by the account's current workspace binding. Restricted
devices have a separate paginated list, including records hidden by fresh-list
visibility. Request previews omit keys, credentials and raw hints.

Allow requires selection of an existing same-account/package device. A revision
binds request identity, ownership and candidate state. Mutation serializes on the
recovery scope and locks the workspace/request/device rows, then rechecks scope
and revision. It rejects recently active targets, stale/expired requests, different
known hints, cross-account/panel/workspace targets and non-active target policies.
It associates a previously missing hint and waits for a fresh signed enrollment;
it does not create a device or deliver a credential directly. Normal recovery
quiet-period protection still applies. Reject changes only the pending request.

Resellers may use existing policy controls to reactivate suspended devices.
Owner blocks and revoked enrollments keep their owner-only controls. An allow
operation must not become a way around those permissions. Detached owner-managed
inventory remains unavailable to its former reseller.

## Validation and delivery

Regression cases cover active hintless legacy TV plus two additional TVs on both
standalone and a registry-admitted branded package, duplicate retirement,
pre-update holds, allocation recovery, restriction preservation, signed matching
recovery, cross-workspace denial, stale revisions, rejection and expired/busy
targets. Mobile browser checks cover real session/CSRF boundaries and the scoped
allow/reject controls. Installer tests cover exact file allowlists, reruns,
modified assets, PHP-lint rejection, symlink isolation and rollback including a
previously absent RecoveryHolds.php.

Website 0.38.0 uses the fixed-site 0.37-to-0.38 installer. It replaces only four
enrollment/recovery routing PHP files, HTML/VERSION pointers and twelve versioned
frontend assets. It does not migrate or edit the database, overwrite private
configuration/keys, change worker services, publish APKs or trigger fanout.
Installation and actual post-install customer check-ins must still be verified.
