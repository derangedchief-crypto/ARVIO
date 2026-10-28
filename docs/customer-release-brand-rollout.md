# Customer publication drives reseller-branded updates

## Contract

A successful **Publish Customer Release** run on `main` starts the production brand
rollout. A `main` push alone does not. Failed/cancelled customer runs, generic test
publication and artifact-only candidate builds do not authorize branded updates.

Every eligible live brand is queued from the **published customer's exact source**,
even if `main` advances later. Existing snapshot cloning, identity/signing checks,
version monotonicity, idempotency, per-brand failure isolation, tenant eligibility
and newer-brand-edit fencing remain in the existing builder/website pipeline.
Logo, artwork, display name, package and branded update channel remain brand-owned.
The generic customer APK is never installed over a branded app. Customers see the
update only after their brand-specific APK is built, verified and published.

## Trigger and verification

Before building, the customer workflow pins `brand-source-VERSION-SHA` while its
approved commit is still current main. If main has already moved, pinning fails
before customer publication. It rechecks the frozen tag immediately before
publishing and sets the customer release's target commit explicitly. An existing
customer version/tag belonging to a different commit is refused, never retargeted.

`Central Branded APK` now subscribes to `workflow_run: completed` for the customer
workflow on main. It requires success, the same repository and the owner rollout
enable flag. It checks out only the protected operator SHA, not the triggering code.

The operator reads the original run through GitHub's API, verifies the exact known
customer workflow path, main branch, workflow_dispatch event, completion, success
and repository. It then verifies the stable customer release, its single correct
uploaded APK/digest and the release tag's exact commit. It queues production only.
Its token has only contents/read and actions/read. It verifies the already-pinned
brand-source tag rather than trying to create an older workflow-bearing tag with
the post-publication Actions token. The approved 124 catch-up needed its missing
source tag pinned once by the authorized operator; no release assets were changed.

The website independently checks that production sources correspond to a published
customer APK and immutable source tag; it no longer substitutes current main for
an already-released commit. Testing fanout retains the current-main check.

Manual retry uses `mode=fanout` plus `customer_run_id=<successful publication run>`.
No unverified source SHA or unreleased main commit may be supplied as a substitute.
The existing source/channel cursor and per-brand build uniqueness prevent duplicate
jobs on retry. Existing monotonic checks reject older rollouts after a newer one.

## Activation / deployment — NOT performed by this staging change

Validation: all 92 branding/builder checks and all 246 isolated MariaDB service
checks pass; actionlint passes for both changed workflows. Coverage includes failed,
cancelled, foreign-repository and testing runs, unpublished/draft/wrong APKs, exact
release SHA matching, preserved branding snapshots and duplicate-safe queueing.
An already-registered testing source cannot bypass production publication checks.
No live rollout or per-brand on-device acceptance is claimed by these tests.

1. Review and approve the staging workflow, operator and website changes.
2. Promote only the intended changes; do not merge the unrelated temporary
   artifact-only `publish-release.yml` left on staging into production.
3. Update the protected `BRAND_BUILDER_OPERATOR_SHA` to the approved operator commit.
   The old pin still runs the old operator and cannot activate this new contract.
4. Deploy the website verifier change with the normal private-config/storage-safe
   website update process. No database schema or worker credential changes required.
5. Keep production builder/dispatcher policies enabled and review any environment
   approvals that intentionally pause branded publication. Workflow queuing is not
   proof that each branded APK has published.
6. After approval, deliberately replay customer run **40704795124** for 124 if a
   catch-up is wanted. It completed before this staged trigger was deployed, so
   installing the workflow later will not replay it automatically.
7. Verify actual per-brand receipts, APK identities/branding and channel visibility.

Do not change live variables, deploy services, trigger a catch-up, modify release
assets or publish reseller apps solely to test this staging change.
