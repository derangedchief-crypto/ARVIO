"""Runtime configuration for the activation-code worker."""

import os
import sys

from dotenv import load_dotenv

load_dotenv()


def _require(name: str) -> str:
    value = os.getenv(name)
    if not value:
        print(f"FATAL: required environment variable {name} is not set.", file=sys.stderr)
        sys.exit(1)
    return value


class Settings:
    # Optional: old activation continues unchanged when management is not enabled.
    management_enrollment_secret = os.getenv("MANAGEMENT_ENROLLMENT_SECRET", "")
    management_control_origin = os.getenv("MANAGEMENT_CONTROL_ORIGIN", "")
    ssh_host = _require("PANEL_SSH_HOST")
    ssh_port = int(os.getenv("PANEL_SSH_PORT", "22"))
    ssh_username = _require("PANEL_SSH_USERNAME")
    ssh_password = _require("PANEL_SSH_PASSWORD")

    db_host = os.getenv("PANEL_DB_HOST", "127.0.0.1")
    db_port = int(os.getenv("PANEL_DB_PORT", "3306"))
    db_name = os.getenv("PANEL_DB_NAME", "overcast")
    db_username = os.getenv("PANEL_DB_USERNAME", "root")
    db_password = os.getenv("PANEL_DB_PASSWORD", "")

    log_file = os.getenv(
        "ACTIVATION_WORKER_LOG_FILE",
        "/var/log/iptv-activation-worker/worker.log",
    )


settings = Settings()
