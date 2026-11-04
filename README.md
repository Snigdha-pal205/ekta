# Ekta SOS

One page, one button. Pressing **SOS**:

| What happens | Real or demo |
|---|---|
| Every phone with Ekta **within 500 m** gets a **full-screen alert** that buzzes and rings, even on the lock screen | **Real** |
| Your **emergency contact** gets an SMS with a Google Maps link to your location, then a fresh one **every 2 min**, until you tap **I'M SAFE** (which sends "I'm safe") | **Real** (normal SMS charges) |
| **112** is called | **Demo**: an on-screen call card, no call is placed |
| SMS to the nearest **women's committee** | **Demo**: the message is shown, not sent |

## Setup
1. Get `google-services.json` for the Firebase project `ekta-sos-3ll94` from the project owner, or create your own Firebase project with an Android app named `com.ekta.sos`.
   Put the file in `app/`. It's not in the repo because it holds the project's API key.
2. If you use your own project: create a Firestore database and publish `firestore.rules` with
   `firebase deploy --only firestore:rules`.
3. Build with `./gradlew :app:assembleDebug`. The APK ends up in `app/build/outputs/apk/debug/`.

On first launch the app walks the user through: location (+ "Allow all the time"), notifications, SMS, battery exemption, full-screen alerts, display over other apps, and choosing an emergency contact.

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
