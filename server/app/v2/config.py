"""Runtime configuration for the v2 engine.

Every capacity value here is a deployment knob, not a product limit: the
number of stored bots is unbounded; these only bound concurrent work.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


def _int(name: str, default: int) -> int:
    return int(os.getenv(name, str(default)))


def _float(name: str, default: float) -> float:
    return float(os.getenv(name, str(default)))


def _bool(name: str, default: bool) -> bool:
    return os.getenv(name, "1" if default else "0").strip().lower() in {"1", "true", "yes", "on"}


@dataclass
class V2Settings:
    data_dir: Path = field(
        default_factory=lambda: Path(os.getenv("DATA_DIR", str(Path.home() / ".open-dots"))).expanduser().resolve()
    )
    # Concurrency (profile defaults from the brief: 4 model runs, 2 computer surfaces).
    max_active_runs: int = field(default_factory=lambda: _int("MAX_ACTIVE_RUNS", 4))
    max_active_surfaces: int = field(default_factory=lambda: _int("MAX_ACTIVE_SURFACES", 2))
    # Leases / heartbeats.
    lease_seconds: float = field(default_factory=lambda: _float("RUN_LEASE_SECONDS", 30.0))
    heartbeat_seconds: float = field(default_factory=lambda: _float("RUN_HEARTBEAT_SECONDS", 10.0))
    # Run limits.
    default_run_timeout_s: int = field(default_factory=lambda: _int("RUN_TIMEOUT_SECONDS", 1800))
    default_max_attempts: int = field(default_factory=lambda: _int("RUN_MAX_ATTEMPTS", 4))
    retry_base_s: float = field(default_factory=lambda: _float("RUN_RETRY_BASE_SECONDS", 2.0))
    retry_max_s: float = field(default_factory=lambda: _float("RUN_RETRY_MAX_SECONDS", 300.0))
    model_timeout_s: float = field(default_factory=lambda: _float("MODEL_TIMEOUT_SECONDS", 120.0))
    tool_timeout_s: float = field(default_factory=lambda: _float("TOOL_TIMEOUT_SECONDS", 60.0))
    # Delegation guards.
    max_delegation_depth: int = field(default_factory=lambda: _int("MAX_DELEGATION_DEPTH", 4))
    max_tasks_per_correlation: int = field(default_factory=lambda: _int("MAX_TASKS_PER_CORRELATION", 40))
    # Approvals.
    approval_ttl_s: int = field(default_factory=lambda: _int("APPROVAL_TTL_SECONDS", 86400))
    # Scheduler.
    default_timezone: str = field(default_factory=lambda: os.getenv("DEFAULT_TIMEZONE", "Europe/Warsaw"))
    scheduler_max_sleep_s: float = field(default_factory=lambda: _float("SCHEDULER_MAX_SLEEP_SECONDS", 300.0))
    # Workers embedded in the API process (single-node default).
    embedded_worker: bool = field(default_factory=lambda: _bool("EMBEDDED_WORKER", True))
    embedded_scheduler: bool = field(default_factory=lambda: _bool("EMBEDDED_SCHEDULER", True))
    # Network policy for web/http tools.
    allow_private_network: bool = field(default_factory=lambda: _bool("ALLOW_PRIVATE_NETWORK", False))
    egress_allowlist: tuple = field(
        default_factory=lambda: tuple(
            h.strip().lower() for h in os.getenv("EGRESS_ALLOWLIST", "").split(",") if h.strip()
        )
    )
    # Pairing.
    pairing_code_ttl_s: int = field(default_factory=lambda: _int("PAIRING_CODE_TTL_SECONDS", 600))
    public_server_url: str = field(default_factory=lambda: os.getenv("PUBLIC_SERVER_URL", "").rstrip("/"))

    @property
    def db_path(self) -> Path:
        return self.data_dir / "opendots-v2.sqlite3"

    @property
    def files_dir(self) -> Path:
        return self.data_dir / "files"

    @property
    def shared_workspace(self) -> Path:
        return self.data_dir / "workspace"

    @property
    def browser_dir(self) -> Path:
        return self.data_dir / "browser-profiles"


def load_settings(**overrides) -> V2Settings:
    s = V2Settings()
    for key, value in overrides.items():
        setattr(s, key, value)
    return s
