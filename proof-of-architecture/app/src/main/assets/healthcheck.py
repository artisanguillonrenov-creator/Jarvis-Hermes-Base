import importlib
import json
import sys


def check(name, fatal, fn):
    try:
        fn()
        return {"module": name, "ok": True, "fatal": fatal}
    except Exception as e:
        return {"module": name, "ok": False, "fatal": fatal, "error": f"{type(e).__name__}: {e}"}


def check_sqlite3():
    import sqlite3
    con = sqlite3.connect(":memory:")
    con.execute("PRAGMA journal_mode=WAL")
    con.execute("SELECT 1").fetchone()


def check_pydantic_core():
    from pydantic import BaseModel
    class _T(BaseModel):
        x: int
    _T(x=1)


def check_cryptography():
    from cryptography.fernet import Fernet
    Fernet(Fernet.generate_key()).encrypt(b"probe")


def check_psutil():
    import psutil
    psutil.Process().pid
    psutil.pid_exists(1)


def check_uvloop():
    import uvloop
    uvloop.new_event_loop()


def check_httptools():
    import httptools  # noqa: F401


def check_websockets():
    import websockets  # noqa: F401


def check_pty():
    import ptyprocess
    p = ptyprocess.PtyProcessUnicode.spawn(["/bin/sh", "-c", "echo probe"])
    p.read()


def check_httpx_certifi():
    import certifi, httpx  # noqa: F401


results = [
    check("sqlite3", True, check_sqlite3),
    check("pydantic_core", True, check_pydantic_core),
    check("cryptography", True, check_cryptography),
    check("psutil", True, check_psutil),
    check("uvloop", False, check_uvloop),        # optional uvicorn accelerator — pure-asyncio fallback if absent
    check("httptools", False, check_httptools),  # optional uvicorn accelerator — h11 fallback if absent
    check("websockets", True, check_websockets),
    # The dashboard's chat page depends on a real PTY bridge (hermes_cli/pty_bridge.py) — without
    # one, the dashboard starts but its embedded terminal never works, so this is fatal too.
    check("pty", True, check_pty),
    check("httpx_certifi", True, check_httpx_certifi),
]

fatal_failures = [r for r in results if r["fatal"] and not r["ok"]]
print(json.dumps({"results": results, "fatal_failure_count": len(fatal_failures)}))
sys.exit(1 if fatal_failures else 0)
