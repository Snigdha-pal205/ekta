package com.ekta.sos

import org.json.JSONArray
import org.json.JSONObject

/**
 * What travels phone-to-phone over Bluetooth / Wi-Fi Direct.
 *
 *  - SOS: someone pressed SOS. Carries her location and who to text, so the first phone
 *    with signal (the "gateway") can upload it and SMS her emergency contact.
 *  - ACK: a gateway got it onto the internet. Travels back so her phone can show it.
 */
data class MeshMessage(
    val type: String,          // TYPE_SOS or TYPE_ACK
    val id: String,            // the alert id (same id for the SOS and its ACKs)
    val sender: String = "",   // device id of the phone that pressed SOS
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val accuracy: Double = 0.0,
    val sentAt: Long = 0L,     // when SOS was pressed (ms)
    val hops: Int = 0,         // how many phones it has passed through
    val senderName: String = "",
    val contactName: String = "",
    val contactNumber: String = "",
    val gateway: String = "",  // ACK: device id of the gateway phone
    val sms: String = "",      // ACK: SMS_SENT / SMS_QUEUED / SMS_NONE
) {
    /** One key per distinct message, so each is passed on only once per phone. */
    val key get() = if (type == TYPE_ACK) "ack:$id:$sms" else "sos:$id"

    fun toJson(): JSONObject = JSONObject().apply {
        put("t", type); put("id", id); put("h", hops)
        if (type == TYPE_SOS) {
            put("s", sender); put("la", lat); put("lo", lng); put("ac", accuracy); put("ts", sentAt)
            put("n", senderName); put("cn", contactName); put("cp", contactNumber)
        } else {
            put("g", gateway); put("sms", sms)
        }
    }

    companion object {
        const val TYPE_SOS = "sos"
        const val TYPE_ACK = "ack"
        const val SMS_SENT = "sent"
        const val SMS_QUEUED = "queued"
        const val SMS_NONE = "none"

        fun fromJson(o: JSONObject) = MeshMessage(
            type = o.getString("t"),
            id = o.getString("id"),
            hops = o.optInt("h"),
            sender = o.optString("s"),
            lat = o.optDouble("la", 0.0),
            lng = o.optDouble("lo", 0.0),
            accuracy = o.optDouble("ac", 0.0),
            sentAt = o.optLong("ts"),
            senderName = o.optString("n"),
            contactName = o.optString("cn"),
            contactNumber = o.optString("cp"),
            gateway = o.optString("g"),
            sms = o.optString("sms"),
        )

        fun encode(list: Collection<MeshMessage>): ByteArray =
            JSONArray().apply { list.forEach { put(it.toJson()) } }.toString().toByteArray()

        fun decode(bytes: ByteArray): List<MeshMessage> = try {
            val arr = JSONArray(String(bytes))
            (0 until arr.length()).mapNotNull {
                try { fromJson(arr.getJSONObject(it)) } catch (_: Exception) { null }
            }.filter { it.type == TYPE_SOS || it.type == TYPE_ACK }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
