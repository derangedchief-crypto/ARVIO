# Branded trial extension — offline integration foundation

`branded_trials.py` extends the CURRENT uploaded **iptv-auth-worker** in `new auth.zip`.
It is not the activation worker. It has not been installed or enabled on any server.
Do not replace the existing worker wholesale or copy historical config/backups.

The supplied worker's general `/create-line` stays compatible. The new restricted
path reuses its panel DB pool, package eligibility, locked credit changes and native
Overcast registration logic, but requires active reseller status and an enabled,
activated TRIAL package with valid duration/nonnegative credits. Notes include first
name, international phone number and durable provisioning request marker. Passwords
use cryptographic randomness. No SMS/OTP, panel schema change or guessed panel API.

## Safety contract

- Called only by the control server, never an APK. Uses a SEPARATE server-side HMAC
  key and `branded-trial-v1` domain, two-minute timestamp check and8 KiB body bound.
- Atomically reserves verified reseller + normalized-phone customer identity +
  recognized device ref. Brand changes within the SAME reseller do not reset history.
  Different resellers and distinct customer/device combinations remain distinct,
  exactly as agreed. Phone numbers are user-provided, NOT verified; invented numbers
  and spoofed/reset device identities are an acknowledged abuse limitation.
- Private worker SQLite ledger stores keyed identity/fingerprint refs, operation ID,
  state and line ID, not plaintext contact/passwords. Preserve the ORIGINAL private
  `identity.key` and ledger across updates/backups; replacing them resets recognition.
- Repeated request key returns the same result without another insert/credit charge
  or another native panel save after preparation. A changed request under the same
  key is rejected. Durable reservations plus cross-process file lock serialize races.
- A lost commit response reconciles an EXACT owner/request-note match under the same
  panel reseller lock. An absent/uncertain outcome is Needs review, never blindly
  recreated. A definitive precommit validation failure releases unused eligibility.
- Failed post-commit registration returns registration_pending; retry repairs the
  SAME line. Prepared means ready for normal activation, NOT proof of playback or
  that the app has already activated. Final owner/plan/trial/contact notes are checked.
- Result returns only that operation's new account to the protected caller. It does
  not expose the existing `/lines` whole-reseller credential listing to an app.
- When enabled, legacy and new native panel refresh calls share a per-process lock
  around the existing cookie-jar workflow. Trial-only response logs omit arbitrary
  panel text so an echoed contact note is not written into logs. Ordinary portal
  logging is unchanged; the extension remains inert with missing configuration.

## Future installation, after integration/pilot approval

At the END of the supplied main.py (after helpers/routes), add:

```python
from branded_trials import install_trial_routes
install_trial_routes(app, globals())
```

Copy the extension beside main.py on the AUTH worker host, not aaPanel/activation
worker. Supply `BRANDED_TRIAL_SHARED_SECRET` (64 lowercase hex characters, distinct
from the portal secret) and `BRANDED_TRIAL_STATE_DIR` via its existing private service
environment. State directory must be owned/writable by its service user with0700;
key/ledger files0600 and preserved. Update systemd writable paths if sandboxing
requires it. Missing config fails closed503. Do not enable any of this live yet.

POST `/branded-trials/provision` with only: operation_id (32 hex), brand_id (32 hex),
verified panel reseller_id, recognized device_ref (64 hex), package_id, plan_fingerprint
(64 hex from the worker catalog), first_name,
phone (`+` and country code). HMAC covers EXACT raw JSON bytes:
`branded-trial-v1\n<TIMESTAMP>\n<RAW_BODY>`, headers `X-Trial-Timestamp`,
`X-Trial-Signature`. The privileged control server must enforce current admin
allowlist/default and trusted brand/device membership before signing. The public
APK must NEVER have this signing key or choose its own reseller/package identity.

### Read-only policy/status integration

POST `/branded-trials/plans` with ONLY reseller_id, signed using domain
`branded-trial-plans-v1`. Returns current enabled/activated TRIAL packages allowed by
that reseller's group/overrides, with actual credit cost, duration, groups, connection
limit and canonical metadata fingerprint (including legacy duration_value). Numeric
panel bouquet IDs are normalized whether stored as JSON numbers or digit strings.
No line or financial write occurs. The creation boundary rechecks the fingerprint
under row locks and refuses silently changed plans. Actual line limits are checked
after native registration before delivering a prepared result.

POST `/branded-trials/status` with ONLY operation_id and reseller_id, signed using
domain `branded-trial-status-v1`. The private ledger supplies the line ID; clients
cannot substitute a line. Returns owner-scoped state, expiry and source checked_at_us,
never phone/account credentials. Converted means no longer marked trial, NOT payment
verification. Missing/transferred rows are unavailable, not healthy/active. Contact
refresh does not replay the native customer-save action or create/renew a line.

The source control-center0.12 TrialWorker bridge uses these read-only endpoints for
admin plan refresh and explicit contact status reads. Configure the matching separate
key in its sole private config.php. It still exposes NO public trial creation API.

## Remaining integration

The control server currently exposes **no public trial-creation route** and branding
page reports trial creation disabled. Website0.12 implements admin plan/default settings,
protected structured contacts/detail views and read-only panel status integration.
A trusted branded-device session, public provisioning broker and native activation
UI/result delivery remain unfinished. Do not call this end-to-end ready.
Do not infer that a typed phone proves a person or that a reported device hash is
hardware attestation. No live panel writes/tests, deployment or APK publication here.

Run offline checks: `python3 reseller-auth-worker/test_branded_trials.py`. Tests use
fake panel callbacks and SQLite; they do not prove actual Overcast expiry semantics,
credit compatibility, native login or real panel activation readiness.
HTTP signature/body-bound tests additionally run with
`python3 reseller-auth-worker/test_trial_bridge_http.py` using the isolated
`requirements-test.txt` dependencies matching the supplied worker versions.
