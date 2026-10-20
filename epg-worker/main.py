"""Daily full XMLTV indexer. Runs separately from activation login."""
import gzip
import fcntl
import hashlib
import hmac
import json
import multiprocessing
import os
import shutil
import sqlite3
import tempfile
import threading
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from datetime import datetime, timezone

import mysql.connector
import requests
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse
from sshtunnel import SSHTunnelForwarder

app = FastAPI(title="Extreme EPG Worker")
ROOT = Path(os.getenv("EPG_DATA_DIR", "/var/lib/iptv-epg-worker"))
DB = ROOT / "guide.sqlite"
GZ = ROOT / "guide.sqlite.gz"
HOST = os.getenv("EPG_XMLTV_HOST", "https://tv.extremeiptv.net").rstrip("/")
SOURCE_URL = os.getenv("EPG_XMLTV_URL", "").strip()
_state = {"updated_at": 0, "programmes": 0, "channels": 0, "error": None, "phase": "idle", "started_at": 0, "downloaded_bytes": 0, "total_bytes": 0}
_process_lock = threading.Lock()
_active_process = None

class GuideRefreshError(Exception):
    pass

def read_state():
    try:
        return {**_state, **json.loads((ROOT / "progress.json").read_text())}
    except (OSError, ValueError):
        return dict(_state)

def update_state(**values):
    _state.update(values); ROOT.mkdir(parents=True, exist_ok=True)
    target = ROOT / f"progress.{os.getpid()}.tmp"
    target.write_text(json.dumps(_state))
    target.replace(ROOT / "progress.json")

def active_line():
    username = os.getenv("EPG_PULL_USERNAME", "").strip()
    password = os.getenv("EPG_PULL_PASSWORD", "")
    if username and password:
        return {"username": username, "password": password}
    with SSHTunnelForwarder((os.environ["PANEL_SSH_HOST"], int(os.getenv("PANEL_SSH_PORT", "22"))), ssh_username=os.environ["PANEL_SSH_USERNAME"], ssh_password=os.environ["PANEL_SSH_PASSWORD"], remote_bind_address=(os.getenv("PANEL_DB_HOST", "127.0.0.1"), int(os.getenv("PANEL_DB_PORT", "3306")),), ssh_timeout=15, tunnel_timeout=15) as tunnel:
        conn = mysql.connector.connect(host="127.0.0.1", port=tunnel.local_bind_port, database=os.getenv("PANEL_DB_NAME", "overcast"), user=os.getenv("PANEL_DB_USERNAME", "root"), password=os.getenv("PANEL_DB_PASSWORD", ""), connection_timeout=15)
        cur = conn.cursor(dictionary=True)
        cur.execute("SELECT username,password FROM customers WHERE enabled=1 AND activated=1 AND (exp_date IS NULL OR exp_date=0 OR exp_date>UNIX_TIMESTAMP()) ORDER BY id LIMIT 1")
        row = cur.fetchone(); conn.close()
        if not row: raise RuntimeError("No active panel line available for XMLTV refresh")
        return row

def parse_time(value):
    parts = value.strip().split()
    stamp = parts[0]
    pattern = "%Y%m%d%H%M%S" if len(stamp) == 14 else "%Y%m%d%H%M"
    parsed = datetime.strptime(stamp + (" " + parts[1] if len(parts) > 1 else ""),
                               pattern + (" %z" if len(parts) > 1 else ""))
    return int(parsed.replace(tzinfo=parsed.tzinfo or timezone.utc).timestamp() * 1000)

def guide_coverage(db, now_ms):
    total = db.execute("SELECT COUNT(DISTINCT channel_id) FROM programmes").fetchone()[0]
    current = db.execute("SELECT COUNT(DISTINCT channel_id) FROM programmes WHERE end_ms>? AND start_ms<?",
                         (now_ms, now_ms + 6 * 3600000)).fetchone()[0]
    expired = db.execute("SELECT COUNT(*) FROM (SELECT channel_id FROM programmes GROUP BY channel_id HAVING MAX(end_ms)<=?)", (now_ms,)).fetchone()[0]
    return {"programme_channels": total, "current_channels": current, "expired_channels": expired}

def validate_coverage(coverage):
    if coverage["current_channels"] == 0 or (coverage["expired_channels"] >= 8 and coverage["expired_channels"] > coverage["current_channels"]):
        raise GuideRefreshError(f"Provider guide is expired: {coverage['current_channels']} current / {coverage['programme_channels']} channels; previous cache retained")

def refresh():
    ROOT.mkdir(parents=True, exist_ok=True)
    lock = open(ROOT / "refresh.lock", "a")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        lock.close()
        return
    raw_path = temp = compressed = None
    db = None
    try:
        _state.update(read_state())
        update_state(phase="connecting", started_at=int(time.time() * 1000), downloaded_bytes=0, total_bytes=0, error=None)
        line = active_line()
        update_state(phase="downloading")
        response = requests.get(SOURCE_URL or f"{HOST}/xmltv.php", params=None if SOURCE_URL else line, stream=True, timeout=(20, 900))
        if not response.ok:
            raise GuideRefreshError(f"Provider guide download failed (HTTP {response.status_code})")
        update_state(total_bytes=int(response.headers.get("content-length") or 0))
        ROOT.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=ROOT, suffix=".xml", delete=False) as raw:
            raw_path = Path(raw.name)
            for chunk in response.iter_content(1024 * 1024):
                raw.write(chunk); update_state(downloaded_bytes=_state["downloaded_bytes"] + len(chunk))
        update_state(phase="indexing")
        temp = ROOT / f"guide.next.{os.getpid()}.sqlite"; temp.unlink(missing_ok=True)
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
                    begin, finish = parse_time(start), parse_time(end)
                    if finish > begin and title:
                        db.execute("INSERT OR IGNORE INTO channels VALUES(?,?)", (channel, channel))
                        db.execute("INSERT INTO programmes VALUES(?,?,?,?,?)", (channel, begin, finish, title, desc)); count += 1; ids.add(channel)
                    if count % 10_000 == 0: update_state(programmes=count, channels=len(ids))
                node.clear()
        db.commit()
        coverage = guide_coverage(db, int(time.time() * 1000))
        update_state(**coverage)
        validate_coverage(coverage)
        db.close(); db = None
        compressed = ROOT / f"guide.next.{os.getpid()}.sqlite.gz"
        update_state(phase="compressing")
        with open(temp, "rb") as source, gzip.open(compressed, "wb", compresslevel=6) as target: shutil.copyfileobj(source, target)
        # Never expose a partially written gzip to downloading clients.
        temp.replace(DB); compressed.replace(GZ)
        update_state(updated_at=int(time.time() * 1000), programmes=count, channels=len(ids), error=None, phase="ready")
    except Exception as error:
        # Provider exceptions can contain credential-bearing URLs. Never publish them.
        message = str(error) if isinstance(error, GuideRefreshError) else f"{type(error).__name__}: guide pull failed; previous cache retained"
        update_state(error=message, phase="failed")
    finally:
        if db is not None: db.close()
        for path in (raw_path, temp, compressed):
            if path is not None: path.unlink(missing_ok=True)
        fcntl.flock(lock, fcntl.LOCK_UN); lock.close()

def start_refresh():
    global _active_process
    with _process_lock:
        if _active_process is not None and _active_process.is_alive():
            return False
        if _active_process is not None: _active_process.join()
        _state.update(read_state())
        update_state(phase="queued", started_at=int(time.time() * 1000), error=None)
        _active_process = multiprocessing.Process(target=refresh)
        _active_process.start()
        return True

@app.on_event("startup")
def schedule():
    def loop():
        while True:
            start_refresh()
            time.sleep(6 * 60 * 60)
    threading.Thread(target=loop, daemon=True).start()

@app.get("/health")
def health():
    return {"status": "ok", **read_state()}

@app.get("/epg/manifest")
def manifest():
    if not GZ.exists(): raise HTTPException(503, "Guide is still building")
    return {**read_state(), "bytes": GZ.stat().st_size, "sha256": hashlib.sha256(GZ.read_bytes()).hexdigest()}

@app.get("/epg/full.sqlite.gz")
def download():
    if not GZ.exists(): raise HTTPException(503, "Guide is still building")
    return FileResponse(GZ, media_type="application/gzip", filename="guide.sqlite.gz")

def require_admin(request):
    secret = os.getenv("EPG_ADMIN_SECRET", "")
    if len(secret) < 32:
        raise HTTPException(503, "EPG admin control is not configured")
    supplied = request.headers.get("authorization", "")
    if not hmac.compare_digest(supplied, "Bearer " + secret):
        raise HTTPException(401, "Unauthorized")

@app.get("/epg/admin/status")
def admin_status(request: Request):
    require_admin(request)
    state = read_state()
    if DB.exists():
        with sqlite3.connect(f"file:{DB}?mode=ro", uri=True) as db:
            state["published_coverage"] = guide_coverage(db, int(time.time() * 1000))
    return state

@app.post("/epg/admin/refresh")
def admin_refresh(request: Request):
    require_admin(request)
    started = start_refresh()
    return {"started": started, "state": read_state()}
