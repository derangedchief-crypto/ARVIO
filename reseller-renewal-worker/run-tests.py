#!/usr/bin/env python3
"""Fresh socket-only MariaDB; no live hosts/configs or existing previews."""
from pathlib import Path
import os
import shutil
import signal
import socket
import subprocess
import tempfile
import time

root=Path(__file__).resolve().parent.parent
state=Path(tempfile.mkdtemp(prefix='renewal-native-mysql-',dir=os.environ.get('CONTROL_TEST_TMPDIR','/tmp/opencode')));state.chmod(0o700)
env={**os.environ,'CONTROL_DEV_DIR':str(state),'RENEWAL_TEST_SOCKET':str(state/'database.sock')}
with (state/'startup.log').open('wb') as log:
    process=subprocess.Popen(['bash','control-center/dev/start-db.sh'],cwd=root,env=env,stdout=log,stderr=log,start_new_session=True)
    try:
        for _ in range(250):
            if process.poll() is not None:raise SystemExit('Isolated DB startup failed: '+str(state/'startup.log'))
            probe=socket.socket(socket.AF_UNIX)
            try:probe.connect(str(state/'database.sock'));break
            except OSError:time.sleep(.1)
            finally:probe.close()
        else:raise SystemExit('Isolated DB startup timed out')
        python='/tmp/opencode/control-worker-test-env/bin/python'
        result=subprocess.run([python,'-m','unittest','discover','-s','reseller-renewal-worker','-p','test*.py'],cwd=root,env=env)
        raise SystemExit(result.returncode)
    finally:
        if process.poll() is None:
            os.killpg(process.pid,signal.SIGTERM)
            try:process.wait(timeout=15)
            except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait()
        # Only our own mkdtemp data, after our own server stopped; not old previews.
        if process.poll() is not None and (state/'data').is_dir():shutil.rmtree(state/'data')
