<p align="center">
  <img src="docs/ekta-mark.svg" width="80" height="80" alt="Ekta logo">
</p>
<h1 align="center">Ekta SOS</h1>
<p align="center"><strong>Nobody walks home alone.</strong></p>
<p align="center">One button alerts every Ekta phone within 500 m, shares her exact live location, and keeps working with no internet or signal.</p>
<p align="center">
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84" alt="Android 8.0 and later">
  <img src="https://img.shields.io/badge/Kotlin-Jetpack_Compose-7F52FF" alt="Kotlin with Jetpack Compose">
  <img src="https://img.shields.io/badge/Firebase-Firestore_%2B_FCM-FFCA28" alt="Firebase Firestore and Cloud Messaging">
  <img src="https://img.shields.io/badge/Offline-Bluetooth_mesh-E84A8A" alt="Works offline over a Bluetooth mesh">
  <img src="https://img.shields.io/badge/Maps-TomTom_%C2%B7_Ola_%C2%B7_OpenStreetMap-151515" alt="TomTom, Ola Maps and OpenStreetMap">
</p>
<p align="center">
  <a href="#what-happens-when-she-presses-sos">What SOS does</a> · <a href="#screens">Screens</a> · <a href="#try-it-in-five-minutes">Try it</a> · <a href="#run-locally">Run locally</a> · <a href="#how-it-works">How it works</a> · <a href="#testing">Testing</a>
</p>

| Her phone | A helper's phone nearby | Forced to cancel (duress) |
| --- | --- | --- |
| ![SOS running: demo 112 call, exact live location, mesh, internet and SMS status](docs/screenshots/sos-active.png) | ![Full-screen alert on a nearby phone: 139 m away, live location, show me where](docs/screenshots/alert.png) | ![Duress alert: she was forced to cancel, do NOT call her phone](docs/screenshots/duress.png) |

## Why Ekta

When a woman feels unsafe, the people who can help fastest are the ones already **nearby**, not just a contact across the city. But a nearby helper needs to know **that** she needs help, **where she is right now**, and **whether it's safe to call her**.

Ekta turns every phone with the app into a neighbour who can respond. Pressing SOS rings every Ekta phone within 500 m with a full-screen alarm, even on the lock screen, and keeps sending her exact live location as she moves. If there's no internet or signal, the alert hops phone-to-phone over Bluetooth until one phone can get it online. If someone forces her to cancel, a secret **duress PIN** makes her screen look cancelled while help keeps coming.

Built for a hackathon: calling **112** is shown as a **demo**. Nothing is dialled.

**11 languages:** English, हिन्दी, বাংলা, తెలుగు, मराठी, தமிழ், ગુજરાતી, ಕನ್ನಡ, മലയാളം, ਪੰਜਾਬੀ and ଓଡ଼ିଆ. Pick one in **Settings → Language**; every screen, alert, notification and the **SMS to her emergency contact** switch to it (a nearby phone relaying her SMS offline uses her language too).

## What happens when she presses SOS

| What happens | Real or demo |
| --- | --- |
| Every Ekta phone **within 500 m** gets a **full-screen alert** that rings (even in silent mode) and vibrates | **Real** |
| Her **emergency contact** gets an SMS with a map link and how exact it is ("accurate to 8 m"), then a fresh location **every 2 min** | **Real** (normal SMS) |
| Her **exact live location** keeps updating on helpers' phones as she moves; **SHOW ME WHERE** opens walking directions to her | **Real** |
| **No internet or signal:** the alert travels over **Bluetooth / Wi-Fi Direct**, hop by hop, until a phone with internet uploads it and texts her contact from its own SIM | **Real** |
| **112** is called | **Demo:** an on-screen call card, no call is placed |
| Nearby **pharmacies, shops and police booths** with phone numbers (within 1 km) get a recorded voice call and an SMS: *"A woman near you needs help. Location: [nearest landmark]. Please help or call 112."* | **Demo:** shown as "called" in a drop-down on the SOS page; nobody is called or texted |

**Cancelling needs a PIN.** Her normal PIN really cancels and tells everyone she's safe. Her **duress PIN** shows the same "Alert cancelled" screen, but the alert keeps running silently and helpers see **"DURESS: she was forced to cancel. Do NOT call her phone."** Later, **"I'm safe now"** (long-press SOS, or Settings) ends it for real.

## Screens

| Home | Settings (⚙ on every page) |
| --- | --- |
| ![Ekta home screen with the SOS button and Know your destination](docs/screenshots/home.png) | ![Settings: emergency contact, I'm safe now](docs/screenshots/settings.png) |
| **One button**, plus **Know your destination** below it. The ⚙ in the corner opens Settings from any page, even during an SOS. | **Everything else** lives here, so the main screen stays simple. |

| Nearby places called (demo) |
| --- |
| ![SOS page drop-down: called 42 nearby places, the recorded message, and each place with its phone number](docs/screenshots/nearby-calls.png) |
| Tap the box under the 112 card: the message they'd hear and every pharmacy, shop and police booth **with a phone number**, nearest first. |

## Safe Score: know your destination

Tap **KNOW YOUR DESTINATION**, search a place, and pick an hour. Ekta gives a score from 0 to 10 (10 = safest) built from five factors, each shown with a colour, a short reason and its own score:

| Factor | How it's worked out |
| --- | --- |
| Past incidents | Reports near the place at that time of day (**sample data** for the demo) |
| People around | Shops and places open within 300 m at that hour |
| Transport | Bus stops, metro and taxi stands within 500 m that run at that hour |
| Police | Distance to the nearest police station |
| Community | Star ratings from other Ekta users for that time of day |

The score is a weighted average: people around 30%, past incidents 25%, transport 20%, police 15%, community 10%. The things that change with the hour count most. A factor with no data (no incident data for that area, no ratings yet) is shown as "no data" and left out, never counted as safe. 8+ is **Safe**, 7 to 7.9 **Fairly safe**, 5 to 6.9 **Be careful**, below 5 **Unsafe**. **Late night in Delhi / Noida (11 PM to 6 AM) the score never goes above 6.5**, so it always says Be careful or worse. **SHARE JOURNEY** sends the place and score to anyone, and you can rate the place yourself.

Screenshots are from the Android emulator placed at Connaught Place, New Delhi, with test data.

## Try it in five minutes

1. **Install and set up.** Open Ekta and tap **TURN ON SOS ALERTS**. Allow each permission, add an emergency contact, and choose a **cancel PIN** and a **duress PIN**.
2. **Press SOS** on one phone. A second phone nearby shows the full-screen alarm with the distance and her live location. Her contact gets the SMS.
3. **Walk around** with the SOS phone; the helper's distance and map follow her. Tap **SHOW ME WHERE**.
4. **Cancel with the duress PIN.** Her screen says "Alert cancelled"; the helper's phone switches to **DURESS**. Long-press SOS and enter the normal PIN to end it for real.
5. **Go offline.** Put the SOS phone in airplane mode with Bluetooth on, keep the phones within a few metres, and press SOS. The alert still arrives.
6. **During an SOS**, tap the **Called … nearby places** box to see who would be called and what they'd hear.
7. **Tap KNOW YOUR DESTINATION** on the home page, search a place and pick an hour to see its Safe Score.
8. **Change the language** in Settings → Language (the item always says LANGUAGE in English too).

**Tip:** for practice, set the emergency contact to your own second number. Keep Location (GPS) on and stand near a window for exact positions.

## Run locally

**Requirements:** Android Studio (JDK 17+), the Android SDK, and an Android 8.0+ phone or emulator.

```bash
git clone https://github.com/Snigdha-pal205/ekta.git
cd ekta
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Firebase:** put `google-services.json` for an Android app named `com.ekta.sos` in `app/` (not committed; it holds the project's API key). Create a Firestore database and publish the rules with `firebase deploy --only firestore:rules`.

**Free map keys** (TomTom is needed for Safe Score; both help find nearby places on SOS). Add them to `local.properties`, which is never committed:

```properties
TOMTOM_API_KEY=...      # free, no card: 2,500 requests a month (my.tomtom.com)
OLA_MAPS_API_KEY=...    # free: 100,000 calls a month
```

Without them, SOS still works and nearby places come from OpenStreetMap alone. Over a free limit, requests are refused, never charged.

## How it works

```mermaid
flowchart LR
    Her["Her phone<br/>(SOS pressed)"] -->|alert + live location| FS[("Firebase Firestore<br/>alerts")]
    Her -->|Bluetooth / Wi-Fi Direct| Mesh["Nearby Ekta phones<br/>(mesh, up to 8 hops)"]
    Mesh -->|first phone online uploads| FS
    FS -->|live listener, 500 m check| Helpers["Helpers' phones<br/>full-screen alarm"]
    FCM["Push (FCM)"] -.-> Helpers
    Her -->|SMS, own SIM| Contact["Emergency contact"]
    Mesh -->|SMS from gateway SIM| Contact
    Her -->|demo lookups| Maps["TomTom · Ola Maps · OpenStreetMap"]
```

| Part | What it does |
| --- | --- |
| `SosSession` | The SOS from press to "I'm safe": keys, live tracking, SMS, cancel and duress. |
| `LiveLocation` | Fresh GPS for the whole SOS (fused + raw GPS chip); sends a new point when she moves. |
| `AlertListenerService` | Always-on listener: alerts from the server, push and mesh; 500 m check; full-screen alarm. |
| `MeshManager`, `Gateway` | Offline Bluetooth / Wi-Fi Direct mesh; the first phone with internet uploads and texts her contact. |
| `AlertActivity`, `AlarmPlayer` | The helper's alert page, live distance, and the looping alarm. |
| `NearbyPlaces`, `TomTomPlaces`, `OlaPlaces`, `OsmPlaces` | Lookup of nearby places with phone numbers when SOS is pressed. |
| `NearbyAlert` | What those places are told. `REAL_MODE = false` (demo); set it to `true` to really text the 10 nearest mobile numbers. |
| `safescore/` | Safe Score page: TomTom search and map, five factor files, and `SafeScoreEngine` that combines them. |
| `firestore.rules` | Who may cancel, report duress or move a location (checked with secret-key hashes). |

### Rules that keep an SOS trustworthy

- **A cancel can't be faked.** On SOS her phone creates secret keys and sends only their SHA-256 hashes. A cancel, duress or location update must reveal the matching key; helpers' phones and the Firestore rules both check it.
- **Duress stays hidden.** Her phone stays silent and looks normal; only the duress key can mark it duress, and only the cancel key can end it.
- **Newest location wins.** An older position can never replace a newer one, on the server or on helpers' phones.
- **Helpers only act on confirmed changes**, and a cancelled alert is final.
- **Privacy:** listeners never upload their location; only the person who presses SOS shares where she is.

## Testing

| Command | What it checks |
| --- | --- |
| `python3 tests/e2e.py 1` | Full end-to-end round on an emulator: setup, SOS, SMS, live location, PINs and duress, distances, lock screen, offline mesh, gateway, push, restart. |
| `./gradlew :app:testDebugUnitTest` | Safe Score rules: verdict bands, weights, "no data" left out, late-night Delhi/Noida limit. |
| `python3 tests/safescore_test.py` | Safe Score on the emulator with real map data: places at different hours, labels, late-night limit, no crashes. |
| `python3 tests/rules_location.py` | Firestore rules for live-location updates. |
| `./test-alert.sh <lat> <lng>` | Sends a fake SOS from that spot; phones within 500 m should ring. |

## Limitations

- Android only; iPhones can't join the Bluetooth mesh. Mesh range is roughly 10–100 m per hop.
- Each phone must open Ekta once and finish setup; after that it keeps listening, even after a restart. On Xiaomi phones, allow pop-up windows, lock-screen display and Autostart (the app walks you through it).
- Indoors, GPS can be tens of metres off; the app says how exact it is and shows **VERY CLOSE** when two rough positions can't give an exact distance.
- Free map data doesn't have every shop or office; the lists show only places that have a phone number.
- There's no sign-in yet, so anyone with the Firebase config can read alerts; add Firebase Auth before a real launch. Revealed duress/location keys could be reused to post fake location points (not to cancel).
- `SEND_SMS` works for direct installs; the Play Store allows it only for certain app types.
- Safe Score's past-incident data is **sample data** for the demo; real use needs a real incident source.
