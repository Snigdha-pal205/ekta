package com.ekta.sos

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * SMS that keep trying until they go through. With no signal Android reports
 * "no service"; we retry every 20 s, so the message leaves the moment signal returns.
 */
object SmsOutbox {
    private const val ACTION_SENT = "com.ekta.sos.SMS_OUTBOX_SENT"
    private const val RETRY_MS = 20_000L
    private const val GIVE_UP_MS = 60 * 60 * 1000L

    private class Item(
        val key: String,
        val number: String,
        val text: String,
        val onSent: () -> Unit,
        val created: Long = System.currentTimeMillis(),
        var inFlight: Boolean = false,
    )

    private val items = LinkedHashMap<String, Item>()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var ready = false

    /** When an SMS last went out (ms) — proof this phone had mobile signal then. */
    private val _lastSentAt = MutableStateFlow(0L)
    val lastSentAt: StateFlow<Long> = _lastSentAt

    fun canSend(context: Context) = ContextCompat.checkSelfPermission(
        context, Manifest.permission.SEND_SMS,
    ) == PackageManager.PERMISSION_GRANTED

    /** Queue [text] for [number]. A message with the same [key] replaces the queued one. */
    fun send(context: Context, key: String, number: String, text: String, onSent: () -> Unit = {}) {
        init(context)
        handler.post {
            items[key] = Item(key, number, text, onSent)
            trySend(items.getValue(key))
        }
    }

    fun cancel(key: String) = handler.post { items.remove(key) }

    fun isPending(key: String) = items.containsKey(key)

    private fun init(context: Context) {
        if (ready) return
        ready = true
        app = context.applicationContext
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val key = intent.getStringExtra("key") ?: return
                val item = items[key] ?: return
                item.inFlight = false
                if (resultCode == Activity.RESULT_OK) {
                    items.remove(key)
                    _lastSentAt.value = System.currentTimeMillis()
                    item.onSent()
                }
                // otherwise: no service / radio off → the retry loop picks it up
            }
        }, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        handler.postDelayed(retryLoop, RETRY_MS)
    }

    private val retryLoop = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            items.values.toList().forEach {
                if (now - it.created > GIVE_UP_MS) items.remove(it.key)
                else if (!it.inFlight) trySend(it)
            }
            handler.postDelayed(this, RETRY_MS)
        }
    }

    private fun trySend(item: Item) {
        if (!canSend(app)) return
        item.inFlight = true
        try {
            val sent = PendingIntent.getBroadcast(
                app, item.key.hashCode(),
                Intent(ACTION_SENT).setPackage(app.packageName).putExtra("key", item.key),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val sms = smsManager()
            val parts = split(sms, item.text)
            if (parts.size == 1) {
                sms.sendTextMessage(item.number, null, item.text, sent, null)
            } else {
                // Only the last part reports back, so success means the whole message went.
                val intents = ArrayList<PendingIntent?>(List(parts.size - 1) { null })
                intents.add(sent)
                sms.sendMultipartTextMessage(item.number, null, parts, intents, null)
            }
        } catch (e: Exception) {
            android.util.Log.w("EktaSms", "send failed (${item.text.length} chars)", e)
            item.inFlight = false
        }
        // If the radio never answers, unstick after a while so the loop retries.
        handler.postDelayed({ item.inFlight = false }, RETRY_MS * 3)
    }

    /**
     * SMS parts. Android's divideMessage throws SecurityException for non-English
     * (Unicode) text on some phones, so then split it ourselves: 67 characters per
     * part (the Unicode multipart limit), breaking at a space where possible.
     */
    private fun split(sms: SmsManager, text: String): ArrayList<String> = try {
        sms.divideMessage(text)
    } catch (e: Exception) {
        val gsm = text.all { it.code < 128 }
        val max = if (gsm) 153 else 67
        if (text.length <= (if (gsm) 160 else 70)) arrayListOf(text)
        else ArrayList<String>().apply {
            var rest = text
            while (rest.length > max) {
                var cut = rest.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
                if (Character.isHighSurrogate(rest[cut - 1])) cut-- // never split an emoji in half
                add(rest.substring(0, cut))
                rest = rest.substring(cut)
            }
            if (rest.isNotEmpty()) add(rest)
        }
    }

    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) app.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()
}
