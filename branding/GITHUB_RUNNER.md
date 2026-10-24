# GitHub signing + worker-host dispatch

## Owner-opted unattended TESTING publication (website0.14.3+)

The owner explicitly approved automatic verified TESTING publication. This is
opt-in on BOTH hosts: site's `brand_builder.automatic_testing_publication=true`
alongside `publication_enabled=true`, and worker `BRAND_AUTO_PUBLISH_TESTING=true`.
Without that worker flag the existing service remains build-only. The worker
requests an authenticated `mode=publish` testing hint and requires a matching
publish-mode response; legacy/build-only responses cannot authorize publication.

Use `python3 configure-poll.py FULL_REVIEWED_SHA --enable-testing-publication` from
the new worker bundle to preserve GH_TOKEN/queue token and add that opt-in. Update
the site's source pin to the SAME reviewed SHA. Pause only the dispatcher timer
during deployment and resume it afterward. Never restart unrelated workers.

Testing hints can select queued builds OR healthy prepared jobs with expired leases.
Prepared jobs reuse retained bytes and may not carry a review failure_code. Active,
published/cancelled, uncertain and artifact-review jobs remain excluded. Polling is
still read-only, token-authenticated,30second and zero-GitHub-call when empty. The
existing signed/retained/downloaded/mirrored-byte verification chain is unchanged.

For unattended testing, the owner approved removing required human reviewers ONLY
from GitHub's brand-testing-publication environment; retain its branch restrictions.
Production's separate environment/review and website/worker channel restrictions
remain. No main-commit update fanout or trial activation is part of this change.

## Build-only30second pickup (default; website0.14.2+)

## Queue-aware30second testing pickup (website0.14.2+)

The dispatcher systemd service uses `--poll --mode build --channel testing`.
Every run first performs a bounded HTTPS GET against the website queue endpoint,
with a dedicated read-only Bearer token in the header. Redirects are forbidden.
An empty queue produces NO GitHub calls or Actions runs. It never polls production
or automatically publishes unless the separate0.14.3 testing opt-in above is set.
Compilation/runner availability are separate delays.

For an eligible job it verifies the protected ref/source and existing active runs,
then records a private durable dispatch-intent fence BEFORE dispatching the exact
job ID. The fence lasts five minutes to cover ambiguous dispatch responses/list
propagation; the website separately enforces leases and a three-attempt build cap.
Normal long-running builds are protected by the active-run check. Failed builds
also have a five-minute website polling backoff. No queue claim occurs over HTTP.

Deployment (owner only; do not upload this bundle to the public website):

1. Disable only `brand-dispatch.timer` if it was enabled. Do not restart any payment,
   auth or unrelated services. The installer refuses an active dispatcher timer.
2. Install website0.14.2 UPDATE preserving the existing config/storage/keys. Restart
   only website PHP8.4 to clear stale bytecode; retain the restricted bridge ACLs.
3. Extract the NEW worker bundle to a root-private folder and run
   `bash install.sh FULL_REVIEWED_SOURCE_SHA`. It preserves `/etc/brand-dispatch.env`
   and does not start/enable anything. The helper requires existing system gh/python.
4. Run `python3 configure-poll.py FULL_REVIEWED_SOURCE_SHA` there as root. This
   preserves GH_TOKEN, backs up the first old env as root0600 `.before-poll`, pins
   the source, sets BRAND_QUEUE_URL and generates/reuses BRAND_QUEUE_TOKEN privately.
   It prints ONLY the token's SHA256 hash. No signing key is created or copied.
5. Add `brand_dispatch.enabled=true` and `brand_dispatch.token_sha256=PRINTED_HASH`
   to the site's existing config. Set `brand_builder.sources['2.0.115']` to the SAME
   reviewed SHA. Preserve all other settings; channels stay testing/trials OFF.
6. Start `brand-dispatch.service` ONCE. Empty queue must log
   `No eligible queued testing build; GitHub was not contacted.` Verify no new run.
7. Save/request one NEW testing-brand revision and run the service once. Verify
   exactly one Actions build is dispatched, the same job is not duplicated, and
   its prepared APK verifies. Publication remains an explicit exact-job approval.
8. Only after the pilot, enable `brand-dispatch.timer`. Timer interval is30seconds
   after each service completion, AccuracySec1second; not a build every30seconds.

The queue token is scoped only to a minimal read-only testing hint. Keep its raw
value/GitHub token private on the worker. The website stores only its hash. If the
ref moves or token/HTTPS/policy checks fail, polling fails closed. Do not resolve a
pin mismatch by using arbitrary/latest source or weakening signing verification.

## Restricted-account prerequisite: website0.14.1

Install0.14.1 before the owner website-bridge setup bundle. Established keys must
be read-only inputs;0.14.0 still reopened/chmodded them on access and cannot safely
share the original key through a narrow read ACL. Do not grant write access to
work around that. See deploy/WEBSITE_BRIDGE_SETUP.md and the0.14.1 site instructions.
On the confirmed website host use /www/server/php/84/bin/php, NOT incomplete system
PHP8.3. The root-owned fixed wrapper sends startup diagnostics to stderr and
disables CLI opcache without altering web PHP configuration.

## Site-owned delivery (website0.14.0 / app2.0.115)

Install website0.14.0 before enabling the connected builder. Add brand_downloads
enabled=true to the EXISTING private config.php, leaving trials OFF. The source pin
for NEW jobs is now sources['2.0.115']; never repin a previously claimed old job.
See control-center/deploy/UPDATE-0.14.0.txt for scoped storage access and optional
Nginx internal offload. The worker/website/GitHub remain separate systems.

After verifying GitHub retained/release bytes, the runner streams the APK through
the same restricted SSH bridge. One bounded JSON header carries the lease; the
server determines size/hash/path from the frozen receipt, verifies the stream and
rechecks the live lease before immutable installation. Enabled site delivery blocks
registry completion unless that exact local file exists. Recovery reuses/verifies
the original release bytes and repairs the same local copy, never creates a version.

Reseller APIs/UI expose only site paths. Metadata/download requests never contact
or redirect to GitHub. New branded APKs use the owner site's metadata endpoint and
reject foreign asset URLs/redirects; generic APK update routing stays unchanged.
Existing installed114 branded tests need one manual install-over-existing to move
to the new endpoint. They cannot be retroactively rewritten by a website update.

This hides provider details in ordinary UI/URLs, not an absolute secrecy promise:
a public repository may be independently discoverable, and APKs can be analysed.
Do not distribute public provenance/run URLs to resellers or claim anti-analysis.

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
   branch; require owner approval for production publication and disable self-review
   if available. Testing also defaults to manual approval unless the owner explicitly
   opts into unattended testing as described in the0.14.3 section above.
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

## Website setup (separate host, website0.14.0)

Preserve the sole private config.php, initialized storage and encryption keys. Check
migrations through017. Under brand_builder, enable pickup, allow testing only, and
pin sources['2.0.115'] to the FULL reviewed workflow/source commit. Keep
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
the build-only pilot. The current timer checks the website every30seconds using
`--poll`; an empty queue makes no GitHub calls. The old0.14.0 setup dispatched a
GitHub check every15minutes instead: replace that bundle before enabling this timer.

An owner-only Brand-Dispatcher-Setup.zip bundles the dispatcher/unit files and
install.sh. On the WORKER host, after installing system Python3 and gh, run
`bash install.sh <full reviewed source SHA>`. It creates only the dedicated service
account/directories, preserves an existing /etc/brand-dispatch.env, and does NOT
start or enable the service/timer. Edit the token privately on that host. Do not
upload this operator bundle into the website public directory or give it to resellers.

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
