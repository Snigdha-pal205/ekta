#!/usr/bin/env python3
"""
End-to-end test for Ekta SOS on an Android emulator against the real Firebase project.

Each round: fresh install -> setup through the real permission popups -> SOS from the app
(real nearby alert + real SMS, demo 112 call + demo committee SMS) -> I'M SAFE -> alerts from
fake nearby/far phones open the full-screen page -> locked screen -> restart.

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


def open_app():
    """am start, retried: right after a restart the first launch can get swallowed."""
    for _ in range(5):
        adb("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity")
        time.sleep(2)
        if f"{PKG}/.MainActivity" in top_activity():
            return True
        wake_and_unlock()
    return False


def ui_xml():
    """Fresh UI dump ('' if uiautomator couldn't read the screen this time)."""
    adb("shell", "rm", "-f", "/sdcard/ui.xml")
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return adb("shell", "cat", "/sdcard/ui.xml")


def ui_tap(pattern, timeout=12):
    end = time.time() + timeout
    while time.time() < end:
        xml = ui_xml()
        for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
            if re.fullmatch(pattern, m.group(1), re.I):
                a, b, c, d = map(int, m.groups()[1:])
                adb("shell", "input", "tap", str((a + c) // 2), str((b + d) // 2))
                time.sleep(2)
                return True
        time.sleep(1)
    return False


def screen_has(text, timeout=10):
    end = time.time() + timeout
    while True:
        if text.lower() in ui_xml().lower():
            return True
        if time.time() > end:
            return False
        time.sleep(1)


def service_running():
    return "ServiceRecord" in adb("shell", "dumpsys", "activity", "services", PKG)


def granted(perm):
    out = adb("shell", "dumpsys", "package", PKG)
    return re.search(rf"{perm}: granted=true", out) is not None


def device_id():
    out = adb("shell", "run-as", PKG, "cat", "shared_prefs/ekta.xml")
    m = re.search(r'name="device_id">([^<]+)<', out)
    return m.group(1) if m else None


def hide_keyboard():
    """BACK only while the keyboard is up (otherwise BACK would close the dialog)."""
    if "mInputShown=true" in adb("shell", "dumpsys", "input_method"):
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(1)


def wake_and_unlock():
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    time.sleep(1)


def top_activity():
    out = adb("shell", "dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity=.*?\{[^}]*? ([\w.]+/[\w.]+)", out)
    return m.group(1) if m else ""


def wait_alert_page(timeout):
    """Waits for the full-screen alert page; returns its 'N m away' text or None."""
    end = time.time() + timeout
    while time.time() < end:
        if "AlertActivity" in top_activity():
            time.sleep(1)
            m = re.search(r'text="(\d+ m away)"', ui_xml())
            return m.group(1) if m else "page shown"
        time.sleep(1)
    return None


def sms_inbox():
    """Messages the phone has actually sent out over the SIM (the 'sent' folder).
    The emulator doesn't loop SMS back into its own inbox; on real phones the
    contact's phone receives them."""
    out = adb("shell", "content", "query", "--uri", "content://sms/sent", "--projection", "body")
    return re.findall(r"body=(.*)", out)


def wait_sms(contains, before_count, timeout=40):
    end = time.time() + timeout
    while time.time() < end:
        hits = [b for b in sms_inbox() if contains in b]
        if len(hits) > before_count:
            return hits[0]
        time.sleep(2)
    return None


def in_phone_call():
    out = adb("shell", "dumpsys", "telephony.registry")
    return any(int(x) != 0 for x in re.findall(r"mCallState=(\d)", out))


# ---------- One round ----------

def run_round(n, place, lat, lng, test_live_update=False):
    log(f"\n=== Round {n}: {place} ({lat}, {lng}) ===")
    clear_alerts()
    for _ in range(3):  # after a restart the lock screen can take a moment to go away
        wake_and_unlock()
        if "keyguard" not in top_activity().lower():
            break
        time.sleep(3)

    # 1. Fresh install + setup through the real permission popups
    adb("uninstall", PKG)
    check("install APK", "Success" in adb("install", APK))
    # Special-access pages (full-screen + draw-over-apps) are toggles inside Settings;
    # pre-allow them so the run doesn't depend on each Android build's Settings layout.
    adb("shell", "appops", "set", PKG, "USE_FULL_SCREEN_INTENT", "allow")
    adb("shell", "appops", "set", PKG, "SYSTEM_ALERT_WINDOW", "allow")
    set_location(lat, lng)
    open_app()
    check("setup screen shown on first launch", screen_has("TURN ON SOS ALERTS", timeout=30))
    ui_tap("TURN ON SOS ALERTS")
    ui_tap("While using the app")        # location
    ui_tap("Allow")                      # SMS
    ui_tap("Allow")                      # notifications
    ui_tap("Allow all the time")         # background location settings page
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(2)
    ui_tap("Allow")                      # battery optimisation
    time.sleep(3)
    check("location granted", granted("android.permission.ACCESS_FINE_LOCATION"))
    check("SMS granted", granted("android.permission.SEND_SMS"))
    check("notifications granted", granted("android.permission.POST_NOTIFICATIONS"))
    check("background location granted", granted("android.permission.ACCESS_BACKGROUND_LOCATION"))
    check("battery saver exemption", PKG in adb("shell", "dumpsys", "deviceidle", "whitelist"))

    # Emergency contact dialog appears at the end of setup. The emulator's own number
    # (5554) is used; sent messages are checked in the phone's SMS "sent" folder.
    check("emergency contact dialog shown", screen_has("EMERGENCY CONTACT"))
    ui_tap("Name")
    for word in ["Test", "%s", "Contact"]:  # type in small chunks so no keys get dropped
        adb("shell", "input", "text", word)
    time.sleep(1)
    ui_tap("Phone number"); adb("shell", "input", "text", "5554")
    time.sleep(1)
    hide_keyboard()
    ui_tap("SAVE")
    time.sleep(2)
    check("SOS screen after setup", screen_has("press for help") and screen_has("Test Contact"))
    check("listener service running", service_running())
    set_location(lat, lng)
    time.sleep(8)  # let the service get its first fix

    # 2. Press SOS in the app
    before_docs = len(list_alerts(token()))
    sos_before = len([b for b in sms_inbox() if "SOS! I need help" in b])
    ui_tap("SOS")
    time.sleep(8)
    docs = list_alerts(token())
    mine = [d for d in docs if d["fields"]["senderId"]["stringValue"] == device_id()]
    check("nearby alert reaches Firebase (exactly one)", len(mine) == 1, f"{len(mine)} from this phone")
    if mine:
        f = mine[0]["fields"]
        dist = math.dist((f["lat"]["doubleValue"], f["lng"]["doubleValue"]), (lat, lng)) * 111_320
        check("alert has correct location", dist < 30, f"{dist:.0f} m off")
    check("SOS active page shown", screen_has("I'M SAFE"))
    check("112 call shown as DEMO", screen_has("Calling 112") and screen_has("DEMO · NO REAL CALL"))
    check("no real phone call placed", not in_phone_call())
    check("women's committee shown as DEMO", screen_has("DEMO · not actually sent"))
    sms = wait_sms("SOS! I need help", sos_before)
    check("REAL SMS with live location sent to contact", sms is not None, (sms or "none")[:70])
    if sms:
        m = re.search(r"q=(-?[\d.]+),(-?[\d.]+)", sms)
        d = math.dist((float(m.group(1)), float(m.group(2))), (lat, lng)) * 111_320 if m else 1e9
        check("SMS map link points to my location", d < 30, f"{d:.0f} m off")
    check("sender phone does NOT alert itself", wait_alert_page(4) is None)

    if test_live_update:
        upd_before = len([b for b in sms_inbox() if "live location:" in b and "update" in b])
        log("  …waiting 2 min for the live-location update SMS")
        upd = wait_sms("Ekta SOS update", upd_before, timeout=170)
        check("live location update SMS after 2 min", upd is not None, (upd or "none")[:60])

    safe_before = len([b for b in sms_inbox() if "I'm safe" in b])
    ui_tap("I'M SAFE")
    time.sleep(2)
    check("I'M SAFE returns to SOS screen", screen_has("press for help"))
    check("'I'm safe' SMS sent to contact", wait_sms("I'm safe", safe_before) is not None)

    # 3. Alerts from other phones at various distances → full-screen page
    def expect(label, a_lat, a_lng, should_ring, **kw):
        post_alert(a_lat, a_lng, **kw)
        page = wait_alert_page(20 if should_ring else 12)
        check(label, (page is not None) == should_ring, page or "no alert")
        if page:
            if not ui_tap("DISMISS", timeout=6):
                adb("shell", "input", "keyevent", "KEYCODE_BACK")
            for _ in range(10):  # make sure it's gone before the next case
                if "AlertActivity" not in top_activity():
                    break
                time.sleep(1)

    expect("110 m north → full-screen alert", *north(lat, lng, 110), True)
    expect("450 m east → full-screen alert", *east(lat, lng, 450), True)
    expect("560 m north → silent", *north(lat, lng, 560), False)
    expect("1.2 km south → silent", *north(lat, lng, -1200), False)
    expect("50 m but 15 min old → silent", *north(lat, lng, 50), False, age_min=15)

    # 4. Phone locked, app in background → page shows over the lock screen
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
    time.sleep(3)
    expect("screen locked: 300 m → full-screen alert", *east(lat, lng, -300), True)
    wake_and_unlock()

    # 5. Restart the phone; app is NOT opened afterwards
    adb("shell", "sync")
    adb("reboot")
    time.sleep(10)
    wait_boot()
    time.sleep(20)
    set_location(lat, lng)
    time.sleep(10)
    check("after restart: service running without opening app", service_running())
    # After a restart Android hands background apps a location only after a while
    # (the service retries every 30 s), so allow up to 3 minutes here.
    post_alert(*north(lat, lng, 400))
    page = wait_alert_page(180)
    check("after restart: 400 m → full-screen alert", page is not None, page or "no alert")
    if page:
        if not ui_tap("DISMISS", timeout=6):
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
    wake_and_unlock()


def main():
    rounds = int(sys.argv[1]) if len(sys.argv) > 1 else 3
    wait_boot()
    for i in range(rounds):
        name, lat, lng = PLACES[i % len(PLACES)]
        run_round(i + 1, name, lat, lng, test_live_update=(i == 0))
    clear_alerts()
    passed = sum(ok for _, ok in results)
    log(f"\n=== {passed}/{len(results)} checks passed ===")
    for name, ok in results:
        if not ok:
            log(f"  FAILED: {name}")
    sys.exit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
