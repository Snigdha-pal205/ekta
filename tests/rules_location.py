"""Server rules for live location: only her phone (with the location key) can move
her position, never backwards in time, and never after she cancelled.
Run: python3 tests/rules_location.py"""
import math
import os
import sys
import time
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import e2e  # noqa: E402  (helpers: http, sha, DOCS, token)

results = []


def check(name, ok):
    results.append(ok)
    print(f"  {'PASS' if ok else 'FAIL'}  {name}", flush=True)


def create(doc_id, extra=None, loc_key="kl"):
    lat, lng = 28.6139, 77.2090
    fields = {
        "lat": {"doubleValue": lat}, "lng": {"doubleValue": lng}, "accuracy": {"doubleValue": 120.0},
        "cell": {"stringValue": f"{math.floor(lat / 0.01)}_{math.floor(lng / 0.01)}"},
        "senderId": {"stringValue": "rules-test-phone"}, "status": {"stringValue": "active"},
        "hashC": {"stringValue": e2e.sha("kc")}, "hashD": {"stringValue": e2e.sha("kd")},
        "statusAt": {"integerValue": "1000"},
        "createdAt": {"timestampValue": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")},
    }
    if loc_key:
        fields["hashL"] = {"stringValue": e2e.sha(loc_key)}
    fields.update(extra or {})
    code, _ = e2e.http("POST", f"{e2e.DOCS}?documentId={doc_id}", body={"fields": fields})
    return code


def patch(doc_id, fields):
    mask = "&".join(f"updateMask.fieldPaths={k}" for k in fields)
    code, _ = e2e.http("PATCH", f"{e2e.DOCS}/{doc_id}?{mask}", body={"fields": fields})
    return code


def loc(key, at, lat=28.6150, acc=6.0):
    f = {"lat": {"doubleValue": lat}, "lng": {"doubleValue": 77.2100}, "accuracy": {"doubleValue": acc},
         "statusAt": {"integerValue": str(at)}}
    if key is not None:
        f["locKey"] = {"stringValue": key}
    return f


def main():
    rid = f"rules-loc-{int(time.time())}"
    check("create alert with location-key hash", create(rid) == 200)
    check("no key → refused", patch(rid, loc(None, 1500)) == 403)
    check("wrong key → refused", patch(rid, loc("nope", 1600)) == 403)
    check("live location with the right key → accepted", patch(rid, loc("kl", 2000)) == 200)
    check("newer point → accepted", patch(rid, loc("kl", 3000, lat=28.6160)) == 200)
    check("older point → refused (never goes backwards)", patch(rid, loc("kl", 2500)) == 403)
    check("location key can't cancel",
          patch(rid, {"status": {"stringValue": "cancelled"}, "statusKey": {"stringValue": "kl"},
                      "statusAt": {"integerValue": "5000"}}) == 403)
    check("location key can't change other fields",
          patch(rid, {**loc("kl", 6000), "senderId": {"stringValue": "x"}}) == 403)
    code, text = e2e.http("GET", f"{e2e.DOCS}/{rid}")
    check("stored point is the newest one", code == 200 and "28.616" in text)
    check("real cancel still works",
          patch(rid, {"status": {"stringValue": "cancelled"}, "statusKey": {"stringValue": "kc"},
                      "statusAt": {"integerValue": "7000"}}) == 200)
    check("after cancel, location can't change", patch(rid, loc("kl", 8000)) == 403)

    old = f"rules-old-{int(time.time())}"
    check("alert from an older app (no location key) still creates", create(old, loc_key=None) == 200)
    check("…and nobody can move its location", patch(old, loc("kl", 2000)) == 403)

    tok = e2e.token()
    for d in (rid, old):
        e2e.http("DELETE", f"{e2e.DOCS}/{d}", tok)
    print(f"\n=== {sum(results)}/{len(results)} rule checks passed ===")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
