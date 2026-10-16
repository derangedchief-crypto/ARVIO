# IPTV activation-code worker

Resolves a customer's six-digit `customers.activation_code` to their existing
Xtream username and password. It is deliberately separate from `1. Worker`:
the reseller portal's permissions and API must not be enlarged for the Android
activation flow.

## Request path

```text
Android app → Netlify `activation-code` function → this worker → panel MariaDB
```

Only Netlify knows `ACTIVATION_WORKER_SHARED_SECRET`; the Android app must
never call this worker, have the shared secret, SSH access, or DB credentials.

The worker accepts only HMAC-signed `POST /activate` requests. It resolves a
code only when exactly one `customers` row is `enabled = 1`, `activated = 1`,
and unexpired. It intentionally gives the same error for unknown, expired,
disabled, and duplicate codes.

## Panel prerequisite

The panel should store activation codes as a six-character string (not an
integer, so leading zeroes are preserved) and enforce unique non-null codes:

```sql
ALTER TABLE customers ADD UNIQUE INDEX customers_activation_code_unique (activation_code);
```

Run this only after confirming every existing non-null code is unique. Never
log or paste real customer credentials into tickets or source control.

## Installation

The deployment ZIP contains a preconfigured `.env` and `install.sh`. After
extracting it into `/opt/iptv-activation-worker`, installation is one command:

```bash
sudo bash /opt/iptv-activation-worker/install.sh
```

The manual steps below are retained as a recovery reference only.

```bash
sudo useradd --system --no-create-home --shell /usr/sbin/nologin activationworker
sudo mkdir -p /opt/iptv-activation-worker /var/log/iptv-activation-worker
sudo chown activationworker:activationworker /var/log/iptv-activation-worker

# Upload these files, then on the worker host:
cd /opt/iptv-activation-worker
python3 -m venv venv
venv/bin/pip install -r requirements.txt
cp .env.example .env
chmod 600 .env
```

Generate a distinct secret, add it to `.env` as
`ACTIVATION_WORKER_SHARED_SECRET`, and add the same value to the Netlify site
environment as `ACTIVATION_WORKER_SHARED_SECRET`. Also set
`ACTIVATION_WORKER_URL` there to this worker's private HTTPS base URL. Set the
panel SSH/MariaDB values to the same tunnel details used by `1. Worker`.

```bash
sudo cp iptv-activation-worker.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now iptv-activation-worker
curl http://127.0.0.1:9092/health
```

## Reverse proxy

Expose HTTPS only for Netlify-to-worker traffic. HMAC remains mandatory even
behind the proxy. Do not expose port 9092 publicly or use an Android-embedded
secret as worker authentication.

## Before production

- Add edge/WAF rate limiting to the Netlify `activation-code` endpoint.
- Confirm `activation_code` is exactly six decimal digits and unique.
- Confirm actual customer fields are named `username`, `password`, `enabled`,
  `activated`, and `exp_date` as used by the existing worker.
