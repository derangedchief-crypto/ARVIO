"""Daily full XMLTV indexer. Runs separately from activation login."""
import gzip
import hashlib
import os
import shutil
import sqlite3
import tempfile
import threading
import time
import xml.etree.ElementTree as ET
from pathlib import Path

import mysql.connector
import requests
from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from sshtunnel import SSHTunnelForwarder

app = FastAPI(title="Extreme EPG Worker")
ROOT = Path(os.getenv("EPG_DATA_DIR", "/var/lib/iptv-epg-worker"))
DB = ROOT / "guide.sqlite"
GZ = ROOT / "guide.sqlite.gz"
HOST = os.getenv("EPG_XMLTV_HOST", "https://tv.extremeiptv.net").rstrip("/")
_state = {"updated_at": 0, "programmes": 0, "channels": 0, "error": None, "phase": "idle", "started_at": 0, "downloaded_bytes": 0, "total_bytes": 0}

def active_line():
    with SSHTunnelForwarder((os.environ["PANEL_SSH_HOST"], int(os.getenv("PANEL_SSH_PORT", "22"))), ssh_username=os.environ["PANEL_SSH_USERNAME"], ssh_password=os.environ["PANEL_SSH_PASSWORD"], remote_bind_address=(os.getenv("PANEL_DB_HOST", "127.0.0.1"), int(os.getenv("PANEL_DB_PORT", "3306")))) as tunnel:
        conn = mysql.connector.connect(host="127.0.0.1", port=tunnel.local_bind_port, database=os.getenv("PANEL_DB_NAME", "overcast"), user=os.getenv("PANEL_DB_USERNAME", "root"), password=os.getenv("PANEL_DB_PASSWORD", ""))
        cur = conn.cursor(dictionary=True)
        cur.execute("SELECT username,password FROM customers WHERE enabled=1 AND activated=1 AND (exp_date IS NULL OR exp_date=0 OR exp_date>UNIX_TIMESTAMP()) ORDER BY id LIMIT 1")
        row = cur.fetchone(); conn.close()
        if not row: raise RuntimeError("No active panel line available for XMLTV refresh")
        return row

def parse_time(value):
    return int(time.mktime(time.strptime(value[:14], "%Y%m%d%H%M%S"))) * 1000

def refresh():
    try:
        _state.update(phase="connecting", started_at=int(time.time() * 1000), downloaded_bytes=0, total_bytes=0, error=None)
        line = active_line()
        _state["phase"] = "downloading"
        response = requests.get(f"{HOST}/xmltv.php", params=line, stream=True, timeout=(20, 900))
        response.raise_for_status()
        _state["total_bytes"] = int(response.headers.get("content-length") or 0)
        ROOT.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=ROOT, suffix=".xml", delete=False) as raw:
            for chunk in response.iter_content(1024 * 1024):
                raw.write(chunk); _state["downloaded_bytes"] += len(chunk)
            raw_path = Path(raw.name)
        _state["phase"] = "indexing"
        temp = ROOT / "guide.next.sqlite"; temp.unlink(missing_ok=True)
        db = sqlite3.connect(temp); db.executescript("CREATE TABLE channels(channel_id TEXT PRIMARY KEY, display_name TEXT); CREATE TABLE programmes(channel_id TEXT,start_ms INTEGER,end_ms INTEGER,title TEXT,description TEXT); CREATE INDEX ix_programmes ON programmes(channel_id,start_ms,end_ms);")
        count = 0; ids = set()
        for _, node in ET.iterparse(raw_path, events=("end",)):
            if node.tag == "channel":
                channel = node.attrib.get("id", "")
                if channel:
                    db.execute("INSERT OR REPLACE INTO channels VALUES(?,?)", (channel, (node.findtext("display-name") or "").strip()))
                node.clear()
            if node.tag == "programme":
                channel=node.attrib.get("channel", ""); start=node.attrib.get("start", ""); end=node.attrib.get("stop", "")
                if channel and start and end:
                    title=(node.findtext("title") or "").strip(); desc=(node.findtext("desc") or "").strip()
                    db.execute("INSERT INTO programmes VALUES(?,?,?,?,?)", (channel, parse_time(start), parse_time(end), title, desc)); count += 1; ids.add(channel)
                    if count % 10_000 == 0: _state.update(programmes=count, channels=len(ids))
                node.clear()
        db.commit(); db.close(); raw_path.unlink(missing_ok=True)
        temp.replace(DB)
        with open(DB, "rb") as source, gzip.open(GZ, "wb", compresslevel=6) as target: shutil.copyfileobj(source, target)
        _state.update(updated_at=int(time.time() * 1000), programmes=count, channels=len(ids), error=None, phase="ready")
    except Exception as error: _state.update(error=str(error), phase="failed")

@app.on_event("startup")
def schedule():
    def loop():
        while True: refresh(); time.sleep(24 * 60 * 60)
    threading.Thread(target=loop, daemon=True).start()

@app.get("/health")
def health(): return {"status": "ok", **_state}

@app.get("/epg/manifest")
def manifest():
    if not GZ.exists(): raise HTTPException(503, "Guide is still building")
    return {**_state, "bytes": GZ.stat().st_size, "sha256": hashlib.sha256(GZ.read_bytes()).hexdigest()}

@app.get("/epg/full.sqlite.gz")
def download():
    if not GZ.exists(): raise HTTPException(503, "Guide is still building")
    return FileResponse(GZ, media_type="application/gzip", filename="guide.sqlite.gz")
