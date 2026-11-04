# Ekta SOS

One screen, one button. Pressing **SOS** alerts every phone with the app installed within 200 m.

## Setup
1. Get `google-services.json` for the Firebase project `ekta-sos-3ll94` from the project owner, or create your own Firebase project with an Android app named `com.ekta.sos`.
   Put the file in `app/`. It's not in the repo because it holds the project's API key.
2. If you use your own project: create a Firestore database and publish `firestore.rules` with
   `firebase deploy --only firestore:rules`.
3. Build with `./gradlew :app:assembleDebug`. The APK ends up in `app/build/outputs/apk/debug/`.

## Testing
- `./test-alert.sh <lat> <lng>` sends a fake SOS from that spot. Phones within 200 m should ring.
- `python3 tests/e2e.py 3` runs the full end-to-end test on an emulator, in 3 rounds.

## How it works
- **Sending:** the phone gets a GPS fix and writes `{lat, lng, cell, senderId, createdAt}` to the Firestore `alerts` collection.
- **Receiving:** after you grant location access, a foreground service ("Ekta is listening") keeps track of where the phone is.
  It listens to alerts in its own ~1 km grid cell and the 8 cells around it. Then it checks the exact distance on the phone
  and shows an alarm-style notification if the sender is ≤ 200 m away. Tapping the notification opens the location in Maps.
- Listeners never upload their location. Only a person who presses SOS shares where they are.

## Limitations
- Each phone must open the app once and allow the permissions. After that it keeps listening, even after a restart, as long as "Allow all the time" location access was granted.
- Alerts older than 10 minutes are ignored.
- Without sign-in, anyone with your Firebase config can read alerts. Add Firebase Auth before a real launch.
