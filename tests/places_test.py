"""Nearby places — internal tests, no real OpenStreetMap / Google calls.

Default source is OpenStreetMap (free, no key); with a Google key it's Google Places.
The app is pointed at fake servers on this computer (tests/places_mock.py) through a
debug-only hook, so every request it sends can be checked exactly.
Sets Ekta up first if needed.
Run: ANDROID_SERIAL=emulator-5554 python3 tests/places_test.py
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import e2e  # noqa: E402
from e2e import adb, check, log, screen_has, ui_tap, wait_until  # noqa: E402
import places_mock  # noqa: E402
from places_mock import Mock  # noqa: E402

PKG = e2e.PKG
LAT, LNG = 28.6139, 77.2090
TEST_KEY = "test-key-ekta-123"
FIELD_MASK = ("places.id,places.displayName,places.primaryType,places.types,places.location,"
              "places.formattedAddress,places.nationalPhoneNumber,places.internationalPhoneNumber")
EXPECTED_TYPES = {
    "police": ["police"],
    "pharmacy": ["pharmacy", "drugstore"],
    "shop": ["convenience_store", "grocery_store", "supermarket", "department_store", "general_store", "market", "store"],
}
# What the Debug panel must show in "ok" mode: nearest first, 700 m one left out,
# Big Bazaar (returned twice) shown once, missing phone shown as "not listed".
EXPECTED_ROWS = [
    ("Sector 18 Police Booth", "POLICE BOOTH", 120, "0120 255 0100"),
    ("Big Bazaar", "PHARMACY", 240, "011 4000 0000"),
    ("Apollo Pharmacy", "PHARMACY", 300, "098100 00001"),
    ("Sharma General Store", "SHOP", 450, "+91 98110 22222"),
]
ALL_PHONES = ["0120 255 0100", "098100 00001", "011 4000 0000", "98110 22222", "011 9999 9999",
              "011 2336 1111", "88269 92200", "11 2332 1747", "011 4100 0000", "98100 12345", "011 9999 0000"]

# OpenStreetMap (the default, no key): nearest first, 650 m one left out, chemist and
# "pharmacy + supermarket" counted as pharmacy, building (way) placed by its centre.
OSM_ROWS = [
    ("Apollo Pharmacy", "PHARMACY", 80, "+91 88269 92200, +91 11 2332 1747"),
    ("Connaught Place Police Booth", "POLICE BOOTH", 150, "011 2336 1111"),
    ("Wellness Forever", "PHARMACY", 260, "98100 12345"),
    ("Modern Bazaar", "SHOP", 350, "011 4100 0000"),
]
OSM_A = "http://10.0.2.2:8765/a/api/interpreter"
OSM_B = "http://10.0.2.2:8765/b/api/interpreter"


def hook(**extras):
    args = ["shell", "am", "broadcast", "-n", f"{PKG}/.DebugPlacesReceiver"]
    for k, v in extras.items():
        args += ["--es", k, f"'{v}'" if v else "''"]
    adb(*args)
    time.sleep(1)


def requests():
    with Mock.lock:
        return list(Mock.requests)


def panel_texts():
    """Every text on the Debug panel, scrolling down through the whole list."""
    seen, order = set(), []
    for _ in range(8):
        xml = e2e.ui_xml()
        new = 0
        for t in re.findall(r'text="([^"]*)"', xml):
            t = t.replace("&#10;", " ").replace("&amp;", "&")
            if t and t not in seen:
                seen.add(t)
                order.append(t)
                new += 1
        if new == 0 and screen_has("BACK", timeout=1):
            break
        adb("shell", "input", "swipe", "540", "1700", "540", "700", "400")
        time.sleep(1)
    adb("shell", "input", "swipe", "540", "500", "540", "2000", "300")  # back to top
    adb("shell", "input", "swipe", "540", "500", "540", "2000", "300")
    return order


def open_panel():
    e2e.open_app()
    if not ui_tap("SETTINGS", timeout=8):
        return False
    return ui_tap("DEBUG PANEL", timeout=8) and screen_has("DEBUG PANEL", timeout=8)


def search_again():
    ui_tap("SEARCH AGAIN", timeout=8)


def leave_panel():
    for _ in range(3):
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(1)


def press_sos_and_cancel():
    e2e.open_app()
    ui_tap("SOS")
    time.sleep(6)
    alert_id = e2e.last_alert_id()
    return alert_id


def cancel_sos():
    ui_tap("CANCEL ALERT", timeout=8)
    e2e.enter_pin(e2e.NORMAL_PIN)
    wait_until(lambda: screen_has("press for help", timeout=2), 15)


def rows_in_order(texts, rows=None):
    names = [n for n, *_ in (rows or EXPECTED_ROWS)]
    return [t for t in texts if t in names]


def osm_reqs():
    return [r for r in requests() if r["path"].endswith("/api/interpreter")]


def google_reqs():
    return [r for r in requests() if r["path"] == "/v1/places:searchNearby"]


def check_list(t, rows, label):
    for name, cat, metres, phone in rows:
        check(f"{label}: lists {name}", name in t)
        check(f"{label}: {name} phone shows '{phone}'", f"phone: {phone}" in t)
    order = rows_in_order(t, rows)
    check(f"{label}: sorted nearest first", order == [n for n, *_ in rows], str(order))
    dists = [int(m.group(1)) for x in t for m in [re.fullmatch(r"(\d+) m", x)] if m]
    want = [m for _, _, m, _ in rows]
    check(f"{label}: distances correct (±3 m)", len(dists) == len(want) and all(abs(a - b) <= 3 for a, b in zip(dists, want)),
          str(dists))
    # Each row reads: TYPE, "N m", name — matched per place, so repeated labels are fine.
    got = panel_rows()
    wrong = {n: got.get(n) for n, c, _, _ in rows if got.get(n) != c}
    check(f"{label}: types correct", not wrong, str(wrong))


def panel_rows():
    """name -> type label for every row on the Debug panel (reads each screen as is)."""
    rows = {}
    for _ in range(8):
        texts = [x.replace("&amp;", "&") for x in re.findall(r'text="([^"]*)"', e2e.ui_xml()) if x]
        for i in range(len(texts) - 2):
            if re.fullmatch(r"\d+ m|\d+\.\d km", texts[i + 1]):
                rows[texts[i + 2]] = texts[i]
        if screen_has("BACK", timeout=1):
            break
        adb("shell", "input", "swipe", "540", "1700", "540", "700", "400")
        time.sleep(1)
    adb("shell", "input", "swipe", "540", "500", "540", "2000", "300")
    adb("shell", "input", "swipe", "540", "500", "540", "2000", "300")
    return rows


def is_set_up():
    prefs = adb("shell", "run-as", PKG, "cat", "shared_prefs/ekta.xml")
    return all(f'name="{k}"' in prefs for k in ("setup_done", "contact_number", "pin_normal"))


def ensure_setup():
    """Make sure Ekta is set up (contact + PINs); quick path via adb grants."""
    if is_set_up():
        return True
    log("  (app not set up yet: setting it up first)")
    for perm in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "SEND_SMS",
                 "POST_NOTIFICATIONS", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE", "BLUETOOTH_SCAN",
                 "NEARBY_WIFI_DEVICES", "ACCESS_LOCAL_NETWORK"):
        adb("shell", "pm", "grant", PKG, f"android.permission.{perm}")
    adb("shell", "appops", "set", PKG, "USE_FULL_SCREEN_INTENT", "allow")
    adb("shell", "appops", "set", PKG, "SYSTEM_ALERT_WINDOW", "allow")
    adb("shell", "dumpsys", "deviceidle", "whitelist", f"+{PKG}")
    adb("shell", "svc", "bluetooth", "enable")
    e2e.open_app()
    for _ in range(3):
        ui_tap("TURN ON SOS ALERTS", timeout=8)
        if screen_has("EMERGENCY CONTACT", timeout=10):
            break
        e2e.open_app()
    ui_tap("Your name.*")
    adb("shell", "input", "text", "Riya")
    time.sleep(1)
    ui_tap("Name")
    adb("shell", "input", "text", "Contact")
    time.sleep(1)
    ui_tap("Phone number")
    adb("shell", "input", "text", "5554")
    time.sleep(1)
    e2e.hide_keyboard()
    ui_tap("SAVE")
    time.sleep(2)
    for label, value in (("Cancel PIN \\(4 digits\\)", e2e.NORMAL_PIN), ("Cancel PIN again", e2e.NORMAL_PIN),
                         ("Duress PIN \\(4 digits\\)", e2e.DURESS_PIN)):
        ui_tap(label)
        adb("shell", "input", "text", value)
        time.sleep(1)
    e2e.hide_keyboard()
    ui_tap("SAVE PINS")
    time.sleep(2)
    return is_set_up()


def main():
    e2e.wait_boot()
    e2e.wake_and_unlock()
    srv = places_mock.start()
    log(f"fake OpenStreetMap + Google Places servers on port {places_mock.PORT}")
    e2e.set_location(LAT, LNG)
    adb("shell", "cmd", "location", "set-location-enabled", "true")
    e2e.airplane(False)
    check("Ekta is set up (contact + PINs)", ensure_setup())
    # No Google key → OpenStreetMap (the default). Both sources point at the fake servers.
    hook(base_url=f"http://10.0.2.2:{places_mock.PORT}", osm_urls=f"{OSM_A},{OSM_B}", key="")
    sms_before = e2e.sms_inbox()

    # ---------- 1. SOS → one OpenStreetMap request, built correctly ----------
    log("\n== 1. SOS → OpenStreetMap request (no key needed)")
    Mock.reset("ok")
    alert_id = press_sos_and_cancel()
    check("SOS makes the lookup", wait_until(lambda: len(osm_reqs()) >= 1, 40), f"{len(requests())} requests")
    time.sleep(3)
    rq = osm_reqs()
    check("one request per map server, asked at the same time (all 3 kinds in one)",
          sorted(x["path"] for x in rq) == ["/a/api/interpreter", "/b/api/interpreter"] and len(requests()) == 2
          and abs(rq[0]["t"] - rq[-1]["t"]) < 3, str([x["path"] for x in rq]))
    check("no Google request without a key", len(google_reqs()) == 0)
    r = rq[0] if rq else {"data": "", "ua": "", "ctype": "", "path": "", "key": None}
    check("POST to /api/interpreter", r["path"].endswith("/api/interpreter"), r["path"])
    check("sent as a form (data=...)", (r["ctype"] or "").startswith("application/x-www-form-urlencoded"), r["ctype"])
    check("identifies the app (User-Agent), no email or personal data", (r["ua"] or "").startswith("EktaSOS/1.0")
          and "@" not in (r["ua"] or ""), r["ua"])
    check("no API key sent to OpenStreetMap", not r.get("key"))
    q = r["data"]
    arounds = re.findall(r"around:(\d+),(-?[\d.]+),(-?[\d.]+)", q)
    off = max((e2e.math.dist((float(a), float(b)), (LAT, LNG)) * 111_320 for _, a, b in arounds), default=1e9)
    radii = sorted(int(r) for r, _, _ in arounds)
    check("around her SOS location: police + pharmacies up to 5 km, shops up to 3 km",
          radii == [3000, 5000, 5000, 5000, 5000] and off < 30, f"radii {radii}, centre {off:.1f} m off")
    shops = next((f"around:{r},{a},{b}" for r, a, b in arounds if r == "3000"), "around:3000")
    check("asks for police", '["amenity"="police"]' in q)
    check("asks for pharmacies + chemists", '["amenity"="pharmacy"]' in q and '["healthcare"="pharmacy"]' in q
          and '["shop"="chemist"]' in q)
    check("asks for shops", f'nwr["shop"]({shops})' in q)
    check("JSON out, buildings placed by their centre", q.startswith("[out:json]") and "out center tags;" in q)
    e2e.set_location(*e2e.north(LAT, LNG, 150))
    time.sleep(25)
    check("no extra lookups while she moves (one per SOS)", len(requests()) == 2, f"{len(requests())} requests")
    e2e.set_location(LAT, LNG)
    cancel_sos()

    # ---------- 2. Debug panel shows the right list ----------
    log("\n== 2. Debug panel (OpenStreetMap)")
    check("Settings → DEBUG PANEL opens", open_panel())
    t = panel_texts()
    check("status DONE · 4 with a phone within 1 km · 2 further away · 2 without a phone hidden",
          "STATUS: DONE" in t and
          "4 places with a phone within 1 km · 2 nearest further away · 2 without a phone hidden" in t, " | ".join(t)[:220])
    check("places WITHOUT a phone are not listed", "Unnamed shop" not in t and "Medicine House" not in t
          and not any(x == "phone: not listed" for x in t))
    check("source shown: OpenStreetMap (free, no key)", "source: OpenStreetMap (free, no key)" in t)
    check("says DEMO · nobody is called or messaged", any("nobody on this list is called or messaged" in x for x in t))
    check("lookup is tied to this SOS", f"SOS {alert_id[:8]}" in t, alert_id[:8])
    check_list(t, OSM_ROWS, "OSM")
    check("section WITHIN 1 KM shown, then NEAREST FURTHER AWAY",
          "WITHIN 1 KM" in t and "NEAREST FURTHER AWAY" in t and t.index("WITHIN 1 KM") < t.index("NEAREST FURTHER AWAY"))
    rows = panel_rows()
    check("further away: only 1 police with a phone nearby → nearest police station with a phone added (1.5 km)",
          rows.get("Sector 62 Police Station") == "POLICE BOOTH" and "1.5 km" in t)
    check("further away: only 1 shop with a phone nearby → nearest further shop with a phone added (1.2 km)",
          rows.get("Too Far Kirana") == "SHOP" and "1.2 km" in t)
    check("further away: pharmacy without a phone is never added", "Far Pharmacy" not in t)
    check("further-away places come after the 1 km list", t.index("Too Far Kirana") > t.index("Modern Bazaar")
          if "Too Far Kirana" in t and "Modern Bazaar" in t else False)
    check("address shown when OpenStreetMap has one", "Janpath, New Delhi" in t)

    # ---------- 3. Nobody is contacted ----------
    log("\n== 3. No calls, no messages")
    ui_tap(r"phone: 011 2336 1111", timeout=5)
    time.sleep(3)
    check("tapping a phone number does nothing (no dialer)", f"{PKG}/.MainActivity" in e2e.top_activity(), e2e.top_activity())
    check("no phone call placed", not e2e.in_phone_call())
    new_sms = [b for b in e2e.sms_inbox() if b not in sms_before]
    check("no SMS sent to any listed place",
          not any(p.replace(" ", "") in b.replace(" ", "") for b in new_sms for p in ALL_PHONES))
    leave_panel()

    # ---------- 4. Survives an app restart ----------
    log("\n== 4. After restart")
    adb("shell", "am", "force-stop", PKG)
    time.sleep(2)
    check("panel still shows the list after the app restarts", open_panel() and screen_has("Wellness Forever", 8))

    # ---------- 5. Busy / broken OpenStreetMap servers ----------
    log("\n== 5. Busy servers and errors")
    Mock.reset("osm_busy")
    search_again()
    check("first server busy (429) → the other server's answer used → list shown", screen_has("4 places with a phone within 1 km", 45)
          and sorted(x["path"] for x in osm_reqs()) == ["/a/api/interpreter", "/b/api/interpreter"],
          str([x["path"] for x in osm_reqs()]))
    Mock.reset("osm_remark")
    search_again()
    check("first server times out inside (\"runtime error\") → other server used", screen_has("4 places with a phone within 1 km", 45)
          and len(osm_reqs()) == 2, str(len(osm_reqs())))
    Mock.reset("osm_all_busy")
    search_again()
    check("all servers busy → says busy and retries by itself", screen_has("SERVERS BUSY", 45) and screen_has("trying again", 5))
    Mock.reset("empty")
    search_again()
    check("nothing nearby → 0 places + clear message",
          screen_has("0 places with a phone within 1 km", 45) and screen_has("No pharmacies, shops or police booths with a phone", 5))

    # ---------- 6. Server too slow → automatic retry ----------
    log("\n== 6. Too slow → retry")
    Mock.reset("slow")
    Mock.slow_seconds = 70          # longer than the app waits (60 s per server)
    search_again()
    check("servers too slow → says busy, will try again", screen_has("trying again", 110))
    Mock.mode = "ok"
    check("…then retries by itself and succeeds", screen_has("4 places with a phone within 1 km", 60))
    leave_panel()

    # ---------- 7. Offline at SOS time → runs when internet returns ----------
    log("\n== 7. Offline")
    Mock.reset("ok")
    e2e.airplane(True)
    press_sos_and_cancel()
    time.sleep(4)
    check("offline → no request yet", len(requests()) == 0, str(len(requests())))
    cancel_sos()
    check("panel says WAITING FOR INTERNET", open_panel() and screen_has("WAITING FOR INTERNET", 8))
    e2e.airplane(False)
    check("internet back → searches by itself", wait_until(lambda: len(requests()) >= 1, 60), str(len(requests())))
    check("…and shows the list", screen_has("4 places with a phone within 1 km", 45))

    # ---------- 8. With a Google key → Google Places instead ----------
    log("\n== 8. Google Places (when a key is added)")
    hook(key=TEST_KEY)
    Mock.reset("ok")
    search_again()
    check("with a key → Google requests, not OpenStreetMap", wait_until(lambda: len(google_reqs()) >= 3, 30)
          and len(osm_reqs()) == 0, f"google={len(google_reqs())} osm={len(osm_reqs())}")
    g = google_reqs()
    check("exactly 3 Google requests (police, pharmacy, shop)", len(g) == 3, str(len(g)))
    check("Google: key + exact field mask + JSON", all(x["key"] == TEST_KEY and x["mask"] == FIELD_MASK
                                                       and (x["ctype"] or "").startswith("application/json") for x in g))
    got_types = sorted(tuple(x["body"].get("includedTypes", [])) for x in g)
    check("Google: right types", got_types == sorted(tuple(v) for v in EXPECTED_TYPES.values()), str(got_types))
    check("Google: 1 km around her, nearest first", all(
        x["body"]["locationRestriction"]["circle"]["radius"] == 1000.0 and x["body"].get("rankPreference") == "DISTANCE"
        for x in g))
    check("Google: list shown, source Google Places", screen_has("4 places with a phone within 1 km", 30)
          and screen_has("source: Google Places", 5))
    t = panel_texts()
    check_list(t, EXPECTED_ROWS, "Google")
    check("Google: place 1.2 km away left out, duplicate shown once",
          "Far Away Mart" not in t and sum(1 for x in t if x == "Big Bazaar") == 1)
    check("Google: place without a phone hidden", "Wellness Chemist" not in t and screen_has("1 without a phone hidden", 5))
    Mock.reset("bad_key")
    search_again()
    check("Google: bad key → says the key is not valid", screen_has("the API key is not valid", 30))
    Mock.reset("billing")
    search_again()
    check("Google: billing off → says billing is not enabled", screen_has("billing is not enabled", 30))
    hook(key="")
    leave_panel()

    # ---------- 9. SOS itself unaffected ----------
    log("\n== 9. SOS still works")
    Mock.reset("bad_key")       # even when the lookup fails, the SOS must go out
    aid = press_sos_and_cancel()
    check("SOS reaches the server even when the lookup fails",
          wait_until(lambda: e2e.doc_field(aid, "status") == "active", 30))
    cancel_sos()
    new_sms = [b for b in e2e.sms_inbox() if b not in sms_before]
    check("whole test: no SMS to any listed place",
          not any(p.replace(" ", "") in b.replace(" ", "") for b in new_sms for p in ALL_PHONES))

    hook(base_url="", osm_urls="", key="")
    srv.shutdown()
    passed = sum(ok for _, ok in e2e.results)
    log(f"\n=== {passed}/{len(e2e.results)} places checks passed ===")
    for name, ok in e2e.results:
        if not ok:
            log(f"  FAILED: {name}")
    sys.exit(0 if passed == len(e2e.results) else 1)


if __name__ == "__main__":
    main()
