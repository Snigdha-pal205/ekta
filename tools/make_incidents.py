"""Makes app/src/main/assets/incidents.json — SAMPLE DATA ONLY, for the hackathon demo.

These are generated points, NOT real incident reports. They exist so the
"Past incidents" factor of the Safe Score can be shown working.
Run: python3 tools/make_incidents.py
"""
import json, math, random

random.seed(2026)
# A few busy areas around Delhi NCR, with how many sample points each gets.
HUBS = [
    ("Noida Sector 62 / Khora", 28.6257, 77.3543, 40),
    ("Connaught Place", 28.6315, 77.2167, 25),
    ("Noida Sector 18", 28.5708, 77.3260, 30),
    ("Lajpat Nagar", 28.5677, 77.2433, 20),
    ("Indirapuram", 28.6460, 77.3695, 25),
    ("Cyber City Gurugram", 28.4950, 77.0890, 15),
]
TYPES = ["harassment", "stalking", "eve-teasing", "theft", "poorly lit street"]
# Hours weighted towards evening and night.
HOURS = list(range(24))
WEIGHTS = [3, 3, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 3, 4, 5, 5, 4, 4]

out = []
for name, lat, lng, n in HUBS:
    for _ in range(n):
        r = random.uniform(0, 1500) * math.sqrt(random.random())  # metres from the hub
        a = random.uniform(0, 2 * math.pi)
        out.append({
            "lat": round(lat + r * math.cos(a) / 111_320, 6),
            "lng": round(lng + r * math.sin(a) / (111_320 * math.cos(math.radians(lat))), 6),
            "date": f"2026-{random.randint(1, 10):02d}-{random.randint(1, 28):02d}",
            "hour": random.choices(HOURS, WEIGHTS)[0],
            "type": random.choice(TYPES),
        })

json.dump({
    "_note": "SAMPLE DATA for the hackathon demo. Generated points, NOT real incident reports.",
    "incidents": out,
}, open("app/src/main/assets/incidents.json", "w"), indent=1)
print(len(out), "sample incidents written")
