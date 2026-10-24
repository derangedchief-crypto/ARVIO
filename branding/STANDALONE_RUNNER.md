# Standalone central builder — no workflow changes

## Status and boundaries

The source adapter now runs the existing queue/build/verify/publish/finish protocol
on a dedicated Linux runner without GitHub Actions. It is NOT installed or enabled
by a website ZIP. Existing GitHub workflows and generic/customer releases stay
untouched. Trials, worker deployment and real panel trial creation remain OFF.

On-device owner tests confirmed revision 1 -> 2 preserves branding and saved data.
That manual APK test did not validate automatic queue pickup, artifact registration
or the in-app brand update channel; those need a connected-runner pilot.

## Owner installation

Use a dedicated trusted Linux host/account, not PHP's web process. Install Java17,
the Android SDK/NDK required by this repository (including apksigner and apkanalyzer),
git, gh, SSH and a Python virtualenv with branding/requirements.txt. Put the reviewed
source in /opt/brand-builder/source and the virtualenv in /opt/brand-builder/venv.
These installation directories must not be writable by brandbuilder. Provision the
SDK under a read-only service-accessible path outside /home. Preinstall licenses and
required packages as an operator; builds must not need SDK-directory writes.

Create /var/lib/brand-builder owned by brandbuilder, mode0700. Caches, disposable
checkouts and durable APK retention live there; restrict backups to the owner.
Create /etc/brand-builder.env root-owned mode0600; systemd reads it before dropping
privileges. Do not upload it to the website, repository, build artifacts or chat.
Required environment names (use real private values only on the host):

- BRAND_SOURCE_SHA: full reviewed commit containing this adapter, also pinned under
  website config brand_builder.sources for the matching core version.
- BRAND_BUILDER_SSH_TARGET, BRAND_BUILDER_SSH_PRIVATE_KEY and
  BRAND_BUILDER_SSH_KNOWN_HOSTS: restricted fixed-command account and pinned host key
  as described in CENTRAL_BUILDER.md. Encode multiline values with systemd-compatible
  quoting; preserve their actual newlines. No shell evaluation or runtime ssh-keyscan.
- RELEASE_KEYSTORE_BASE64, RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS,
  RELEASE_KEY_PASSWORD: existing central release key, supplied by the owner directly
  on this runner. GitHub secrets cannot be downloaded through the API; if the only
  copy is in Actions, recover the owner's original keystore/backup first. Do not
  generate a replacement key or export secrets through a workflow.
- GH_TOKEN: dedicated owner-managed repository release publication credential.
- ANDROID_HOME, ANDROID_SDK_ROOT and JAVA_HOME: service-accessible tool installations.
- Optional existing app inputs: TMDB_API_KEY, ENTITLEMENT_CLOUD_STREAM_MANIFEST_URL,
  SENTRY_DSN, SUPABASE_URL, SUPABASE_ANON_KEY.

Preserve the website's sole private config.php, storage, encryption keys and trial
history. Its restricted brand-builder.php bridge already exists in0.13.2. Check all
migrations through017, configure the source pin, expected central signer and channels,
then enable build pickup only. Keep publication_enabled=false for the first build.
No reseller can select source, signing settings, updater URLs or runner commands.

## Staged pilot

1. Copy deploy/brand-builder.service and .timer to systemd's unit directory and review
   their paths/permissions. Do not enable the timer yet. Run one testing build-only
   operation with the service. It claims an eligible job and retains only its signed
   APK/provenance under artifacts/<numeric retention ID>/<build ID>/ before freezing
   the prepared receipt. The database's historical runner_run_id field stores this
   retention ID for standalone jobs; it is not a GitHub Actions run number.
2. Inspect the prepared website job and APK identity. Explicitly enable testing
   publication server-side. Run the standalone runner with `run --channel testing
   --build-id <exact prepared ID> --publish` under the SAME protected service
   environment/state. It retrieves and verifies the original retained bytes; it never
   rebuilds a prepared version. Use a temporary systemd ExecStart override for this
   exact operation, then remove it. Do not source the environment file in a shell.
3. Confirm the brand-only prerelease/assets, website published status and artifact
   registry after download verification. Test the installed brand's in-app updater
   using a subsequent NEW revision, checking login/favourites/settings again.
4. Only after that pilot, opt in to unattended testing publication: override the
   service ExecStart with `run --channel testing --publish`, then enable the timer.
   Production needs its own explicit policy/approval; never silently switch channels.

One host-wide service lock covers checkout/setup and a durable-store publisher lock
covers queue/publication. Never operate a second runner/store/publisher concurrently.
Disposable checkouts are fresh per operation; name/artwork inputs are immutable per
job and every output is verified against that job's package/version/signer.
Per-brand releases are brand-<UUID>-testing or brand-<UUID>; all are prereleases
with latest=false. No generic release is temporarily replaced by this adapter.

## Recovery and retention

Retain artifacts indefinitely by default; no cleanup timer deletes prepared bytes.
Back up the durable artifact directory. Files contain APK/public provenance only,
not keystores, lease tokens, SSH keys or input bundles. Private ephemeral inputs are
removed with the operation's own disposable directory, including on ordinary failure.
Systemd kills the entire process group on stop/timeout. Disk-full/retention errors
stop before a prepared receipt, not after a false success report.

Publication uncertainty stays blocked for owner review. Stop the original service
and timer BEFORE recovery, then run `reconcile --channel testing --build-id <ID>`
with the same protected environment/state. Reconcile only downloads and verifies;
it does not upload or change titles. Never reset uncertain state or replace retained
bytes. Missing prepared bytes require cancelling that unpublished job and saving a
new revision; never rebuild a previously frozen version.

The old GitHub artifact backend remains available only for its original run IDs.
Do not switch retention backends for an already-prepared job. Existing manual owner
revision1/2 APKs were not registered through this protocol: do not backfill them by
pretending this runner produced them. Start the connected pilot with a new revision
after inspecting/cancelling stale unpublished queue entries.
