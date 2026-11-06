# Master category catalogue

Install this update on the control website host, then My Hub 0.1.9 on phones.
The update contains no master credentials. Sign in with the website owner login,
open Saved setups → Channel layouts → Master catalogue · owner only, paste the
private HTTPS playlist link and choose Save & refresh catalogue.

Only the owner can read/change the source. It is encrypted with the existing
application key in private `storage/master-layout.json`. Resellers and native
workspace operators receive names/visibility defaults only, never playlist links,
stream URLs or credentials. The master link must belong to the configured
`ocpanelserver.extremeiptv.net` provider. The URL is fetched server-side, without
redirects, at most 100 MB / 90 seconds; invalid responses retain the last catalogue.

New layouts start with the cached master groups. Resellers can reorder and hide
groups without a device being online. Existing templates are unchanged; Add new
master categories appends missing groups while preserving current edits.
The owner refreshes the source when provider groups change.

Applying a setup still uses the customer's own authenticated catalogue and
revision-safe commands. Master groups unavailable to that customer are skipped,
matching groups retain the saved relative order, and additional customer groups
are retained afterward. No master credentials are sent to a device, and the
master line never grants a customer extra access. No-match/ambiguous names are
reported rather than guessed. A device must check in to receive queued changes.

No database migration is required. Installer checks PHP without loading server
extensions, preserves other hotfixes, and prints its source backup path.
