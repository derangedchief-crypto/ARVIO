#!/usr/bin/env python3
"""Isolated installer contracts; no real systemd, database, root or payments."""
from pathlib import Path
import os
import socket
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'deploy/fix-renewal-jobs-socket.sh').read_text()
assert 'PrivateTmp=true' in (root / 'deploy/renewal-jobs.service').read_text()
assert 'BindReadOnlyPaths=' in source and 'PrivateTmp=false' not in source
assert 'restart' not in '\n'.join(line for line in source.splitlines() if not line.startswith('#'))
assert 'fix-renewal-jobs-socket.sh' in (root / 'deploy/connect-renewal-worker.sh').read_text()

with tempfile.TemporaryDirectory(prefix='renewal-socket-test-', dir='/tmp/opencode') as tmp:
    base = Path(tmp)
    site, bins, private = base / 'site', base / 'bin', base / 'private'
    for path in [site, bins, private]:
        path.mkdir()
    sock = base / 'mysql.sock'
    server = socket.socket(socket.AF_UNIX)
    server.bind(str(sock))
    dropin = base / 'units/renewal-jobs.service.d/30-database-socket.conf'
    log = base / 'commands.log'
    php = bins / 'php'
    def executable(path, text):
        path.write_text(text)
        path.chmod(0o700)
    executable(bins / 'id', '#!/bin/sh\necho 0\n')
    executable(bins / 'runuser', '#!/bin/sh\n[ "$1" = -u ] && [ "$2" = www ] && [ "$3" = -- ] || exit 99\nshift 3\nexec "$@"\n')
    executable(php, '#!/bin/sh\ncat >/dev/null\n[ "${PHP_FAIL:-0}" = 0 ] || exit 2\nprintf "%s" "$SOCKET_PATH"\n')
    executable(bins / 'systemctl', '''#!/bin/sh
printf '%s\n' "$*" >> "$COMMAND_LOG"
case "$*" in
  'show renewal-jobs.service -p User --value') echo "${JOB_USER:-www}" ;;
  'show renewal-jobs.service -p WorkingDirectory --value') echo "$TEST_SITE" ;;
  'show renewal-jobs.service -p ExecStart --value') echo "$TEST_PHP -d opcache.enable_cli=0 cli/console.php renewal-jobs" ;;
  'show renewal-jobs.service -p PrivateTmp -p BindReadOnlyPaths') echo 'PrivateTmp=yes' ;;
esac
''')
    executable(bins / 'install', '''#!/usr/bin/env python3
import os,sys
args=[];i=1
while i<len(sys.argv):
    if sys.argv[i] in ('-o','-g'): i+=2
    else: args.append(sys.argv[i]);i+=1
os.execv('/usr/bin/install',['install',*args])
''')
    script = base / 'fix.sh'
    text = source.replace('/www/wwwroot/control.fecxbrfzku.net', str(site)).replace('/www/server/php/84/bin/php', str(php)).replace('/etc/systemd/system/renewal-jobs.service.d/30-database-socket.conf', str(dropin)).replace('/root/renewal-socket-dropin-', str(private / 'dropin-'))
    executable(script, text)
    env = dict(os.environ, PATH=str(bins)+':'+os.environ['PATH'], SOCKET_PATH=str(sock), COMMAND_LOG=str(log), TEST_SITE=str(site), TEST_PHP=str(php))
    def run(**overrides):
        return subprocess.run(['bash', str(script)], env=env | overrides, text=True, capture_output=True)
    result = run()
    assert result.returncode == 0, result.stderr
    expected = '# Managed renewal job database socket access\n[Service]\nBindReadOnlyPaths='+str(sock)+'\n'
    assert dropin.read_text() == expected
    commands = log.read_text()
    assert 'daemon-reload' in commands and 'start --no-block renewal-jobs.service' in commands
    assert 'restart' not in commands and 'mysql.service' not in commands
    assert 'enable --now renewal-jobs.timer' in commands
    print('PASS narrow read-only socket binding keeps PrivateTmp and starts only the managed job/timer')
    assert run().returncode == 0
    print('PASS identical managed socket override can be installed again safely')
    dropin.write_text('# user override\n[Service]\nBindReadOnlyPaths=/custom.sock\n')
    before = log.read_text()
    assert run().returncode != 0 and dropin.read_text().startswith('# user override')
    assert 'daemon-reload' not in log.read_text()[len(before):]
    print('PASS differing user override is preserved without reloading systemd')
    dropin.unlink()
    for overrides in [{'PHP_FAIL':'1'}, {'SOCKET_PATH':str(base / 'missing.sock')}, {'SOCKET_PATH':'/tmp/unsafe%name.sock'}, {'JOB_USER':'root'}]:
        before = log.read_text()
        assert run(**overrides).returncode != 0
        assert not dropin.exists() and 'daemon-reload' not in log.read_text()[len(before):]
    print('PASS failed connection probe missing socket unsafe path and unexpected job user stop without installing')
    target = base / 'user.conf'
    target.write_text('preserve')
    dropin.symlink_to(target)
    assert run().returncode != 0 and target.read_text() == 'preserve'
    print('PASS a symlink override cannot overwrite another file')
    server.close()
