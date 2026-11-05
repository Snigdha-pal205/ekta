# Ekta SOS

One page, one button. Pressing **SOS**:

| What happens | Real or demo |
|---|---|
| Every phone with Ekta **within 500 m** gets a **full-screen alert** that buzzes and rings, even on the lock screen | **Real** |
| Your **emergency contact** gets an SMS with a Google Maps link to your location, then a fresh one **every 2 min**, until you tap **I'M SAFE** (which sends "I'm safe") | **Real** (normal SMS charges) |
| **112** is called | **Demo**: an on-screen call card, no call is placed |
| SMS to the nearest **women's committee** | **Demo**: the message is shown, not sent |

## No network? It still works
1. She presses SOS with no internet and no SIM signal.
2. Her phone sends the alert over **Bluetooth / BLE / Wi-Fi Direct** (Google Nearby Connections) to every Ekta phone in range.
3. Those phones show the full-screen alert (if within 500 m) and **pass it on** to Ekta phones near them, hop by hop (up to 8 hops, for 30 min).
4. The **first phone with internet becomes the gateway**. It uploads the alert, and Firestore allows only one upload per alert, so exactly one phone does this. That phone then **texts her emergency contact from its own SIM** and sends a "delivered" note back through the mesh.
5. Online Ekta phones within 500 m of her get the alert through Firestore, as usual. 112 and the women's committee stay **demo**.
6. Meanwhile her phone keeps retrying. The SMS is queued and sent the moment there's signal (skipped if a gateway already texted her contact). The upload goes through when internet returns, and the 112 demo switches to "calling" once there's signal.

Range is roughly 10–100 m per hop, so it needs Ekta phones physically nearby. Android only; iPhones can't join the mesh.

## Setup
1. Get `google-services.json` for the Firebase project `ekta-sos-3ll94` from the project owner, or create your own Firebase project with an Android app named `com.ekta.sos`.
   Put the file in `app/`. It's not in the repo because it holds the project's API key.
2. If you use your own project: create a Firestore database and publish `firestore.rules` with
   `firebase deploy --only firestore:rules`.
3. Build with `./gradlew :app:assembleDebug`. The APK ends up in `app/build/outputs/apk/debug/`.

On first launch the app walks the user through: location (+ "Allow all the time"), notifications, SMS, nearby devices (Bluetooth), battery exemption, full-screen alerts, display over other apps, turning Bluetooth on, and choosing an emergency contact (plus your own name for the SMS).

## How it works
- **Sending:** the phone gets a GPS fix and writes `{lat, lng, cell, senderId, createdAt}` to the Firestore `alerts` collection.
- **Receiving:** a foreground service ("Ekta is listening") keeps track of where the phone is.
  It listens to alerts in its own ~1 km grid cell and the 8 cells around it. Then it checks the exact distance on the phone.
  If the sender is ≤ 500 m away, it opens the full-screen alert page. The page uses a full-screen intent, plus a direct launch when "display over other apps" is allowed.
- **Privacy:** listeners never upload their location. Only a person who presses SOS shares where they are.
- **SMS:** sent with Android's `SmsManager` from the phone's own SIM. No server is involved.

## Testing
- `./test-alert.sh <lat> <lng>` sends a fake SOS from that spot. Phones within 500 m should show the alert.
- `python3 tests/e2e.py 3` runs the full end-to-end test on an emulator, in 3 rounds.

## Limitations
- Each phone must open the app once and finish setup. After that it keeps listening, even after a restart.
- Alerts older than 10 minutes are ignored.
- Live-location SMS updates stop after 30 minutes.
- `SEND_SMS` is fine for direct APK installs; the Play Store only allows it for certain app types.
- Without sign-in, anyone with your Firebase config can read alerts. Add Firebase Auth before a real launch.
