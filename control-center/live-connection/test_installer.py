"""Exercise installer writes/rollback in a sandbox with mocked host services."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class InstallerTests(unittest.TestCase):
    def run_install(self, failure=None):
        with tempfile.TemporaryDirectory(prefix='live-installer-', dir='/tmp/opencode') as tmp:
            root = Path(tmp)
            source = Path(__file__).resolve().parent
            bundle = root / 'bundle'
            site = root / 'site'
            commands = root / 'bin'
            for directory in (bundle / 'src', bundle / 'live-connection', site / 'src', site / 'storage',
                              commands, root / 'etc/systemd/system', root / 'root', root / 'opt'):
                directory.mkdir(parents=True, exist_ok=True)
            def executable(path, text):
                path.write_text('#!/usr/bin/env bash\nset -e\n' + text)
                path.chmod(0o755)
            old = {}
            for name in ('Auth', 'Application', 'Database', 'DeviceProfiles'):
                old['src/' + name + '.php'] = b'old ' + name.encode()
            old['VERSION'] = b'0.40.0\n'
            for name, data in old.items():
                (site / name).write_bytes(data)
                (bundle / name).write_bytes(b'new')
            (bundle / 'src/LiveConnection.php').write_text('new')
            (bundle / 'BASELINE.sha256').write_text(''.join(
                f'{hashlib.sha256(data).hexdigest()}  {name}\n' for name, data in old.items()))
            (site / 'config.php').write_text('private')
            (site / 'storage/application.key').write_text('existing-key')
            (root / 'nginx.conf').write_text('server {\n listen 443 ssl;\n}\n')
            for name in ('service.py', 'requirements.txt', 'nginx-location.conf', 'extreme-live-connection.service'):
                shutil.copyfile(source / name, bundle / 'live-connection' / name)
            script = (source / 'install.sh').read_text()
            replacements = {
                '/www/wwwroot/control.fecxbrfzku.net': str(site),
                '/www/server/php/84/bin/php': str(commands / 'php'),
                '/www/server/nginx/sbin/nginx': str(commands / 'nginx'),
                '/www/server/panel/vhost/nginx/control.fecxbrfzku.net.conf': str(root / 'nginx.conf'),
                '/opt/extreme-live-connection': str(root / 'opt/extreme-live-connection'),
                '/etc/': str(root / 'etc') + '/',
                '/root/': str(root / 'root') + '/',
            }
            for before, after in replacements.items():
                script = script.replace(before, after)
            installer = bundle / 'live-connection/install.sh'
            installer.write_text(script)
            (bundle / 'MANIFEST.sha256').write_text(''.join(
                f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(bundle)}\n'
                for p in bundle.rglob('*') if p.is_file()))
            executable(commands / 'id', 'if [[ "${1:-}" == -u ]]; then echo 0; fi\n')
            for name in ('php', 'systemctl', 'curl', 'useradd'):
                executable(commands / name, 'exit 0\n')
            executable(commands / 'nginx',
                       f'if [[ "${{FAILURE:-}}" == nginx ]] && grep -q /api/device/live "{root}/nginx.conf"; then exit 1; fi\n')
            real_python = shutil.which('python3')
            executable(commands / 'python3', f'''if [[ "${{1:-}}" == -m && "${{2:-}}" == venv ]]; then
mkdir -p "$3/bin"
printf '#!/bin/sh\\nexit 0\\n' > "$3/bin/pip"
chmod 700 "$3/bin/pip"
exit 0
fi
exec "{real_python}" "$@"
''')
            if failure == 'baseline':
                (site / 'src/Auth.php').write_text('unexpected edit')
            result = subprocess.run(['bash', str(installer)], env={**os.environ,
                'PATH': str(commands) + ':' + os.environ['PATH'], 'FAILURE': failure or ''},
                capture_output=True, text=True)
            self.assertEqual((site / 'config.php').read_text(), 'private')
            self.assertEqual((site / 'storage/application.key').read_text(), 'existing-key')
            if failure:
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual((site / 'VERSION').read_text(), '0.40.0\n')
                self.assertNotIn('/api/device/live', (root / 'nginx.conf').read_text())
                self.assertFalse((site / 'storage/live-connection.key').exists())
                self.assertFalse((site / 'src/LiveConnection.php').exists())
                if failure == 'nginx':
                    for name, data in old.items():
                        self.assertEqual((site / name).read_bytes(), data)
            else:
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual((site / 'VERSION').read_text(), '0.41.0\n')
                self.assertIn('/api/device/live', (root / 'nginx.conf').read_text())
                self.assertEqual((site / 'storage/live-connection.key').stat().st_mode & 0o777, 0o600)
                self.assertEqual((root / 'opt/extreme-live-connection/venv/bin/pip').stat().st_mode & 0o005, 0o005)

    def test_success_and_service_read_permissions(self):
        self.run_install()

    def test_failed_nginx_validation_restores_backend_and_keys(self):
        self.run_install('nginx')

    def test_unexpected_baseline_stops_before_installation(self):
        self.run_install('baseline')


if __name__ == '__main__':
    unittest.main()
