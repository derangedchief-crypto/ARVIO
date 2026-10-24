#!/usr/bin/env python3
"""One queue operation in a disposable, exact-source checkout. No GitHub workflow.

Run periodically through the provided systemd timer. Owner environment selects
the reviewed commit/channel/publication policy; queue inputs cannot change them.
"""
import argparse
import fcntl
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
import time

REPOSITORY = 'https://github.com/derangedchief-crypto/ARVIO.git'


def command(args, *, env=None):
    process = subprocess.Popen(args, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               start_new_session=True)
    try:process.communicate(timeout=7200)
    except BaseException:
        # Never leave a detached signing/publishing child running after cleanup.
        try:os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:pass
        process.communicate()
        raise
    if process.returncode:
        raise RuntimeError('Standalone operation failed; inspect queue state before retrying publication.')


def operate(args):
    source = os.environ.get('BRAND_SOURCE_SHA', '')
    if not re.fullmatch('[a-f0-9]{40}', source):
        raise ValueError('Pin the full owner-reviewed source commit.')
    state = Path(os.environ['BRAND_RUNNER_STATE']).resolve(strict=True)
    if state.stat().st_mode & 0o077:
        raise ValueError('Runner state must be private (0700).')
    if args.build_id and not re.fullmatch('[a-f0-9]{32}', args.build_id):
        raise ValueError('Invalid build identity.')
    with (state/'.operation.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        store = state/'artifacts';store.mkdir(mode=0o700, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='job-', dir=state) as temporary:
            base = Path(temporary);checkout = base/'source';private = base/'private';private.mkdir(mode=0o700)
            command(['git', '-c', 'credential.helper=', '-c', 'credential.helper=!gh auth git-credential',
                     'clone', '--no-checkout', '--no-hardlinks', REPOSITORY, str(checkout)])
            command(['git', '-C', str(checkout), '-c', 'core.hooksPath=/dev/null', 'checkout', '--detach', source])
            env = dict(os.environ, RUNNER_TEMP=str(private), GITHUB_SHA=source)
            env.pop('GITHUB_RUN_ID', None)
            # Code, signing inputs and bridge configuration are exclusively owner-controlled.
            command([sys.executable, str(checkout/'branding/configure_runner.py')], env=env)
            env['BRAND_BUILDER_SSH_KEY_FILE'] = str(private/'brand-builder-ssh-key')
            env['BRAND_BUILDER_KNOWN_HOSTS_FILE'] = str(private/'brand-builder-known-hosts')
            invocation = [sys.executable, str(checkout/'branding/central_builder.py'), args.mode,
                          '--channel', args.channel, '--workdir', str(base/'work'),
                          '--artifact-store', str(store), '--retention-id', str(time.time_ns())]
            if args.build_id:invocation += ['--build-id', args.build_id]
            if args.publish:invocation += ['--publish']
            command(invocation, env=env)


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['run', 'reconcile'])
    parser.add_argument('--channel', choices=['testing', 'production'], default='testing')
    parser.add_argument('--build-id', default='')
    parser.add_argument('--publish', action='store_true')
    args = parser.parse_args()
    if args.mode == 'reconcile' and (not args.build_id or args.publish):
        parser.error('Reconcile needs an exact build ID and never publishes.')
    try:operate(args)
    except Exception:
        print('Standalone builder stopped; check owner configuration and private queue state. No automatic uncertain-publication retry.', file=sys.stderr)
        return 1
    print('Queue operation finished. Only approved brand channels may have changed.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
