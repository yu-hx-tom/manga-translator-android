"""Host-only transport to Python's SQLite; never part of the APK or Android runtime evidence."""
import json
import os
import sqlite3
import sys

sys.stdin.reconfigure(encoding="utf-8", errors="strict")
sys.stdout.reconfigure(encoding="utf-8", errors="strict")

path, readonly = sys.argv[1], sys.argv[2] == "1"
db = sqlite3.connect("file:" + path.replace("\\", "/") + "?mode=ro", uri=True, isolation_level=None) if readonly else sqlite3.connect(path, isolation_level=None)
for line in sys.stdin:
    try:
        request = json.loads(line)
        if request.get("close"):
            db.close()
            print('{"ok":true}', flush=True)
            break
        cursor = db.execute(request["sql"], request.get("args", []))
        result = {"ok": True, "rows": cursor.fetchall() if cursor.description else []}
        print(json.dumps(result, ensure_ascii=True), flush=True)
    except Exception as exc:
        print(json.dumps({"ok": False, "error": type(exc).__name__ + ": " + str(exc)}), flush=True)
