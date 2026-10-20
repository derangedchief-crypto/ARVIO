EXTREME EPG WORKER — ADMIN REBUILD CONTROL UPDATE

This update goes on the EXISTING EPG CACHE SERVER via SSH, not in the aaPanel
website's document root. The other ZIP (aaPanel EPG Controls Update) updates the
admin website on your other machine. Both parts are needed.

1. Upload and extract Extreme-EPG-Worker-Admin-Update.zip on the EPG server.
2. Open that extracted folder via SSH and run:

   sudo bash update-admin-control.sh

   Assumes the existing service is iptv-epg-worker, installed at
   /opt/iptv-epg-worker with its venv and epgworker service user.
   If its directory differs:
   sudo EPG_APP_DIR=/actual/worker/path bash update-admin-control.sh

   The updater backs up main.py, preserves provider credentials/cache, creates
   a private connection secret if none exists, installs a systemd environment
   override, and restarts the worker. No new Python packages are required by
   this update. Restarting begins a background guide pull immediately.

3. Privately view the generated connection secret on the EPG server:

   sudo cat /etc/extreme-epg-admin.env

   Copy only the value after EPG_ADMIN_SECRET=. Do not post it in chat.

4. On the OTHER machine, extract the aaPanel EPG Controls Update ZIP directly
   into the existing hubmanager.extremeiptv.net website folder. It intentionally
   does not replace config.php, accounts, maintenance state or setup keys.

5. Edit that website's existing config.php in aaPanel File Manager. Add these
   entries inside its return array (before the final ];):

   'epg_worker_url' => 'https://activationworker.extremeiptv.net',
   'epg_worker_secret' => 'PASTE_THE_PRIVATE_SECRET_VALUE_HERE',

   Keep all existing settings. This PHP file must be executed by PHP, not served
   as plain text. The shared secret is never sent to your browser or to the APK.

6. Sign into hubmanager.extremeiptv.net, then click Check EPG worker. You should
   see phase, last successful pull, and CURRENT versus EXPIRED published coverage.
   Use Rebuild server EPG cache to request a fresh bulk pull at any time.

Protected worker endpoints:
 GET  /epg/admin/status
 POST /epg/admin/refresh
Both require Authorization: Bearer <private secret>. The existing /epg/ Nginx
proxy location forwards these to the same EPG worker on localhost port 9093.
If the website reports HTTP 404, ensure that the update/restart succeeded and
that /epg/admin/ requests reach the EPG worker rather than another service.

The worker now refreshes automatically every SIX HOURS instead of once daily.
The admin panel polls progress only during an active job; duplicate requests do
not start a second local job. It reads actual progress saved by the child process,
fixing the previous idle/updated_at:0 manifest issue. XMLTV timezone offsets are
honoured. Gzip output is published atomically only after indexing/validation.
Most-expired or failed pulls keep the previous published cache available and
report the failure; rebuilding cannot invent guide data absent from the provider.

Once the worker reports READY with current coverage, refresh IPTV/EPG from the
app's Settings once to load the new server artifact immediately. Do not clear
app data or uninstall. Updated APK 2.0.094 also detects widespread expired cache
listings and recovers via a bulk provider/XMLTV import without restoring the
slow playlist-wide per-channel sweep.

Check worker service/logs (no maintenance command is issued):
 sudo systemctl status iptv-epg-worker --no-pager
 sudo journalctl -u iptv-epg-worker -n 60 --no-pager

Never upload /etc/extreme-epg-admin.env or a populated website config.php into
GitHub, the website's public ZIP directory, or an APK. Delete uploaded ZIPs from
the public website folder after extraction. Keep the site/worker behind HTTPS.
