EXTREME HUB MANAGER — DRAG-AND-DROP aaPANEL WEBSITE

This is the WEBSITE ZIP, not the Python/systemd installer.
No terminal commands, Python service, database server or reverse proxy are needed.

1. In aaPanel create hubmanager.extremeiptv.net as a PHP 8.1+ website.
   Enable HTTPS and Force HTTPS. Do NOT select a pure static/HTML site.
2. Upload the ZIP to that site's document root and extract it there.
   index.php, config.php and admin.js must be directly in the document root,
   not inside an extra hub-manager or php-site subfolder.
3. Remove aaPanel's placeholder index.html if it takes priority over index.php.
   Do not remove this package's admin.html.
4. Make the _private directory writable by the site's PHP user (normally www).
   Use aaPanel's permission/owner tool. Do not set everything to 777.
5. Visit https://hubmanager.extremeiptv.net/ once to initialize the site.
   Then open _private/setup-key.php in aaPanel File Manager and copy the
   64-character key on its last line. It is generated privately ON YOUR SERVER.
   Never share that key publicly or paste it into a URL.
6. Visit https://hubmanager.extremeiptv.net/setup/ and use the setup key to
   create your two separate accounts with different passwords.
7. Visit https://hubmanager.extremeiptv.net/ to sign in and manage maintenance.

No working passwords/setup keys are included in the public ZIP. There are NO
default admin passwords. The generated key is also your owner recovery key: setup resets
both admin accounts and their admin sessions, preserving maintenance and audit.

API check: https://hubmanager.extremeiptv.net/api/maintenance/
Expected: JSON with active:false. The Android app's URL without a trailing slash
also works through the web server's normal directory redirect.

Do not enable CDN/page/proxy caching on this hostname. Status must be no-store.
Keep the PHP handler enabled for all .php files, including _private/*.php.
The private state is stored as guarded executable PHP, not downloadable JSON;
requesting it directly returns 404. No SQLite/PDO extension is required.
The .htaccess file adds protection for Apache; Nginx still relies on the .php
handler and the built-in guard. Do not expose backup copies of config/state.

IMPORTANT: Save your setup key privately, then DELETE THIS ZIP FROM THE PUBLIC
SITE FOLDER after extraction. Never publish a ZIP/backup of an initialized site:
that would include its generated private setup key and state.
Also remove config.example.php and README.txt from the public website if desired.
Never upload a backup archive of _private into a public website folder.

The app checks about every 10 seconds; allow roughly 10–15 seconds for connected
updated apps to block/unblock. Internal playback is stopped, navigation is blocked,
and customer logins are kept. Offline devices cannot receive new commands and old
APKs cannot be blocked. Already-blocked devices stay blocked offline until they
reconnect and receive an explicit off status. External players cannot be closed.

Maintenance starts OFF. Provisioning accounts does not enable it. End maintenance
before taking this website offline. Keep config.php and _private when updating
the website; those contain the setup authority and saved status/accounts.

Security: hashed passwords, hashed session tokens, Secure/HttpOnly/SameSite cookies,
12-hour admin sessions, strict-origin admin POSTs, rate-limited login attempts,
atomic locked writes, revision conflict checks, latest 1,000 status changes audited.
With a trusted CDN/proxy, configure aaPanel's real-IP handling so rate limiting
uses the actual visitor IP, not a shared proxy IP. Do not trust arbitrary XFF.

EPG REBUILD CONTROLS
The EPG section requires a separate update on the actual EPG worker server plus
matching epg_worker_url/epg_worker_secret entries in this website's config.php.
See the EPG Controls Update / Worker Admin Update deployment instructions.
Merely extracting the website cannot restart a pull on a different machine.
