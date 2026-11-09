"""
Safe Score end-to-end test on the emulator (real TomTom data, so needs internet).

Opens KNOW YOUR DESTINATION, searches real places, picks hours and reads the result
card off the screen. Checks the rules, not exact numbers (shops open and close):
  - the label always matches the score band (8+ Safe, 7+ Fairly safe, 5+ Be careful, else Unsafe)
  - Delhi / Noida from 11 PM to 6 AM: never above 6.5; the 🌙 note shows only when lowered
  - an area with no incident data says "no data" (not counted as safe)
  - Sarojini Nagar Market is Unsafe at 2 AM and Safe-ish at 2 PM

Run:  python3 tests/safescore_test.py      (app must be set up and in English)
"""
import html
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import e2e  # noqa: E402
from e2e import adb, ui_xml, ui_tap, screen_has  # noqa: E402

results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(("  PASS  " if ok else "  FAIL  ") + name + (f"  ({detail})" if detail else ""), flush=True)


def nodes(xml=None):
    xml = xml if xml is not None else ui_xml()
    out = []
    for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?class="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        t = html.unescape(m.group(1))
        a, b, c, d = map(int, m.groups()[2:])
        out.append((t, m.group(2), (a + c) // 2, (b + d) // 2))
    return out


def tap(x, y, after=1.5):
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(after)


def swipe(x1, y1, x2, y2):
    adb("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), "350")
    time.sleep(1.2)


def hour_label(h):
    return "12 AM" if h == 0 else f"{h} AM" if h < 12 else "12 PM" if h == 12 else f"{h - 12} PM"


def to_top():
    for _ in range(3):
        swipe(540, 700, 540, 1900)


def search(query, name):
    to_top()
    field = next(((x, y) for t, cls, x, y in nodes() if cls.endswith("EditText")), None)
    if not field:
        return False
    tap(*field)
    adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    for _ in range(4):
        adb("shell", "input", "keyevent", *(["KEYCODE_DEL"] * 15))
    adb("shell", "input", "text", query.replace(" ", "%s"))
    end = time.time() + 20
    while time.time() < end:
        hits = [(t, x, y) for t, cls, x, y in nodes() if t.startswith("📍 ") and "·" not in t]
        exact = [h for h in hits if h[0][2:].strip().lower() == name.lower()]
        loose = [h for h in hits if name.lower() in h[0].lower()]
        if exact or loose:
            t, x, y = (exact or loose)[0]
            tap(x, y, after=3)
            return t[2:].strip()
        time.sleep(1.5)
    return None


def pick_hour(h):
    want = re.compile(rf"(Now · )?{re.escape(hour_label(h))}")
    # Chips start at "Now": scroll the row back to the start, then move right to the hour.
    for _ in range(8):
        ns = nodes()
        chip = next(((x, y) for t, cls, x, y in ns if re.fullmatch(r"(Now · )?\d+ (AM|PM)", t)), None)
        if not chip or any(t.startswith("Now · ") for t, *_ in ns):
            break
        swipe(150, chip[1], 1000, chip[1])
    for _ in range(14):
        ns = nodes()
        for t, cls, x, y in ns:
            if want.fullmatch(t):
                tap(x, y, after=2)
                return True
        chip = next(((x, y) for t, cls, x, y in ns if re.fullmatch(r"(Now · )?\d+ (AM|PM)", t)), None)
        if not chip:
            swipe(540, 1500, 540, 1100)  # chips scrolled off: bring them back
            continue
        swipe(950, chip[1], 250, chip[1])  # later hours are to the right
    return False


def read_card(name, h, timeout=70):
    """Scroll to the result card and read it once it shows this place and hour."""
    head = f"📍 {name} · {hour_label(h)}".lower()
    end = time.time() + timeout
    while time.time() < end:
        for _ in range(2):
            texts = [t for t, *_ in nodes()]
            low = [t.lower() for t in texts]
            if any(head in t for t in low) and not any("checking safety" in t for t in low):
                score_t = next((t for t in texts if t.startswith("Safe score:")), None)
                if score_t:
                    score = float(re.search(r"Safe score: ([\d.]+)", score_t).group(1))
                    verdict = next((t for t in texts if " · " in t and t[:1] in ("🟢", "🟡", "🟠", "🔴")), "")
                    label = verdict[1:].split(" · ")[0].strip()
                    factors = [t for t in texts if re.match(r"(Past incidents|People around|Transport|Police|Community rating): ", t)]
                    night = any(t.startswith("🌙") for t in texts)
                    return score, label, factors, night
            swipe(540, 1900, 540, 1000)
        time.sleep(2)
    return None


def band(score):
    return "Safe" if score >= 8 else "Fairly safe" if score >= 7 else "Be careful" if score >= 5 else "Unsafe"


CASES = [
    # query,                   name on card,             hour, Delhi/Noida
    ("Sarojini Nagar Market", "Sarojini Nagar Market", 2, True),
    ("Sarojini Nagar Market", "Sarojini Nagar Market", 14, True),
    ("Connaught Place", "Connaught Place", 22, True),
    ("Connaught Place", "Connaught Place", 23, True),
    ("Sector 18 Noida", "Sector 18", 23, True),
    ("Sector 62 Noida", "Sector 62", 23, True),
]


def main():
    lang = re.search(r'app_language">(\w+)<', adb("shell", "run-as", e2e.PKG, "cat", "shared_prefs/ekta.xml"))
    if lang and lang.group(1) != "en":
        print("Set the app to English first (Settings → Language).")
        return 1
    adb("logcat", "-c")
    adb("shell", "am", "force-stop", e2e.PKG)
    e2e.open_app()
    for _ in range(3):
        if screen_has("press for help", 6):
            break
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
    check("Safe Score page opens", ui_tap("KNOW YOUR DESTINATION") and screen_has("Where are you going?", 10))

    for query, name, h, ncr in CASES:
        print(f"\n== {name} at {hour_label(h)}", flush=True)
        picked = search(query, name)
        if not picked:
            check("place found", False, query)
            continue
        name = picked  # the card shows the name of the place picked
        if not pick_hour(h):
            check("hour picked", False, hour_label(h))
            continue
        card = read_card(name, h)
        if not card:
            check("result shown", False)
            continue
        score, label, factors, night = card
        print(f"  score {score} · {label} · night note: {night}")
        for f in factors:
            print("   ", f)
        check("label matches the score band", label == band(score), f"{score} -> {label}")
        check("5 factors listed", len(factors) == 5, str(len(factors)))
        late = h >= 23 or h < 6
        if ncr and late:
            check("late night Delhi/Noida: at most 6.5", score <= 6.5, str(score))
            check("late night: Be careful or Unsafe", label in ("Be careful", "Unsafe"), label)
            check("🌙 note only when lowered to 6.5", (not night) or score == 6.5, f"note={night} score={score}")
        else:
            check("no 🌙 note before 11 PM", not night)
        inc = next((f for f in factors if f.startswith("Past incidents")), "")
        if "Sarojini" in name:
            check("no incident data shows 'no data', not safe", "no data" in inc, inc)
            if h == 2:
                check("Sarojini Nagar 2 AM is Unsafe", label == "Unsafe", f"{score} {label}")
            if h == 14:
                check("Sarojini Nagar 2 PM is at least Fairly safe", score >= 7, f"{score} {label}")

    crashes = adb("logcat", "-d", "-b", "crash").count(e2e.PKG)
    check("no crashes", crashes == 0, str(crashes))
    passed = sum(results)
    print(f"\n{passed}/{len(results)} checks passed")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
