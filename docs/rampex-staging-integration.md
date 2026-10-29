# Rampex staging integration — not deployment approval

This continues the first increment described in `rampex-staging-foundation.md`.
That file records the historical foundation, not the current implementation.
All changes remain staging-only. No original ZIP/config, running worker, live
website, payment account, panel line or published APK was changed.

## Owner-requested preview delivery

The owner subsequently requested a website update and selected **Extreme Hub 1
testing**, explicitly excluding customer APK publication. Website0.27.0 is an
off-by-default settings preview, not permission for live payments or worker
deployment. `deploy/UPDATE-0.27.0.txt` describes aaPanel installation, migration026
and the owner CLI command that queues ONLY the existing testing brand from its
published cosmetic snapshot. A separate immutable staging-source registry permits
that exact source for testing, never production or main fanout; private config is
not rewritten. Native staging target is2.0.127/443. Its central signed build waits
for the installed site's exact testing job. 277 expanded service checks pass.

## Added

- Migration `026_renewal_payments.sql`: off-by-default owner rollout policy,
  tenant-scoped encrypted merchant connections, revisioned package/price plans,
  checkout-only challenges/sessions, immutable order snapshots, encrypted raw
  webhook evidence, settlement fencing and isolated simulation tables.
- Customer renewals workspace: write-only merchant secrets, old-connection
  retention, current worker catalog, plan editing and paid-order repair without
  creating a new charge. Monthly module licensing is unchanged.
- Existing installation P-256 key signs a two-minute checkout-only nonce bound
  to device, package, key and original primary fingerprint. No playback token is
  issued. Server checks registered branded artifact, active workspace/device,
  approved enrollment and reseller-owned panel line. Generic apps are refused.
- Fifteen-minute opaque QR URL. Phone checkout displays server-owned plans,
  collects receipt email and redirects to hosted payment; no card fields or
  merchant secrets enter the TV app. Order preparation is serialized per line.
  Unresolved orders remain blocking even after the local quote expires: local
  expiry is not proof that a hosted payment link was cancelled.
- Raw HMAC webhook route only records evidence. `php cli/console.php renewal-jobs`
  processes one leased order: verify merchant-scoped provider status, amount,
  currency and link, then request the original worker operation. Paid but
  unfulfilled orders remain pending/review without another customer charge.
- Branded-only expired/inactive screen offers QR only after server eligibility
  confirms genuine expiry. Completion still invokes fresh primary validation;
  no playlist, credential, activation, favourite or settings replacement occurs.
  Generic inactive-account screen and global playback gates remain in place.

## Existing worker transaction guard

`patch_renewal_worker.py` is an OFFLINE source transformer, not an installer.
It accepts only the reviewed original SHA256 (optionally with the exact known
trial hook). It writes a new candidate exclusively and never reads private
config, starts a service or contacts the panel. Unknown/modified sources fail.

`branded_renewals_runtime.py` adds signed catalog/eligibility/renew routes to the
same worker. A separate 64-hex `BRANDED_RENEWAL_SHARED_SECRET`, absolute private
`BRANDED_RENEWAL_STATE_DIR` and explicit `BRANDED_RENEWAL_WRITE_ENABLED=true` are
required; writes default OFF. Website `renewal_worker.shared_secret` uses the
same ASCII secret with domain-separated nonce-bound request/response signatures.

The guarded native renewal locks reseller then original line, rechecks identity,
administrative restrictions, allowed package fingerprint and funding. Its native
line update, credit debit and `BRN1` operation/payment marker commit together in
the existing `users_credits_log.note`. All four involved native tables must be
InnoDB; missing/nontransactional tables or short/truncated journal support fail
closed. Replays reconcile that marker under the same transaction locks.
Refresh failures retry native refresh only, not another debit/period extension.
Legacy portal calls cannot supply payment metadata without the internal context.
The prior trial ledger and existing portal configuration are untouched.

## Simulator and live boundaries

The simulator additionally requires `Config::local()` and
`renewal_payments.simulation_enabled=true`. It uses synthetic `mock_` keys,
separate demo targets and demo operations; it never renews a real line. A live
merchant cannot be connected while this simulator is selected. Live API calls
separately default OFF (`renewal_payments.allow_live_api`). No approval to turn
them on has been given. Rampex does not document a real-money-free sandbox.

The live gateway remains an **unaccepted candidate**: exact status identity,
gross/received denomination, hosted URL fields/hosts and provider behavior still
need confirmed fixtures. No conversion from net crypto payouts is inferred.
Late/missing signed paid timestamps hold fulfilment for review. A redirect does
not verify payment. Never enable live payments based on mock success alone.

## Remaining acceptance work

Current local validation: 276 isolated MariaDB/service cases pass, including a
signed existing-device checkout, duplicate scan/challenge rejection, synthetic
webhook verification, automatic demo fulfilment and replay without a second
renewal. All 59 reseller-worker offline/HTTP tests pass in the existing test
virtualenv. The 68 existing browser checks and five focused renewal browser
checks pass: 360px checkout, escaped seller text, server-priced plan submission,
paid-pending/no-repeat-payment UI, demo completion and authenticated reseller
settings with password-only merchant fields. Phone payment API responses in the
focused browser test are synthetic; service payment flow is tested separately.
PHP/JavaScript syntax and whitespace checks pass. A clean full native
run passed 1,018 tests, zero failures/errors, one skip. Earlier attempts encountered
temporary disk exhaustion and interrupted-result heap errors; preserving the
interrupted output outside Gradle's results directory allowed a clean run.
After the final QR-expiry refinement, all 33 targeted access/checkout tests pass
and both internal-test and staging Kotlin variants compile successfully.

- Expand the authenticated checkout → webhook → leased job → same-line renewal
  service test into HTTP/browser coverage, including concurrent devices,
  ownership/key changes, partial/late
  payments, expired hosted links and uncertain provider link creation.
- Execute the patched native route against an isolated realistic panel schema
  and prove journal/line/credit rollback, concurrent workers, lost replies,
  insufficient funding and refresh-only repair. Unit/static checks alone are
  not production exactly-once proof.
- Mobile browser and dedicated branded TV acceptance, QR expiry/refresh handling,
  background polling and original Back/focus behavior.
- Confirm Rampex live contract without creating a charge, then seek separate
  approval for any dedicated real-money test, worker/website deployment,
  main promotion, APK build publication or customer rollout.

Do not apply the migration on the live website or deploy the runtime as part of
routine trial updates. No production installer/bundle is provided at this stage.
