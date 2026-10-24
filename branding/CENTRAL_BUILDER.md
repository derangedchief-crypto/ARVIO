# Central branded builder — deployment and recovery boundary

## Current status: standalone adapter; existing workflows stay unchanged

The no-workflow standalone adapter is now implemented in source. See
STANDALONE_RUNNER.md for dedicated-host installation, durable local retention,
systemd examples and the gated pilot. It is not deployed: owner signing material
and a restricted website SSH connection still need provisioning on that host.
Manual centrally signed owner revision1 -> 2 installation/update passed on the
owner's device, preserving branding/login/favourites/settings. Normal testing114
was restored after those manual tests; customer latest remainsv2.0.101.
The remaining historical GitHub-environment instructions below apply only to the
deferred Actions adapter, not to the new standalone runner.

The proposed extra GitHub builder workflow was WITHDRAWN at the user's direction.
No existing GitHub workflow is modified. Normal testing114 uses the established
Publish Testing APK workflow; reseller builds wait until that normal test succeeds.
The restricted queue/lease/verifier CLI remains source scaffolding, but its dedicated
runner/retained-artifact adapter is NOT connected to an installed workflow. Do not
route branded output into the normal testing channel or claim unattended branding
is operational. The runner/environment instructions below describe the DEFERRED
adapter design only, not an existing workflow or authorization to install one.

Implemented source workflow; **not deployed or run against a live queue**. Regular
testing113 remains published. No reseller APK or real panel trial has been published
by these changes. Website0.13 requires migrations015–017 after prior migrations.

## What runs where

- Website: immutable cosmetic build inputs and queue/lease state. There is **no HTTP
  builder endpoint**. Reseller sessions cannot finish builds, choose source commits,
  change signing, inject Gradle flags or select update URLs.
- Separate trusted runner: checks out the owner-reviewed exact source commit,
  prepares bounded generated resources, builds with the central release key and
  verifies actual APK bytes using Android SDK apksigner + apkanalyzer.
- Repository: existing testing/release workflows are untouched. A separate branded
  runner adapter is deferred; its source CLI describes separate build and publication
  operations but is not invoked by the established normal testing workflow.

## Owner setup (separate deployment authorization required)

1. Install source on the website host and apply migrations, preserving the EXISTING
   sole private config.php, initialized storage and original application encryption
   key. Add brand_builder settings there; do not introduce a second config file.
   Defaults: enabled=false, publication_enabled=false, channels=[testing], sources=[].
2. After committing reviewed code, pin sources['2.0.114'] to the FULL40-hex commit
   containing that release's code/workflow. A commit chosen by the APK/reseller is
   never used. The runner's checked-out commit must match that exact source pin.
   The runner refuses modified or untracked build source. Never change the source
   beneath an already-pinned queued identity/version; release fixes need a new core
   version. Source is not dynamically checked out from an untrusted queue field.
3. Configure a dedicated SSH account, with read-only source/config access and the
   database permissions needed for this service. Its authorized key must use a fixed
   forced command such as:

   `restrict,command="/usr/bin/php /srv/control/cli/brand-builder.php" ssh-ed25519 ...`

   Use the actual absolute installation path and PHP8.3 executable. Disable shell,
   PTY, forwarding and arbitrary commands. The PHP entry point ignores
   SSH_ORIGINAL_COMMAND and accepts one bounded JSON command from stdin. The account
   must NOT be able to edit source, config, authorized_keys or signing policy. Keep
   access to website storage/DB least-privileged. Test the forced-command restriction
   separately before enabling the builder. Never use the website deploy/root SSH key.
4. Configure a PINNED known_hosts entry and runner inputs:
   BRAND_BUILDER_SSH_TARGET (user@host), BRAND_BUILDER_SSH_PRIVATE_KEY,
   BRAND_BUILDER_SSH_KNOWN_HOSTS and the existing owner release-signing/cloud inputs.
   No SSH host-key discovery at runtime. These inputs stay in protected GitHub
   environments; the website has no signing key and APKs have no builder key.
5. Pre-create/protect `brand-build-only`, `brand-testing-publication` and
   `brand-production-publication` environments. Require reviewers and restrict
   allowed refs for publication; prevent self-review where available. A workflow
   environment name alone does NOT configure approval protection. Review repository
   Actions permissions and workflow registration separately; do not merge into main
   merely to make dispatch available without approval.
6. With explicit build authorization, dispatch one testing-channel build without
   publishing. A blank build ID picks the oldest eligible UNPREPARED job for this
   pinned source, never repeatedly selecting an already-prepared job and starving
   the queue; an explicit ID targets that job. Later authorize publication separately, enable
   only the required server channel and publication flag, and dispatch with publish
   and the exact prepared build ID.
   A successful build-only run retains its EXACT APK/provenance in the Actions
   artifact for14 days and releases the prepared lease. A later publish run downloads
   and verifies those frozen bytes from the original Actions run—it does NOT rebuild
   the same version. Missing/expired/different retained bytes stop for review; cancel
   that unpublished job and save a new brand revision rather than replacing bytes.
   Test-channel package ends `.test`; production uses the stable base brand package.
   These never replace com.arvio.tv, .beta, .test or their update channels.
   Each immutable job is pinned to ONE channel on first claim. To follow a testing
   job with a production build, save the existing branding again (a new revision,
   same persistent brand/package family) and request the next job; never repurpose
   the testing job or overwrite its artifact/channel. Trial history does not reset.

The runner creates private lease/bundle/key files in the disposable runner. Uploaded
Actions artifacts include ONLY the signed APK and non-secret provenance; never the
input bundle, lease token, keystore, SSH keys or generated secret properties. Targeted
cleanup runs even on failure. The build provenance records source, input hash, APK
identity/digest/size and build seconds; it is not a claim of hardware startup speed or
a promised first/cached build time. First/cached signed timings still need measuring.

## States and fencing

waiting_builder -> building (30-minute renewable lease, max3 attempts) ->
ready_to_publish (signed+verified, NOT downloaded/published) -> publishing -> published.

Wrong/expired lease tokens cannot renew, prepare, authorize or replace another
runner's work. Expired BUILDING jobs can be rebuilt with a NEW token; PREPARED jobs
get a fresh lease only to retrieve/publish the original frozen APK/provenance, never
to rebuild the same version. Older callbacks are rejected. Pin source/channel on first claim and preserve pinned
images/name even if the reseller saves a later revision. There is only one active
queued/publishing identity for that brand. Retry-cap jobs cannot starve queue pickup.

Failure before a prepared receipt -> build_failed, eligible for bounded rebuild.
Once a receipt is frozen, retrieval failure stays prepared/review and cannot replace
that artifact. Queued/prepared/failed unpublished jobs can be explicitly cancelled;
publication uncertainty cannot be cancelled away. Maintenance marks expired building
leases failed and expired publications review-needed without claiming success. Failure or
timeout after authorization -> publication_review. Such jobs are NEVER blindly
reclaimed/rebuilt: GitHub might already have received the APK. A previous uncertain
publication blocks later channel writes. Suspension/unlinking/brand disablement
reject claim/heartbeat/publication/finish; none revoke ordinary viewing.

## Immutable updates and provenance

Version allocation: coreVersionCode*100000 + brandRevision, with revision1–99999 and
Android integer bounds. Version names are core.version.number.brandRevision, with
`-test` only for testing. Cosmetic edits preserve package and central signer while
producing a newer install/update version. A newer core always sorts above revisions
of an older core. No same-version/different-byte replacement is allowed.

Only brand-UUID[-testing] rolling releases in the central repository are writable.
EVERY brand channel is a prerelease with --latest=false, including production brands,
so the customer /latest channel never changes. Assets are immutable
`Brand-<full-version>.apk` plus `.provenance.json`; old versions remain retained.
Existing filenames may be reused only when downloaded bytes are identical—never
--clobber. Upload both files BEFORE updating the release title. The branded updater
selects ONLY the exact APK matching that title, not any older/generic APK by ABI.

The runner verifies actual package, version name/code, non-debuggable manifest,
package-scoped providers, protocol metadata and exactly one central signer. After
publication, it checks channel metadata and downloads/re-verifies the APK AND
provenance digest before committing the artifact registry. That registry is MAC-bound
to original application.key, package, source, size, signing/hash/channel and URL.
An unknown package or client-reported signer does not become an enrolled package.

## Uncertain publication recovery

Stop/cancel the ORIGINAL CI job and ensure it cannot continue writes BEFORE recovery.
An expired lease cannot revoke an external GitHub token or fence a paused process
inside a remote upload atomically. The workflow serializes central builds and checks
lease ownership before every write; operating a second external publisher outside
that lock is unsupported. Do not assume a database lease alone can provide GitHub CAS.

Dispatch mode=reconcile, exact build ID/channel, publish=false, from the same pinned
source. It acquires a fresh reconciliation lease, invalidating old callbacks. It
ONLY downloads/verifies the frozen expected asset/provenance/current channel title
and finishes the SAME registry entry. It never builds, uploads, overwrites or edits
release metadata. Mismatch/missing/incomplete upload remains publication_review for
owner investigation. Do not reset it to queued or delete history to retry blindly.
If bytes were uploaded but the title update never completed, the owner must inspect
and explicitly complete that exact frozen publication; reconcile intentionally will
not advertise an incomplete channel. Lost finish responses are idempotent.

## Enrollment, callbacks and trial publication

The source enrollment worker accepts the strict branded-package FORMAT after actual
account authentication. The control center additionally requires a centrally
registered published artifact for that exact package/channel. Brand strings do not
prove an authentic APK or select the account's reseller; signed panel ownership and
device key possession remain authoritative. This small activation-worker extension
is a SEPARATE deployment from the iptv-auth-worker trial extension and website.

Provider authorities are applicationId-scoped and checked on APK output. Normal
activation uses the existing HTTPS activation worker and is package-independent.
Legacy optional `arvio://install-pack` and Discord callback intent schemes remain
unchanged; plugin support is off, and optional external/Discord callback behavior
still needs testing with side-by-side branded packages before any such feature is
claimed supported. Do not rewrite backend/OAuth identifiers as cosmetic text.

Trials additionally need owner rollout opt-in (default OFF), current verified build,
fresh approved plan/default and a separate explicit reseller offer-publication action.
Settings saves/website upgrade/build publication NEVER silently enable a trial offer.
The publication UI discloses broad-compatible/public signup limitations, supports
daily quotas and can unpublish without revoking created trials or ordinary viewing.
Real signed installation/update, enrollment and panel activation/contact/repeat-trial
validation remain a separately authorized pilot, not something offline tests prove.
