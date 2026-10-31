"""Build a frozen helper module from the supplied, reviewed auth-worker source.
Never imports the old app, starts it, reads config/env, or ships its portal routes.
"""
import ast
import copy
import hashlib

BASE_SHA='c5a93d1a4d98f214a4427e8ac0ca7dbdcb03fb5d59632ad4212740a188a7e8d1'
TRIAL_HOOK=b'\n# EXTREME_BRANDED_TRIALS_V1\nfrom branded_trials import install_trial_routes\ninstall_trial_routes(app, globals())\n'
CATALOGUE_HOOK=b'\n# EXTREME_RENEWAL_CATALOGUE_ONLY_V1\nfrom branded_renewal_catalogue import install_catalogue_routes\ninstall_catalogue_routes(app, globals())\n'
FUNCTIONS={
    '_ensure_tunnel_and_pool','_start_tunnel_and_pool','_stop_tunnel','panel_db_cursor',
    '_utcnow','_from_ts','_add_months','_add_duration','_log_credit_change',
    '_credit_trigger_active','_set_credits','_lock_reseller_row','_lock_line_row',
    '_parse_id_list','_reseller_package_scope','_package_allowed','_renewal_window',
    '_overcast_login','_multipart_form','_overcast_save_customer','_overcast_refresh_customer',
}
PROLOGUE='''# Generated from reviewed supplied auth-worker helpers. No original app/routes.
import calendar
import datetime
import http.cookiejar
import json
import logging
import threading
import time
import urllib.parse
import urllib.request
import urllib.error
import uuid
from contextlib import contextmanager
from decimal import Decimal
import mysql.connector
import mysql.connector.pooling
from sshtunnel import SSHTunnelForwarder
try:
    from config import settings
except Exception:
    raise RuntimeError("Private panel configuration could not load; values omitted") from None

SOURCE_SHA = "'''+BASE_SHA+'''"
class _PrivatePanelLog(logging.Filter):
    def filter(self,record):
        record.msg="Native panel operation (private details omitted)"
        record.args=();record.exc_info=None;record.exc_text=None;record.stack_info=None
        return True
log=logging.getLogger("iptv-renewal-worker.panel")
log.addFilter(_PrivatePanelLog())
_tunnel=None
_pool=None
_tunnel_lock=threading.Lock()
_trigger_state={"at":0.0,"active":None}
_overcast_cookiejar=http.cookiejar.CookieJar()
_overcast_opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(_overcast_cookiejar))
'''


def build(source):
    if not isinstance(source,bytes):raise ValueError('Source must be bytes')
    if source.endswith(CATALOGUE_HOOK):source=source[:-len(CATALOGUE_HOOK)]
    if source.endswith(TRIAL_HOOK):source=source[:-len(TRIAL_HOOK)]
    if hashlib.sha256(source).hexdigest()!=BASE_SHA:raise ValueError('Supplied native helper source differs; review before building')
    selected=[]
    for node in ast.parse(source).body:
        if isinstance(node,ast.FunctionDef) and node.name in FUNCTIONS:
            node=copy.deepcopy(node)
            node.decorator_list=[d for d in node.decorator_list if node.name=='panel_db_cursor' and isinstance(d,ast.Name) and d.id=='contextmanager']
            selected.append(node)
    if {node.name for node in selected}!=FUNCTIONS or len(selected)!=len(FUNCTIONS):raise ValueError('Native helper set differs')
    result=PROLOGUE+'\n\n'+ast.unparse(ast.Module(body=selected,type_ignores=[]))+'\n'
    ast.parse(result)
    return result
