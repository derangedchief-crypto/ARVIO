#!/bin/sh
set -eu
SITE=/www/wwwroot/control.fecxbrfzku.net
test "$(id -u)" = 0
test -f "$SITE/public/index.php"
test -f ./sync-customer-apk.py
install -d -m 755 /usr/local/lib/customer-apk
install -m 755 ./sync-customer-apk.py /usr/local/lib/customer-apk/sync.py
python3 /usr/local/lib/customer-apk/sync.py
printf '%s\n' '*/5 * * * * root /usr/bin/python3 /usr/local/lib/customer-apk/sync.py >> /var/log/customer-apk-sync.log 2>&1' > /etc/cron.d/customer-apk
chmod 644 /etc/cron.d/customer-apk
echo 'Installed: https://control.fecxbrfzku.net/downloads/customer/ExtremeTV.apk'
