"""Standalone maintenance controller. Bind to localhost behind aaPanel HTTPS."""
import getpass
import hashlib
import hmac
import os
import secrets
import sqlite3
import sys
import time
from contextlib import contextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request, Response
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, Field, StrictBool

DEFAULT_MESSAGE = "We’re performing maintenance. Please wait; the app will reopen automatically."
ORIGIN = os.environ.get("HUB_MANAGER_ORIGIN", "https://hubmanager.extremeiptv.net").rstrip("/")
COOKIE = "__Host-hub_manager"
ROOT = Path(__file__).parent


class Conflict(Exception):
    pass


class Unauthorized(Exception):
    pass


class RateLimited(Exception):
    pass


def password_hash(password, salt):
    return hashlib.scrypt(password.encode(), salt=bytes.fromhex(salt), n=16384, r=8, p=1).hex()


class Store:
    def __init__(self, path, clock=time.time):
        self.path, self.clock = str(path), clock
        Path(self.path).parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        with self.connection() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS admins(name TEXT PRIMARY KEY, salt TEXT NOT NULL, hash TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS sessions(hash TEXT PRIMARY KEY, admin TEXT NOT NULL, expires INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS attempts(ip TEXT NOT NULL, at INTEGER NOT NULL);
                CREATE INDEX IF NOT EXISTS attempts_ip_at ON attempts(ip,at);
                CREATE TABLE IF NOT EXISTS maintenance(id INTEGER PRIMARY KEY CHECK(id=1), active INTEGER NOT NULL,
                    message TEXT NOT NULL, revision INTEGER NOT NULL, updated_at INTEGER NOT NULL, updated_by TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS audit(revision INTEGER PRIMARY KEY, active INTEGER NOT NULL,
                    message TEXT NOT NULL, at INTEGER NOT NULL, admin TEXT NOT NULL);
            """)
            db.execute("INSERT OR IGNORE INTO maintenance VALUES(1,0,?,0,0,'')", (DEFAULT_MESSAGE,))
        os.chmod(self.path, 0o600)

    @contextmanager
    def connection(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def set_admin(self, name, password):
        if not name or len(name) > 80 or len(password) < 16 or len(password) > 256:
            raise ValueError("Use a name (1–80 characters) and a unique password of 16–256 characters")
        salt = secrets.token_hex(16)
        hashed = password_hash(password, salt)
        with self.connection() as db:
            db.execute("INSERT INTO admins VALUES(?,?,?) ON CONFLICT(name) DO UPDATE SET salt=excluded.salt,hash=excluded.hash",
                       (name, salt, hashed))
            db.execute("DELETE FROM sessions WHERE admin=?", (name,))

    def login(self, name, password, ip):
        now = int(self.clock())
        with self.connection() as db:
            # Serialize attempts, so concurrent guesses cannot bypass the limit.
            db.execute("BEGIN IMMEDIATE")
            db.execute("DELETE FROM attempts WHERE at<=?", (now - 900,))
            if db.execute("SELECT COUNT(*) FROM attempts WHERE ip=?", (ip,)).fetchone()[0] >= 10:
                raise RateLimited()
            db.execute("INSERT INTO attempts VALUES(?,?)", (ip, now))
            admin = db.execute("SELECT * FROM admins WHERE name=?", (name,)).fetchone()
        expected = admin["hash"] if admin else "0" * 128
        actual = password_hash(password, admin["salt"] if admin else "00" * 16)
        if not hmac.compare_digest(actual, expected):
            raise Unauthorized()
        token = secrets.token_urlsafe(48)
        with self.connection() as db:
            # Password rotation while a login was hashing invalidates the attempt.
            current = db.execute("SELECT hash FROM admins WHERE name=?", (name,)).fetchone()
            if not current or current[0] != expected:
                raise Unauthorized()
            db.execute("DELETE FROM sessions WHERE expires<=?", (now,))
            db.execute("INSERT INTO sessions VALUES(?,?,?)", (hashlib.sha256(token.encode()).hexdigest(), name, now + 43200))
        return token

    def authenticate(self, token):
        with self.connection() as db:
            row = db.execute("SELECT admin FROM sessions WHERE hash=? AND expires>?",
                             (hashlib.sha256(token.encode()).hexdigest(), int(self.clock()))).fetchone()
        if not row:
            raise Unauthorized()
        return row[0]

    def logout(self, token):
        with self.connection() as db:
            db.execute("DELETE FROM sessions WHERE hash=?", (hashlib.sha256(token.encode()).hexdigest(),))

    def status(self):
        with self.connection() as db:
            row = dict(db.execute("SELECT active,message,revision,updated_at,updated_by FROM maintenance WHERE id=1").fetchone())
        row["active"] = bool(row["active"])
        return row

    def update(self, active, message, revision, actor):
        now = int(self.clock())
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            if db.execute("SELECT revision FROM maintenance WHERE id=1").fetchone()[0] != revision:
                raise Conflict()
            db.execute("UPDATE maintenance SET active=?,message=?,revision=revision+1,updated_at=?,updated_by=? WHERE id=1",
                       (int(active), message, now, actor))
            db.execute("INSERT INTO audit VALUES(?,?,?,?,?)", (revision + 1, int(active), message, now, actor))
            result = dict(db.execute("SELECT active,message,revision,updated_at,updated_by FROM maintenance WHERE id=1").fetchone())
        result["active"] = bool(result["active"])
        return result


def database():
    return Store(os.environ.get("HUB_MANAGER_DB", "/var/lib/extreme-hub-manager/manager.sqlite"))


def create_app(store=None):
    app = FastAPI(title="Extreme Hub Manager", docs_url=None, redoc_url=None, openapi_url=None)
    store = store or database()

    @app.middleware("http")
    async def security(request, call_next):
        if request.method == "POST":
            if request.headers.get("origin") != ORIGIN or request.headers.get("x-hub-manager") != "1":
                return JSONResponse({"error": "forbidden_origin"}, status_code=403)
            if request.headers.get("content-length", "0").isdigit() and int(request.headers.get("content-length", "0")) > 8192:
                return JSONResponse({"error": "body_too_large"}, status_code=413)
        response = await call_next(request)
        response.headers.update({
            "Cache-Control": "no-store, max-age=0", "X-Content-Type-Options": "nosniff",
            "Referrer-Policy": "no-referrer", "X-Frame-Options": "DENY",
            "Content-Security-Policy": "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
            "Strict-Transport-Security": "max-age=31536000",
        })
        return response

    def admin(request):
        try:
            return store.authenticate(request.cookies.get(COOKIE, ""))
        except Unauthorized:
            raise HTTPException(401, "Sign in required")

    @app.get("/api/maintenance")
    def status():
        state = store.status()
        return {key: state[key] for key in ("active", "message", "revision")}

    class Login(BaseModel):
        username: str = Field(min_length=1, max_length=80)
        password: str = Field(min_length=1, max_length=256)

    @app.post("/api/admin/login")
    def login(body: Login, request: Request, response: Response):
        try:
            token = store.login(body.username, body.password, request.client.host)
        except RateLimited:
            raise HTTPException(429, "Too many attempts. Wait 15 minutes.")
        except Unauthorized:
            raise HTTPException(401, "Invalid credentials")
        response.set_cookie(COOKIE, token, max_age=43200, secure=True, httponly=True, samesite="strict", path="/")
        return {"ok": True}

    @app.post("/api/admin/logout")
    def logout(request: Request, response: Response):
        store.logout(request.cookies.get(COOKIE, ""))
        response.delete_cookie(COOKIE, secure=True, httponly=True, samesite="strict", path="/")
        return {"ok": True}

    @app.get("/api/admin/status")
    def admin_status(request: Request):
        admin(request)
        return store.status()

    class Update(BaseModel):
        active: StrictBool
        message: str = Field(max_length=500)
        revision: int = Field(ge=0)

    @app.post("/api/admin/maintenance")
    def update(body: Update, request: Request):
        actor = admin(request)
        try:
            return store.update(body.active, body.message.strip() or DEFAULT_MESSAGE, body.revision, actor)
        except Conflict:
            raise HTTPException(409, "Another admin changed the status. Reload before changing it again.")

    @app.get("/")
    def page():
        return FileResponse(ROOT / "admin.html")

    @app.get("/admin.js")
    def script():
        return FileResponse(ROOT / "admin.js", media_type="text/javascript")

    return app


if __name__ == "__main__":
    if len(sys.argv) != 3 or sys.argv[1] != "set-admin":
        sys.exit("Usage: python service.py set-admin USERNAME")
    password = getpass.getpass("New admin password (16+ characters): ")
    if password != getpass.getpass("Confirm password: "):
        sys.exit("Passwords do not match")
    database().set_admin(sys.argv[2], password)
    print("Admin saved; existing sessions for this admin were revoked.")
