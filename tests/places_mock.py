"""A fake Google Places API (New) "searchNearby" server for tests.

It records every request the app makes and answers like Google would, depending
on `Mock.mode`. Places are placed at fixed distances from the centre the app sends,
so the test knows exactly what the Debug panel should show.
"""
import json
import math
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8765


def _north(lat, lng, m):
    return lat + m / 111_320.0, lng


def _east(lat, lng, m):
    return lat, lng + m / (111_320.0 * math.cos(math.radians(lat)))


# (id, name, category it answers to, metres, direction, phone, address)
OK_PLACES = {
    "police": [
        ("P1", "Sector 18 Police Booth", 120, "n", "0120 255 0100", "Sector 18, Noida"),
    ],
    "pharmacy": [
        ("M1", "Apollo Pharmacy", 300, "e", "098100 00001", "Atta Market"),
        ("M2", "Wellness Chemist", 60, "n", None, None),            # no phone listed
        ("D1", "Big Bazaar", 240, "e", "011 4000 0000", "Mall Road"),  # also a shop (duplicate)
    ],
    "shop": [
        ("S1", "Sharma General Store", 450, "n", "+91 98110 22222", "Main Market"),
        ("D1", "Big Bazaar", 240, "e", "011 4000 0000", "Mall Road"),  # same place again
        ("S2", "Far Away Mart", 1200, "e", "011 9999 9999", "Too far"),  # outside 1 km
    ],
}

# OpenStreetMap test data: (osm type, id, metres, direction, tags)
OSM_ELEMENTS = [
    ("node", 101, 150, "n", {"amenity": "police", "name": "Connaught Place Police Booth", "phone": "011 2336 1111"}),
    ("node", 102, 80, "e", {"amenity": "pharmacy", "name": "Apollo Pharmacy", "phone": "+91 88269 92200;+91 11 2332 1747;+91 11 0000 0000"}),
    ("node", 103, 200, "n", {"shop": "chemist", "name": "Medicine House"}),                         # chemist = pharmacy, no phone
    ("way", 104, 350, "e", {"shop": "supermarket", "name": "Modern Bazaar", "contact:phone": "011 4100 0000",
                            "addr:street": "Janpath", "addr:city": "New Delhi"}),                      # a building: has "center"
    ("node", 105, 50, "n", {"shop": "convenience"}),                                                   # no name
    ("node", 106, 260, "e", {"amenity": "pharmacy", "shop": "supermarket", "name": "Wellness Forever", "mobile": "98100 12345"}),
    ("node", 107, 1200, "n", {"shop": "convenience", "name": "Too Far Kirana", "phone": "011 9999 0000"}),  # outside 1 km
    ("node", 108, 1500, "e", {"amenity": "police", "name": "Sector 62 Police Station", "phone": "0120 240 0000"}),  # outside 1 km
    ("node", 109, 2200, "n", {"amenity": "pharmacy", "name": "Far Pharmacy"}),  # outside 1 km, but 3 pharmacies are near
]

CATEGORY_OF = {
    "police": "police",
    "pharmacy": "pharmacy",
    "convenience_store": "shop",
}


class Mock:
    mode = "ok"  # ok | empty | bad_key | billing | partial | slow | osm_busy | osm_remark | osm_all_busy
    slow_seconds = 20
    requests = []          # every request received
    lock = threading.Lock()

    @classmethod
    def reset(cls, mode="ok"):
        with cls.lock:
            cls.mode = mode
            cls.requests = []


def category_from_body(body):
    for t in body.get("includedTypes", []):
        if t in CATEGORY_OF:
            return CATEGORY_OF[t]
    return "unknown"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code, obj):
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self._send(404, {"error": {"code": 404, "message": "only POST", "status": "NOT_FOUND"}})

    def do_POST(self):
        if self.path.endswith("/api/interpreter"):
            return self.osm()
        n = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(n).decode() if n else ""
        try:
            body = json.loads(raw) if raw else {}
        except Exception:
            body = {"_unparsed": raw}
        rec = {
            "path": self.path,
            "method": "POST",
            "key": self.headers.get("X-Goog-Api-Key"),
            "mask": self.headers.get("X-Goog-FieldMask"),
            "ctype": self.headers.get("Content-Type"),
            "body": body,
            "t": time.time(),
        }
        with Mock.lock:
            Mock.requests.append(rec)
            mode = Mock.mode
        cat = category_from_body(body)

        if self.path != "/v1/places:searchNearby":
            return self._send(404, {"error": {"code": 404, "message": "bad path", "status": "NOT_FOUND"}})
        if mode == "slow":
            time.sleep(Mock.slow_seconds)
            return self._send(200, {})
        if mode == "bad_key":
            return self._send(400, {"error": {"code": 400, "message": "API key not valid. Please pass a valid API key.", "status": "INVALID_ARGUMENT"}})
        if mode == "billing":
            return self._send(403, {"error": {"code": 403, "message": "This API method requires billing to be enabled. Please enable billing on project #123.", "status": "PERMISSION_DENIED"}})
        if mode == "partial" and cat == "police":
            return self._send(500, {"error": {"code": 500, "message": "Internal error encountered.", "status": "INTERNAL"}})
        if mode == "empty":
            return self._send(200, {})  # Google answers {} when nothing is found

        c = body.get("locationRestriction", {}).get("circle", {}).get("center", {})
        lat, lng = c.get("latitude", 0.0), c.get("longitude", 0.0)
        places = []
        for pid, name, m, d, phone, addr in OK_PLACES.get(cat, []):
            plat, plng = (_north if d == "n" else _east)(lat, lng, m)
            p = {"id": pid, "displayName": {"text": name, "languageCode": "en"},
                 "location": {"latitude": plat, "longitude": plng},
                 "primaryType": cat, "types": [cat, "point_of_interest", "establishment"]}
            if phone:
                p["nationalPhoneNumber"] = phone
                p["internationalPhoneNumber"] = "+91 " + phone.lstrip("0+91 ")
            if addr:
                p["formattedAddress"] = addr
            places.append(p)
        return self._send(200, {"places": places})


    def osm(self):
        """Fake OpenStreetMap Overpass server: /a/api/interpreter (first) and /b/... (backup)."""
        from urllib.parse import parse_qs
        n = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(n).decode() if n else ""
        data = parse_qs(raw).get("data", [""])[0]
        rec = {"path": self.path, "method": "POST", "ua": self.headers.get("User-Agent"),
               "ctype": self.headers.get("Content-Type"), "data": data, "key": self.headers.get("X-Goog-Api-Key"),
               "t": time.time()}
        with Mock.lock:
            Mock.requests.append(rec)
            mode = Mock.mode
        first = self.path.startswith("/a/")
        if mode == "slow":
            time.sleep(Mock.slow_seconds)
            return self._send(200, {"elements": []})
        if mode == "osm_all_busy" or (mode == "osm_busy" and first):
            return self._send(429, {"error": "rate_limited"})
        if mode == "osm_remark" and first:
            return self._send(200, {"elements": [], "remark": "runtime error: Query timed out in \"query\" at line 3 after 25 seconds."})
        if mode == "empty":
            return self._send(200, {"version": 0.6, "elements": []})
        import re
        m = re.search(r"around:\d+,(-?[\d.]+),(-?[\d.]+)", data)
        lat, lng = (float(m.group(1)), float(m.group(2))) if m else (0.0, 0.0)
        els = []
        for typ, oid, metres, d, tags in OSM_ELEMENTS:
            plat, plng = (_north if d == "n" else _east)(lat, lng, metres)
            e = {"type": typ, "id": oid, "tags": tags}
            if typ == "node":
                e.update(lat=plat, lon=plng)
            else:
                e["center"] = {"lat": plat, "lon": plng}
            els.append(e)
        return self._send(200, {"version": 0.6, "generator": "Overpass API (test)", "elements": els})


def start():
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv
