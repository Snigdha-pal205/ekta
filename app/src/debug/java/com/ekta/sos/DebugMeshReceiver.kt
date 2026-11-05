package com.ekta.sos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray

/**
 * Debug builds only. Simulates a message arriving from a nearby phone over the mesh:
 *   adb shell am broadcast -n com.ekta.sos/.DebugMeshReceiver --es json '[{...}]'
 */
class DebugMeshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val json = intent.getStringExtra("json") ?: return
        MeshManager.injectForTest(JSONArray(json).toString().toByteArray())
    }
}
