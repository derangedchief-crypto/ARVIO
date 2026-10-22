# Extreme Control Center

PHP 8.3 / MariaDB backend and responsive owner/reseller dashboard for
`https://control.fecxbrfzku.net`, delivered as an aaPanel Nginx upload/extract ZIP.
Work is isolated on `reseller-control-center`; no changes to the Android app,
activation/maintenance services, central updater, `main` or publishing workflows.

## Current milestone — 0.7.0

### Favourites recovery: good versions survive an accidental wipe

Testing client111 captures nonempty Live TV channel/group favourites on managed
foreground check-ins. Distinct versions are appended, not overwritten. An empty
customer state updates live counts ONLY and can never become a replacement backup.
The initial nonempty version per profile is protected automatically (within pin
limits); operators can label/protect other known-good versions. Protected versions
are never automatically pruned. Rolling versions have bounded count/retention.

In a device's **Favourites** tab, view saved versions/current reported counts,
request a manual capture, protect a version, then **Preview recovery on the app**.
Preview checks the SAME installation/profile/source, resolves channel/category
identities against that profile/source cache and reports unavailable entries.
**Merge back** is default: existing favourites/order remain and missing saved ones
are appended in their saved order. **Replace** is explicit and refused if ANY
saved entry no longer resolves. Nothing is silently matched by a partial name.
Current favourites or channel-availability changes after preview reject restore.
Expired previews require a fresh one. Restores update both lists in ONE guarded
DataStore transaction and do not change playlists, guides, visibility, player or
update settings.

Before a restore the app must successfully capture current state as a safety
checkpoint (nonempty state gets a separate pre-restore version; empty is safely
ignored). Queued is not restored: UI waits for app receipts, and live counts update
on the next actual check-in/capture. Recovery is foreground/check-in based, not
Android offline waking. Maximum1000 combined favourites per snapshot/restore.

Backups are server-side AES-GCM encrypted with the existing private application.key.
Only the device context endpoint returns original identities to THAT device token;
browser views expose sanitized labels/opaque refs, never original IDs or ciphertext.
Source bindings/HMACs prevent unrelated profile/source replay. Saved groups that
contain sensitive-looking text have masked labels, but their necessary recovery
identities remain encrypted. Preview responses use a separate short-lived table
and command history contains only small summaries; large response bodies never
fill the app duplicate journal.

Limits:100 total backup versions/device,20 protected/device,5 protected per
profile/source,32 live status contexts. Protected versions survive normal retention;
unprotected versions follow the configured retention/count policy. Empty updates
do not delete any good version. User labels/protection changes are audited.

This cannot reconstruct favourites deleted BEFORE the first successful capture.
Open111 with known-good favourites first and verify a protected server version
before recovery testing. A different installation/cleared app data/profile/source
is NOT guessed as an equivalent restore target. Device/profile migrations need
their own explicit workflow; encrypted server identities are retained for future
authorized recovery tooling rather than tied to a lost device-only cipher key.
Automatic unchanged-state refreshes are coalesced for six hours; actual changes
are captured at check-ins. A change-and-wipe entirely between captures cannot be
reconstructed, and stopped/unmanaged apps do not upload backups.

### Verified line usernames; automatic customer UI

Device/customer lists show the verified LINE USERNAME, not the numeric `Panel line`
placeholder.111 reports its saved login username at check-in; the backend verifies
it against the activation worker's previously signed HMAC account reference before
storing/displaying it. A spoofed username cannot rename another line. Existing
records fill in on their next111 check-in (Settings -> check in now); until then
automatic placeholders say the username is awaiting verification. No worker patch
or panel/SQL credentials are needed. Numeric panel IDs remain internal ownership
keys. Customer search includes verified usernames, including owner-unassigned devices.

Manual Add customer buttons are removed from Customers/Resellers. Verified linked
enrollment creates records; existing notes/reference editing is retained. Internal
fixture/service APIs remain scoped, not public self-claim registration endpoints.

Install **0.7.0 UPDATE ZIP and migrations008/009** before the111 testing APK. Preserve
config.php/storage/application.key. Keep the control site's existing2m request limit
from0.6.0. No new Android permission or worker change. Billing/branding, cross-device
favourite migration, full playlist editing and advanced diagnostics remain later work.

### Organize locally, then push ONE complete layout

The110 testing client replaces the slow one-command-per-category workflow. In a
device's **Categories** tab, read one complete cached Live TV source layout (up to
2000 categories). The website then edits a LOCAL draft: drag/drop rows, enter an
absolute position, move selected blocks to top/bottom, filter/select across local
pages, bulk hide/show, or use provider order. None of those edits send a device
command. **Push entire layout once** queues ONE atomic order/visibility update.
The app still needs to be foreground/reachable; this is not an OS wake/push service.

Pushes validate the complete category set and the original profile/source/catalog/
preference revision, then write both preference lists in one DataStore transaction.
No partial page is treated as a replacement. Other sources, unavailable saved keys,
favourites, login/source credentials, guides and playback/updater settings remain
intact. Provider-order mode explicitly clears only that source's custom ordering.
Mutations retain persist-before-effect duplicate protection. Large layout deliveries
are exclusive so the app never receives five huge layouts in one heartbeat.

Drafts survive SPA navigation, command errors and failed/stale pushes in browser
memory. A fresh read never silently replaces a dirty draft. Explicit **Rebase**
previews added/removed categories, preserves your changed order/visibility, adopts
fresh visibility you did not edit and appends new categories. Changed profile/source
refs cannot be rebased by guessing: save a template and explicitly load the new
source instead. Save a template before page reload/closing; unsaved drafts trigger
a leave/sign-out warning and are cleared on logout/session expiry for privacy.

After a successful push, the UI requests one fresh read and clears the draft only
after confirmation. It does not pretend queued changes are applied. A small status
poll runs only while work is pending, not while you are simply organizing a draft.
Interactive command deliveries temporarily request a15-second next check-in
(`interactive_heartbeat_seconds`, optional in the SAME config.php); idle intervals
return to the configured normal heartbeat. No new permanent service is required.

### Custom playlist/category templates under Settings presets

**Save as layout template** saves the organized draft's safe full category names,
order and visibility without waiting for/pushing the device. Templates are workspace
scoped, named, version-guarded and archivable (100 active per workspace). Settings
presets now lists both the older channel-sort presets and full layout templates.
Owners may explicitly copy a template to another workspace; nothing is globally
shared or automatically pushed.

On another device/source, read its complete layout and **Preview / apply saved layout
template**. Exact complete category names map to THAT device's opaque refs, never
the source device's IDs. Preview shows matched/missing/target-only categories.
Missing template categories are skipped; target-only categories follow matched ones
in their existing order and keep their visibility. Order-only application is also
available. Ambiguous matches are rejected, not guessed. Shortened/redacted names
are explicitly excluded when saving because they cannot be matched reliably.
Applying a template changes only the browser draft until you press Push.

Migration007 adds a bounded full-snapshot cache and durable templates. Only the
latest three full snapshots per device are retained; command history keeps small
read summaries. Full metadata is served through tenant-authorized dedicated routes,
not embedded in every command-history page. Full uploads are authenticated before
decoding and bounded to2MiB; batch pushes are bounded to512KiB. Ordinary endpoint
limits remain32KiB. Template archives follow normal configured retention.

Deployment: install **0.6.0 UPDATE ZIP**, back up/apply migration007 under Enrollments,
and change the CONTROL WEBSITE's Nginx `client_max_body_size 64k;` to
`client_max_body_size 2m;` if it uses the old snippet. PHP post_max_size should be at
least2M (defaults are normally higher). No worker change. Then install the110 testing
APK;109 retains its old per-command controls until upgraded. This is Live TV category
layout management, not manual channel-row sorting or movie/series library editing.

### Remote category management (testing client 2.0.109)

The device **Categories** tab now requests a typed cached Live TV category page from one
active profile/source. It lists visibility, channel counts and current positions;
operators can hide/show, move up/down/to top, or reset custom ordering for that
source. Resetting order does not reset visibility, favourites or another source.
After a queued change succeeds, request a fresh read to confirm it: existing
tables are labelled snapshots, not live or assumed-applied state. The selected
device tab remains open after queue/refresh operations.

Source choices use generic Playlist/Portal labels, not provider URLs or user-entered
source names. Row/source references are opaque device-keyed HMACs; a private random
catalog salt stays in the app's encrypted no-backup session. Even source identities
containing credentials are never exported as public dictionary-guessable password
hashes. Titles are shortened to80 Unicode codepoints and obvious URL/credential
assignments are masked both app-side and server-side. Raw channel URLs, MAC/login
configuration and playlist keys never leave the device through this schema.

Reads paginate50 categories at a time (up to2000 categories/source,32 active
sources). Unavailable caches require opening Live TV/refreshing the playlist;
they are not guessed from another profile. Invalid/ambiguous legacy preferences
are not overwritten remotely. Legacy custom ordering may require a local migration
before reorder controls are enabled. A20-second cooperative timeout keeps a stalled
catalog request from deliberately monopolizing the foreground management loop.

Writes bind the profile/source, complete selected category catalog and current
preference revision. The app rechecks them inside an explicit-profile DataStore
edit, preserving other sources, unknown saved keys, favourites, activation and
retained guide data. No network guide import/cache purge or player operation is
triggered by category edits. Source/profile/account/preferences/catalog conflicts
fail safely. Mutations use the existing persist-before-effect duplicate journal.
Large read pages are NOT stored in that journal: on retry they may be reread only
against the original profile/source/page target. Mutations never replay blindly.

The backend accepts closed typed catalogs only for successful get_categories
commands and enforces requested profile/source/page echoes. Set-category success
is a mutation receipt, not a fabricated fresh read. Standard tenant authorization,
capability/lease checks, cancellation and retention apply. Operator-supplied refs
cannot add categories, change sources, URLs, entitlements, updater settings or
perform arbitrary Android operations.

Deploy the **0.5.0 website UPDATE ZIP before the109 testing APK**. If already on
0.4.0, this increment needs no new database migration; apply any older pending
migrations if Enrollments shows them. No worker patch or Android permission changes.
Linked-reseller-only admission, owner unassignment without blocking, previous
settings/presets and the first-activation lifetime fix remain intact. Full playlist
editing, favourite synchronization, log/screenshot permissions, billing and branding
remain subsequent work. Physical-device category testing is still required.
This increment does not edit movie/series library categories.

### Linked-reseller admission only (future paid-service boundary)

An outside panel reseller does NOT automatically get a place in this service.
The owner must create the control-center workspace AND verify/link its panel
reseller ID first. A valid app login from any other reseller returns `not_managed`:
no pending request, customer, device, poll token, proof journal or per-device audit
entry is created. Matching display names and legacy proofs without verified
ownership cannot bypass this rule. This is an owner-controlled admission boundary,
NOT implemented billing, payment collection or subscription-expiry enforcement.

The app's IPTV activation/viewing is unchanged. The management agent backs off for
an hour while foreground, persists that backoff across restarts, and allows
**Settings -> Check in / retry management now** after an owner links the reseller.
Older 107 clients still cannot create unmatched rows, but do not understand the
new quiet status/backoff as well as 108. Temporary challenge/rate-limit security
state is bounded/expired; it is not a device/customer discovery list.

Migration 005 expires legacy never-assigned enrollment deliveries. Unverified
legacy requests are hidden and cron prunes them; existing approved records,
customer/device data and audit history are preserved. Owner-approved/unassigned
devices remain intentional owner inventory and can renew their own credentials.
They do not grant a new unknown installation admission. Only admitted reseller
ownership/allocation exceptions enter the review queue. Creating a workspace
without linking it is deliberately insufficient.

### More management functions

- **Remote channel sorting:** request a fresh Settings snapshot, then choose
  provider order / channel number / channel name. The command targets its hashed
  active-profile reference and expected settings revision. The app checks the
  source account and compare-and-sets that profile's sort key inside one DataStore
  edit. A profile/account/local-sort change rejects the update instead of silently
  overwriting another profile. Credentials, playlists, favourites, guide caches,
  decoders and update settings are not writable through this allowlist.
- **Settings presets:** create/edit/archive named channel-sorting presets under
  the workspace-scoped Settings presets page. Up to 100 active presets per
  workspace; list/search/pagination and normal archive retention are provided.
  Saving/editing a preset does NOT push anything. Apply one explicitly from a
  device's Settings tab after obtaining a fresh revision-bearing snapshot.
- **Cancel queued commands:** the requesting user or owner may cancel a pending
  or delivered command. A foreign reseller/user cannot cancel another user's
  command. Cancelled leases cannot upload results/start via the 108 preflight.
  Running/finished actions cannot be recalled, and already-started work is not
  claimed to be reversible.

0.4.0 retains the 0.3.0 unassignment and 108 first-activation fixes below. Install
the **0.4.0 website UPDATE ZIP** and apply migrations 005/006 under Enrollments
before the combined centrally signed 108 testing APK. The existing ownership
worker bridge is compatible; no additional worker update is needed. Presets
currently configure sorting ONLY; full playlist editing and favourite synchronization,
log uploads/screenshots and billing remain separate later increments.

### Unassign reseller without blocking the app

Owner device Overview now separates **Remove from reseller — keep app working**
from **Block app / Revoke enrollment (locks app)**. Unassignment moves that ONE
device into the owner-only `Unassigned devices` customer, sets management policy
active, cancels pending/delivered/running commands and removes former reseller
access immediately. The composite command-history FK cascades into the owner
workspace without deleting history. No app login/profile/guide data is wiped.
The app revalidates a command lease before starting it. Cancellation prevents new
delivery/results; an operation that already started cannot be atomically recalled.
Existing valid credentials continue to check in; previously revoked credentials
stay revoked and recover only through the existing signed enrollment exchange.

An explicit detached marker keeps later proof/credential renewals owner-managed;
the panel's original account mapping still applies to other installations of that
line. Suspending the former reseller does not suspend the detached device. This is
management assignment only: it does NOT transfer the IPTV line in the external
panel or prevent its panel reseller from changing the underlying subscription.
Owner re-assignment of detached devices is separate future transfer work.

### Next remote-management increment

Testing client 2.0.108 adds `refresh_playlist`, `reload_epg`, `get_settings` and
`get_diagnostics`. The Settings/Logs tabs show requested read-only snapshots with
their receipt time; unknown/stale results are not presented as live. The typed
closed schema rejects passwords, URLs, raw logs/files, unsupported fields and
impossible counters. Only hashed active-profile identity, safe enums/counts and
basic app/storage/guide state are exported. Snapshots use the same tenant-scoped
leased command/acknowledgment flow and existing retention.

Playlist reload uses the existing repository with NO cache purge or config write,
and a bounded 45-second wait. A guide action requests/reuses the shared background
job and ACKs only that request: it is NOT proof that a full guide import succeeded.
Existing listings remain available. Action receipts are persisted before side
effects so retries cannot repeat an expensive action blindly. Account/profile
changes cancel/discard inappropriate results. Full remote configuration editing,
playlist URL changes, favourite synchronization, log uploads and optional
screenshots remain later increments; their controls are not falsely enabled.

The 2.0.108 first-activation fix keeps the initial cold-start policy read but does
not re-enter that gate when saving the first activated playlist. That transient
gate previously removed the NavHost and could cancel activation mid-submit,
causing a second code prompt. Explicit known restrictions still replace/dispose
the app/player UI. Physical first-run confirmation is part of the next pilot.

Deployment: back up and install the **0.3.0 website UPDATE ZIP**, then apply the
database upgrade under Enrollments (includes migration 004). Existing 107 clients
can use reseller unassignment immediately; the new remote capabilities and
activation fix require the centrally signed 108 testing APK. No additional worker
patch is needed for these changes. If 0.2.1 ownership was not installed yet, keep
its separate activation-worker update step; do not alter the auth/EPG workers.

### Verified panel reseller ownership / automatic enrollment

The uploaded Overcast auth worker confirms `customers.member_id -> users.id`.
The activation worker now includes the database-read customer/reseller IDs in the
existing signed enrollment assertion. No APK field is accepted as ownership.

Upgrade the PHP website and apply migration 003 under Enrollments, then update
the EXISTING activation worker on its separate server. The reseller portal AUTH
worker and the EPG worker are not patched. The same bridge settings/key are reused;
no new PHP config file, SQL access from the browser or new APK is required.

As owner, use **Resellers -> Link panel reseller** for existing workspaces (including
the owner workspace if it owns panel lines). The form defaults to the workspace
name; enter the exact PANEL LOGIN username. For new workspaces, use the **Existing
panel reseller username** field in Create reseller. A blank field deliberately
creates an unlinked workspace that cannot admit new app devices. The worker verifies a unique active,
exact-case username through a signed server-to-server lookup, and the backend
permanently stores its numeric panel ID. Names alone never authorize enrollment.
Each panel reseller may link to only one workspace, and vice versa. Workspace or
panel username changes do not change the stored ownership ID. Re-linking to a
different identity is not a supported transfer operation.

For linked active workspaces with capacity, new verified devices automatically
receive a customer record (`Panel line <id>`) and a device credential. Repeated
installations of the same panel line reuse the customer record. Unknown/unlinked
resellers are ignored, not queued for review. Conflicting admitted assignments,
rejected requests and existing/removed installations are not silently transferred
or resurrected. Allocation exceptions from admitted resellers may require review.

Linking a reseller expires outstanding pending deliveries so existing 2.0.107
apps automatically obtain fresh proofs on their next polls. Keep the app open
for a few minutes. It does not clear login, profiles, favourites, guide data or
already-approved device credentials. Existing approved devices are not silently
reassigned when adding a link. Transfers remain a separate owner-reviewed action;
this does not claim instantaneous synchronization of panel ownership changes for
all already-enrolled/offline devices.

- One-time owner setup, private generated keys, password hashing, secure sessions,
  password change/session revocation, origin/CSRF enforcement and rate limiting.
- Reseller workspaces with owner-controlled allocations/suspension; scoped customer
  records and pending device provisioning, paginated search, notes, activity audit.
- Independent presence versus authorization policy, explicit test-device labels,
  expiring allowlisted commands, idempotency keys, leased delivery and acknowledgments.
- Authenticated check-in/ack API contract, encryption/redaction primitives, checked
  SQL migrations and cron retention. No arbitrary commands, APK URLs or remote shell.

The device contract currently accepts `schema_version: 1` (omission is treated as
version 1 for local tooling). Unsupported versions are rejected before command
delivery or acknowledgment; check-in responses identify their schema version.

### Owner-reviewed real device enrollment

Management-enabled app builds obtain a short-lived account assertion from the
existing activation worker and prove possession of an installation P-256 key.
Verified linked panel accounts enroll automatically as described above. The
control-center owner reviews admitted conflicts and allocation exceptions, not
devices from outside/unlinked resellers.
No reseller/customer ownership ID submitted by an APK is accepted, and no unknown
panel columns or external panel schema changes are required. Pending requests are
not presented as connected devices or given management credentials.

After upgrading the website, open **Enrollments** as owner. Apply the versioned
database upgrade through that page, download the worker bridge settings, deploy
the separate source-only worker update, and enable enrollment. The worker retains
its existing `.env`, SSH/DB settings and six-digit activation behavior. The
website still has exactly ONE `config.php`; no extra PHP configuration is needed.
See `activation-code-worker/MANAGEMENT-BRIDGE-UPDATE.txt` in the repository for the
worker deployment/Nginx steps.

The Android client reads existing eligible activation credentials without asking
customers to reactivate, uses Keystore-backed signing/encrypted no-backup storage,
and checks in only while its activity is started. Support installation IDs/status
are visible in Settings. Profiles sharing an activated account reuse its binding;
different accounts have isolated identities and tokens, not immutable hardware IDs.
Device key possession is NOT official APK/hardware attestation.

Already-approved account/key identities can renew credentials without another
device slot. Renewal never clears suspension/block policy, and a removed device
requires owner review again. A rejected enrollment does not erase/revoke the
customer's underlying IPTV login. Owner-approved account-to-customer mappings
cannot silently be reassigned to another reseller by a new request.

Initial real-client capabilities are `request_checkin` and `show_message`, plus
authenticated policy suspension/block/removal and restoration. Cached known
restrictions persist encrypted; an unknown/outage response does not invent one.
The message/deduplication journal is committed atomically. Unsupported remote
actions stay disabled instead of being falsely acknowledged as executed.

Older APKs do not gain these capabilities without an update. Provisioning a record
alone does not make an app online. Local simulations remain explicitly marked TEST
and their credential issuer remains CLI/test-only, loopback-only, never HTTP.

Playlist editing, full settings profiles/per-device overrides, favourite sync, raw diagnostic
file/log uploads and screenshots are later milestones. Their unsupported controls
remain unavailable. Read-only settings/basic diagnostics and refresh requests are
implemented as described above. Android permission/OEM limitations still apply. Rebranding,
launcher icons/names and branded APK generation are deferred. Every customer and
reseller continues to use **our existing centrally controlled update source**.

## Hosting

Read [aaPanel installation](deploy/INSTALL-AAPANEL.txt) and the Nginx snippet.
Serve **only `public/`**. All runtime secrets/data remain in the parent directory.
PHP extensions: PDO/pdo_mysql, mbstring, OpenSSL, cURL, fileinfo. No Composer, Node,
Python or permanent queue/WebSocket service is needed on the hosting server.
There are no third-party production PHP packages in this initial milestone.

The install ZIP already contains **one `config.php` beside `public/`**. Open that
file, enter the database password privately and reload. Host, port, database/user
and origin are prefilled for this deployment. No copying/renaming is required.
Never commit a filled config.php, publish an initialized archive or put secrets in
URLs. The DB must be dedicated
and empty on first setup. Backup/restore instructions include both DB and private
keys; a new encryption key cannot decrypt a restored database's previous ciphertext.

## Local development / integration tests

The Ubuntu 24.04 helper extracts official distro PHP/MariaDB packages into a
user-owned directory. It does not need sudo, a Docker socket or system installs:

```sh
bash control-center/dev/bootstrap-ubuntu.sh
python3 control-center/dev/run-integration.py
# This starts a NEW private socket-only DB for the run and stops it afterward.
node --check control-center/public/assets/app.js
python3 control-center/tools/package.py
```

The test runner creates only randomly named `control_test_*` databases through the
local Unix socket, tests actual MySQL/MariaDB constraints and real HTTP endpoints,
and drops only those generated databases. It creates an isolated application copy
under `/tmp/opencode`, never overwriting `config.php` in this checkout. Test
credentials are synthetic, not defaults, and tests/dev tools are excluded from ZIPs.
Keep generated logs/backups private. Package hashes are recorded by the tool helper.

Optional real-browser checks use pinned Playwright 1.64.1 / Chromium, installed
outside the website with `bash control-center/dev/bootstrap-browser.sh`.
`bash control-center/dev/php.sh control-center/dev/demo.php` creates a NEW private
local preview directory/database with explicitly synthetic customers/test devices;
it prints its directory, not its generated password. Start PHP on 127.0.0.1:8186
with that directory's `public/` root and `dev/router.php`, then run:

```sh
LD_LIBRARY_PATH=/tmp/opencode/control-center-browser-tools/root/usr/lib/x86_64-linux-gnu \
  node control-center/tests/browser.cjs /PRIVATE/PREVIEW/PATH/preview-credentials.json
```

The browser suite refuses non-loopback URLs and non-preview credentials. It checks
actual owner/reseller login, literal rendering of hostile customer text, device
provisioning, disabled unsupported actions, queue visibility and mobile overflow.
Preview data is not evidence that a real Android management client is connected.

Tests cover tenant boundaries at service and HTTP levels, cookie/CSRF/origin checks,
private-file exposure, tampered requests, allocation/command state, revoked keys,
retries, suspension, encryption, migrations, retention and database backup restore.
See `test-results/integration.json` for the latest actual run; unsupported/unrun
platforms and real Android behavior are not inferred from these backend tests.

Live aaPanel/SQL connectivity and remote-server TLS have not been tested. No live
database credentials or production secrets are needed for local development/tests.
Delivery includes the repository's Apache-2.0 LICENSE and the complete PHP/JS source.
