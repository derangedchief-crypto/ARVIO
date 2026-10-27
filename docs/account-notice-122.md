# Client 2.0.122 / 438 — account notice instead of reactivation

Validation: 958 full native tests, zero failures/errors, one existing skip; both
testing/customer Kotlin variants compile. All79 branding/builder checks pass.
New tests cover notice routing, no activation/trial/manual-sync controls for bound
accounts, inactive-status retention across outages and automatic renewal recovery.

An already-activated installation with a confirmed expired/inactive primary now
shows a dedicated account page: “Your account is expired or inactive. Please
contact your provider to renew or reactivate your account.” It contains no code
form, trial action or manual verification control. Saved data is not changed.

Background verification continues. Once the provider restores the account and
verification succeeds, access returns automatically without another activation
code. A later network failure does not overwrite a confirmed inactive status.

An already-bound account that cannot be verified due to connectivity/grace expiry
gets a separate connection notice, not a false expired-account message. Only an
installation without a trusted binding enters the activation-code flow. Checking
and valid-access states keep their existing routing and playback safeguards.

This is a staging-only client change. No website/worker update is needed. APK
publication and promotion to main/customer release require separate approval.
