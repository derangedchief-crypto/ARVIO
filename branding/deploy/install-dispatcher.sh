#!/usr/bin/env bash
# Run ONLY from the owner dispatcher setup bundle, on the worker host as root.
# Does not install signing keys, touch existing workers or start/enable a service.
set -euo pipefail
if [[ $(id -u) != 0 ]]; then echo 'Run this owner setup script as root.' >&2; exit 1; fi
source_sha="${1:-}"
if [[ ! "$source_sha" =~ ^[a-f0-9]{40}$ ]]; then echo 'Usage: bash install.sh FULL_REVIEWED_SOURCE_SHA' >&2; exit 1; fi
test -x /usr/bin/python3 || { echo 'Install system Python3 first (/usr/bin/python3).' >&2; exit 1; }
test -x /usr/bin/gh || { echo 'Install system GitHub CLI first (/usr/bin/gh). No signing key is needed on this host.' >&2; exit 1; }
base="$(cd -- "$(dirname -- "$0")" && pwd)"
test -f "$base/branding/dispatch_github.py"
if systemctl is-active --quiet brand-dispatch.timer; then
  echo 'Disable only brand-dispatch.timer before upgrading this dispatcher.' >&2; exit 1
fi
if ! id branddispatch >/dev/null 2>&1; then
  useradd --system --home-dir /var/lib/brand-dispatch --create-home --shell /usr/sbin/nologin branddispatch
fi
install -d -m 0755 -o root -g root /opt/brand-dispatch/branding
install -d -m 0700 -o branddispatch -g branddispatch /var/lib/brand-dispatch
install -m 0644 -o root -g root "$base/branding/dispatch_github.py" /opt/brand-dispatch/branding/dispatch_github.py
install -m 0644 -o root -g root "$base/branding/deploy/brand-dispatch.service" /etc/systemd/system/brand-dispatch.service
install -m 0644 -o root -g root "$base/branding/deploy/brand-dispatch.timer" /etc/systemd/system/brand-dispatch.timer
if [[ ! -e /etc/brand-dispatch.env ]]; then
  umask 077
  {
    echo 'GH_TOKEN='
    echo "BRAND_SOURCE_SHA=$source_sha"
    echo 'BRAND_WORKFLOW_REF=reseller-control-center'
  } > /etc/brand-dispatch.env
  chmod 0600 /etc/brand-dispatch.env
fi
systemctl daemon-reload
echo 'Dispatcher files installed. Existing workers were not changed.'
echo 'Service/timer have NOT been started or enabled by this installer.'
echo 'Privately edit /etc/brand-dispatch.env with the dedicated Actions-write/Contents-read token.'
echo 'Default mode is build-only. Automatic testing publication requires explicit worker and website owner opt-ins.'
echo 'Read branding/GITHUB_RUNNER.md before starting the service.'
echo 'For30second queue-aware polling, configure the private queue token and website endpoint before starting anything.'
