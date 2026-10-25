#!/usr/bin/env python3
"""Upgrade an existing private dispatcher env; never generate or print credentials."""
import os
from pathlib import Path
import re
import tempfile


def configure(path,source):
    if not re.fullmatch('[a-f0-9]{40}',source):raise ValueError('Reviewed main SHA required.')
    if path.is_symlink() or not path.is_file() or path.stat().st_mode&0o077:raise ValueError('Existing private0600 environment required.')
    original=path.read_text();lines=original.splitlines();values={}
    for line in lines:
        if not line or line.startswith('#'):continue
        key,value=line.split('=',1)
        if key in values:raise ValueError('Duplicate private configuration needs review.')
        values[key]=value
    if not values.get('GH_TOKEN') or not re.fullmatch('[a-f0-9]{64}',values.get('BRAND_QUEUE_TOKEN','')):raise ValueError('Original GitHub/queue tokens required; none were replaced.')
    changes={'BRAND_SOURCE_SHA':source,'BRAND_WORKFLOW_REF':'brand-source-2.0.116-'+source,'BRAND_CHANNEL':'production','BRAND_AUTO_PUBLISH_TESTING':'false','BRAND_AUTO_PUBLISH_PRODUCTION':'true'}
    content='\n'.join([line for line in lines if line.split('=',1)[0] not in changes]+[key+'='+value for key,value in changes.items()])+'\n'
    with tempfile.NamedTemporaryFile(mode='w',dir=path.parent,delete=False) as stream:
        temporary=Path(stream.name)
        try:os.chmod(temporary,0o600);stream.write(content);stream.flush();os.fsync(stream.fileno());os.replace(temporary,path)
        finally:temporary.unlink(missing_ok=True)
