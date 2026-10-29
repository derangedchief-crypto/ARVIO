# Extreme Control Center

PHP 8.3 / MariaDB backend and responsive owner/reseller dashboard for
`https://control.fecxbrfzku.net`, delivered as an aaPanel Nginx upload/extract ZIP.
Current development and delivery use `staging/testing`; updating `main` requires
separate explicit owner approval. Website delivery never publishes a customer APK.
The site archive never installs an APK or worker. Native source changes require a
separate centrally signed app release; shared build/source pins are not moved here.

## 0.27.0 — renewal settings preview and one-brand testing queue

See `deploy/UPDATE-0.27.0.txt`. Migration026 adds renewal settings/orders with payment
rollout OFF and a separate immutable staging-testing source registry. The owner CLI
can queue one existing branded TESTING identity from its last published artwork,
without changing private config or main/production sources. It never publishes by
itself. Rampex live API calls and worker writes remain disabled; the no-money
simulator is restricted to an isolated localhost staging website. Installing this
website update does not enable a live QR pilot or install any worker/APK.

## Current source update — 0.25.0 primary-line and playback safeguards

Compatible client source pins the account established by verified activation-code
entry separately from editable sources. An expired/invalid primary blocks ordinary
app use and controlled playback. Extra Playlists licensing now gates USE of all
saved extras, not merely creation; there is no exemption based on customer/source
age. The valid primary remains available without the optional add-on.

Outage grace is capped at15 minutes since successful verification and by any known
earlier expiry. Confirmed negatives discard permission. Positive grants are kept
only in memory: process/device restart requires online checks, not new offline
time. Primary checks combine the fixed provider and activation-worker validation.

Legacy installs without a trustworthy binding need one activation-code confirmation,
retaining extra source IDs and data. A retained fourth source is supported when
confirmation must add the real primary beside three legacy entries. Cloud/profile
source edits cannot replace the protected binding. Uncontrolled stock casting is
unavailable until a receiver can enforce expiry after the app is gone.

The separate worker update is targeted/read-only and preserves existing config,
environment, keys and trial ledger. See `deploy/UPDATE-0.25.0.txt` for the ordered
worker/website/testing-client rollout. No new migration beyond024 is needed.
Keep enforcement OFF until controlled validation. Native118/434 is not a customer
release merely because its source is prepared. Old/modified APKs are not claimed
to enforce the safeguards. Customer117 remains unchanged by source preparation.

## Previous 0.24.1 PHP update/cache compatibility

Release ZIPs now use distinct, reproducible timestamps per version instead of one
shared timestamp. This lets PHP OPcache notice overwritten routers/classes when
timestamp validation is enabled. Restart the website's PHP8.4 service after the
affected update to clear any existing cache; installations that disable timestamp
validation need a PHP restart on backend updates. See `deploy/UPDATE-0.24.1.txt`.
No licence semantics, stored keys, migration024 or client APK changes are included.

## Previous 0.24.0 monthly reseller Add-ons

Extra Playlists is the first monthly workspace-wide module. Owner issues keys;
resellers activate them for all their customers/devices. Owner renews, suspends,
reactivates or permanently revokes. Keys are shown once, stored hashed, scoped to
one workspace/module and cannot renew themselves. UTC calendar months are clamped
at month-end. No payment processor or automatic charging is included.

Migration 024 defaults enforcement OFF. Only deliberately enable it after a
compatible app update has been tested. New source previews, queueing and device
contexts are server-checked; native additions/managed restores/cloud imports are
checked at persistence too. Existing playlists, activation and viewing are not
revoked by add-on expiry. No implicit owner exemption exists.

See `deploy/UPDATE-0.24.0.txt`: extract the update and use the existing owner
database-upgrade action. This does not publish a client APK, update workers, or
retroactively protect existing customer 2.0.117 installations. A later signed
client release needs a higher native version and separate publication approval.
Client source is staged at 2.0.118 / 434; it is not a published customer update.

## Previous 0.23.2 current status, not growing logs

Working screens show active requests or just the latest result from the last
24 hours, rather than old command tables. Active requests are paginated, current
setup work remains actionable, and old support reports no longer fill the screen.
The Activity log is a paginated last-24-hours view. Saved state, protected backups
and full stored audit/command history are retained; nothing is erased to declutter
the interface. See `deploy/UPDATE-0.23.2.txt`; extract-and-reload from 0.23/0.23.1.

## Previous 0.23.1 visible device access actions

Owner removal actions are now in an expanded **Device access** card on Device ->
Overview, before customer notes, rather than buried in secondary details. Existing
backend permissions and semantics are unchanged: unassigning keeps app access and
an owner-managed record; removing enrollment revokes app access. Owner-managed or
already-unassigned records explain why no reseller assignment needs removing.
No inventory hard-delete or new no-block unenrollment operation is introduced.
See `deploy/UPDATE-0.23.1.txt`; extract-and-reload only from0.23.

## Previous0.23.0 compact task-based interface

Website-only presentation cleanup across dashboard, customer/device lists, device
controls, app branding/downloads, saved layouts/settings/setups, trials/contacts,
activity and owner tools. The existing blue light/dark palette is retained with
smaller headings, cards, forms and navigation spacing. Mobile inputs retain readable
text and44px action targets; data tables/long values stay inside their own containers.

Navigation and primary actions use task names. Saved playback settings, saved device
setups and automatic setup are distinct, and saving a template does not apply it.
Device changes follow load -> review -> apply with actual receipt confirmation.
Playlist forms show only the selected connection method rather than mixing link,
Xtream and portal fields. Protected activation credentials remain non-editable.
Trials and contacts have separate views. Repetitive static explanations leave the
main workspace; optional details retain technical rules, while missing prerequisites,
errors, consent and consequential action confirmations remain explicit.

No backend policy change, new migration, APK or worker update. Global package/pause
controls, reseller-owned switches/daily limits, owner Unlimited, signing/source gates,
private credentials, trial/device history and tenant isolation remain unchanged.
The normal0.22 ->0.23 update is extract-and-reload, with no terminal command.
See `deploy/UPDATE-0.23.0.txt` for installation and `test-results/compact-metrics.json`
for optional local before/after measurements (synthetic fixtures, never production).

## Previous0.22.0 global approval and reseller self-service

This supersedes0.21's per-reseller admin model, following the user's correction:

- Admin controls **one global pause/resume switch** and **one global approved
  trial-package list**. Approve packages once. There is no reseller enable step
  and no admin-assigned reseller daily limit.
- Every active, verified, panel-linked reseller chooses an available globally
  approved package, **My daily trial limit**, and its own app ON/OFF setting,
  then **Save my trial settings**. A registered signed branded APK is still needed.
- Owner app stays Unlimited. Global pause overrides all app preferences without
  deleting them. Existing trial history, contacts and anti-duplicate rules remain.

Global approval intersects the real package list available to each reseller's
panel account; it never bypasses panel group restrictions or changed costs. The
upgrade imports existing owner-approved terms once, not a union of arbitrary
reseller approvals, and never overwrites later global edits on reruns. New apps
are not automatically switched on. Ordinary unchanged terms remain update-stable.

Install0.22 directly over0.20 or0.21 using `deploy/UPDATE-0.22.0.txt` and its one-time
website helper. Private config/storage are excluded; no APK or worker update needed.

## Previous0.21.0 model — superseded, do not use for new setup

The current UI replaces the old catalogue/default/publication setup cards:

- Admin: select a workspace, turn **Allow trials** on, choose allowed packages and
  a daily limit, then **Save trial access**.
- Reseller (or admin acting for it): choose a **Trial package**, set **Show trial
  button in this app**, then **Save trial settings**. One transaction saves the
  default and its reviewed offer; no separate publication button or terminal step.
- A single status panel states On, Off, Paused by admin, or the precise prerequisite
  still missing. Existing contacts remain in the same page. The owner app keeps its
  explicitly approved Unlimited policy; reseller quotas cannot be edited by a reseller.

Migration022 deliberately leaves existing configuration-only approvals OFF for live
reseller access. Explicit per-workspace admin approval supersedes the old owner-only
pilot veto for THAT reseller; it never resets trial history or enables everyone.
Package metadata refreshes automatically with bounded retries. Unchanged approved
terms survive ordinary verified APK updates and cosmetic edits. Actual price/plan
changes still require review; the artifact/signing registry, ownership, duplicate
  eligibility, quotas, recovery and global/app opt-outs remain enforced independently.

See `deploy/UPDATE-0.21.0.txt` for the one-time website-only upgrade. No native build,
worker reinstall, new secret or trial account is needed. The older APIs/renderer
remain compatible for a cached client during deployment, but are not shown by the
new interface.

## Previous source update — 0.18.0 build progress

My app brand shows the latest build's live stage bar and rough remaining-time range.
It refreshes the status/download region without replacing unsaved name/artwork edits
or showing old build history. Queue, publication/approval wait, retry/review, stale
updates and completion are distinct; no timer-driven compilation percentage or false
100% is generated. Only verified publication completes all6 stage milestones.

Migration020 stores server-clock stage timings. Privileged lease-owned heartbeats
accept only the fixed current-state-compatible stages, never customer-supplied
percentages or elapsed times. New runners negotiate progress_protocol=1; older
ones keep working with coarse state/indeterminate compilation and server-known
publication/mirror milestones. Detailed artwork/compile/verify/retention updates
begin when the updated runner is used in a reviewed build source, without bypassing
source pins or signing/retained-byte checks.

ETA learns from completed active-stage measurements, excluding stopped attempts and
publication/approval idle time. A documented testing bootstrap uses EXISTING real
CI measurements (no dummy builds):607/620/1087s compile/staging windows and43/61s
publication work. It is a rough range, excludes unpredictable queue/approval waits,
and becomes unavailable/overdue rather than pretending that a delayed build is done.
No GitHub credentials/log links or lease/signing secrets are exposed to resellers.
See `deploy/UPDATE-0.18.0.txt`.

## Previous source update — 0.17.0

Trials & contacts has an owner-only **Allow in-app trials globally** master switch
and a workspace-scoped **Show trial button in this app** switch. Resellers can change
only their own app; they cannot read/change the master or override an admin pause.
The master is persisted/version-guarded/audited in migration019. Fresh installations
start OFF; upgrades preserve an already explicitly published offer without changing
private deployment/worker gates, approved terms, publication stamps or quotas.

Reseller opt-outs survive global OFF/ON. A valid seller preference may be prepared
during the admin pause, but no new session/signup/dispatch is admitted while paused.
Current locking reads serialize dispatch decisions with the switches; a request
already authorized/underway can finish. Pending original operations pause without
losing their request, reservation or identity and can resume as the SAME operation.
Prepared activation handoffs, existing accounts and ordinary viewing remain usable.
No pilot/history reset, credit/expiry change or broad rollout occurs by toggling.

The companion app source refreshes its offer on activation-screen entry/resume and
about every30 seconds only while that screen is visible. It hides NEW signup when
unavailable, while retaining pending-request recovery and created-account activation.
This enhancement awaits the next signed APK; existing builds already enforce the
server decision when opening/refreshing the offer. No APK rebuild per toggle.
See `deploy/UPDATE-0.17.0.txt` for the website/migration installation.

## Previous source update — 0.16.0

Owner workspace access is now shared across the whole site, rather than being a set
of reseller-only exceptions. An authenticated workspace-context endpoint resolves the
EXISTING owner record even though an owner login has no reseller tenant_id. A common
picker guarantees **My owner workspace**, supports server-paginated workspace search
and labels the actual current scope. Branding, trials, layouts, settings profiles,
device recipes and new-device defaults use this shared context. Dashboard, customers,
devices and audit can filter to the owner workspace or retain the aggregate admin view.

Workspace administration includes owner branding/trial/default shortcuts and explicit
owner device-allocation editing, but never self-suspension. Already owner-held devices
do not offer a misleading remove-from-reseller action. Implicit owner create/read
requests resolve the same owner workspace, not a null reseller ID; reseller scopes
and signed panel-link requirements stay enforced. No records are moved or duplicated.

Served HTML derives asset versions and the visible footer from VERSION. Open tabs
detect a newer server version and offer a deliberate reload without silently losing
unsaved drafts. The current owner one-account pilot is preserved and its remaining
slot/status is visible; another brand cannot show an enabled publish action while
the pilot restriction applies. See `deploy/UPDATE-0.16.0.txt`.

## Previous source update — 0.15.3

The approved one-account testing pilot can now be enforced with the private
`trial_pilot_brand_id` setting. Only that brand may admit/publish offers, and the
broker reserves at most ONE operation ever for it under the existing tenant lock.
Daily resets, new devices, branding revisions, failed/cancelled/uncertain work or
released daily quotas cannot free that slot. Original-operation retries/reconciliation
still work. No limit is inferred for ordinary rollouts without this pilot setting.

`deploy/enable-owner-trial-pilot.sh` checks the exact signed panel link and freshly
reviewed plan fingerprint, verified site-delivered testing APK and absence of another
rollout/previous operation. It prepares only that plan/default and one/day offer,
preserves private config/key/ACLs and activates the brand-only lifetime guard. It
does not request a trial account. No worker reinstall, APK build, migration or
dispatcher repin. See `deploy/UPDATE-0.15.3.txt` before separately enabling the worker
creation flag and making the single explicit signup request.

## Previous source update — 0.15.2

Owner-only trial setup now supports the existing owner-branded workspace, but still
requires a signed exact panel reseller link, current approved plans/default, verified
branded artifact and explicit offer publication. No duplicate reseller workspace or
new APK is needed. Resellers cannot access owner policy/contacts. No worker or Android
code changes. Existing branding alone still grants no panel/trial rights.

The private server CLI `owner-trial-catalog EXACT_PANEL_USERNAME` verifies/establishes
the owner's permanent panel link through the existing signed activation-worker lookup,
refuses conflicting permanent links/transfers, then refreshes the separate auth-worker
catalogue. It does not choose/approve plans, alter offer publication settings, create
panel accounts or export credentials. See `deploy/UPDATE-0.15.2.txt`.

## Previous source update — 0.15.1

Branding now shows only the latest build request; older build records, immutable
inputs and published downloads remain intact behind the scenes. The existing-brand
name/artwork editor is collapsed by default and opens by clicking its heading. New
brand setup stays open, and unsaved drafts stay visible when rerendered.

Includes the0.15.0 shared-main/trial deployment work below; no Android changes.

## Previous source update — 0.15.0

Migration018 adds testing-only shared-main fanout: preserve each LIVE branded APK's
name/artwork/revision/package while increasing platform versions. Draft branding is
never auto-published. Exact immutable source tags and existing signature/lease/byte-
verification/downgrade gates remain mandatory. Main currently lacks compatible app
source; old/unsupported main cannot queue a downgrade. Production and generic releases
are untouched. See `../branding/MAIN_UPDATES.md` and `deploy/UPDATE-0.15.0.txt`.

The separate auth-worker extension now has an additive hash-checked installer,
rollback on failed readiness, a separate private HMAC/state directory and a default-
OFF creation switch. `deploy/connect-trial-worker.sh` imports only the private
connection into the original config, preserving all other values and key/ACLs. It
does not select a reseller/plan, enable offers or create panel accounts.

## Previous hotfix — 0.13.2

Fixes the owner/admin exclusion from branding: the selector includes/defaults to
the existing owner workspace, and owner-only name/artwork/save/export/build preparation
works without creating a separate reseller login or panel link. One stable owner
brand is enforced by the same workspace uniqueness/version rules. Resellers still
need a verified link and cannot read or alter owner branding. Financial trial policy
and managed-device ownership checks are unchanged; owner branding enables neither.

Branding first checks the existing owner system-status endpoint for pending database
upgrades and offers a direct upgrade link before attempting branding-table queries.
Malformed API responses now show safe HTTP status/request-ID diagnostics instead of
asserting a PHP/Nginx configuration fault. Branding failures link directly to pending
website upgrades. No migration beyond017 is added. See deploy/UPDATE-0.13.2.txt.

## Previous update — 0.13.1 (user reports installed)

Adds owner-only **Download build inputs** to the immutable branding queue, so the
operator can download a pinned name/artwork bundle without a server terminal.
Reseller/anonymous requests are rejected; suspended/cancelled builds are not exported,
and no panel/worker/signing credentials are included. All public trial rollout remains
off. See deploy/UPDATE-0.13.1.txt for the existing-site update from0.10.3 and preparation
of the reseller test inputs. Existing config.php/private storage/keys are preserved.

## Previous development milestone — 0.13.0

The user's correction preserves ALL existing GitHub workflows. The proposed extra
builder workflow is withdrawn; the separate branded runner adapter remains deferred.
Normal testing114 is being built first through the established testing workflow.
No branded output is routed to the generic testing channel.

Migrations016/017 add the default-OFF public brand-scoped signup broker and central
builder queue/leases/verified artifact registry. The user selected broad compatibility,
not mandatory hardware attestation; public brand/device claims remain spoofable.
Explicit offer publication requires owner rollout opt-in, current centrally verified
branded build, fresh approved/default plan, confirmation and per-reseller daily limit.
Save/upgrade/build actions never silently enable trial offers. Unpublishing does not
revoke already-created trials or ordinary activation/viewing.

Central builder access is a restricted SSH forced-command CLI, not a public HTTP API.
Lease expiry fences stale callbacks; uncertain publication requires same-byte download
reconciliation, never blind recreation/overwrite. Cosmetic revisions retain package/
signer with newer Android/four-part update versions. Only exact-version immutable
brand APK assets are selected; generic releases and customer latest are untouched.
The branded enrollment format additionally requires a MAC-verified centrally registered
package/channel here; actual account/panel ownership remains authoritative. Its small
activation-worker source extension is a separate deployment from the trial auth worker.

See ../branding/CENTRAL_BUILDER.md for private configuration, source pins, protected
environments, recovery and separately approved live testing. All external boundaries
in tests are synthetic except read-only SDK checks of an already-published generic APK.
No workflow dispatch, service deployment, new APK publication or live trial performed.

## Previous development milestone — 0.12.0

### Trial plan approvals, reseller defaults and follow-up contacts

Migration015 adds **Trials & contacts**. The owner explicitly refreshes a READ-ONLY
trial catalog from the separate iptv-auth-worker extension, approves a limited subset
for a verified reseller, then that reseller chooses one default. Catalog responses
are scoped to its actual panel reseller ID and include duration, credit cost, groups,
connection limits and a worker-generated revision fingerprint. Approval/default saves
are version guarded; changed prices/limits or admin approval require explicit review.
Disabling still works with stale metadata. These actions do NOT create panel accounts.

The worker checks the approved fingerprint again under the owner/package write locks,
and checks actual created limits after native panel registration. Read-only status
lookups are bound to the original provisioning operation and owner, expose no account
credentials and distinguish conversion to a non-trial line from proof of payment.
Source revisions prevent delayed status replies from overwriting newer confirmations.

Contacts from a confirmed trusted worker receipt are stored as encrypted, resource/
tenant-bound first-name/phone envelopes. Lists mask phone numbers; the owning reseller
can open details and explicitly call the number, including after the trial expires.
Phone numbers remain unverified; no SMS/OTP or automated messaging. Original trial expiry
is retained when a later panel status reports conversion. Full contact reads are audited
without logging name/phone. An already-created receipt is not discarded merely because
offers were subsequently suspended; it grants no device-control or viewing permission.

Default local contact retention is90 days after known trial expiry, or creation while
expiry is unknown, owner-configurable via trial_contact_retention_days (7–365). The UI
shows the deadline. Removing/expiring local PII retains minimal eligibility history and
never resets trial usage or restores erased PII on receipt replay. This does NOT remove
IPTV panel notes or govern independent panel/backups retention. Adjust the local policy
before deployment to match the desired follow-up period.

Configure the separate connection in the EXISTING private config.php (no second config):
trial_worker.base_url and trial_worker.shared_secret. This key is separate from the
activation-worker/portal key, stays server-side and is blank/disabled by default. The
worker extension also needs its matching private environment and original durable state.

**App offers remain OFF.** Policy configuration and contact read/status/redaction routes
are authenticated management tools, not a public provisioning API. Trusted branded-install
validation, the native activation trial form/handoff, central builder/publication and
brand-only update version allocation still need completion. No worker/site deployment or
new APK publication is implied. Regular testing113 remains the published storage test.

## Previous development increment — 0.11.0

### Self-service app brand preparation (not yet end-to-end APK/trial automation)

Migration014 adds **My app -> My app brand**. Each active, verified linked reseller
has ONE persistent app identity. Upload a loading image, in-app logo and launcher
icon (PNG/JPEG/WebP,1 MB each) and set the replacement app name; colours are unchanged.
Editing always reuses its package/brand identity. No reseller clone/delete/transfer,
arbitrary package/update URL, signing-key export or downstream branding interface.
The platform's unbranded owner app is separate from reseller brand records.

Saving does NOT build or install an APK. Request build pins immutable image hashes,
brand version and the platform-controlled app version. It currently shows **Waiting
for central builder**, not Ready. Repeated requests deduplicate; only one build can
be pending per brand. The requester/owner can cancel unstarted work. An owner can
export a pinned job using `php cli/console.php brand-export BUILD_ID PRIVATE_NEW_FILE`.
Owner Resellers rows link directly to a workspace's brand editor. Assets remain
authenticated/private; orphan uploads are retained1 day and limited100/workspace.
Initialized config, DB, private storage and encryption keys are preserved by updates.

`branding/prepare.py` in the source repository creates all density/banner/icon/loading
resources and localized brand strings from that export, re-encoding uploads and
rejecting tampered package/image identities. Branded Gradle inputs use OUR repository
and brand-specific production/testing update tags; distributable builds refuse debug
signing fallback. Sample branded resource/Kotlin compilation is covered locally.
See `branding/README.md` for completed pieces and remaining builder/enrollment/update
integration. No reseller-branded APK is claimed ready or published by this increment.

**Trials stay disabled.** The separately tested `reseller-auth-worker` extension in
the source repository prepares restricted/idempotent worker operations with contact
notes, eligibility history and post-commit repair. It is not deployed or connected
to a public app trial route. Admin trial-plan/default UI, structured contact display,
trusted branded-device policy and the native activation flow remain unfinished.

Android development113 separately adds Settings -> Storage usage (read-only fixed
component counters) and atomic compressed VOD/series catalogue caching, preserving
legacy files, saved timestamps, TTL and offline fallback. A website ZIP cannot apply
these native changes. No app-data/guide/favourites purge, playback-engine change or
APK publication happens by installing this website ZIP. Regular113 testing publication
has been separately authorized. Physical fresh-install/sync storage savings
and speed still need an owner-device pilot; do not claim the reported1.55 GB is fixed.

## Previous milestone — 0.10.3

### Consistent menu theme

The grouped sidebar now uses the SAME shared light/dark palette as the website:
white/ink in light mode, matching navy surfaces in dark mode, and blue active rows,
hover/focus states and icons. The reference screenshot's fixed charcoal/red colours
are removed, including the separate dark Devices palette. Menu grouping, responsive
drawer and all workflows are unchanged. Asset versions are bumped to refresh caches.
No new migration, backend change or APK required for this styling fix.

### Cleaner Devices directory

The Devices screen now follows the user's cleaner reference: a real scoped device
count, one search/access-filter toolbar and a compact five-column table (Device,
Customer, App access, Connection, Actions). Model/app version and last check-in are
secondary text instead of separate columns. Username chips identify the customer;
technical identifiers remain in device details. Phones show compact readable cards.

App access filters use the existing authenticated server query, including an explicit
Revoked enrollments view; connection is still separate and means app check-ins only.
Counts reflect the current search/filter/customer scope, not a fabricated quota.
The row refresh icon requires confirmation and queues only the existing supported
playlist-refresh operation. Disabled states explain incompatible/inactive enrollment.
View opens the full device workspace. No one-tap block/delete, invented welcome/
broadcast tools or automatic device actions were added to copy the example screen.

0.10.2 includes0.10.1's grouped menu and the complete0.10 rebuild. No new migration
beyond013, no Android/worker changes and no live deployment.
Device search also converts stored ASCII IDs to UTF-8 for comparison, preventing
Unicode customer/device search terms from causing a database collation failure.

### Grouped portal menu

The user's reference menu replaces0.10's horizontal navigation: a theme-matched sidebar,
muted section labels, outline icons and a blue active-page highlight. Desktop keeps
the sidebar visible; phones use a scrollable slide-out drawer over a dimmed page.
Escape/backdrop/link selection close it; keyboard focus is contained and restored.
The duplicate phone bottom bar is removed. Main content keeps its light/dark theme.

Sections are **Overview**, **My clients**, **Device setup**, **Playlist editor**,
owner-only **User management**, and **Workspace**. Layouts/playlists, settings
profiles, device profiles, new-device defaults and the template maker have direct
links. Only real existing features appear: no placeholder VPN, VOD, referral or
sub-reseller functionality is implied by matching the reference menu style.

The0.10.1 UPDATE includes the complete0.10 rebuild below. No new migration beyond013,
no Android/worker changes and no live deployment. Update preserves config/storage.

### Task-based workspace rebuild and real new-device defaults

This is the approved structural rebuild, not another0.9 reskin. Light-first
blue/ink appearance (matching dark mode), now with the grouped portal sidebar
described above. Customers lead to their scoped devices by
verified line username. Raw IDs/capability maps live under Technical details.

Each device has FIVE sections: **Summary**, **Playback**, **Channels & favourites**,
**Playlists & guide**, **Help & activity**. Messages, access policy, source actions,
support and queued-action history remain available but have a clear home. Owner
administration is under **More**; reseller permissions are unchanged.

Playback has inline buffer/sort controls. **Load device settings** explicitly asks
the app, watches THAT action and updates the same screen—no manual page refresh
or unrelated command page. Drafts are marked unsent. Success follows the real app
receipt; stale/conflicting/expired/offline work is not called applied. Queued work
cannot wake a stopped app. Switching views/signing out stops stale UI updates.
Live buffer scope/timing and compatible-player language settings remain distinct.

**Library -> Channel & playlist layouts -> Create layout** opens the maker. Add
safe exact category names, set order/visibility, import a device catalogue, preview
matching against reported metadata, or save an organized device draft as a layout.
Template edits are local until Save; saving a template sends NO device command.
Actual device application still requires a fresh guarded source/profile revision.
Keep target-only groups; ambiguous/unsafe matching needs review, never fuzzy IDs.

**Library -> Device profiles** combines a pinned layout version, optional broader
settings profile, sorting and device-wide Live TV buffer. These are configuration
recipes, not newly created Android/customer app profiles. Source passwords/URLs,
favourites cloning, update/signing controls, entitlements, blocking/revocation,
screenshots and restarts are NOT default-profile fields.

**Library -> New-device defaults** explicitly assigns ONE versioned recipe to
future devices admitted through a verified reseller link. Existing inventory is
not selected. A first verified owner-reviewed admission can also qualify; credential
renewals/check-ins/unassignment do not reapply it. Editing a layout/settings/recipe
does NOT change an existing policy or a pending assignment's pinned versions.
Enable/revise the policy explicitly for future admissions. Owner Resellers rows
link directly to that workspace's defaults, including workspaces beyond page one.

Migration013 adds immutable recipe versions, policies and staged setup jobs. The
backend uses EXISTING112 app operations—no new APK, worker patch or permission.
Stages read settings/catalogue first, bind the first eligible active profile/source,
then send supported changes through existing leases/idempotency/CAS, and confirm
the final layout read-back. Existing explicit preference overrides are preserved.
New-device setup is not globally atomic: show individual confirmed stages and
partial/waiting/review results. Complete means every required stage confirmed.

No-match/ambiguous/unsafe names, source/profile changes, native revision conflicts
or a layout that would hide every category stop for review. Missing safe template
names are listed as notes; target-only categories retain visibility/order. Catalogue
reads retry at most3 times with60/300/900-second backoff. Jobs expire after48 hours;
unsupported/closed apps wait without blocking normal viewing. Successful, failed,
expired and cancelled stages are not silently replayed with fresh revisions.

Disabling defaults pauses unstarted automatic stages and stops future assignments;
already-running/applied work cannot be recalled/undone by that switch. Use explicit
review/cancel/resume controls. Existing-device application is separately confirmed,
requires active enrolled access and starts fresh guarded stages. Only its requester
or owner controls a manual job; workspace default-policy controls remain tenant-scoped.
Removing reseller assignment cancels outstanding setups without blocking viewing.

Setup progression runs from check-ins, acknowledgments and the EXISTING maintenance
cron; no new daemon/port. Publisher/workspace access is rechecked. Progression owns
a short READ COMMITTED transaction with device/job locks and nested command
savepoints; disabling/cancelling reloads the current locked job before cancelling
tracked pending commands. Immutable recipe versions remain pinned by policies/jobs;
unreferenced versions older than the latest20 may be pruned. Manual terminal job
history follows90-day retention; admission assignments retain their one-time marker.

**Upgrade:** back up, extract the0.10 UPDATE ZIP, then **More -> Enrollments & website
upgrades -> Apply database upgrade013**. Apply older pending migrations too when
skipping earlier releases. Keep config.php/storage/application.key and the existing
2m limits/required PHP extensions. App112 is already supported; no APK publication
or live deployment is implied. Run the owner-device pilot before broad rollout.

## Previous visual milestone — 0.9.0

### Mobile-first visual refresh (website only)

Calmer graphite/mint dark theme plus a remembered light theme, clearer typography,
an integrated metrics strip, flatter nested sections and less decorative framing.
Navigation uses an accessible drawer on phone/tablet (Escape closes and returns
focus; background is inert while open), and the desktop sidebar retains all scoped
owner/reseller links. Device/customer/history lists use labelled mobile records;
large source/category/settings editors keep their own keyboard-accessible horizontal
scroll region instead of overflowing the page. Mobile actions have44px touch targets,
inputs avoid iOS focus zoom, dialogs fit the viewport, and reduced-motion preferences
are respected. All existing safety copy/confirmation flows remain available.

Only the appearance choice is stored in browser localStorage—no customer records,
tokens or source secrets. Dark is the default. Server ownership/CSRF/leases, app
controls, credentials and Android playback/update behavior are unchanged. Modal
secret inputs are wiped synchronously on button-driven dismissal as well as close.

Install the0.9.0 website UPDATE ZIP over the existing site, preserving config.php
and private storage. No NEW migrations, worker patch or APK is needed for this
visual increment. If the prior0.8 update has not been installed, still apply its
pending010–012 migrations and enable PHP zlib before using112's new controls.

## Previous functional milestone — 0.8.0

### Complete managed-control increment (testing client112; not published yet)

**Install the website update and migrations010–012 BEFORE112.** Upgrading from0.6
also requires prior008/009. PHP **zlib** is now checked by CLI preflight and required
for bounded PNG validation; all other existing PHP requirements remain. Keep the
control site's existing2m request limit. No worker patch or Android permission is
added. Preserve config.php/private storage/application.key and database backups.

**Clear expendable cache** invokes only Coil image memory/disk and ordinary OkHttp
response-cache owner APIs. No directory traversal, source/catalog/guide purge,
credential/profile/favourite/history/enrollment reset, update-file deletion or
playback-buffer clearing. Partial failures are reported per cache class. New
requests may refill caches immediately.

**Restart app** means a best-effort foreground **app-screen/task reopen**, NOT a
process kill, Android reboot or ability to wake a stopped app. It requires a resumed
MainActivity, idle updater and five-minute cooldown. The encrypted duplicate journal
is committed before acknowledgment/effect; lost acknowledgments and repeated
deliveries never replay the reopen. The receipt says REQUESTED, not proof of a
successful relaunch. Activation/maintenance/required-update gates run normally.

**Playlists -> source editor** reads a private profile checkpoint, builds one local
draft and previews one atomic complete-source push. Add/edit supplementary M3U,
Xtream and Stalker sources, rename/reorder within each source type, enable/disable,
replace guide preferences and remove sources. Existing native limits remain3 M3U/
Xtream playlists and3 Stalker portals. The verified activation source must remain
first with the same identity/URL/credentials; account transfer requires verified
activation. Its label, guide preferences and enabled state are editable explicitly.
At least one M3U/Xtream source must stay enabled: this editor does not override the
app's existing all-disabled legacy fallback. Use canonical M3U/xmltv URLs; edits
that the existing local parser would rewrite are refused BEFORE mutation. The
Xtream replacement form accepts URL-safe credentials, not reserved characters.

URLs, Xtream credentials and MACs are write-only browser replacements. Only safe
labels/counts/opaque refs are returned. Full snapshots/preview targets are encrypted
server-side; command history contains an opaque draft ID, never source secrets.
Device-only context access requires that device's current command lease. The app
requires a successful pre-apply safety checkpoint, fresh profile/account/source
revision and one DataStore compare-and-set, then a captured read-back before success.
Stale/conflicting work fails rather than overwriting newer local configuration.

Source history supports explicit previewed rollback within compatible profile/
activation identity. Original legacy storage shape is retained in checkpoints so
rollback need not force a representation migration. Up to10 initial profile
checkpoints are protected;50 rolling versions (plus any active-preview bases) are
retained for90 days. At most10 previews/device, expiring in10 minutes. Existing
guides/favourites/history are not purged; source changes can invalidate the old
favourites recovery context, so restore the compatible source checkpoint before
using that old favourites version. No guessed source migration. Profile source
changes follow the app's EXISTING IPTV Cloud-sync behavior when enabled; this is
not a server-side provider fetch, entitlement grant or provider-health check.
Changing a supplementary source URL/credentials/MAC allocates a NEW source identity,
so old channel-ID collisions cannot silently turn saved favourites/categories into
unrelated channels. Existing IDs are restored only through explicit checkpoint
rollback, never guessed by source labels. Labels containing known credentials are
masked; changing credentials/topology while keeping a private label requires a new
nonsensitive label rather than leaking the old secret through a preview.

**Broader settings profiles** are versioned workspace templates under Settings
presets. Supported fields: preferred audio/subtitle language (listed choices),
subtitle size, clock format, autoplay-next, loading stats, spoiler blur, scrolling,
trailer autoplay and bounded volume boost. Default/local custom values are preserved;
unsupported local labels are represented as local_custom, not uploaded as free text.
Buffer, sort and category controls remain in their dedicated sections. No arbitrary
preference keys, URL/DNS injection, decoder override, update switch or entitlement
field is accepted.

Read effective settings in the device Settings tab, select an optional base profile,
edit individual explicit device/profile overrides, preview effective changes/origins,
then push one guarded policy. Overrides win over base values. Template edits never
auto-push. Clearing management restores the underlying local/Cloud choice only if
the managed value has not since been edited locally. Unrelated values are untouched.
Policy and values commit in ONE DataStore edit. Device overlays are stripped from
Cloud exports, and imports update their underlying choices while preserving the
overlay in the SAME import transaction; other installations do not inherit overrides.
UI/audio observers may apply immediately; there is no forced player restart.

Typed inventory (all fields below are **device/profile** scoped; no forced restart).
Language/subtitle/player fields use the app's existing compatible/VOD player
consumers, NOT the embedded Live TV engine's independent audio/subtitle settings.

| Field | Type / permitted values | Unset consumer default | Application timing / effect |
|---|---|---|---|
| default_audio_language | enum: Auto (Original), English/French/Spanish/German/Italian/Portuguese | Auto (Original) | Next compatible player settings load; no live-engine reconfiguration |
| default_subtitle | enum: Off or the listed languages | Off | Next compatible player/subtitle settings load |
| subtitle_size | enum: Small/Medium/Large | Medium | Existing subtitle renderer settings read |
| clock_format | enum: 12h/24h | 24h | Existing clock/UI observers/settings read |
| auto_play_next | boolean | true | Compatible player settings read / next episode boundary |
| show_loading_stats | boolean | true | Existing compatible player UI settings read |
| spoiler_blur | boolean | false | Home/detail settings refresh |
| smooth_scrolling | boolean | false in Home | Home settings refresh; legacy Settings UI has a different unset display default, so explicit managed values remove that ambiguity without changing defaults |
| trailer_auto_play | boolean | false | Home/trailer lifecycle; may start/stop trailers through normal UI behavior |
| volume_boost_db | bounded enum: 0/3/6 dB | 0 | Compatible player settings read; changes sound level, possible clipping |

Existing unsupported custom local values are not rewritten by a read. The separate
Live TV pre-buffer is **device-wide**, defaults Off and applies only at the next
standard live tune. Arbitrary URL/DNS/decoder/update/entitlement fields are not
part of this settings inventory. A stored/read-back value is not hardware proof
that a particular active screen/player has consumed it yet.

**Advanced support** adds private fixed-code event exports for live tune/recovery,
guide refresh and management receipts, plus typed network/memory diagnostics.
History is limited to128 events/seven days per locally bound managed account;
no raw logcat, exception strings, URLs, headers, channel/profile names or credentials.
Event timestamps are device-reported and events cover the device/account across
profiles; the diagnostic snapshot itself identifies the active profile by opaque ref.
Logging failures never interrupt playback/guide work. Stopped apps cannot collect
continuous history and requests still require foreground check-ins.

Optional **customer-consented support-panel images** capture ONLY a dedicated safe
app panel showing app/Android version—not the current app page, TV video, sensitive
forms, other apps or a full-device screenshot. Every request requires one-time
foreground consent within20 seconds; decline/expiry/profile/account/background/
updater changes refuse capture/upload. Duplicate deliveries never re-prompt. No
MediaProjection/accessibility/overlay/capture permission is added. This deliberately
does NOT promise screenshots of protected video or arbitrary app pages.

Support bundles/PNGs are encrypted in private database storage, not served from
public files. Every download requires the current tenant session and is audited.
No public links or remote URL fetching. Limits:4 uploads/minute/device,15 artifacts/
device,7-day expiry,128 event codes,32KiB JSON,128KiB PNG,512x288 pixels/static8-bit
PNG, bounded zlib decode and no animation/text/trailing-data chunks. Source/settings/
support mutation APIs enforce CSRF and ownership; device uploads require live leases.
Physical owner-device pilot remains required; synthetic receipts are not proof of
TV buffering improvement, storage behavior or OEM activity reopening.

### Per-device Live TV pre-buffer (testing client112)

Settings -> request a fresh snapshot -> **Adjust Live TV pre-buffer**. Choices are
**Off (existing fast-start default), 2, 5 or 10 seconds**. Try2 first. This is the
amount of video requested before playback/recovery, not a guaranteed countdown.
More pre-buffer can cushion brief bandwidth dips; it cannot repair sustained low
throughput, missing provider data, a bad codec or server faults, and can increase
startup/live delay. Existing engine heap caps/recovery guards remain in force;
high-bitrate streams may not reach the requested amount.

This is an installation-local setting, shared by that device's profiles; it is
NOT a playlist/source change and is excluded from Cloud sync/source signatures.
Commands require a fresh settings revision and the same active profile/account
at execution. One DataStore compare-and-set writes ONLY the device buffer key.
The existing OwnTV fast Media3 engine and mpv fallback receive the same per-tune
pre-roll through their supported SourceEntity/LiveRequest fields. No dependency
upgrade, decoder changes, byte-cap override, global engine-setting observer or
new player path. Off remains0 exactly as before.

Changes apply on the NEXT standard live channel tune; they do not rebuild or
interrupt a playing stream. Same-tune retries/fallback retain that tune's choice.
Catch-up, DRM-specific adapter and movies/series are unchanged. A missing/failed
local preference read falls back to Off; reads are bounded and a cancelled/stopped
tune cannot later start itself. Saving/receipt means stored for a later tune,
not proof that this amount was buffered or that customer buffering is cured.
No forced retune/restart or live deployment is performed by saving this setting.

### Expanded redacted diagnostics (testing client112)

The device Logs tab can request a fresh diagnostics snapshot through the existing
leased command queue. Client112 adds app heap used/limit, Android low-memory state,
active network validation state, transport class (VPN/Ethernet/Wi-Fi/cellular/other)
and metered status. Fixed enums/counters only: no SSIDs, IP/interface addresses,
provider probes, process inventories, file scans, exception text or raw logs.
Unknown, unavailable and permission-denied states are not reported as healthy.
Android network validation is NOT proof that any provider/stream is reachable.
This is a one-time requested observation, not continuous telemetry.

No new permission or worker patch is needed. The basic network/memory fields need
no schema change; the combined controls above require migrations010–012. Existing111/basic
diagnostics stay supported. Install website0.8.0 before testing112 so its strictly
validated extended receipt is understood. Previous0.7 migrations008/009 still
apply if upgrading from0.6. Updates preserve config.php/storage/application.key.
Raw crash/logcat collection, arbitrary-page/full-device screenshots and branding
remain unsupported/deferred. The diagnostics collection does not change player, activation, guides, favourites
or updates. The separate optional pre-buffer above changes only per-tune pre-roll.

## Previous milestone — 0.7.0

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
