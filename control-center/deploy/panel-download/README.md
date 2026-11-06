# Latest My Hub download on the reseller panel

Install this bundle on the reseller-panel website host:

```sh
python3 install-latest-my-hub.py
/etc/init.d/php-fpm-84 reload
```

The default site is `/www/wwwroot/fecxbrfzku.net`; an optional first argument
selects another site. Requires the existing My Hub showcase and licensing page.
The installer backs up and patches only `am-app/my-hub.php`,
`am-app/my-hub-showcase.php`, and adds `am-app/MyHubRelease.php`.

The active-license download now goes through the existing reseller identity and
license checks and redirects to the highest published `My-Hub-X.Y.Z-testing.apk`
version in ARVIO's `staging-testing` release (GitHub release ID 414147175).
It excludes other apps, unfinished uploads and unexpected download hosts/paths.
It uses anonymous paginated asset requests, with a five-minute private metadata
cache beside the My Hub licensing database. New versioned APK uploads to this
release are picked up automatically within five minutes. No GitHub token is needed.
If the release itself is deleted/recreated, update the fixed release ID.

API failures produce a retry message rather than a stale APK link. Existing
license activation and My TV files are unchanged. Test by activating an existing
valid My Hub license and clicking Download; currently it should download 0.1.8.

Validation: fixture installer/idempotence, active/inactive license checks,
version ordering (0.1.10 over 0.1.9), URL validation, and anonymous live asset
lookup selecting 0.1.8.
