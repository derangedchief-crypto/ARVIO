# Rampex renewal — first staging foundation

Historical first increment at `6cfb727`. For subsequent staging integration and
its still-open acceptance gates, see `rampex-staging-integration.md`.

## Confirmed decisions

- Each reseller connects its OWN merchant account; no master/sub-merchant or
  platform fee arrangement is selected.
- Verified payment must automatically renew the SAME existing primary line.
- Reuse the existing reseller panel/auth worker, not the separate activation worker.
- Reseller-branded apps only. Generic customer/testing apps do not get this option.
- Staging and simulated payments first. No live payment, deployment, panel write,
  main promotion or APK publication is authorized by this foundation.

## Existing worker reviewed, not executed

Authoritative upload: `new auth.zip`, entry `new auth/main.py`, SHA256
`c5a93d1a4d98f214a4427e8ac0ca7dbdcb03fb5d59632ad4212740a188a7e8d1`.
Original ZIP/config/backups remain untouched. `/renew-line` (lines1928–2129):

- Confirms ownership, locks reseller and line, checks credit balance, applies
  native calendar-duration logic and records the credit debit.
- Updates the same line; credentials, activation code and notes are not replaced.
- Calls the native Overcast refresh after its database transaction commits.
- Has NO payment/order operation ID. Replaying it can add another period/debit.
- Refresh failure is a post-commit failure: the correct repair is refresh-only,
  never another `/renew-line` call.
- Existing `_set_credits(..., note=...)` and `users_credits_log` provide a possible
  atomic operation/payment-marker location. A supported guarded extension must be
  implemented/tested before a real payment can invoke renewal. Do not assume the
  old route is idempotent or simply wrap it in retries.

## Implemented and tested in this increment

`RampexPaymentVerifier` is a PURE verification contract. It checks exact raw-body
HMAC before parsing, immutable merchant/link/gross amount/currency, a separately
verified merchant-scoped full-payment result and settlement reference. It does
not compare the customer's gross price with net payout after fees. Extra deposits,
wrong merchant/link/amount, unverified completion and invalid signatures are not
renewal authorizations. The documented Rampex HMAC test vector passes.

Initial amount parsing supports two-decimal USD/CAD/EUR/GBP/AUD with exact minor
units and no rounding. Other currencies need their own explicit exponent handling.
`fully_paid` is an INTERNAL adapter assertion: it cannot yet be inferred from an
undocumented received_amount field or browser redirect. The live API adapter is
not implemented; this class does not fetch Rampex or accept public requests.

`branded_renewals.RenewalOperations` is an OFFLINE worker operation foundation:

- Private separate ledger/key, preserved across service objects/process restart;
  one order ID binds immutable line/reseller/package/merchant/payment reference.
- Duplicate/parallel operations cannot perform a second renewal in the fake adapter.
  One verified payment cannot be reused under another order ID.
- An uncertain commit must reconcile from the atomic adapter, not blindly retry.
  Definitive not-applied evidence may retry the SAME operation.
- Post-commit refresh failures retry refresh/verification only. Fulfilled order
  replays do not trigger another native save or imply a permanent playback grant.
- Corrupt receipts/missing original keys fail closed without resetting history.
- The unchanged legacy worker is explicitly refused: an adapter must implement
  atomic renewal AND unique payment-reference fencing. A capability dictionary in
  a fake test is NOT proof the live panel already implements this contract.

The new worker module has no route hook, installer or production adapter and is
not included in the existing trial deployment bundle. It cannot currently be
enabled to process payments. No original portal routes/trial behaviour changed.

## Results

267 isolated service checks pass (including six new payment-contract cases).
10 new offline renewal-operation tests pass. Existing32 trial-core checks pass.
All tests use synthetic secrets, fake panel callbacks and temporary isolated
state; none creates a payment link, charges money, renews a real line or connects
to the live panel. Native code has not changed in this increment.

## Remaining before a QR pilot is ready

Tenant-scoped merchant/key settings, real provider adapter, order storage,
expired-account checkout authentication, typed expiry eligibility, signed webhook
HTTP route/async fulfilment, the atomic extension to the existing worker, phone
checkout, TV QR UI and end-to-end device tests are NOT implemented yet.

Keep production off. Rampex documents production-only testing, so even a key
labelled Development does not mean fake money. Ask separately before any real
transaction or deployment. Panel credits are separate from money received in
Rampex; check funding and handle paid-but-renewal-pending without charging again.
