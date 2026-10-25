# Shared-main updates with immutable reseller branding

## Scope and current release boundary

One protected `main` source serves every branded app; no per-reseller/revision branches.
Every supported **new platform version** automatically queues one update per enabled,
active, verified workspace with an existing approved testing APK. It reuses that LIVE
APK's name, artwork, brand revision and package; unpublished branding drafts are not
released. Central signing and saved-data identity remain unchanged. A draft-brand
build explicitly requested later uses the newest registered main source, not115.

**This rollout is testing-only.** Production environment review, website channels,
customer latest and generic workflows are unchanged. This is not permission to ship
production APKs. Existing testing identities remain `.test`/`-test`; separately
authorized production identities stay clean. Never rename an installed identity.

Current main has older app source. It MUST first integrate compatible branding support
and a real next release (platform version name >2.0.115 and code >431, matching both
Gradle platform constants and generic literals). Until then, the fanout exits without
creating a tag/job/APK. Do not bump versions or create a dummy build to demonstrate it.
Repeated same-version main commits are refused rather than silently replacing bytes.

## Trust and flow

The existing branded workflow gains the `main` push trigger; generic workflows are
not modified. The queueing job checks out only the owner's reviewed operator SHA
from repository variable `BRAND_BUILDER_OPERATOR_SHA`. It reads and checks the actual
main source/version, creates the lightweight source tag
`brand-source-VERSION-FULL_SHA` once, then calls restricted SSH `fanout` in bounded,
idempotent100-brand batches. The website independently verifies GitHub main/tag/file
identity before registering a new source. Registered identities cannot change.

The existing30-second dispatcher polls the authenticated website. Empty queues still
make zero GitHub calls. A main-sourced hint carries its exact registered SHA/tag; the
worker checks tag resolution before dispatching the exact job, using existing active-
run and durable five-minute fences. The branded build checks out `github.sha`, builds
and signs in GitHub, retains/verifies the original bytes, mirrors them to the site and
updates the existing brand registry. All existing signer, monotonic-version, lease,
retry-cap, uncertain-publication and local-delivery gates still apply. Production may
not claim these testing-pinned jobs.

## Consolidated operator deployment

1. Deploy the website update without replacing private `config.php`, keys or storage.
   On WEBSITE run the existing finalizer with its new optional flag:
   `bash deploy/enable-testing-automation.sh REVIEWED_FULL_SHA --main-updates`.
   It applies migration018 then enables testing fanout; other config remains intact.
2. In the dispatcher bundle run `bash upgrade.sh REVIEWED_FULL_SHA` as root on the
   existing worker. It drains the dispatch lock, atomically updates only code/source
   pin and restores the prior timer state, preserving tokens/state/testing opt-in.
3. Set `BRAND_BUILDER_OPERATOR_SHA` to that tested source SHA. Register ONLY the updated
   `.github/workflows/central-brand-builder.yml` on main, keeping the old application
   intact until a separately reviewed real source integration. That registration push
   must skip fanout because old main is unsupported; it does not release a client APK.
4. Protect main and source tags. GitHub rules for `brand-source-*` must prohibit updates
   and deletion while allowing the workflow to create new tags. Configure the existing
   `brand-build-only` environment to allow main (queueing only), the operator branch and
   source tags. Allow `brand-source-*` in `brand-testing-publication` only. Preserve all
   production reviewers/restrictions. These are environment ref changes, not approval
   to publish production. Never give resellers repository/runner/secret access.
5. When the next real compatible release lands on main, inspect its queue/run and
   verified site updater metadata. No repeated artwork revision/device-install test
   is needed. Resuming `fanout` deliberately on that exact source is idempotent;
   completed batches queue zero duplicates. Uncertain publication still reconciles
   the same retained bytes, never a blind rebuild.

Not yet deployed by local tests: source registration, main application integration,
repository environment/tag-rule changes and website/worker upgrades must all be
verified before calling automatic main updates live. Production remains outside this
testing rollout; it needs a separate owner-authorized release policy.
