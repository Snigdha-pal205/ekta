#!/usr/bin/env python3
"""
End-to-end test for Ekta SOS on an Android emulator against the real Firebase project.

Each round: fresh install -> setup flow through the real permission popups -> SOS from
the app -> alerts from fake nearby/far phones -> locked screen -> restart.

Usage: python3 tests/e2e.py [rounds]
Needs: a running emulator (adb), the Firebase CLI logged in (for cleanup reads/deletes).
"""
import json, math, os, re, subprocess, sys, time
from datetime import datetime, timedelta, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APK = os.path.join(ROOT, "Ekta-SOS.apk")
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
FIREBASE = os.path.expanduser("~/.npm-global/bin/firebase")
PKG = "com.ekta.sos"
PROJECT = "ekta-sos-3ll94"
DOCS = f"https://firestore.googleapis.com/v1/projects/{PROJECT}/databases/(default)/documents/alerts"

# A different place each round. Round 2 sits right next to a grid-cell edge, so
# alerts come from the neighbouring cell.
PLACES = [
    ("New Delhi", 28.6139, 77.2090),
    ("Mumbai (cell edge)", 19.0199, 72.8426),
    ("Bengaluru", 12.9716, 77.5946),
]

results = []


def sh(*args, check=False):
    return subprocess.run(args, capture_output=True, text=True, check=check).stdout


def adb(*args):
    return sh(ADB, *args)


def log(msg):
    print(msg, flush=True)


def check(name, ok, detail=""):
    results.append((name, ok))
    log(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f"  ({detail})" if detail else ""))


# ---------- Firestore ----------

def token():
    sh(FIREBASE, "projects:list")  # makes the CLI refresh its access token
    cfg = json.load(open(os.path.expanduser("~/.config/configstore/firebase-tools.json")))
    return cfg["tokens"]["access_token"]


def http(method, url, tok=None, body=None):
    """HTTP via curl (python.org Python on macOS often lacks CA certificates)."""
    cmd = ["curl", "-s", "-X", method, url, "-w", "\n%{http_code}"]
    if tok:
        cmd += ["-H", f"Authorization: Bearer {tok}"]
    if body is not None:
        cmd += ["-H", "Content-Type: application/json", "-d", json.dumps(body)]
    out = sh(*cmd)
    text, _, code = out.rpartition("\n")
    return int(code or 0), text


def list_alerts(tok):
    code, text = http("GET", DOCS + "?pageSize=300", tok)
    return json.loads(text).get("documents", []) if code == 200 else []


def clear_alerts():
    tok = token()
    for d in list_alerts(tok):
        http("DELETE", "https://firestore.googleapis.com/v1/" + d["name"], tok)


def post_alert(lat, lng, sender="test-phone", age_min=0):
    """Same request a phone pressing SOS makes (no auth, like the app)."""
    created = datetime.now(timezone.utc) - timedelta(minutes=age_min)
    body = {"fields": {
        "lat": {"doubleValue": lat}, "lng": {"doubleValue": lng},
        "accuracy": {"doubleValue": 5},
        "cell": {"stringValue": f"{math.floor(lat / 0.01)}_{math.floor(lng / 0.01)}"},
        "senderId": {"stringValue": sender},
        "createdAt": {"timestampValue": created.strftime("%Y-%m-%dT%H:%M:%S.%fZ")},
    }}
    return http("POST", DOCS, body=body)[0]


def north(lat, lng, meters):
    return lat + meters / 111_320.0, lng


def east(lat, lng, meters):
    return lat, lng + meters / (111_320.0 * math.cos(math.radians(lat)))


# ---------- Device ----------

def wait_boot():
    adb("wait-for-device")
    for _ in range(120):
        if adb("shell", "getprop", "sys.boot_completed").strip() == "1":
            return
        time.sleep(2)


def set_location(lat, lng):
    for _ in range(2):
        adb("emu", "geo", "fix", str(lng), str(lat))
        time.sleep(1.5)


def sos_texts():
    """Texts of all SOS notifications currently showing."""
    out = adb("shell", "dumpsys", "notification", "--noredact")
    return set(re.findall(r"android\.text=String \((Someone \d+ m from you[^)]*)\)", out))


def wait_new_alert(before, timeout=20):
    end = time.time() + timeout
    while time.time() < end:
        new = sos_texts() - before
        if new:
            return new
        time.sleep(1)
    return set()


def ui_tap(pattern, timeout=12):
    end = time.time() + timeout
    while time.time() < end:
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        xml = adb("shell", "cat", "/sdcard/ui.xml")
        for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
            if re.fullmatch(pattern, m.group(1), re.I):
                a, b, c, d = map(int, m.groups()[1:])
                adb("shell", "input", "tap", str((a + c) // 2), str((b + d) // 2))
                time.sleep(2)
                return True
        time.sleep(1)
    return False


def screen_has(text):
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return text.lower() in adb("shell", "cat", "/sdcard/ui.xml").lower()


def service_running():
    return "ServiceRecord" in adb("shell", "dumpsys", "activity", "services", PKG)


def granted(perm):
    out = adb("shell", "dumpsys", "package", PKG)
    return re.search(rf"{perm}: granted=true", out) is not None


def device_id():
    out = adb("shell", "run-as", PKG, "cat", "shared_prefs/ekta.xml")
    m = re.search(r'name="device_id">([^<]+)<', out)
    return m.group(1) if m else None


def wake_and_unlock():
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    time.sleep(1)


# ---------- One round ----------

def run_round(n, place, lat, lng):
    log(f"\n=== Round {n}: {place} ({lat}, {lng}) ===")
    clear_alerts()
    wake_and_unlock()

    # 1. Fresh install + setup through the real popups
    adb("uninstall", PKG)
    check("install APK", "Success" in adb("install", APK))
    set_location(lat, lng)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(4)
    check("setup screen shown on first launch", screen_has("TURN ON SOS ALERTS"))
    ui_tap("TURN ON SOS ALERTS")
    ui_tap("While using the app")
    ui_tap("Allow")                      # notifications
    ui_tap("Allow all the time")         # background location settings page
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(2)
    ui_tap("Allow")                      # battery optimisation
    time.sleep(3)
    check("location granted", granted("android.permission.ACCESS_FINE_LOCATION"))
    check("notifications granted", granted("android.permission.POST_NOTIFICATIONS"))
    check("background location granted", granted("android.permission.ACCESS_BACKGROUND_LOCATION"))
    check("battery saver exemption", PKG in adb("shell", "dumpsys", "deviceidle", "whitelist"))
    check("SOS screen after setup", screen_has("press for help") and screen_has("on duty"))
    check("listener service running", service_running())
    set_location(lat, lng)
    time.sleep(8)  # let the service get its first fix

    # 2. Press SOS in the app
    before_docs = len(list_alerts(token()))
    before = sos_texts()
    ui_tap("SOS")
    time.sleep(0.3)
    ui_tap("SOS|…", timeout=2)          # panicked double tap → should not double-send
    time.sleep(8)
    docs = list_alerts(token())
    mine = [d for d in docs if d["fields"]["senderId"]["stringValue"] == device_id()]
    check("SOS from app reaches Firebase", len(docs) == before_docs + 1 and len(mine) == 1,
          f"{len(docs) - before_docs} new")
    if mine:
        f = mine[0]["fields"]
        dist = math.dist((f["lat"]["doubleValue"], f["lng"]["doubleValue"]), (lat, lng)) * 111_320
        check("SOS has correct location", dist < 30, f"{dist:.0f} m off")
    check("app shows 'SOS sent'", screen_has("SOS sent"))
    check("sender phone does NOT alert itself", not (sos_texts() - before))

    # 3. Alerts from other phones at various distances
    def expect(label, a_lat, a_lng, should_ring, **kw):
        before = sos_texts()
        post_alert(a_lat, a_lng, **kw)
        new = wait_new_alert(before, timeout=20 if should_ring else 12)
        check(label, bool(new) == should_ring, ", ".join(new) if new else "no alert")

    expect("110 m north → rings", *north(lat, lng, 110), True)
    expect("190 m east → rings", *east(lat, lng, 190), True)
    expect("230 m north → silent", *north(lat, lng, 230), False)
    expect("700 m south → silent", *north(lat, lng, -700), False)
    expect("50 m but 15 min old → silent", *north(lat, lng, 50), False, age_min=15)

    # 4. Phone locked, app in background
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
    time.sleep(3)
    expect("screen locked: 120 m → rings", *east(lat, lng, -120), True)
    wake_and_unlock()

    # 5. Restart the phone; app is NOT opened afterwards
    adb("shell", "sync")
    adb("reboot")
    time.sleep(10)
    wait_boot()
    time.sleep(15)
    set_location(lat, lng)
    time.sleep(8)
    check("after restart: service running without opening app", service_running())
    expect("after restart: 150 m → rings", *north(lat, lng, 150), True)
    wake_and_unlock()


def main():
    rounds = int(sys.argv[1]) if len(sys.argv) > 1 else 3
    wait_boot()
    for i in range(rounds):
        name, lat, lng = PLACES[i % len(PLACES)]
        run_round(i + 1, name, lat, lng)
    clear_alerts()
    passed = sum(ok for _, ok in results)
    log(f"\n=== {passed}/{len(results)} checks passed ===")
    for name, ok in results:
        if not ok:
            log(f"  FAILED: {name}")
    sys.exit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
