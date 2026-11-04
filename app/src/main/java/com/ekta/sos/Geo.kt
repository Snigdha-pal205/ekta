package com.ekta.sos

import kotlin.math.floor

/** Alerts reach every phone within this radius of the sender. */
const val ALERT_RADIUS_METERS = 200f

/** Alerts older than this are ignored (e.g. when a phone comes back online late). */
const val ALERT_MAX_AGE_MS = 10 * 60 * 1000L

/**
 * The world is split into a 0.01° grid (~1.1 km tall). An alert is tagged with its
 * cell, and each phone listens to its own cell plus the 8 surrounding ones, so any
 * alert within 200 m is always in one of the 9 cells it watches.
 */
private const val CELL_DEG = 0.01

fun cellOf(lat: Double, lng: Double): String =
    "${floor(lat / CELL_DEG).toLong()}_${floor(lng / CELL_DEG).toLong()}"

fun cellsAround(lat: Double, lng: Double): List<String> {
    val cy = floor(lat / CELL_DEG).toLong()
    val cx = floor(lng / CELL_DEG).toLong()
    return buildList {
        for (dy in -1L..1L) for (dx in -1L..1L) add("${cy + dy}_${cx + dx}")
    }
}
