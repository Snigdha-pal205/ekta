package com.ekta.sos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only (tests): point the nearby-places lookup at a local test server
 * and set a test API key, so the whole flow can be checked without calling Google.
 *   --es base_url http://10.0.2.2:8765   ("" = back to Google)
 *   --es key test-key                     ("" = no pasted key)
 *   --es osm_urls http://10.0.2.2:8765/a/api/interpreter,...   ("" = real OpenStreetMap servers)
 */
class DebugPlacesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.hasExtra("base_url")) NearbyPlaces.setTestBaseUrl(context, intent.getStringExtra("base_url"))
        if (intent.hasExtra("key")) NearbyPlaces.saveApiKey(context, intent.getStringExtra("key") ?: "")
        if (intent.hasExtra("osm_urls")) NearbyPlaces.setTestOsmUrls(context, intent.getStringExtra("osm_urls"))
        if (intent.hasExtra("ola_url")) NearbyPlaces.setTestOlaUrl(context, intent.getStringExtra("ola_url"))
        if (intent.hasExtra("tomtom_url")) NearbyPlaces.setTestTomTomUrl(context, intent.getStringExtra("tomtom_url"))
        if (intent.hasExtra("women_tomtom_url")) WomenHelp.setTestTomTomUrl(context, intent.getStringExtra("women_tomtom_url"))
    }
}
