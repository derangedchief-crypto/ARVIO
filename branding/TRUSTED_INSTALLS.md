# Trusted branded installations — activation integration prerequisites

This is an implementation/security boundary note, NOT a deployed verifier or a
claim that every sideload TV supports attestation. Trial offers remain off today.

## Authoritative inputs

The owner-controlled builder must register the actual APK package, signer certificate
digest, permitted version/channel, brand and verified reseller after checking the
signed artifact. Resellers cannot submit their own signer/package/update identities.
A public BRAND_ID, package string or signing hash merely REPORTED by the APK is not
authentication. Never embed the worker/portal secret in the APK.

## Suitable supported-device path

- Use Android Keystore hardware-backed attestation with a fresh, server-issued,
  short-lived challenge bound to the brand/package, operation and request digest.
  Verify the certificate chain OFF DEVICE and require the attested application ID,
  signer and minimum build to match the approved artifact registry, then prove
  possession of that certified key over the actual request. A challenge by itself
  does not prevent relay/rebinding.
- Prefer Android's maintained Kotlin verifier in a bounded server-side helper, not
  a rushed custom Python ASN.1/X.509 validator. The Python auth worker can call a
  locally controlled verifier boundary; never accept a client verification flag.
- Use maintained official trust anchors/revocation data and explicit freshness/
  outage rules. Check hardware security level, locked/verified boot state and the
  app identity; unknown roots/software attestations are not automatically trusted.
- Do not assume the attestation extension is in the leaf. Android documents that
  only the first extension nearest the trusted root can be trusted; attackers can
  append certificates/extensions farther down a chain. The maintained verifier also
  handles legacy factory-certificate expiry differently from short-lived RKP certs.
- The application's protected code should read the OS app-signing/user/device-scoped
  Android ID directly (not from intents/user input), bind that value to the certified
  proof and let the server derive a keyed pseudonymous device ref. Do not collect
  IMEI/MAC or add restricted identifier permissions. Verify how this behaves across
  same-signer brand revisions and Android users before claiming stable recognition.

## Important compatibility limits

Google-rooted hardware attestation is not universal on generic boxes or non-Google
devices. Manufacturer roots need separately documented trust/semantics; do not trust
an arbitrary self-signed root to make trials "work everywhere." No Fire TV support
claim has been established by this investigation. Unsupported or unverifiable devices
must keep ordinary activation/viewing; automatic trials can fail closed without
blocking legitimate subscriptions. Any weaker/manual fallback needs an explicit
owner policy and honest abuse limitations, not a silent embedded-secret fallback.

Reinstall-cleared installation IDs alone cannot enforce the requested device rule.
Android IDs can change after resets and differ across users/signers. Even on supported
devices, do not promise an absolute lifetime physical-device/person ban. First name/
phone remain user-provided with NO OTP, so changed/invented numbers remain a known
limitation of the agreed reseller/customer/device combination rule.

## Before enabling public trial creation

Complete the artifact registry/trusted session, bounded nonce/replay/quota handling,
encrypted provisioning-operation state, exact policy/fingerprint rechecks, existing
worker idempotency/reconciliation, contact-note verification/ingestion and secure
single-operation normal-activation handoff. Do not collect/persist contact PII for an
unverified/unmatched installation. No generic app offer or public account-creation
route until these pieces and a separately approved pilot are validated.

Branding-only edits also need newer install/update identities. The current numeric
version comparison can compare a fourth numeric revision, but the release-acceptance
pattern and publisher metadata still need an explicit brand-only revision contract.
Do not ship another name/logo APK with the same indistinguishable update version.

## References checked during development

- https://developer.android.com/privacy-and-security/security-key-attestation
  (page updated2026-07-09): off-device validation, roots, revocation, extension order,
  factory/RKP expiry differences and non-Google device limitations.
- https://github.com/android/keyattestation : maintained verifier, request-bound
  challenges/relay limits and application-ID constraints. Do not blindly copy an
  example permissive unknown-package constraint into a branded-app authorization rule.
- https://android.googleapis.com/attestation/root : official current root set.
- https://android.googleapis.com/attestation/status : revocation status, cache freshness.

No trust roots, verifier daemon, public challenge endpoint or new Android permission
has been installed/added by this document. Regular testing113 remains unchanged.
