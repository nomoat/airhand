#!/usr/bin/env python3
"""Read this app's debug database without root or exposing its encrypted API key."""
import argparse
import json
import sqlite3
import subprocess
import tempfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--serial", required=True)
parser.add_argument("--output", type=Path)
args = parser.parse_args()
adb = ["adb", "-s", args.serial]
with tempfile.TemporaryDirectory(prefix="rebyte-run-") as directory:
    folder = Path(directory)
    for name in ("runs.db", "runs.db-wal", "runs.db-shm"):
        result = subprocess.run(
            adb + ["exec-out", "run-as", "ai.rebyte.mobile", "cat", "databases/" + name],
            capture_output=True,
        )
        if result.returncode == 0:
            (folder / name).write_bytes(result.stdout)
        elif name == "runs.db":
            raise SystemExit("No readable debug run database; start a task first.")
    db = sqlite3.connect(folder / "runs.db")
    db.row_factory = sqlite3.Row
    run = db.execute("SELECT * FROM runs ORDER BY created DESC LIMIT 1").fetchone()
    if run is None:
        raise SystemExit("No tasks yet.")
    report = dict(run)
    report["logs"] = [r[0] for r in db.execute("SELECT text FROM logs WHERE run=? ORDER BY id", (run["id"],))]
    report["calls"] = db.execute("SELECT count(*) FROM calls WHERE run=?", (run["id"],)).fetchone()[0]
    report["unfinished_calls"] = db.execute("SELECT count(*) FROM calls WHERE run=? AND result IS NULL", (run["id"],)).fetchone()[0]
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))
