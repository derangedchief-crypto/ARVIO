# IPTV panel notes correction (0.42.0)

Customer and device tables now label the column **Notes** and read the actual
IPTV line's `customers.reseller_notes` through the activation worker. Notes are
retrieved on list load. Empty notes show a dash; failed lookups show
“Panel notes unavailable”. Hub-local private notes are not substituted.

Install the worker update first on the activation-worker host, then the control
update on the website host. Both are available in the `staging-testing` release.

## Activation-worker host

Extract `IPTV-Panel-Notes-Worker.zip` into a new directory and run:

```sh
/opt/iptv-activation-worker/venv/bin/python install-panel-notes.py
curl -fsS https://activationworker.extremeiptv.net/management/line-notes-health
```

Expected health: `{"schema_version":1,"source":"customers.reseller_notes"}`.
The installer registers a separate route in existing main.py, backs up modified
source, restarts the service, and restores source if startup validation fails.
The existing HTTPS proxy currently forwards this management path to the worker.

## Control-panel host

Extract `IPTV-Panel-Notes-Control-0.42.0.zip` into a new directory and run:

```sh
python3 install-panel-notes.py IPTV-Panel-Notes-Control-0.42.0.zip
/etc/init.d/php-fpm-84 reload
```

Supports original 0.42.0 and the online-filter, customer-notes and device-notes
hotfixes. Includes those earlier UI/filter fixes. Validates known source hashes
and PHP syntax before replacing files; prints the backup location.

Refresh the website, compare one customer's Notes with their IPTV line, then
check the device row. Edit or clear the notes in the IPTV panel and reload the
list to confirm the change. Live installation and this operator check are still
required; automated checks use synthetic panel responses.
