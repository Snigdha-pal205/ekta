package com.ekta.sos

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Xiaomi / Redmi / POCO (MIUI, HyperOS) block an app from opening a page in the
 * background unless two extra Xiaomi-only permissions are on:
 *   "Display pop-up windows while running in the background" (op 10021)
 *   "Show on lock screen" (op 10020)
 * and "Autostart" (op 10008) to keep listening after a restart / update.
 * Without them a nearby SOS only shows as a notification, not the full alert page.
 */
object XiaomiPopups {
    private const val OP_BACKGROUND_START_ACTIVITY = 10021
    private const val OP_SHOW_WHEN_LOCKED = 10020
    private const val OP_AUTO_START = 10008

    fun isXiaomi(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
    }

    /** True if not a Xiaomi phone, or both pop-up permissions are on (or can't be read). */
    fun allowed(context: Context): Boolean {
        if (!isXiaomi()) return true
        return opAllowed(context, OP_BACKGROUND_START_ACTIVITY) != false &&
            opAllowed(context, OP_SHOW_WHEN_LOCKED) != false
    }

    /**
     * Xiaomi "Autostart": without it Ekta can't start listening again after the phone
     * restarts, after an app update, or after Xiaomi kills it to save battery.
     */
    fun autostartAllowed(context: Context): Boolean =
        !isXiaomi() || opAllowed(context, OP_AUTO_START) != false

    fun autostartIntents(context: Context): List<Intent> = listOf(
        Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
    )

    /** null = couldn't check on this phone. */
    private fun opAllowed(context: Context, op: Int): Boolean? = try {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val m = AppOpsManager::class.java.getMethod(
            "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java,
        )
        val mode = m.invoke(ops, op, android.os.Process.myUid(), context.packageName) as Int
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        null
    }

    /** Xiaomi's "Other permissions" page for this app, or app info as a fallback. */
    fun settingsIntents(context: Context): List<Intent> = listOf(
        Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", context.packageName),
        Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
            .putExtra("extra_pkgname", context.packageName),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
    )
}
