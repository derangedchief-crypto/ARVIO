# Live management connections

## Behaviour

The shared standalone/branded agent remains lifecycle STARTED-scoped. It checks
in immediately on entering that lifecycle, reports active while visible (including
watching without remote input), and reconnects on network changes. It no longer
labels a visible app idle after five minutes without input.

An authenticated WebSocket to `/api/device/live` carries only `ready` and
`check_in` hints. The agent fetches commands and policy through its existing
authenticated check-in endpoint. Nothing in a notification grants access or
executes a command. Existing leases, receipts, scoped permissions, stale-preview
checks and installation identities remain authoritative.

The server sends hints after the outer database transaction commits. Nested
rollbacks discard their notifications. Device commands, setup requests and device
policy changes target that device; workspace suspension targets that workspace.
Notifications are best effort. A dropped notification or relay outage is covered
by a 15-second fallback check-in. With a live connection, periodic check-ins are
capped at 30 seconds to keep server last-seen fresh. All check-in starts are spaced
at least four seconds apart to remain within the current server rate limit.

Typical delivery is network latency plus the check-in request, or up to four
seconds when coalescing a burst. This isn't zero latency. An already-running
device command is still serialized before the next command can run. There is no
guarantee while Android stops the app, the screen is asleep, the process is killed,
or the device is offline. External player activity can stop the app's lifecycle.

Enrollment without an issued credential uses fallback polling, not a live socket.
The existing not-managed retry policy is retained. Approvals on such installations
can take the next 15-second poll; a manual retry still wakes it immediately.

## Relay design

- Python 3.10+, websockets 17.3; no database or provider credentials in the service.
- Loopback 19094 for WebSockets behind the existing Nginx HTTPS origin.
- Loopback 19095 for authenticated internal wake requests, never proxied publicly.
- Dedicated independent secret at `storage/live-connection.key`, shared with the
  service's root-only environment file. The application encryption key is not copied.
- Five-minute single-use HMAC tickets minted by the authenticated device API.
  Tickets bind device and tenant, omit the app credential, and travel only in the
  Authorization header. No URL query tokens, browser origins or client commands.
- WS ping/pong detects dead connections. Expired tickets close their socket;
  reconnect obtains a new one. Active connections and replay-cache size are bounded.
- A revoked credential cannot fetch commands even during its ticket's remaining
  lifetime. Wake hints do not reveal a policy or any command/customer content.
- Duplicate wake hints coalesce. They are never stored as extra device commands.

## Delivery

Requires both a new signed APK and the control-panel backend/service update.
The marketing website ZIP is unrelated. No native APK is published by installing
the server package. Deploy the server first, then test an in-place APK update on
the existing testing installation before customer publication.

The backend installer targets the established 0.40.0 control site and verifies
baseline hashes, backs up changed PHP files and Nginx, installs the separate
service and enables only the exact WebSocket location. It refuses a different
version, unexpected edits or an existing relay installation. Failure after
website writes restores the backend and Nginx. It does not migrate the database,
rewrite config.php, restart payment/auth workers or change branding/signing keys.

Nginx multi-server/custom vhosts need a reviewed manual inclusion of
`control-center/live-connection/nginx-location.conf`. Port 19095 must remain
loopback only. Before deployment confirm the PHP worker can read the new key;
the installer matches the existing application.key ownership.

## Acceptance on a connected test device

1. Record the installed package, signer, device ID and device record.
2. Apply the matching signed testing APK in place; do not clear data.
3. Keep the app open without touching it for more than five minutes. Check-ins
   must continue and activity must remain active.
4. Queue a message and a supported settings action; confirm receipt promptly and
   exactly once. Observe during playback as well as on the home screen.
5. Disconnect/reconnect Wi-Fi; verify immediate retry without new enrollment.
6. Stop the relay temporarily on a test host; confirm fallback polling, then
   restore it and confirm live reconnection.
7. Confirm a second reseller/device never receives the first device's hint.
8. Compare identity/record with step 1 and verify existing recovery restrictions.

Source/unit verification does not substitute for these installed-device checks.

## My Hub deployment baseline

Sites updated with the November 5 My Hub workspace-access/showcase packages have
different `Auth.php` and `Application.php` hashes while still reporting 0.40.0.
Use `Extreme-Control-Live-Connection-0.41.0-MyHub.zip` for that exact baseline.
Generate it with `package-update.py OUTPUT.zip My-Hub-reseller-showcase.zip`;
the generator verifies the released showcase archive hash and performs a
three-way merge against the pre-live baseline, failing on merge conflicts.
The resulting package adds only the live audit hook and live-ticket route to
those two My Hub files, preserving the My Hub authentication and routes.
Its baseline checks remain mandatory. The ordinary package's mismatch refusal
happens before backups, service creation, or website writes, so that failure
can be followed directly by the matching My Hub package installation.
