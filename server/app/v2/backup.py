"""Online backup and offline restore.

A backup is a tar.gz with a consistent SQLite snapshot (sqlite3 backup API,
safe while the server runs) plus artifacts and the shared workspace.
The encryption key is deliberately NOT included: store ``APP_ENCRYPTION_KEY``
(or ``DATA_DIR/.encryption.key``) separately, otherwise encrypted secrets in
the backup cannot be decrypted — which is the point.
"""

from __future__ import annotations

import io
import json
import os
import shutil
import sqlite3
import tarfile
import tempfile
from pathlib import Path
from typing import Any, Dict, List

from app.v2.util import now_iso


def create_backup(data_dir: Path, db_path: Path, dest_dir: Path) -> Path:
    dest_dir.mkdir(parents=True, exist_ok=True)
    stamp = now_iso().replace(":", "").replace("-", "")[:15]
    out = dest_dir / f"opendots-backup-{stamp}.tar.gz"
    with tempfile.TemporaryDirectory() as tmp:
        snap = Path(tmp) / "opendots-v2.sqlite3"
        src = sqlite3.connect(str(db_path))
        dst = sqlite3.connect(str(snap))
        with dst:
            src.backup(dst)
        src.close()
        dst.close()
        check = sqlite3.connect(str(snap))
        ok = check.execute("PRAGMA integrity_check").fetchone()[0]
        version = check.execute("SELECT MAX(version) FROM schema_migrations").fetchone()[0]
        check.close()
        if ok != "ok":
            raise RuntimeError(f"Snapshot integrity check failed: {ok}")
        manifest = {"format": "opendots.backup.v1", "created_at": now_iso(), "schema_version": version,
                    "includes": ["db", "files", "workspace"], "excludes": ["encryption key", "auth token", "browser profiles"]}
        with tarfile.open(out, "w:gz") as tar:
            tar.add(snap, arcname="opendots-v2.sqlite3")
            for name in ("files", "workspace"):
                p = data_dir / name
                if p.exists():
                    tar.add(p, arcname=name)
            data = json.dumps(manifest, indent=2).encode()
            info = tarfile.TarInfo("manifest.json")
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
    os.chmod(out, 0o600)
    return out


def list_backups(dest_dir: Path) -> List[Dict[str, Any]]:
    if not dest_dir.exists():
        return []
    return [{"name": p.name, "size": p.stat().st_size} for p in sorted(dest_dir.glob("opendots-backup-*.tar.gz"))]


def restore_backup(archive: Path, data_dir: Path) -> Dict[str, Any]:
    """Offline restore (server stopped). Existing state is moved aside, never deleted."""
    data_dir.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:gz") as tar:
        names = tar.getnames()
        if "manifest.json" not in names or "opendots-v2.sqlite3" not in names:
            raise ValueError("Not an Open Dots backup.")
        for m in tar.getmembers():
            target = (data_dir / m.name).resolve()
            if data_dir.resolve() not in target.parents and target != data_dir.resolve():
                raise ValueError("Unsafe path in archive.")
            if m.issym() or m.islnk():
                raise ValueError("Links are not allowed in backups.")
        manifest = json.loads(tar.extractfile("manifest.json").read())
        aside = data_dir / f"pre-restore-{now_iso().replace(':', '')[:15]}"
        aside.mkdir()
        for name in ("opendots-v2.sqlite3", "opendots-v2.sqlite3-wal", "opendots-v2.sqlite3-shm", "files", "workspace"):
            p = data_dir / name
            if p.exists():
                shutil.move(str(p), str(aside / name))
        tar.extractall(data_dir, filter="data") if hasattr(tarfile, "data_filter") else tar.extractall(data_dir)
    return {"restored": manifest, "previous_state_moved_to": str(aside)}


if __name__ == "__main__":  # pragma: no cover - CLI
    import argparse

    from app.v2.config import load_settings

    ap = argparse.ArgumentParser(description="Open Dots backup/restore")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("backup")
    r = sub.add_parser("restore")
    r.add_argument("archive")
    args = ap.parse_args()
    s = load_settings()
    if args.cmd == "backup":
        print(create_backup(s.data_dir, s.db_path, s.data_dir / "backups"))
    else:
        print(json.dumps(restore_backup(Path(args.archive), s.data_dir), indent=2))
