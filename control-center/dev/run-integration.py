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
import shutil

repository = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(); parser.add_argument('--browser', action='store_true'); parser.add_argument('--browser-only', action='store_true', help='UI development only: skip service tests; release validation uses --browser'); parser.add_argument('--keep-db', action='store_true'); args = parser.parse_args()
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
        result = subprocess.CompletedProcess([],0) if args.browser_only else subprocess.run(['bash', 'control-center/dev/php.sh', 'control-center/tests/run.php'], cwd=repository, env=environment)
        if result.returncode == 0 and (args.browser or args.browser_only):
            probe = socket.socket(); probe.bind(('127.0.0.1', 0)); port = probe.getsockname()[1]; probe.close()
            preview_env = {**environment, 'CONTROL_PREVIEW_PORT': str(port)}
            seeded = subprocess.run(['bash', 'control-center/dev/php.sh', 'control-center/dev/demo.php'], cwd=repository, env=preview_env, text=True, capture_output=True)
            if seeded.returncode:
                raise SystemExit(seeded.stdout + seeded.stderr)
            preview = Path(seeded.stdout.strip())
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
        # Only this run's mkdtemp-created database, after its process has stopped.
        # Keep reports/logs/source/private fixture keys; never old user previews.
        if not args.keep_db and process.poll() is not None and (state/'data').is_dir():
            shutil.rmtree(state/'data')
