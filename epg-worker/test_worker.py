"""Offline tests; provider/SSH modules are stubbed, no live refresh is started."""
import gzip
import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import types
import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import patch, Mock

from fastapi.testclient import TestClient


stubs = {name: types.ModuleType(name) for name in ["mysql", "mysql.connector", "requests", "sshtunnel"]}
stubs["mysql"].connector = stubs["mysql.connector"]
stubs["sshtunnel"].SSHTunnelForwarder = Mock()
stubs["requests"].get = Mock()
with patch.dict(sys.modules, stubs):
    spec = importlib.util.spec_from_file_location("epg_worker_under_test", Path(__file__).with_name("main.py"))
    worker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(worker)


class WorkerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        worker.ROOT = Path(self.tmp.name)
        worker.DB = worker.ROOT / "guide.sqlite"
        worker.GZ = worker.ROOT / "guide.sqlite.gz"
        worker._state = {"updated_at": 0, "phase": "idle", "error": None, "programmes": 0, "channels": 0}
        self.client = TestClient(worker.app)

    def tearDown(self):
        self.client.close()
        self.tmp.cleanup()

    def test_xmltv_offsets_are_converted_to_utc_not_server_local_time(self):
        self.assertEqual(worker.parse_time("20261020170000 -0400"), worker.parse_time("20261020210000 +0000"))
        self.assertEqual(worker.parse_time("20261021023000 +0530"), worker.parse_time("20261020210000 +0000"))

    def test_majority_expired_source_is_rejected(self):
        with self.assertRaises(worker.GuideRefreshError):
            worker.validate_coverage({"current_channels": 58, "expired_channels": 4017, "programme_channels": 4075})
        worker.validate_coverage({"current_channels": 4000, "expired_channels": 20, "programme_channels": 4020})

    def test_manifest_reads_persisted_child_process_progress(self):
        worker.GZ.write_bytes(b"fixture")
        (worker.ROOT / "progress.json").write_text(json.dumps({"phase": "indexing", "updated_at": 123, "channels": 4000}))
        result = self.client.get("/epg/manifest").json()
        self.assertEqual("indexing", result["phase"])
        self.assertEqual(123, result["updated_at"])

    def test_admin_commands_require_server_side_secret(self):
        with patch.dict(os.environ, {"EPG_ADMIN_SECRET": ""}):
            self.assertEqual(503, self.client.post("/epg/admin/refresh").status_code)
        with patch.dict(os.environ, {"EPG_ADMIN_SECRET": "x" * 64}):
            self.assertEqual(401, self.client.get("/epg/admin/status").status_code)
            self.assertEqual(401, self.client.post("/epg/admin/refresh", headers={"Authorization": "Bearer bad"}).status_code)
            with patch.object(worker, "start_refresh", return_value=True) as start:
                result = self.client.post("/epg/admin/refresh", headers={"Authorization": "Bearer " + "x" * 64})
                self.assertEqual(200, result.status_code)
                self.assertTrue(result.json()["started"])
                start.assert_called_once()

    def test_repeated_refresh_does_not_start_a_second_process(self):
        process = Mock()
        process.is_alive.return_value = True
        with patch.object(worker, "_active_process", process), patch.object(worker.multiprocessing, "Process") as factory:
            self.assertFalse(worker.start_refresh())
            factory.assert_not_called()

    def fixture_pull(self, stale=False):
        now = datetime.now(timezone.utc)
        xml = ["<tv>"]
        for i in range(24):
            start = now - timedelta(days=2) if stale and i > 0 else now - timedelta(minutes=5)
            end = start + timedelta(hours=2)
            xml.append(f'<channel id="c{i}"><display-name>Channel {i}</display-name></channel>')
            xml.append(f'<programme channel="c{i}" start="{start:%Y%m%d%H%M%S %z}" stop="{end:%Y%m%d%H%M%S %z}"><title>Programme {i}</title></programme>')
        xml.append("</tv>")
        response = Mock(ok=True, headers={})
        response.iter_content.return_value = ["".join(xml).encode()]
        with patch.object(worker, "active_line", return_value={"username": "fixture", "password": "not-real"}), patch.object(worker.requests, "get", return_value=response):
            worker.refresh()

    def test_bad_pull_keeps_previous_published_cache(self):
        worker.GZ.write_bytes(b"last-good-cache")
        self.fixture_pull(stale=True)
        self.assertEqual(b"last-good-cache", worker.GZ.read_bytes())
        self.assertEqual("failed", worker.read_state()["phase"])
        self.assertIn("expired", worker.read_state()["error"])
        self.assertEqual([], list(worker.ROOT.glob("*.xml")))

    def test_successful_pull_publishes_complete_gzip_and_fresh_coverage(self):
        self.fixture_pull()
        self.assertEqual("ready", worker.read_state()["phase"])
        self.assertEqual(24, worker.read_state()["current_channels"])
        self.assertGreater(worker.read_state()["updated_at"], 0)
        self.assertEqual(b"SQLite format 3\x00", gzip.decompress(worker.GZ.read_bytes())[:16])
        self.assertEqual([], list(worker.ROOT.glob("guide.next.*")))

    def test_provider_errors_never_publish_credential_urls(self):
        with patch.object(worker, "active_line", side_effect=RuntimeError("https://provider.test/?password=private")):
            worker.refresh()
        self.assertNotIn("private", worker.read_state()["error"])


if __name__ == "__main__":
    unittest.main()
