# Normal branded releases

This cutover is only for owner/reseller brands. It does not dispatch the generic
customer workflow or change the published customer release. Signing remains in
GitHub. Website downloads and updates stay website-owned.

## Identity and data

The first normal build uses the brand's base package, without `.test`, and version
`2.0.116.REVISION`, without `-test`. Android treats this as a **separate installation**
from the earlier `.test` package. Existing testing data stays in the testing app;
it is not automatically migrated. Do not uninstall or clear the testing app to
make a normal installation work. Subsequent normal updates keep the same base
package, central signer and stored data, with increasing Android version codes.

Main updates copy each published brand's name/artwork/revision, not an unpublished
draft. A rebrand that finishes during a main rollout gets a durable catch-up job;
an older brand revision cannot publish over a newer one. Website deduplication
separates testing and production jobs. Neither channel overwrites the other's APK.

## Operator sequence

1. Validate and integrate the reviewed source into main, without running customer
   publishing. Record the exact resulting SHA and create its immutable lightweight
   `brand-source-2.0.116-SHA` tag. Protect source tags against modification/deletion.
2. On WORKER `38.190.133.171`, stop only `brand-dispatch.timer`. Wait for accepted
   branded Actions runs to finish. Do not kill active services, cancel a publishing
   job, delete dispatch fences or reset build records. Uncertain publication must
   be reconciled before proceeding. Back up the site/database and private worker
   files/state using the existing restricted backups.
3. On WEBSITE `38.190.133.176`, extract `Extreme-Control-Center-0.19.0-Update.zip`
   into `/www/wwwroot/control.fecxbrfzku.net`. It excludes config.php and storage.
   Run as root:

   ```sh
   bash /www/wwwroot/control.fecxbrfzku.net/deploy/enable-production-brands.sh APPROVED_MAIN_SHA
   ```

   This migrates schema021, checks exact main/tag/platform, queues normal branded
   builds from published snapshots, then atomically installs the production opt-in
   using the original config/key/bridge ACLs. It refuses active/uncertain builds.
   It never re-runs pilot setup or grants new trial access. Private config backup
   is root-only under `/root/extreme-site-config-backups/`.
4. On WORKER, extract `Brand-Normal-Releases-0.19.0.zip` into a new root-only folder.
   Run `bash production.sh APPROVED_MAIN_SHA` from that folder. It preserves tokens,
   dispatch state and timer state. It upgrades the known existing dispatcher,
   installs only a marked service override, updates future trial credentials to
   10/10 if needed, and narrowly patches activation-proof package validation.
   Original auth/activation `.env`, portal settings, account records, trial secrets,
   identity key and history are not replaced. Unknown source/policy stops for review.
5. Before resuming the timer, set the trusted operator SHA to the reviewed source,
   allow immutable source tags on the branded production environment, and remove
   only its per-build reviewer prompt. Set `BRAND_MAIN_UPDATE_CHANNEL=production`
   and `BRAND_MAIN_ROLLOUT_ENABLED=true`. Generic customer workflows/environments
   stay unchanged. Run `systemctl start brand-dispatch.timer` on WORKER.
6. Verify the actual completed build receipt, signature, clean package/version,
   immutable source and website-mirrored download bytes. Do not call a queued,
   compiled or merely signed APK published. Hard-refresh the website.

All commands use the same approved main SHA. Do not substitute a branch name.
The website helper is intentionally release-specific; future normal source updates
are registered by the guarded main workflow and need no credential repinning.

## Trials and remaining evidence

Global/app preferences, approved plans, contacts and the spent one-account pilot
remain unchanged. Binding an offer to the normal artifact requires an explicit
publication review; release cutover never auto-enables additional trials. Existing
prepared activation/recovery remains available. The created pilot keeps its original
credentials; only future credentials use ten characters each. Do not create another
account to check formatting. Phone identity is canonical but unverified; public
brand routing is not APK attestation or reset-proof hardware identity.

An automated test pass is not device evidence: playback/activation of the existing
pilot and measured hardware storage savings still require their outstanding checks.
