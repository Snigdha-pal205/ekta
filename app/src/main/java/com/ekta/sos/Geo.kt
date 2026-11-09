package com.ekta.sos

import kotlin.math.floor

/** Alerts reach every phone within this radius of the sender. */
const val ALERT_RADIUS_METERS = 1000f

/** Alerts older than this are ignored (e.g. when a phone comes back online late). */
const val ALERT_MAX_AGE_MS = 10 * 60 * 1000L

/** Offline (mesh) alerts are passed on and uploaded for up to this long after SOS. */
const val RELAY_MAX_AGE_MS = 30 * 60 * 1000L

/**
 * The world is split into a 0.01° grid (~1.1 km tall, ~1 km wide in India). An alert is
 * tagged with its cell, and each phone listens to its own cell plus two rings around it
 * (5 x 5 = 25 cells, within Firestore's 30-value limit), so any alert within 1 km is
 * always in a cell it watches, even where a cell is a little under 1 km wide.
 */
private const val CELL_DEG = 0.01
private const val CELL_RINGS = 2L

fun cellOf(lat: Double, lng: Double): String =
    "${floor(lat / CELL_DEG).toLong()}_${floor(lng / CELL_DEG).toLong()}"

fun cellsAround(lat: Double, lng: Double): List<String> {
    val cy = floor(lat / CELL_DEG).toLong()
    val cx = floor(lng / CELL_DEG).toLong()
    return buildList {
        for (dy in -CELL_RINGS..CELL_RINGS) for (dx in -CELL_RINGS..CELL_RINGS) add("${cy + dy}_${cx + dx}")
    }
}
