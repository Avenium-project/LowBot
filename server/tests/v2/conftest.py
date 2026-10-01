"""Fixtures for v2 tests.

All model behaviour in these tests comes from the SCRIPTED MOCK provider
(``scripted_mock``) — deterministic and explicitly not a live model. Live
provider tests live in ``test_live_providers.py`` and are skipped without
credentials.
"""

import asyncio
import os

import pytest

from app.v2.config import load_settings
from app.v2.providers.scripted import ScriptedAdapter
from app.v2.runtime import Runtime
from app.v2.util import reset_clock


def make_runtime(tmp_path, **overrides):
    settings = load_settings(data_dir=tmp_path, heartbeat_seconds=0.2, lease_seconds=2.0, retry_base_s=0.01,
                             retry_max_s=0.05, embedded_worker=False, embedded_scheduler=False, **overrides)
    return Runtime(settings)


@pytest.fixture
def rt(tmp_path, monkeypatch):
    monkeypatch.setenv("APP_ENCRYPTION_KEY", "")
    runtime = make_runtime(tmp_path)
    yield runtime
    reset_clock()
    runtime.core.db.close()


def mock_profile(rt, script=None, fn=None, name="mock"):
    prof = rt.services["providers"].upsert({"kind": "scripted_mock", "name": name, "default_model": "scripted-mock",
                                            "script": script or []})
    if fn is not None or script is not None:
        rt.services["providers"].overrides[prof["id"]] = ScriptedAdapter(script=script, fn=fn)
    return prof


def run(coro):
    return asyncio.get_event_loop().run_until_complete(coro) if False else asyncio.run(coro)
