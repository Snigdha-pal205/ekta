"""Generates res/raw/safeher_alarm.mp3: an original 2-second, seamlessly looping alarm.

Two alternating tones (like a European two-tone siren but faster), each 0.25 s, with a
little third harmonic so it cuts through street noise, and soft 5 ms edges so the loop
has no clicks. Run: python3 tools/make_alarm.py  (needs `lame` for the mp3 step).
"""
import math, struct, subprocess, sys, wave, os

RATE = 44100
TONES = [988.0, 784.0]          # B5 / G5
BEEP = 0.25                      # seconds per tone
LOOP = 2.0                       # seconds
EDGE = int(0.005 * RATE)

out_dir = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")
wav = os.path.join(os.path.dirname(__file__), "safeher_alarm.wav")
mp3 = os.path.join(out_dir, "safeher_alarm.mp3")

samples = []
n_beep = int(BEEP * RATE)
for i in range(int(LOOP / BEEP)):
    f = TONES[i % 2]
    for n in range(n_beep):
        t = n / RATE
        v = math.sin(2 * math.pi * f * t) + 0.35 * math.sin(2 * math.pi * 3 * f * t)
        env = min(1.0, n / EDGE, (n_beep - 1 - n) / EDGE)
        samples.append(0.62 * v / 1.35 * env)

with wave.open(wav, "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(RATE)
    w.writeframes(b"".join(struct.pack("<h", int(s * 32767)) for s in samples))

subprocess.run(["lame", "--quiet", "-b", "96", "-m", "m", wav, mp3], check=True)
os.remove(wav)
print("wrote", os.path.relpath(mp3), os.path.getsize(mp3), "bytes")
