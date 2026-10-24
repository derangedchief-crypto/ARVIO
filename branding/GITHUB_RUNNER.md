# GitHub signing + worker-host dispatch

## Current choice and limits

The owner explicitly authorised ONE additional branded-only workflow after confirming
the central keystore exists only in GitHub. `.github/workflows/central-brand-builder.yml`
is that workflow. Existing testing/customer workflows remain byte-for-byte unchanged.
The worker host holds NO signing key and runs only dispatch_github.py; GitHub retains
signing keys inside its existing repository secrets. Do not install the standalone
signing service or move/export the keystore for this deployment.

Source tests are not deployment. A live website SSH bridge, repository environments,
source pin and dispatcher credential still need owner configuration. No worker/website
deployment, timer activation, live trial or automatic branded publication is claimed.

## GitHub setup (owner)

1. Push the authorised workflow with an owner credential permitted to update Actions
   workflow files. A classic OAuth token needs the workflow scope; a fine-grained
   credential needs the corresponding repository workflow permission. Do not broaden
   the worker's runtime token to grant workflow/source edits.
2. Check that GitHub registers `Central Branded APK` in Actions. GitHub may require a
   workflow_dispatch workflow to exist on the default branch before accepting dispatch.
   Do not merge the reseller branch/main/customer merely to bypass this. If registration
   is unavailable, stop and obtain a separate explicit decision about a workflow-only
   default-branch commit; no application/customer release changes belong in that commit.
3. Create/protect brand-build-only, brand-testing-publication and
   brand-production-publication environments. Restrict refs to the reviewed source
   branch; require owner approval for publication and disable self-review if available.
   The workflow's environment name does NOT itself configure protection.
4. Reuse existing repository RELEASE_KEYSTORE_BASE64, RELEASE_KEYSTORE_PASSWORD,
   RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD and existing app configuration secrets.
   Production release validation remains enabled: supply valid existing Supabase/
   APP_ANON_KEY and TRAKT_CLIENT_ID/TRAKT_CLIENT_SECRET configuration before a
   production pilot. This workflow does not bypass missing production configuration.
   Configure BRAND_BUILDER_SSH_TARGET, BRAND_BUILDER_SSH_PRIVATE_KEY,
   BRAND_BUILDER_SSH_KNOWN_HOSTS as owner-managed repository/environment secrets.
   Use a NEW restricted website bridge SSH key, not a worker/root/deploy login key.

The SSH target is the WEBSITE host, not the worker host: only the website has the
queue/database/private config. Install the public key with the restricted forced PHP
command described in CENTRAL_BUILDER.md. Validate the host key from the website
console and pin known_hosts; never trust an unchecked runtime ssh-keyscan result.
The bridge account must not edit PHP source, config, authorized_keys or signing policy.
No HTTP endpoint or privileged build/signing credential is exposed to resellers.

## Website setup (separate host, existing0.13.2 code)

Preserve the sole private config.php, initialized storage and encryption keys. Check
migrations through017. Under brand_builder, enable pickup, allow testing only, and
pin sources['2.0.114'] to the FULL reviewed workflow/source commit. Keep
publication_enabled=false initially. Keep trial signup/offer rollout OFF.

Manual owner revisions1/2 were not registry builds. Inspect/cancel stale unpublished
queue entries, then save the SAME brand as a new revision and queue it. Never recreate
the package or reset history, impersonate an earlier build receipt, or rebuild a frozen
version using different source. A job already claimed under another source pin cannot
be repinned; cancel it if definitely unpublished and create a new revision.

## Worker-host dispatcher setup

Use a separate branddispatch service account and directories, leaving existing workers,
their virtualenvs, ports and service units untouched. Install Python3 and gh. Put the
reviewed branding/dispatch_github.py under /opt/brand-dispatch/branding (root-owned,
not writable by branddispatch). Create /var/lib/brand-dispatch owned by branddispatch,
mode0700. Create /etc/brand-dispatch.env root-owned mode0600 with:

```
GH_TOKEN=<dedicated fine-grained token: this repo Actions write, Contents read>
BRAND_SOURCE_SHA=<full reviewed workflow/source commit>
BRAND_WORKFLOW_REF=reseller-control-center
```

Use real values only on the server, never in chat or a ZIP. This dispatcher token can
trigger builds but cannot read signing secrets. The SSH bridge key lives in GitHub,
not in the dispatcher environment. Protect the chosen branch from unreviewed changes.
The dispatcher refuses to run when that ref differs from the reviewed commit, or when
a branded run is active/queued/waiting approval. Update the pin only after review.

Copy deploy/brand-dispatch.service and .timer into systemd's unit directory, review
paths, then daemon-reload. Do NOT enable the timer yet. Start the service once for
the build-only pilot. The default timer checks every15 minutes; an empty queue still
starts a short GitHub check, so do not enable unnecessary high-frequency polling.

## Pilot and publication

1. Dispatch mode=build, channel=testing, exact new build ID. The workflow checks out
   its own exact commit, claims frozen inputs and signs in GitHub. Only the APK and
   public provenance are uploaded as central-branded-apk-<Actions run ID> for30 days.
2. The continuation DOWNLOADS and verifies that retained artifact BEFORE telling the
   website it is prepared. Upload/download failure never freezes a new receipt or
   publishes a build. No inputs, keystore, lease, SSH key or Gradle logs are artifacts.
3. Inspect the prepared job. Explicitly enable testing publication server-side, then
   dispatch mode=publish with that exact prepared ID. It reuses the ORIGINAL retained
   bytes, not a rebuild. Owner environment approval is required as configured.
4. Only brand-<UUID>-testing (or separately approved production brand-<UUID>) releases
   are writable. All are prereleases/latest=false. APK/provenance names are immutable;
   uploads precede the version-title update. The workflow downloads/re-verifies both
   before finishing the website registry. Generic testing/customer assets never change.
5. Save a subsequent brand revision and repeat publication. Verify the installed
   brand's in-app updater selects it and retains login/favourites/settings/artwork.
   Manual install-over-existing already passed; the automatic update channel has not.
6. After the pilot, explicitly opt into automatic testing publication by changing ONLY
   the dispatcher service ExecStart to --mode publish --channel testing and enabling
   its timer. Keep GitHub environment approval or deliberately configure unattended
   testing approval as an owner policy decision. Production remains separate.

## Recovery

One workflow-wide concurrency group serialises every brand/channel and never cancels
an active publisher. Do not run the standalone signer or a second publisher beside it.
Stop the dispatcher timer and cancel/stop the ORIGINAL GitHub run before reconciling an
uncertain publication. mode=reconcile needs the exact build ID; it only downloads and
verifies frozen publication bytes, never builds/uploads/changes release metadata.
Never blind-retry or reset publication_review. Expired/missing prepared Actions bytes
require cancelling that definitely unpublished job and saving a new revision.

Failed/cancelled operations notify the lease service; final cleanup removes private
inputs. Runner termination can prevent cleanup, so use GitHub-hosted disposable runners,
not a persistent self-hosted signing machine for this workflow.
