"""Idle/load profile of the server process with N bots (scripted mock model).

Measures the API+worker+scheduler process only. Mock model calls return
instantly, so these numbers are orchestration overhead, NOT real agent
performance (real runs are dominated by model latency and browser work).

usage: python scripts/bench.py [--bots 50] [--tasks 50] [--idle 30]
"""

import argparse
import json
import os
import socket
import statistics
import subprocess
import sys
import tempfile
import time
import urllib.request

TOKEN = "bench-token-0123456789"


def proc_stats(pid):
    with open(f"/proc/{pid}/status") as f:
        rss = next(int(l.split()[1]) for l in f if l.startswith("VmRSS")) / 1024
    with open(f"/proc/{pid}/stat") as f:
        parts = f.read().split(")")[1].split()
        ticks = int(parts[11]) + int(parts[12])
    return rss, ticks / os.sysconf("SC_CLK_TCK")


def api(base, path, body=None):
    req = urllib.request.Request(f"{base}/api/v2{path}", data=json.dumps(body).encode() if body is not None else None,
                                 method="POST" if body is not None else "GET",
                                 headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"})
    t0 = time.perf_counter()
    with urllib.request.urlopen(req, timeout=30) as r:
        data = json.loads(r.read() or b"null")
    return data, (time.perf_counter() - t0) * 1000


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bots", type=int, default=50)
    ap.add_argument("--tasks", type=int, default=50)
    ap.add_argument("--idle", type=int, default=30)
    a = ap.parse_args()
    s = socket.socket(); s.bind(("127.0.0.1", 0)); port = s.getsockname()[1]; s.close()
    data = tempfile.mkdtemp()
    env = {**os.environ, "DATA_DIR": data, "APP_AUTH_TOKEN": TOKEN, "BROWSER_DISABLED": "1"}
    p = subprocess.Popen([sys.executable, "-m", "uvicorn", "app.main:app", "--port", str(port), "--log-level", "warning"],
                         env=env, cwd=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    base = f"http://127.0.0.1:{port}"
    for _ in range(100):
        try:
            urllib.request.urlopen(f"{base}/api/v2/health", timeout=1); break
        except OSError:
            time.sleep(0.1)
    out = {"machine": {"cpus": os.cpu_count(), "python": sys.version.split()[0]}, "bots": a.bots}
    rss0, _ = proc_stats(p.pid)
    prof, _ = api(base, "/providers", {"kind": "scripted_mock", "name": "bench", "default_model": "scripted-mock",
                                       "script": [{"when": "remember", "call": {"name": "memory.save", "arguments": {"content": "{{last}}"}}},
                                                  {"on": "tool", "reply": "ok"}, {"reply": "pong"}]})
    bots = [api(base, "/bots", {"name": f"Bot {i}", "provider_profile_id": prof["id"]})[0] for i in range(a.bots)]
    convs = [api(base, f"/bots/{b['id']}/conversation", {})[0] for b in bots]
    rss1, cpu1 = proc_stats(p.pid)
    time.sleep(a.idle)
    rss2, cpu2 = proc_stats(p.pid)
    model_calls_idle = api(base, "/events?after=0&limit=1000")[0]["events"]
    out["idle"] = {"rss_mb_start": round(rss0, 1), "rss_mb_after_bots": round(rss1, 1), "rss_mb_after_idle": round(rss2, 1),
                   "cpu_seconds_during_idle": round(cpu2 - cpu1, 3), "idle_seconds": a.idle,
                   "cpu_percent_idle": round(100 * (cpu2 - cpu1) / a.idle, 2),
                   "model_calls_while_idle": sum(1 for e in model_calls_idle if e["type"] == "run.model_call"),
                   "client_requests_during_idle": 0}
    lat_post, t0 = [], time.perf_counter()
    task_ids = []
    for i in range(a.tasks):
        c = convs[i % len(convs)]
        r, ms = api(base, f"/conversations/{c['id']}/messages", {"text": f"remember item {i}", "client_msg_id": f"b{i}"})
        lat_post.append(ms)
        task_ids.append(r["tasks"][0]["id"])
    peak = 0
    while True:
        rows, _ = api(base, "/tasks?status=active&limit=500")
        peak = max(peak, proc_stats(p.pid)[0])
        if not rows:
            break
        time.sleep(0.05)
    total = time.perf_counter() - t0
    lat_get = [api(base, "/bots")[1] for _ in range(20)]
    rss3, cpu3 = proc_stats(p.pid)
    done = api(base, "/tasks?status=completed&limit=500")[0]
    out["load"] = {"tasks": a.tasks, "completed": len(done), "wall_seconds": round(total, 2),
                   "tasks_per_second": round(a.tasks / total, 1), "post_message_ms_p50": round(statistics.median(lat_post), 1),
                   "post_message_ms_max": round(max(lat_post), 1), "get_bots_ms_p50": round(statistics.median(lat_get), 1),
                   "rss_mb_peak": round(peak, 1), "cpu_seconds_total": round(cpu3 - cpu2, 2),
                   "max_active_runs": 4, "note": "scripted mock model; 2 model calls + 1 tool call per task"}
    p.terminate(); p.wait(10)
    print(json.dumps(out, indent=2))


if __name__ == "__main__":
    main()
