"""Exact additive source hook. Does not import/execute the uploaded worker."""
import ast
import hashlib

BASE_SHA='c5a93d1a4d98f214a4427e8ac0ca7dbdcb03fb5d59632ad4212740a188a7e8d1'
TRIAL_HOOK=b'\n# EXTREME_BRANDED_TRIALS_V1\nfrom branded_trials import install_trial_routes\ninstall_trial_routes(app, globals())\n'
HOOK=b'\n# EXTREME_RENEWAL_CATALOGUE_ONLY_V1\nfrom branded_renewal_catalogue import install_catalogue_routes\ninstall_catalogue_routes(app, globals())\n'


def transform(data):
    if not isinstance(data,bytes):raise ValueError('Worker source must be bytes')
    original=data[:-len(HOOK)] if data.endswith(HOOK) else data
    baseline=original[:-len(TRIAL_HOOK)] if original.endswith(TRIAL_HOOK) else original
    if hashlib.sha256(baseline).hexdigest()!=BASE_SHA:raise ValueError('Unknown worker source; no file was changed')
    result=original+HOOK;ast.parse(result)
    return result
