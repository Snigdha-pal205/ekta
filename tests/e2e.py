#!/usr/bin/env python3
"""
End-to-end test for Ekta SOS on an Android emulator against the real Firebase project.

Each round: fresh install -> setup through the real permission popups -> SOS from the app
(real nearby alert + real SMS, demo 112 call + demo committee SMS) -> PIN cancel (wrong / normal / duress) -> alerts from
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

EMULATOR = os.path.expanduser("~/Library/Android/sdk/emulator/emulator")


def start_emulator():
    subprocess.Popen(
        [EMULATOR, "-avd", "ekta_test", "-no-snapshot-load", "-no-snapshot-save", "-no-audio",
         "-gpu", "swiftshader_indirect", "-dns-server", "8.8.8.8,1.1.1.1"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )


def power_cycle():
    """Switch the phone off and on (cold boot). Soft 'adb reboot' crashes this emulator."""
    adb("shell", "sync")
    sh(ADB, "-s", "emulator-5554", "emu", "kill")
    for _ in range(30):
        if "emulator-5554" not in sh(ADB, "devices"):
            break
        time.sleep(2)
    subprocess.run(["pkill", "-9", "-f", "qemu-system"])
    time.sleep(3)
    for f in os.listdir(os.path.expanduser("~/.android/avd/ekta_test.avd")):
        if f.endswith(".lock"):
            os.remove(os.path.expanduser(f"~/.android/avd/ekta_test.avd/{f}"))
    start_emulator()
    wait_boot()
    time.sleep(25)
    for k in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        adb("shell", "settings", "put", "global", k, "0")


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


def wait_ui_ready(timeout=180):
    """After a restart: wait until the home screen is up and the screen can be read."""
    end = time.time() + timeout
    while time.time() < end:
        wake_and_unlock()
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
        if "launcher" in top_activity().lower() and "<hierarchy" in ui_xml():
            return True
        time.sleep(5)
    log("  WARNING: emulator UI not ready after restart")
    return False


def open_app():
    """am start, retried: right after a restart/install a cold start can take 10 s+."""
    for _ in range(8):
        adb("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity")
        if wait_until(lambda: f"{PKG}/.MainActivity" in top_activity(), 15):
            return True
        wake_and_unlock()
    log("  WARNING: couldn't bring the app to the front")
    return False


def ui_xml():
    """Fresh UI dump ('' if uiautomator couldn't read the screen this time)."""
    adb("shell", "rm", "-f", "/sdcard/ui.xml")
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return adb("shell", "cat", "/sdcard/ui.xml")


def ui_tap(pattern, timeout=12, after=2):
    end = time.time() + timeout
    while time.time() < end:
        xml = ui_xml()
        for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
            if re.fullmatch(pattern, m.group(1), re.I):
                a, b, c, d = map(int, m.groups()[1:])
                adb("shell", "input", "tap", str((a + c) // 2), str((b + d) // 2))
                time.sleep(after)
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


NORMAL_PIN = "1234"
DURESS_PIN = "9999"


def sha(x):
    import hashlib
    return hashlib.sha256(x.encode()).hexdigest()


def enter_pin(pin):
    for d in pin:
        ui_tap(d, timeout=5, after=0.3)
    ui_tap("OK", timeout=5, after=0)  # read the screen right away: "Alert cancelled" shows 2.5 s


def long_press_sos():
    """Long-press the SOS button (hidden "I'm safe now")."""
    for _ in range(3):
        m = re.search(r'text="SOS"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', ui_xml())
        if m:
            a, b, c, d = map(int, m.groups())
            x, y = str((a + c) // 2), str((b + d) // 2)
            adb("shell", "input", "swipe", x, y, x, y, "1500")
            time.sleep(2)
            return True
        time.sleep(2)
    return False


def cancel_with_pin():
    ui_tap("CANCEL ALERT")
    enter_pin(NORMAL_PIN)
    time.sleep(4)


def pins_saved():
    out = adb("shell", "run-as", PKG, "cat", "shared_prefs/ekta.xml")
    return 'name="pin_normal"' in out and 'name="pin_duress"' in out \
        and NORMAL_PIN not in re.findall(r'name="pin_normal">([^<]+)<', out)[0]


def her_notifications_mention_sos():
    out = adb("shell", "dumpsys", "notification", "--noredact")
    texts = re.findall(r"android\.(?:title|text)=String \(([^)]*)\)", out)
    return any(("DURESS" in t or "SOS" in t) and "listening" not in t.lower() for t in texts)


def alarm_playing():
    out = adb("shell", "dumpsys", "audio")
    return bool(re.search(r"u/pid:\d+/\d+ state:started attr:AudioAttributes: usage=USAGE_ALARM", out))


def create_keyed(doc_id, c_lat, c_lng, kc, kd):
    body = {"fields": {
        "lat": {"doubleValue": c_lat}, "lng": {"doubleValue": c_lng},
        "cell": {"stringValue": f"{math.floor(c_lat / 0.01)}_{math.floor(c_lng / 0.01)}"},
        "senderId": {"stringValue": "server-test-phone"}, "status": {"stringValue": "active"},
        "hashC": {"stringValue": sha(kc)}, "hashD": {"stringValue": sha(kd)},
        "createdAt": {"timestampValue": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")},
    }}
    http("POST", f"{DOCS}?documentId={doc_id}", body=body)


def patch_status(doc_id, status, key, p_lat, p_lng):
    fields = {"status": {"stringValue": status}, "statusKey": {"stringValue": key},
              "statusAt": {"integerValue": str(int(time.time() * 1000))},
              "lat": {"doubleValue": p_lat}, "lng": {"doubleValue": p_lng}, "accuracy": {"doubleValue": 5.0}}
    mask = "&".join(f"updateMask.fieldPaths={k}" for k in fields)
    http("PATCH", f"{DOCS}/{doc_id}?{mask}", body={"fields": fields})


def ringing():
    """Our alarm (safeher_alarm on the ALARM stream) is playing right now."""
    return alarm_playing()


def channel_ok():
    out = adb("shell", "dumpsys", "notification")
    i = out.find("mId='safeher_alerts'")
    if i < 0:
        return False
    block = out[i:i + 3000]
    return ("mImportance=4" in block and f"android.resource://{PKG}/" in block
            and "usage=USAGE_ALARM" in block and "mVibrationPattern=[0, 900" in block)


def quiet_safe_notification():
    out = adb("shell", "dumpsys", "notification", "--noredact")
    return "She is safe, alert cancelled" in out and "channel=safeher_updates" in out


def send_push(data):
    """A real FCM push from Google's servers to this phone (what a server would send)."""
    out = adb("shell", "run-as", PKG, "cat", "shared_prefs/ekta.xml")
    m = re.search(r'name="fcm_token">([^<]+)<', out)
    if not m:
        return False
    body = {"message": {"token": m.group(1), "android": {"priority": "HIGH"}, "data": data}}
    code, _ = http("POST", "https://fcm.googleapis.com/v1/projects/ekta-sos-3ll94/messages:send", token(), body)
    return code == 200


def tap_notification(text):
    """Open the notification panel and tap the notification. The panel sometimes
    isn't fully open when the screen is read, so try up to 3 times."""
    for _ in range(3):
        adb("shell", "cmd", "statusbar", "expand-notifications")
        time.sleep(3)
        if ui_tap(text, timeout=8):
            time.sleep(2)
            return True
        adb("shell", "cmd", "statusbar", "collapse")
        time.sleep(2)
    return False


def clear_notifications():
    """Swipe away old notifications ("Clear all") so a test taps the right one."""
    adb("shell", "cmd", "statusbar", "expand-notifications")
    time.sleep(2)
    ui_tap("Clear all", timeout=4)
    adb("shell", "cmd", "statusbar", "collapse")
    time.sleep(1)


def airplane(on):
    adb("shell", "cmd", "connectivity", "airplane-mode", "enable" if on else "disable")
    time.sleep(6)


def inject(messages):
    """Simulate messages arriving over Bluetooth from a nearby phone (debug builds only)."""
    adb("shell", "am", "broadcast", "-n", f"{PKG}/.DebugMeshReceiver", "--es", "json",
        "'" + json.dumps(messages).replace("'", "") + "'")
    time.sleep(2)


def last_alert_id():
    out = adb("shell", "run-as", PKG, "cat", "shared_prefs/ekta.xml")
    m = re.search(r'name="last_alert_id">([^<]+)<', out)
    return m.group(1) if m else ""


def doc_field(doc_id, field):
    code, text = http("GET", f"{DOCS}/{doc_id}", token())
    if code != 200:
        return None
    v = json.loads(text).get("fields", {}).get(field)
    return list(v.values())[0] if v else None


def precreate(doc_id, p_lat, p_lng):
    """Another gateway already uploaded this alert."""
    body = {"fields": {
        "lat": {"doubleValue": p_lat}, "lng": {"doubleValue": p_lng},
        "cell": {"stringValue": f"{math.floor(p_lat / 0.01)}_{math.floor(p_lng / 0.01)}"},
        "senderId": {"stringValue": "neha-phone"},
        "createdAt": {"timestampValue": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")},
        "via": {"stringValue": "mesh"}, "gateway": {"stringValue": "other-phone"},
    }}
    http("POST", f"{DOCS}?documentId={doc_id}", body=body)


def wait_until(cond, timeout):
    end = time.time() + timeout
    while time.time() < end:
        try:
            if cond():
                return True
        except Exception:
            pass
        time.sleep(3)
    return False


def nearby_ok():
    """Google Nearby logs 'Missing required permissions' if it refused to start the mesh."""
    adb("logcat", "-c")
    adb("shell", "am", "force-stop", PKG)
    open_app()
    time.sleep(10)
    log_ = adb("logcat", "-d", "-s", "NearbyConnections")
    started = "startAdvertising" in log_
    refused = "Missing required permissions" in log_
    return started and not refused


def mesh_running():
    out = adb("shell", "dumpsys", "activity", "services", PKG)
    m = re.search(r"types=0x([0-9a-f]+)", out)
    return bool(m) and int(m.group(1), 16) & 0x10 != 0


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
    dns = adb("shell", "ping", "-c", "1", "-W", "5", "firestore.googleapis.com")
    if "1 received" not in dns and "bytes from" not in dns:
        log("  WARNING: emulator can't reach firestore.googleapis.com (DNS/network) — results below will be wrong")
    clear_alerts()
    for _ in range(3):  # after a restart the lock screen can take a moment to go away
        wake_and_unlock()
        if "keyguard" not in top_activity().lower():
            break
        time.sleep(3)

    # 1. Fresh install + setup through the real permission popups
    wait_ui_ready()
    adb("shell", "am", "force-stop", PKG)
    adb("uninstall", PKG)
    check("install APK", "Success" in adb("install", APK))
    # Special-access pages (full-screen + draw-over-apps) are toggles inside Settings;
    # pre-allow them so the run doesn't depend on each Android build's Settings layout.
    adb("shell", "appops", "set", PKG, "USE_FULL_SCREEN_INTENT", "allow")
    adb("shell", "appops", "set", PKG, "SYSTEM_ALERT_WINDOW", "allow")
    set_location(lat, lng)
    open_app()
    check("setup screen shown on first launch", screen_has("TURN ON SOS ALERTS", timeout=30))
    # Walk the real popups. If the chain stops early (slow emulator), tap the button
    # again, just like a user would; already-granted steps are skipped automatically.
    for attempt in range(3):
        ui_tap("TURN ON SOS ALERTS", timeout=8)
        ui_tap("While using the app", timeout=6)  # location
        for _ in range(6):                          # nearby devices, local network, notifications, SMS
            if not ui_tap("Allow", timeout=5):
                break
        if ui_tap("Allow all the time", timeout=6):  # background location settings page
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(2)
        for _ in range(3):                          # battery optimisation, Bluetooth on
            if not ui_tap("Allow", timeout=6):
                break
        time.sleep(2)
        if screen_has("EMERGENCY CONTACT", timeout=5):
            break
        log(f"  (setup didn't finish on try {attempt + 1}; tapping the button again)")
        open_app()
    check("location granted", granted("android.permission.ACCESS_FINE_LOCATION"))
    check("SMS granted", granted("android.permission.SEND_SMS"))
    check("notifications granted", granted("android.permission.POST_NOTIFICATIONS"))
    check("background location granted", granted("android.permission.ACCESS_BACKGROUND_LOCATION"))
    check("battery saver exemption", PKG in adb("shell", "dumpsys", "deviceidle", "whitelist"))
    check("nearby devices (Bluetooth) granted", granted("android.permission.BLUETOOTH_CONNECT")
          and granted("android.permission.BLUETOOTH_ADVERTISE") and granted("android.permission.BLUETOOTH_SCAN"))

    # Emergency contact dialog appears at the end of setup. The emulator's own number
    # (5554) is used; sent messages are checked in the phone's SMS "sent" folder.
    check("emergency contact dialog shown", screen_has("EMERGENCY CONTACT"))
    ui_tap("Your name.*")
    adb("shell", "input", "text", "Riya")
    time.sleep(1)
    ui_tap("Name")
    for word in ["Test", "%s", "Contact"]:  # type in small chunks so no keys get dropped
        adb("shell", "input", "text", word)
    time.sleep(1)
    ui_tap("Phone number"); adb("shell", "input", "text", "5554")
    time.sleep(1)
    hide_keyboard()
    ui_tap("SAVE")
    time.sleep(2)
    check("PIN setup dialog shown", screen_has("CANCEL PINS"))
    for label, value in (("Cancel PIN \\(4 digits\\)", NORMAL_PIN), ("Cancel PIN again", NORMAL_PIN),
                         ("Duress PIN \\(4 digits\\)", DURESS_PIN)):
        ui_tap(label)
        adb("shell", "input", "text", value)
        time.sleep(1)
    hide_keyboard()
    ui_tap("SAVE PINS")
    time.sleep(2)
    check("PINs saved (hashed, not plain)", pins_saved())
    check("SOS screen after setup", screen_has("press for help") and screen_has("Test Contact"))
    check("listener service running", service_running())
    check("mesh running (Bluetooth service type)", mesh_running())
    check("mesh really started (Nearby accepted, no missing permission)", nearby_ok())
    check("sound: one 'safeher_alerts' channel (safeher_alarm, alarm usage, strong vibration)", channel_ok())
    set_location(lat, lng)
    time.sleep(8)  # let the service get its first fix

    # 2. Press SOS in the app
    before_docs = len(list_alerts(token()))
    sos_before = len([b for b in sms_inbox() if "SOS from" in b])
    ui_tap("SOS")
    time.sleep(8)
    docs = list_alerts(token())
    mine = [d for d in docs if d["fields"]["senderId"]["stringValue"] == device_id()]
    check("nearby alert reaches Firebase (exactly one)", len(mine) == 1, f"{len(mine)} from this phone")
    if mine:
        f = mine[0]["fields"]
        dist = math.dist((f["lat"]["doubleValue"], f["lng"]["doubleValue"]), (lat, lng)) * 111_320
        check("alert has correct location", dist < 30, f"{dist:.0f} m off")
    check("SOS active page shown", screen_has("CANCEL ALERT"))
    check("112 call shown as DEMO", screen_has("Calling 112") and screen_has("DEMO · NO REAL CALL"))
    check("no real phone call placed", not in_phone_call())
    check("women's committee shown as DEMO", screen_has("DEMO · not actually sent"))
    sms = wait_sms("SOS from", sos_before)
    check("REAL SMS with live location sent to contact", sms is not None, (sms or "none")[:70])
    if sms:
        m = re.search(r"q=(-?[\d.]+),(-?[\d.]+)", sms)
        d = math.dist((float(m.group(1)), float(m.group(2))), (lat, lng)) * 111_320 if m else 1e9
        check("SMS map link points to my location", d < 30, f"{d:.0f} m off")
    check("sender phone does NOT alert itself", wait_alert_page(4) is None)

    if test_live_update:
        upd_before = len([b for b in sms_inbox() if "Ekta SOS update" in b])
        log("  …waiting 2 min for the live-location update SMS")
        upd = wait_sms("Ekta SOS update", upd_before, timeout=170)
        check("live location update SMS after 2 min", upd is not None, (upd or "none")[:60])

    # PIN: wrong → keeps running
    my_id = last_alert_id()
    ui_tap("CANCEL ALERT")
    enter_pin("0000")
    check("wrong PIN → 'Wrong PIN, try again'", screen_has("Wrong PIN, try again"))
    check("wrong PIN → alert still active on server", doc_field(my_id, "status") == "active")
    # PIN: normal → really cancelled
    safe_before = len([b for b in sms_inbox() if "is safe, alert cancelled" in b])
    enter_pin(NORMAL_PIN)
    check("normal PIN → 'Alert cancelled' screen", screen_has("Alert cancelled", timeout=3))
    check("normal PIN → back to home screen", screen_has("press for help", timeout=8))
    check("normal PIN → server status = cancelled",
          wait_until(lambda: doc_field(my_id, "status") == "cancelled", 30))
    check("normal PIN → 'safe' SMS to contact", wait_sms("is safe, alert cancelled", safe_before) is not None)

    # PIN: duress → looks cancelled, keeps running silently
    open_app()
    ui_tap("SOS")
    time.sleep(8)
    duress_id = last_alert_id()
    duress_sms = len([b for b in sms_inbox() if "EKTA DURESS:" in b])
    ui_tap("CANCEL ALERT")
    enter_pin(DURESS_PIN)
    check("duress PIN → same 'Alert cancelled' screen", screen_has("Alert cancelled", timeout=3))
    check("duress PIN → back to normal home screen", screen_has("press for help", timeout=8))
    check("duress PIN → no SOS page / 112 call visible",
          not screen_has("CANCEL ALERT", timeout=2) and not screen_has("Calling 112", timeout=1))
    check("duress PIN → no duress notification on her phone", not her_notifications_mention_sos())
    check("duress PIN → her phone stays silent (no alarm sound)", not ringing())
    check("duress PIN → server status = duress (not cancelled)",
          wait_until(lambda: doc_field(duress_id, "status") == "duress", 30))
    check("duress PIN → DURESS SMS to contact", wait_sms("EKTA DURESS:", duress_sms) is not None)
    if test_live_update:
        at0 = doc_field(duress_id, "statusAt")
        upd0 = len([b for b in sms_inbox() if "EKTA DURESS update" in b])
        log("  …waiting 2 min: duress keeps sending location silently")
        check("duress → location keeps updating on server",
              wait_until(lambda: doc_field(duress_id, "statusAt") not in (None, at0), 170))
        check("duress → live location SMS keeps going", wait_sms("EKTA DURESS update", upd0, timeout=150) is not None)
    # Later she is really safe: hidden "I'm safe now" (long-press SOS)
    adb("logcat", "-c")
    long_press_sos()
    check("long-press SOS → hidden 'I'M SAFE NOW' pad", screen_has("I'M SAFE NOW"))
    enter_pin(DURESS_PIN)
    check("safe-now + duress PIN again → same 'Alert cancelled' screen", screen_has("Alert cancelled", timeout=3))
    time.sleep(5)
    check("safe-now + duress PIN again → still duress on server", doc_field(duress_id, "status") == "duress")
    long_press_sos()
    enter_pin("0000")
    check("safe-now + wrong PIN → 'Wrong PIN, try again'", screen_has("Wrong PIN, try again"))
    safe2 = len([b for b in sms_inbox() if "is safe, alert cancelled" in b])
    enter_pin(NORMAL_PIN)
    check("safe-now + normal PIN → 'Alert cancelled' screen", screen_has("Alert cancelled", timeout=3))
    check("safe-now + normal PIN → server status cancelled",
          wait_until(lambda: doc_field(duress_id, "status") == "cancelled", 30))
    check("safe-now + normal PIN → contact told 'is safe, alert cancelled'",
          wait_sms("is safe, alert cancelled", safe2) is not None)
    demo = adb("logcat", "-d", "-s", "EktaDemo")
    check("committee + police test number informed (DEMO, not sent)",
          "women's committee" in demo and "police test number" in demo and "is safe, alert cancelled" in demo)
    check("duress tracking stopped after real cancel", not screen_has("CANCEL ALERT", timeout=2))
    # Settings → I'm safe now with nothing running looks exactly the same (gives nothing away)
    wait_until(lambda: screen_has("press for help", timeout=2), 10)
    ui_tap("SETTINGS")
    ui_tap("I'M SAFE NOW")
    enter_pin(NORMAL_PIN)
    check("Settings → I'm safe now (no alert) → same 'Alert cancelled' screen", screen_has("Alert cancelled", timeout=3))
    wait_until(lambda: screen_has("press for help", timeout=2), 10)

    # 2b. Exact location: follows her when she moves, says how exact it is, GPS off handled
    def her_dist(doc_id, t_lat, t_lng):
        a, b = doc_field(doc_id, "lat"), doc_field(doc_id, "lng")
        return math.dist((float(a), float(b)), (t_lat, t_lng)) * 111_320 if a is not None and b is not None else 1e9

    open_app()
    acc_before = len([b for b in sms_inbox() if "accurate to " in b])
    ui_tap("SOS")
    time.sleep(8)
    lid = last_alert_id()
    check("location: her SOS page shows her exact location (±m, live)",
          screen_has("YOUR EXACT LOCATION") and screen_has(" m · live"))
    acc_sms = wait_sms("accurate to ", acc_before)
    check("location: SOS SMS says how exact it is", acc_sms is not None, (acc_sms or "none")[-40:])
    check("location: server has accuracy", doc_field(lid, "accuracy") is not None)
    m_lat, m_lng = north(lat, lng, 150)
    set_location(m_lat, m_lng)
    check("location: she moves 150 m → server follows her within 25 s",
          wait_until(lambda: her_dist(lid, m_lat, m_lng) < 30, 25), f"{her_dist(lid, m_lat, m_lng):.0f} m off")
    m2_lat, m2_lng = east(m_lat, m_lng, 200)
    set_location(m2_lat, m2_lng)
    check("location: moves again 200 m → server follows again",
          wait_until(lambda: her_dist(lid, m2_lat, m2_lng) < 30, 25), f"{her_dist(lid, m2_lat, m2_lng):.0f} m off")
    ui_tap("CANCEL ALERT")
    enter_pin(NORMAL_PIN)
    check("location: cancel still works while tracking",
          wait_until(lambda: doc_field(lid, "status") == "cancelled", 30))
    set_location(lat, lng)
    wait_until(lambda: screen_has("press for help", timeout=2), 10)

    # GPS switched off, then she presses SOS
    adb("shell", "cmd", "location", "set-location-enabled", "false")
    time.sleep(2)
    open_app()
    ui_tap("SOS")
    time.sleep(4)
    check("location: GPS off → 'Turn on location' dialog pops up",
          "com.ekta.sos/.MainActivity" not in top_activity() or screen_has("location", timeout=3))
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(2)
    open_app()
    check("location: GPS off → SOS page says Location is OFF", screen_has("Location (GPS) is OFF", timeout=8))
    gid = last_alert_id()
    adb("shell", "cmd", "location", "set-location-enabled", "true")
    set_location(lat, lng)
    time.sleep(3)
    set_location(lat, lng)
    check("location: GPS turned on → her exact location goes out",
          wait_until(lambda: her_dist(gid, lat, lng) < 30, 40), f"{her_dist(gid, lat, lng):.0f} m off")
    check("location: GPS on → page shows live ±m", screen_has(" m · live", timeout=15))
    ui_tap("CANCEL ALERT")
    enter_pin(NORMAL_PIN)
    wait_until(lambda: doc_field(gid, "status") == "cancelled", 30)
    wait_until(lambda: screen_has("press for help", timeout=2), 10)

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

    # 5. No network at all (airplane mode): her side
    def count(prefix):
        return len([b for b in sms_inbox() if b.startswith(prefix)])

    sos_sms = count("SOS from")
    airplane(True)
    open_app()
    ui_tap("SOS")
    time.sleep(6)
    check("offline: 112 waits for signal", screen_has("Waiting for signal"))
    check("offline: SMS queued until signal", screen_has("no signal · will send automatically"))
    check("offline: internet shown offline", screen_has("INTERNET · OFFLINE"))
    check("offline: nothing sent yet", count("SOS from") == sos_sms)
    alert_id = last_alert_id()
    airplane(False)
    ok = wait_until(lambda: count("SOS from") > sos_sms, 70)
    check("signal back: queued SMS sent automatically", ok)
    check("signal back: alert uploaded automatically", wait_until(lambda: doc_field(alert_id, "via") == "direct", 40))
    check("signal back: 112 now calling (demo)", screen_has("Calling 112"))
    cancel_with_pin()

    # 6. Offline, and a nearby gateway confirms it texted her contact → no duplicate
    sos_sms = count("SOS from")
    airplane(True)
    ui_tap("SOS")
    time.sleep(6)
    alert_id = last_alert_id()
    inject([{"t": "ack", "id": alert_id, "h": 1, "g": "gateway-phone", "sms": "sent"}])
    check("ACK: shows SMS sent by nearby phone", screen_has("sent by a nearby Ekta phone"))
    check("ACK: shows online through gateway", screen_has("online through a nearby Ekta phone"))
    airplane(False)
    time.sleep(45)
    check("ACK: her phone does NOT text the contact again", count("SOS from") == sos_sms)
    cancel_with_pin()
    adb("shell", "input", "keyevent", "KEYCODE_HOME")

    # 7. This phone as the gateway for someone else's offline SOS
    def mesh_sos(mid, m_lat, m_lng, name, hops=0):
        now = int(time.time() * 1000)
        return [{"t": "sos", "id": mid, "h": hops, "s": f"{name.lower()}-phone", "la": m_lat, "lo": m_lng,
                 "ac": 8, "ts": now, "n": name, "cn": "Mom", "cp": "5554"}]

    def dismiss_page():
        if not ui_tap("DISMISS", timeout=6):
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
        wait_until(lambda: "AlertActivity" not in top_activity(), 10)

    gid = f"mesh-gw-{n}-{int(time.time())}"
    gw_sms = count("EKTA SOS: Priya")
    inject(mesh_sos(gid, *north(lat, lng, 110), "Priya"))
    page = wait_alert_page(20)
    check("mesh SOS 110 m → full-screen alert", page is not None, page or "no alert")
    check("alert says it came over Bluetooth/Wi-Fi", screen_has("via Bluetooth/Wi-Fi"))
    if page:
        dismiss_page()
    check("gateway: uploads it (via mesh, me as gateway)",
          wait_until(lambda: doc_field(gid, "gateway") == device_id() and doc_field(gid, "via") == "mesh", 30))
    check("gateway: texts her contact from this SIM", wait_until(lambda: count("EKTA SOS: Priya") == gw_sms + 1, 40))
    check("gateway: tells this phone's owner", "Your phone relayed an SOS" in adb("shell", "dumpsys", "notification", "--noredact"))
    inject(mesh_sos(gid, *north(lat, lng, 110), "Priya", hops=2))  # same SOS via another route
    time.sleep(12)
    check("gateway: same SOS again → no second SMS", count("EKTA SOS: Priya") == gw_sms + 1)

    # 8. Relay phone is offline too → buzzes now, uploads + texts once it gets internet
    lid = f"mesh-late-{n}-{int(time.time())}"
    late_sms = count("EKTA SOS: Asha")
    airplane(True)
    inject(mesh_sos(lid, *east(lat, lng, 200), "Asha", hops=2))
    page = wait_alert_page(20)
    check("offline relay: still shows full-screen alert", page is not None, page or "no alert")
    if page:
        dismiss_page()
    time.sleep(5)
    check("offline relay: nothing uploaded/sent while offline",
          doc_field(lid, "via") is None and count("EKTA SOS: Asha") == late_sms)
    airplane(False)
    check("offline relay: uploads when internet returns", wait_until(lambda: doc_field(lid, "via") == "mesh", 70))
    check("offline relay: then texts her contact", wait_until(lambda: count("EKTA SOS: Asha") == late_sms + 1, 60))

    # 9. Another gateway already put it online → this phone must not text again
    did = f"mesh-dup-{n}-{int(time.time())}"
    precreate(did, *north(lat, lng, 150))
    dup_sms = count("EKTA SOS: Neha")
    inject(mesh_sos(did, *north(lat, lng, 150), "Neha", hops=1))
    page = wait_alert_page(15)
    if page:
        dismiss_page()
    time.sleep(10)
    check("already online elsewhere → no SMS from this phone", count("EKTA SOS: Neha") == dup_sms)
    check("already online elsewhere → gateway unchanged", doc_field(did, "gateway") == "other-phone")

    # 10. Mesh SOS from 2 km away → no full-screen alert here, but still relayed online
    fid = f"mesh-far-{n}-{int(time.time())}"
    inject(mesh_sos(fid, *north(lat, lng, -2000), "Far", hops=3))
    page = wait_alert_page(12)
    check("mesh SOS 2 km away → no full-screen alert", page is None, page or "no alert")
    if page:
        dismiss_page()
    check("mesh SOS 2 km away → still uploaded for phones near her", wait_until(lambda: doc_field(fid, "via") == "mesh", 30))

    # 11. Receiving phone: cancel / duress over the mesh, forged cancel ignored
    def sos_with_keys(mid, m_lat, m_lng, name, kc, kd, hops=0):
        m = mesh_sos(mid, m_lat, m_lng, name, hops)
        m[0]["hc"], m[0]["hd"], m[0]["hl"] = sha(kc), sha(kd), sha("kl-" + mid)
        return m

    def status_msg(mid, status, key, name, m_lat, m_lng):
        return [{"t": "status", "id": mid, "h": 1, "s": f"{name.lower()}-phone", "la": m_lat, "lo": m_lng,
                 "ac": 8, "ts": int(time.time() * 1000), "n": name, "cp": "5554", "st": status, "k": key}]

    cid = f"pin-c-{n}-{int(time.time())}"
    p_lat, p_lng = north(lat, lng, 120)
    inject(sos_with_keys(cid, p_lat, p_lng, "Meera", "kc-" + cid, "kd-" + cid))
    check("mesh SOS with keys → alert page", wait_alert_page(20) is not None)
    check("sound: mesh alert rings (safeher_alarm, alarm stream)", wait_until(ringing, 10))
    time.sleep(6)
    check("sound: still ringing after 6 s (loops)", ringing())
    inject(status_msg(cid, "cancelled", "forged-key", "Meera", p_lat, p_lng))
    time.sleep(3)
    check("FORGED cancel → ignored, still alarming", screen_has("SOMEONE NEARBY NEEDS HELP") and alarm_playing())
    safe_gw = count("EKTA: Meera is safe")
    inject(status_msg(cid, "cancelled", "kc-" + cid, "Meera", p_lat, p_lng))
    check("real cancel → page says ALERT CANCELLED", screen_has("ALERT CANCELLED", timeout=8))
    check("real cancel → alarm stops", wait_until(lambda: not alarm_playing(), 8))
    check("real cancel → server status cancelled (this phone as gateway)",
          wait_until(lambda: doc_field(cid, "status") == "cancelled", 30))
    check("real cancel → gateway texts contact 'She is safe'",
          wait_until(lambda: count("EKTA: Meera is safe") == safe_gw + 1, 40))
    ui_tap("OK")
    wait_until(lambda: "AlertActivity" not in top_activity(), 10)

    did2 = f"pin-d-{n}-{int(time.time())}"
    q_lat, q_lng = east(lat, lng, 150)
    inject(sos_with_keys(did2, q_lat, q_lng, "Sara", "kc-" + did2, "kd-" + did2))
    if wait_alert_page(20):
        dismiss_page()
    duress_gw = count("EKTA DURESS: Sara")
    inject(status_msg(did2, "duress", "kd-" + did2, "Sara", q_lat, q_lng))
    check("duress → alert page re-opens", wait_alert_page(20) is not None)
    check("sound: duress update rings again", wait_until(ringing, 10))
    check("sound: page 'STOP SOUND' stops it, page stays", ui_tap("STOP SOUND", timeout=6) and
          wait_until(lambda: not ringing(), 6) and "AlertActivity" in top_activity())
    check("duress → 'DURESS' + 'Do NOT call her phone'",
          screen_has("DURESS") and screen_has("Do NOT call her phone"))
    check("duress → server status duress", wait_until(lambda: doc_field(did2, "status") == "duress", 30))
    check("duress → gateway texts contact DURESS warning",
          wait_until(lambda: count("EKTA DURESS: Sara") == duress_gw + 1, 40))
    # she's really safe later ("I'm safe now" on her phone) → cancel arrives
    safe_gw2 = count("EKTA: Sara is safe")
    inject(status_msg(did2, "cancelled", "kc-" + did2, "Sara", q_lat, q_lng))
    check("duress → then real cancel: page says ALERT CANCELLED", screen_has("ALERT CANCELLED", timeout=10))
    check("sound: 'She is safe' is a quiet notification (no alarm)", quiet_safe_notification() and not ringing())
    check("duress → then real cancel: alarm stops", wait_until(lambda: not alarm_playing(), 8))
    check("duress → then real cancel: server status cancelled",
          wait_until(lambda: doc_field(did2, "status") == "cancelled", 30))
    check("duress → then real cancel: gateway texts 'is safe, alert cancelled'",
          wait_until(lambda: count("EKTA: Sara is safe") == safe_gw2 + 1, 40))
    ui_tap("OK")
    wait_until(lambda: "AlertActivity" not in top_activity(), 10)

    # 11b. Receiving phone: her live location follows her (mesh + server); forged moves ignored
    lv = f"live-{n}-{int(time.time())}"
    v_lat, v_lng = north(lat, lng, 100)
    inject(sos_with_keys(lv, v_lat, v_lng, "Asha", "kc-" + lv, "kd-" + lv))
    check("live: mesh SOS → alert page", wait_alert_page(20) is not None)
    w_lat, w_lng = north(v_lat, v_lng, 60)
    mv = status_msg(lv, "active", "kl-" + lv, "Asha", w_lat, w_lng)
    mv[0]["ac"] = 6
    inject(mv)
    check("live: her new position over Bluetooth → page shows ±6 m, live", screen_has("±6 m", timeout=10))
    fake = status_msg(lv, "active", "wrong-key", "Asha", 0.5, 0.5)
    fake[0]["ac"] = 77
    fake[0]["ts"] = int(time.time() * 1000) + 5000
    inject(fake)
    time.sleep(3)
    check("live: forged position (wrong key) ignored", not screen_has("±77 m", timeout=3) and screen_has("±6 m", timeout=3))
    check("live: gateway put her new position online",
          wait_until(lambda: doc_field(lv, "lat") is not None and abs(float(doc_field(lv, "lat")) - w_lat) < 0.0002, 40))
    dismiss_page()

    # 12. Receiving phone: cancel / duress arriving from the server
    sid = f"pin-s-{n}-{int(time.time())}"
    s_lat, s_lng = north(lat, lng, 200)
    clear_notifications()
    create_keyed(sid, s_lat, s_lng, "kc-" + sid, "kd-" + sid)
    check("server alert → alert page", wait_alert_page(25) is not None)
    check("sound: server alert rings the same way", wait_until(ringing, 10))
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    check("sound: opening the alert from the notification keeps it ringing",
          tap_notification(r"Someone \d+ m from you needs help.*") and
          wait_until(lambda: "AlertActivity" in top_activity(), 8) and (time.sleep(4) or ringing()))
    check("sound: then 'STOP SOUND' on the page stops it",
          ui_tap("STOP SOUND", timeout=6) and wait_until(lambda: not ringing(), 6))
    patch_status(sid, "duress", "kd-" + sid, s_lat, s_lng)
    check("server duress → page shows DURESS", screen_has("Do NOT call her phone", timeout=15))
    patch_status(sid, "cancelled", "kd-" + sid, s_lat, s_lng)  # duress key can't cancel
    time.sleep(6)
    check("server: duress key can't cancel → still DURESS", screen_has("Do NOT call her phone", timeout=5))
    patch_status(sid, "cancelled", "kc-" + sid, s_lat, s_lng)
    check("server duress → then real cancel: ALERT CANCELLED", screen_has("ALERT CANCELLED", timeout=15))
    ui_tap("OK")
    wait_until(lambda: "AlertActivity" not in top_activity(), 10)
    sid2 = f"pin-s2-{n}-{int(time.time())}"
    create_keyed(sid2, s_lat, s_lng, "kc-" + sid2, "kd-" + sid2)
    wait_alert_page(25)
    patch_status(sid2, "cancelled", "kc-" + sid2, s_lat, s_lng)
    check("server cancel → page says ALERT CANCELLED", screen_has("ALERT CANCELLED", timeout=15))
    ui_tap("OK")
    wait_until(lambda: "AlertActivity" not in top_activity(), 10)

    # 13. Push from the server (real FCM message) → same alert + same sound
    pid = f"push-{n}-{int(time.time())}"
    u_lat, u_lng = east(lat, lng, 250)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    sent = send_push({"type": "sos", "id": pid, "senderId": "push-phone", "lat": str(u_lat), "lng": str(u_lng),
                      "createdAt": str(int(time.time() * 1000)), "status": "active",
                      "hashC": sha("kc-" + pid), "hashD": sha("kd-" + pid)})
    check("push: real FCM message accepted by Google", sent)
    page = wait_alert_page(30)
    check("push → full-screen alert page", page is not None, page or "no alert")
    check("push → same sound (safeher_alarm, alarm stream)", wait_until(ringing, 10))
    send_push({"type": "status", "id": pid, "senderId": "push-phone", "lat": str(u_lat), "lng": str(u_lng),
               "status": "duress", "statusAt": str(int(time.time() * 1000))})
    check("push duress → DURESS page + rings", screen_has("Do NOT call her phone", timeout=15) and wait_until(ringing, 10))
    send_push({"type": "status", "id": pid, "senderId": "push-phone", "lat": str(u_lat), "lng": str(u_lng),
               "status": "cancelled", "statusAt": str(int(time.time() * 1000) + 1)})
    check("push cancel → ALERT CANCELLED, sound stops, quiet notification",
          screen_has("ALERT CANCELLED", timeout=15) and wait_until(lambda: not ringing(), 8) and quiet_safe_notification())
    ui_tap("OK")
    wait_until(lambda: "AlertActivity" not in top_activity(), 10)

    # 14. Restart the phone; app is NOT opened afterwards
    power_cycle()
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
