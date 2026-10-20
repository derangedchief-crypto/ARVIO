# Extreme Hub Manager — aaPanel deployment

Standalone admin page and maintenance API for **https://hubmanager.extremeiptv.net**.
This is separate from Netlify and does not change login, playlists or entitlements.

## Server installation

Requires Linux with systemd, Python **3.10+**, Python venv support, and aaPanel Nginx.
Upload this `hub-manager` directory to the server, then run inside it:

```bash
sudo bash install.sh
```

The service listens **only on localhost port 8766** as an unprivileged dedicated
user. It keeps state, salted scrypt password hashes, session hashes and an audit
trail in `/var/lib/extreme-hub-manager/manager.sqlite`. Never put that file in the
website's public document root. Installation/updates preserve the database.

## aaPanel website / reverse proxy

1. Point `hubmanager.extremeiptv.net` DNS to this server.
2. Create that website in aaPanel. Issue its TLS certificate and force HTTPS.
3. Add a reverse proxy for `/` to `http://127.0.0.1:8766`.
4. **Disable proxy/CDN caching** for this domain, including `/api/maintenance`.
5. Use the real client IP from Nginx; do not pass through client-supplied forwarded
   headers. Keep port 8766 closed to the public internet.

Equivalent Nginx location (use aaPanel's reverse-proxy editor, not a duplicate
`location /` alongside its generated one):

```nginx
location / {
    proxy_pass http://127.0.0.1:8766;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_cache off;
    client_max_body_size 8k;
}
```

TLS is mandatory: the admin session cookie is Secure, HttpOnly, SameSite=Strict,
and host-only. An HTTP-only setup will not let an admin stay signed in. Admin POSTs
also require the exact HTTPS origin and a custom same-origin header. If a trusted
CDN precedes Nginx, configure its real-IP handling before relying on IP rate limits.

## Create your two separate administrator accounts

Run each command on the server. Password prompts are hidden. Use **different,
random passwords of at least 16 characters**; do not send them in chat or put them
in shell command arguments. Names below can be changed.

```bash
sudo -u extreme-hub-manager env HUB_MANAGER_DB=/var/lib/extreme-hub-manager/manager.sqlite /opt/extreme-hub-manager/.venv/bin/python /opt/extreme-hub-manager/service.py set-admin owner
sudo -u extreme-hub-manager env HUB_MANAGER_DB=/var/lib/extreme-hub-manager/manager.sqlite /opt/extreme-hub-manager/.venv/bin/python /opt/extreme-hub-manager/service.py set-admin second-admin
```

There are **no default accounts, public registration, APK admin secrets or customer
admin privileges**. Re-running `set-admin NAME` rotates that password and revokes
that administrator's existing sessions. Sessions expire after 12 hours. Login
attempts are limited to 10 per IP per 15 minutes, including successful logins.

## Using it

Open **https://hubmanager.extremeiptv.net/** and sign in. Set a customer message,
then click **Enable maintenance…** and confirm. Either admin can click **End
maintenance…** to reopen the app. The page displays the authoritative current
status and last changing administrator. If another admin changes it, an old
revision is rejected; use **Reload status** before retrying. Admin sessions are
independent of customer sessions. Do not enable live maintenance just to test the
service without intending to interrupt all supported customers.

The public read-only endpoint is `GET /api/maintenance`:

```json
{"active":false,"message":"We’re performing maintenance…","revision":0}
```

It deliberately requires no customer login so it can block startup/login screens.
It never reveals admin names, sessions, passwords or customer data. Only the
authenticated admin POST endpoint can change it. Every successful change is saved
transactionally with its administrator, message, timestamp and revision in `audit`.

## App behavior and limits

- Updated apps check at launch, whenever the activity becomes visible again, and
  every 10 seconds while visible (including supported picture-in-picture).
- Network calls have a four-second timeout; normal propagation is about 10–15
  seconds, **not an instantaneous push guarantee**.
- Maintenance replaces navigation, disposing internal movie/series and IPTV
  player surfaces. Back cannot bypass it. Customer auth/profile storage is untouched.
- Ending maintenance opens the app automatically; it does not restart interrupted
  playback automatically. Normal player disposal saves VOD progress.
- Once active status is received it persists across restart/network failure. Only
  an explicit valid `active:false` from the HTTPS endpoint unlocks that device.
- Before the first active command is received, an offline/service failure leaves
  the app usable; otherwise a DNS outage would lock everyone out. An offline
  device cannot receive a new event, and old APKs without this integration cannot
  be remotely blocked. Externally launched third-party players cannot be closed
  by this app. Android must schedule the app for it to receive any remote update.
- Do not remove the service/DNS while maintenance is enabled: end maintenance
  first and keep the endpoint available so blocked devices can receive the off state.

## Verify without enabling live maintenance

```bash
systemctl status extreme-hub-manager --no-pager
curl --fail https://hubmanager.extremeiptv.net/api/maintenance
journalctl -u extreme-hub-manager -n 50 --no-pager
```

Expect HTTP 200, JSON with a boolean `active`, and `Cache-Control: no-store`.
Visit the admin page and confirm both accounts can sign in and read the status.
These checks do not enable maintenance. Use a separate staging hostname/build for
an end-to-end interruption drill if customers already use the live endpoint.

## Local validation

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements-test.txt
.venv/bin/python -m unittest -v test_service.py
```

The tests use temporary databases and HTTPS test clients. They never change the
live maintenance flag. Keep the SQLite database and its WAL directory private;
back up using SQLite's backup API rather than copying just the live database file.
