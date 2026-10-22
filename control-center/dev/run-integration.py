#!/usr/bin/env python3
"""Start an isolated socket-only DB for this test run, then stop only our process.
Does not restart the old user-stopped preview services or contact a live host.
"""
from pathlib import Path
import os
import signal
import socket
import subprocess
import tempfile
import time
import argparse

repository = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(); parser.add_argument('--browser', action='store_true'); args = parser.parse_args()
state = Path(tempfile.mkdtemp(prefix='control-enrollment-tests-', dir='/tmp/opencode'))
state.chmod(0o700)
environment = {**os.environ, 'CONTROL_DEV_DIR': str(state), 'CONTROL_TEST_SOCKET': str(state / 'database.sock')}
with (state / 'startup.log').open('wb') as log:
    process = subprocess.Popen(['bash', 'control-center/dev/start-db.sh'], cwd=repository, env=environment,
                               stdout=log, stderr=log, start_new_session=True)
    web = None
    try:
        for _ in range(200):
            if process.poll() is not None:
                raise SystemExit(f'Local DB startup failed; see {state}/startup.log')
            probe = socket.socket(socket.AF_UNIX)
            try:
                probe.connect(str(state / 'database.sock'))
                break
            except OSError:
                time.sleep(0.1)
            finally:
                probe.close()
        else:
            raise SystemExit(f'Local DB did not become ready; see {state}/startup.log')
        result = subprocess.run(['bash', 'control-center/dev/php.sh', 'control-center/tests/run.php'], cwd=repository, env=environment)
        if result.returncode == 0 and args.browser:
            probe = socket.socket(); probe.bind(('127.0.0.1', 0)); port = probe.getsockname()[1]; probe.close()
            preview_env = {**environment, 'CONTROL_PREVIEW_PORT': str(port)}
            preview = Path(subprocess.check_output(['bash', 'control-center/dev/php.sh', 'control-center/dev/demo.php'], cwd=repository, env=preview_env, text=True).strip())
            web = subprocess.Popen(['bash', 'control-center/dev/php.sh', '-S', f'127.0.0.1:{port}', '-t', str(preview/'public'), str(preview/'dev/router.php')],
                                   cwd=repository, env=preview_env, stdout=log, stderr=log, start_new_session=True)
            for _ in range(100):
                try:
                    with socket.create_connection(('127.0.0.1', port), timeout=0.1): break
                except OSError: time.sleep(0.05)
            browser_env = {**environment, 'CONTROL_PREVIEW_URL': f'http://127.0.0.1:{port}',
                           'LD_LIBRARY_PATH': '/tmp/opencode/control-center-browser-tools/root/usr/lib/x86_64-linux-gnu'}
            result = subprocess.run(['node', 'control-center/tests/browser.cjs', str(preview/'preview-credentials.json')], cwd=repository, env=browser_env)
        raise SystemExit(result.returncode)
    finally:
        if web is not None and web.poll() is None:
            os.killpg(web.pid, signal.SIGTERM)
            try: web.wait(timeout=5)
            except subprocess.TimeoutExpired: os.killpg(web.pid, signal.SIGKILL); web.wait()
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
