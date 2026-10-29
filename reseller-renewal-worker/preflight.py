"""Read-only startup proof, run as the renewal user before systemd startup.
No payment request, renewal, schema alteration or credit movement is called.
"""
import asyncio
import contextlib
import io
import json
import logging
import traceback
from pathlib import Path


async def check():
    import main
    async with main.lifespan(main.app):pass


if __name__=='__main__':
    logging.disable(logging.CRITICAL)
    try:
        with contextlib.redirect_stdout(io.StringIO()),contextlib.redirect_stderr(io.StringIO()):asyncio.run(check())
    except Exception as error:
        print(json.dumps({'preflight':'failed','error_type':type(error).__name__,'frames':[{'file':Path(f.filename).name,'line':f.lineno} for f in traceback.extract_tb(error.__traceback__)[-8:]]}))
        raise SystemExit(1)
    print(json.dumps({'preflight':'passed','panel_writes':False}))
