# Main integration — publication held

This promotion integrates the reviewed platform work into `main`. It does **not**
publish a customer APK, release tag, reseller APK or updater-feed change.

## App boundaries
- The official app remains standalone. Its `BRAND_ID` stays empty, its existing
  `com.arvio.tv` / customer `.beta` / testing `.test` package identities stay, and
  it continues using its official release feed. No reseller branding, trial offer
  or renewal-payment UI is enabled for it.
- Centrally branded apps retain their own `com.extreme.brand.b<ID>` identity,
  `.test` separation, saved branding snapshot, merchant/workspace binding and
  per-channel website update feed. Provider-only features remain brand gated.
- Shared playback, data recovery, network/updater and compatibility fixes reach
  both distributions when their next separately approved APK is published.

## Prepared version, not a published release
`2.0.130` / base code `446` advances beyond customer `2.0.126` and already-built
`2.0.129` testing apps. Branded codes remain `base * 100000 + saved brand revision`;
the revision and artwork are not reset for a core update. No existing release is
replaced with different bytes under the same version. Android signing identity
remains the existing approved certificate; the publication workflow refuses a
different certificate before building/publishing.

## Publication controls
- A merge/push cannot run the customer publisher: it is manual-dispatch only,
  restricted to `main`, with `confirmed_publication=false` by default.
- A distinct **Build Unpublished Customer Candidate** workflow may retain an
  Actions artifact when deliberately dispatched. It has read-only repository
  permission and cannot create a release/tag/feed or trigger branded fanout.
- The customer publisher checks out its exact dispatched commit, verifies real
  cloud build inputs, pins the immutable customer/branded source before building
  and rechecks the pin immediately before publication.
- Only a successfully published, source-verified customer release can authorize
  production brand fanout. Fanout has contents/read + actions/read and verifies
  the existing source tag; it does not create or retarget tags after publication.
- Testing-only source registration is not production approval. Both the reseller
  source selector and builder enforce the channel's registered rollout. A source
  older than the installed branded core is refused before queuing a manual build.
- Historical owner-pinned source versions remain usable where appropriate;
  preparing a newer core does not silently downgrade their fallback to 2.0.115.

## Before a future customer release
1. Obtain explicit publication approval. Do not dispatch either publication or
   production fanout as part of this code-only promotion.
2. Install the matching website backend update before approving new rollout
   sources, preserving private config/storage, database, APKs and socket repair.
   Website 0.36.0 adds channel-isolated source selection and fixes footer version
   rendering; there is no new schema migration or worker change for it.
   When reseller fanout is enabled, publication now checks this website version
   through its public read-only status API and refuses an old/unavailable control
   system before creating a customer source pin or publishing an APK. Disabling
   reseller rollout does not change the standalone app's independent update feed.
3. Check signing/package identity and increasing version codes against the actual
   installed distributions. Test an in-place update without uninstalling or
   clearing credentials, device identity, playlists, favourites or settings.
4. Keep standalone and reseller feeds distinct. Production activation/payment
   controls remain explicit owner decisions, not a consequence of merging code.
5. When separately approved, dispatch **Publish Customer Release** on `main` with
   publication confirmation ON. Only after its verified customer publication may
   the existing approved production fanout register/queue reseller core updates.

No test suite can guarantee every OEM installer, server, network or credential
will remain available. These controls fail closed rather than publish an
unverified/downgrade/cross-package update or ask users to erase saved app data.
