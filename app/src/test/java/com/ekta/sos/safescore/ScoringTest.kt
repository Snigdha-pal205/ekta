package com.ekta.sos.safescore

import com.ekta.sos.safescore.Scoring.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Safe Score rules that don't need a phone: bands, weights, the late-night limit. */
class ScoringTest {
    // Places used below
    private val cp = 28.6315 to 77.2167          // Connaught Place, Delhi
    private val sarojini = 28.5762 to 77.1960    // Sarojini Nagar, Delhi
    private val noida62 = 28.6266 to 77.3655     // Sector 62, Noida
    private val mumbai = 19.0760 to 72.8777      // outside Delhi / Noida

    @Test fun verdictBands() {
        assertEquals(Verdict.SAFE, Scoring.verdictOf(10.0))
        assertEquals(Verdict.SAFE, Scoring.verdictOf(8.0))
        assertEquals(Verdict.FAIRLY_SAFE, Scoring.verdictOf(7.9))
        assertEquals(Verdict.FAIRLY_SAFE, Scoring.verdictOf(7.0))
        assertEquals(Verdict.BE_CAREFUL, Scoring.verdictOf(6.9))
        assertEquals(Verdict.BE_CAREFUL, Scoring.verdictOf(6.5))
        assertEquals(Verdict.BE_CAREFUL, Scoring.verdictOf(5.0))
        assertEquals(Verdict.UNSAFE, Scoring.verdictOf(4.9))
        assertEquals(Verdict.UNSAFE, Scoring.verdictOf(0.0))
    }

    @Test fun factorColoursMatchBands() {
        assertEquals(Level.GREEN, Scoring.levelOf(7.0))
        assertEquals(Level.ORANGE, Scoring.levelOf(6.9))
        assertEquals(Level.ORANGE, Scoring.levelOf(5.0))
        assertEquals(Level.RED, Scoring.levelOf(4.9))
    }

    @Test fun lateNightIs11pmTo6am() {
        assertFalse(Scoring.isLateNight(22))
        assertTrue(Scoring.isLateNight(23))
        assertTrue(Scoring.isLateNight(0))
        assertTrue(Scoring.isLateNight(2))
        assertTrue(Scoring.isLateNight(5))
        assertFalse(Scoring.isLateNight(6))
        assertFalse(Scoring.isLateNight(14))
    }

    @Test fun delhiNoidaArea() {
        assertTrue(Scoring.inDelhiNoida(cp.first, cp.second))
        assertTrue(Scoring.inDelhiNoida(sarojini.first, sarojini.second))
        assertTrue(Scoring.inDelhiNoida(noida62.first, noida62.second))
        assertTrue(Scoring.inDelhiNoida(28.4744, 77.5040))   // Greater Noida
        assertFalse(Scoring.inDelhiNoida(mumbai.first, mumbai.second))
        assertFalse(Scoring.inDelhiNoida(26.9124, 75.7873))   // Jaipur
    }

    @Test fun nightCapLowersHighScoresInDelhiNoidaOnly() {
        // 11 PM in Connaught Place: 8.0 -> 6.5, marked as capped, reads Be careful
        val (s, capped) = Scoring.applyNightCap(8.0, 23, cp.first, cp.second)
        assertEquals(6.5, s, 0.0); assertTrue(capped)
        assertEquals(Verdict.BE_CAREFUL, Scoring.verdictOf(s))
        // Noida 2 AM, a perfect 10 still can't pass 6.5
        assertEquals(6.5, Scoring.applyNightCap(10.0, 2, noida62.first, noida62.second).first, 0.0)
        // Already low: never raised, not marked
        assertEquals(3.1 to false, Scoring.applyNightCap(3.1, 2, sarojini.first, sarojini.second))
        assertEquals(6.5 to false, Scoring.applyNightCap(6.5, 23, cp.first, cp.second))
        // 10 PM: no limit yet
        assertEquals(8.0 to false, Scoring.applyNightCap(8.0, 22, cp.first, cp.second))
        // 6 AM: limit over
        assertEquals(8.0 to false, Scoring.applyNightCap(8.0, 6, cp.first, cp.second))
        // Outside Delhi / Noida: no limit
        assertEquals(9.0 to false, Scoring.applyNightCap(9.0, 23, mumbai.first, mumbai.second))
    }

    @Test fun weightedAverage() {
        // All five factors: incidents .25, people .30, transport .20, police .15, community .10
        assertEquals(10.0, Scoring.combine(listOf(10.0, 10.0, 10.0, 10.0, 10.0)), 0.0)
        assertEquals(4.9, Scoring.combine(listOf(10.0, 1.0, 1.0, 10.0, 4.0)), 0.0) // (2.5+.3+.2+1.5+.4) / 1.0
    }

    @Test fun noDataIsLeftOutNotCountedAsSafe() {
        // Sarojini Nagar 2 AM: no incident data, nothing open, no transport, police 147 m, no ratings
        val s = Scoring.combine(listOf(null, 1.0, 1.0, 10.0, null))
        assertEquals(3.1, s, 0.0)
        assertEquals(Verdict.UNSAFE, Scoring.verdictOf(s))
        // If "no data" were wrongly counted as 10, it would come out much higher:
        assertTrue(Scoring.combine(listOf(10.0, 1.0, 1.0, 10.0, null)) > s + 1.5)
    }

    @Test fun peopleAroundCountsMost() {
        // Same place, only "people around" differs: busy (10) vs empty (1)
        val busy = Scoring.combine(listOf(8.0, 10.0, 7.0, 8.0, null))
        val empty = Scoring.combine(listOf(8.0, 1.0, 7.0, 8.0, null))
        assertTrue(busy - empty >= 2.5)
    }

    @Test fun hourBands() {
        assertEquals("night", Scoring.band(2))
        assertEquals("morning", Scoring.band(6))
        assertEquals("afternoon", Scoring.band(14))
        assertEquals("evening", Scoring.band(21))
        assertEquals("night", Scoring.band(23))
    }
}
