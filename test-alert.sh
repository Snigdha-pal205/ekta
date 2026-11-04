#!/bin/bash
# Sends a fake SOS as if another phone pressed the button at LAT,LNG.
# Usage: ./test-alert.sh 28.6150 77.2090
# Tip: in Google Maps, long-press your spot to copy its coordinates, then add
# ~0.003 to the latitude (about 330 m away) so it counts as "nearby" (within 500 m).
set -e
LAT=$1; LNG=$2
if [ -z "$LAT" ] || [ -z "$LNG" ]; then echo "Usage: $0 <lat> <lng>"; exit 1; fi
CELL=$(python3 -c "import math;print(f'{math.floor($LAT/0.01)}_{math.floor($LNG/0.01)}')")
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST -H "Content-Type: application/json" \
  "https://firestore.googleapis.com/v1/projects/ekta-sos-3ll94/databases/(default)/documents/alerts" \
  -d "{\"fields\":{\"lat\":{\"doubleValue\":$LAT},\"lng\":{\"doubleValue\":$LNG},\"accuracy\":{\"doubleValue\":5},\"cell\":{\"stringValue\":\"$CELL\"},\"senderId\":{\"stringValue\":\"test-script\"},\"createdAt\":{\"timestampValue\":\"$NOW\"}}}")
[ "$CODE" = "200" ] && echo "Fake SOS sent at $LAT,$LNG. Phones within 500 m should ring now." || echo "Failed (HTTP $CODE)"
