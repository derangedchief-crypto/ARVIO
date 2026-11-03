# EXTREME ecosystem website preview

Six-page, responsive marketing prototype. Open `index.html` through a static HTTP server. Navigation uses hashes and works without a build step.

Pages: Home, Extreme TV, Extreme Hub, For resellers, Downloads, Support.

The prototype lives separately from the existing website. It is not a deployment, web-app change or reseller-panel change. Its enquiry form is explicitly local-only, with no submission or storage. Dashboard records are fictional and labelled as such. Repository screenshots are reference imagery, not newly captured current-build screenshots.

## Content basis (November 3, 2026)

- Public GitHub release checked: `v2.0.131`. Staging code is `2.0.132`; do not advertise staging refresh fixes as already shipped in the public release.
- `app/build.gradle.kts`: Android API 26 minimum; ARM builds; separate branding, testing and production identities.
- Root `README.md`: viewer-facing media, profiles, libraries, cloud and web capabilities. Integration availability is qualified by build.
- `app/src/main/res/values/strings.xml` and app artwork: Extreme Hub identity and managed activation experience.
- `control-center/public/assets/app.js`, branding/renewal modules and `deploy/UPDATE-0.40.0.txt`: customer/device workspaces, recovery holds, branding, trials, renewals, saved setups and current portal composition.
- Prior session `ses_e75fffff4ffe9ojmYUgzXSIqsf`: rollout history, recovery restrictions, approved panel direction and distinction between staging and published changes.

## Before production implementation

- Confirm the final public-facing positioning of Extreme TV vs Extreme Hub; the repo has mixed historical branding.
- Supply the intended reseller sales/enquiry destination and commercial terms. No prices or contact details are invented.
- Capture fresh screenshots from the public app and an anonymised reseller demo workspace.
- Confirm the public web app offering/access model. Old ARVIO Premium prices, trial claims and Play Store buttons are deliberately absent until verified.
- Review supporting/localised pages, metadata, redirects and sitemap as part of the actual full-site migration. Existing production pages have not been replaced by this preview.

The typography file and product imagery are copied from repository assets. The new website uses charcoal, cyan from the existing Hub artwork, and purple from the current Control Center palette.
