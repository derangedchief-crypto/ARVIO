#!/usr/bin/env bash
# Owner-only, restricted WEBSITE SSH bridge. Never runs on the worker host.
set -euo pipefail
case "${1:-}" in ''|--check) ;; *) echo 'Usage: bash install.sh [--check]' >&2; exit 2 ;; esac
[[ $(id -u) == 0 ]] || { echo 'Run on the website host as root.' >&2; exit 1; }
site="${BRAND_SITE_ROOT:-/www/wwwroot/control.fecxbrfzku.net}"
php="${BRAND_PHP_BINARY:-/www/server/php/84/bin/php}"
web="${BRAND_WEB_USER:-www}"
account=brandbridge
home=/var/lib/extreme-brand-bridge
entry=/usr/local/lib/extreme-brand-bridge/entry.sh
base="$(cd -- "$(dirname -- "$0")" && pwd)"
for command in setfacl getfacl runuser useradd ssh-keygen sha256sum stat; do
  command -v "$command" >/dev/null || { echo "Missing $command. On Ubuntu install the acl package if setfacl/getfacl are missing." >&2; exit 1; }
done
[[ "$site" =~ ^/[a-zA-Z0-9._/-]+$ && "$php" =~ ^/[a-zA-Z0-9._/-]+$ && "$web" =~ ^[a-z_][a-z0-9_-]*$ ]] || exit 1
test -x "$php"; id "$web" >/dev/null
for path in "$site" "$site/storage" "$site/storage/brand-apks" "$home" "$home/.ssh" "$home/.ssh/authorized_keys" "$(dirname "$entry")" "$entry"; do
  test ! -L "$path" || { echo 'Setup paths must not be symlinks; stop for owner review.' >&2; exit 1; }
done
for path in "$site/config.php" "$site/storage/application.key" "$site/cli/brand-builder.php" "$site/src/Config.php" "$site/VERSION"; do
  test -f "$path" && test ! -L "$path" || { echo 'Expected installed site files are missing or symlinked; stop for review.' >&2; exit 1; }
done
"$php" -d display_errors=stderr -d opcache.enable_cli=0 -r '
  if(PHP_VERSION_ID<80300)exit(1);
  foreach(["pdo_mysql","mbstring","openssl","fileinfo","curl","ctype"] as $e)if(!extension_loaded($e))exit(1);
  exit(version_compare(trim(file_get_contents($argv[1])),"0.14.1",">=")?0:1);
' "$site/VERSION" || { echo 'Install website0.14.1+ and the required PHP extensions first.' >&2; exit 1; }
test -f "$base/bridge-public-key.pub"
mapfile -t keys < "$base/bridge-public-key.pub"
[[ ${#keys[@]} == 1 && "${keys[0]}" =~ ^ssh-ed25519[[:space:]][A-Za-z0-9+/=]+([[:space:]][a-zA-Z0-9._-]+)?$ ]] || { echo 'Invalid dedicated public key.' >&2; exit 1; }
ssh-keygen -lf "$base/bridge-public-key.pub" >/dev/null
# Do not silently unmask unrelated pre-existing ACL grants during setup.
paths=("$site" "$site/config.php" "$site/VERSION" "$site/storage" "$site/storage/application.key")
parent="$(dirname "$site")"
while [[ "$parent" != / ]]; do paths+=("$parent");parent="$(dirname "$parent")";done
for tree in src cli database; do
  if [[ -n "$(find "$site/$tree" -type l -print -quit)" ]]; then echo 'Source symlinks need owner review.' >&2; exit 1; fi
  acls="$(find "$site/$tree" -exec getfacl -cp {} +)"
  if grep -q '#effective:' <<< "$acls"; then echo 'Pre-existing masked source ACLs need owner review.' >&2; exit 1; fi
done
acls="$(getfacl -cp "${paths[@]}")"
if grep -q '#effective:' <<< "$acls"; then echo 'Pre-existing masked private/path ACLs need owner review.' >&2; exit 1; fi
if [[ "${1:-}" == --check ]]; then
  echo 'Prerequisites OK. No account, ACL or config changes made.'
  ssh-keygen -E sha256 -lf /etc/ssh/ssh_host_ed25519_key.pub
  exit 0
fi
if id "$account" >/dev/null 2>&1; then
  test -f "$home/.managed" || { echo 'An unrelated brandbridge account exists; stop for owner review.' >&2; exit 1; }
  [[ "$(id -Gn "$account")" == "$account" ]] || { echo 'Unexpected supplementary bridge groups; stop for review.' >&2; exit 1; }
else
  test ! -e "$home" && test ! -e "$(dirname "$entry")" || { echo 'Bridge paths already exist; stop for review.' >&2; exit 1; }
  # An impossible password, not a reusable secret. Only the restricted public key works.
  useradd --system --user-group --home-dir "$home" --shell /bin/sh --password '*' "$account"
fi
[[ "$(stat -c %U "$site/storage/application.key")" != "$account" ]] || { echo 'Bridge must not own the application key.' >&2; exit 1; }
authline="restrict,command=\"$entry\" ${keys[0]}"
if [[ -f "$home/.ssh/authorized_keys" && "$(cat "$home/.ssh/authorized_keys")" != "$authline" ]]; then
  echo 'Existing bridge authorization differs; no keys overwritten. Review explicitly.' >&2; exit 1
fi
key_before="$(sha256sum "$site/storage/application.key")"
config_before="$(sha256sum "$site/config.php")"
install -d -m 0755 -o root -g root "$home" "$home/.ssh" "$(dirname "$entry")"
if [[ ! -e "$home/pre-setup.acl" ]]; then
  umask 077
  getfacl -p "${paths[@]}" > "$home/pre-setup.acl"
  for tree in src cli database; do find "$site/$tree" -exec getfacl -p {} + >> "$home/pre-setup.acl"; done
  if [[ -d "$site/storage/brand-apks" ]]; then getfacl -p "$site/storage/brand-apks" >> "$home/pre-setup.acl"; fi
  chown root:root "$home/pre-setup.acl";chmod 0600 "$home/pre-setup.acl"
fi
touch "$home/.managed"; chown root:root "$home/.managed"; chmod 0644 "$home/.managed"
printf '#!/bin/sh\nunset SSH_ORIGINAL_COMMAND\nexec %s -d display_errors=stderr -d display_startup_errors=0 -d opcache.enable_cli=0 %s/cli/brand-builder.php\n' "$php" "$site" > "$entry"
chown root:root "$entry"; chmod 0755 "$entry"
printf '%s\n' "$authline" > "$home/.ssh/authorized_keys"
chown root:root "$home/.ssh/authorized_keys"; chmod 0644 "$home/.ssh/authorized_keys"
# Read/traverse only; never join the web user's group or grant source/config/key writes.
parent="$site"
while [[ "$parent" != / ]]; do setfacl -m "u:$account:--x" "$parent"; parent="$(dirname "$parent")"; done
for tree in src cli database; do
  find "$site/$tree" -type d -exec setfacl -m "u:$account:r-x" {} +
  find "$site/$tree" -type f -exec setfacl -m "u:$account:r--" {} +
done
setfacl -m "u:$account:r--" "$site/config.php" "$site/VERSION" "$site/storage/application.key"
setfacl -m "u:$account:--x" "$site/storage"
webgroup="$(id -gn "$web")"
install -d -m 2750 -o "$web" -g "$webgroup" "$site/storage/brand-apks"
setfacl -m "u:$account:rwx" "$site/storage/brand-apks"
for path in "$site/config.php" "$site/storage/application.key" "$site/src/Config.php" "$site/src" "$entry" "$home/.ssh/authorized_keys"; do
  runuser -u "$account" -- test ! -w "$path" || { echo 'Unexpected write permission; bridge not safe to enable.' >&2; exit 1; }
done
runuser -u "$account" -- "$php" -d display_errors=stderr -d opcache.enable_cli=0 -r '
  require $argv[1]."/src/bootstrap.php";
  $c=new \ControlCenter\Config($argv[1]);$c->secret("application.key",false);
  echo "Existing application key read OK; no key value displayed.\n";
' "$site"
[[ "$key_before" == "$(sha256sum "$site/storage/application.key")" && "$config_before" == "$(sha256sum "$site/config.php")" ]] || { echo 'Unexpected private-file content change; stop for review.' >&2; exit 1; }
echo 'Restricted bridge installed. Existing config/key CONTENTS and worker services unchanged.'
echo 'No public signup, builder pickup, publication or timer was enabled by this installer.'
echo 'Server public host-key fingerprint:'
ssh-keygen -E sha256 -lf /etc/ssh/ssh_host_ed25519_key.pub
