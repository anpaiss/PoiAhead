package com.anpaiss.poiahead

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Locale

/** The CSV is what every phase-2 decision rests on, so it must survive the device it is written on. */
class SampleLogFormatTest {

    private fun row(alertId: String? = null, reason: String? = null) = SampleLog.Row(
        tMs = 1_700_000_000_000L, rideState = "Recording", posSrc = "gps",
        lat = 45.12345678, lng = 9.87654321,
        routePosKarooM = 1234.56, onRoute = 1.0, rejoinActive = false, routeLenM = 42000.0,
        nPois = 17, nRoutePois = 2,
        nearAirId = "123", nearAirM = 250.4, nearAirDetourM = 12.3,
        alertId = alertId, alertReason = reason,
    )

    @Test
    fun `column count always matches the header`() {
        val n = SampleLog.COLUMNS.split(",").size
        assertEquals(n, SampleLog.toCsv(row()).split(",").size)
        assertEquals(n, SampleLog.toCsv(row("123", "fired")).split(",").size)
    }

    @Test
    fun `decimals use a dot regardless of the device locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ITALY)
            val cells = SampleLog.toCsv(row()).split(",")
            assertEquals(SampleLog.COLUMNS.split(",").size, cells.size)
            assertEquals("45.12346", cells[3])
            assertEquals("1234.6", cells[5])
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `free text can never inject a separator`() {
        val csv = SampleLog.toCsv(row("42", "detour 120m > 100m, rearm"))
        assertEquals(SampleLog.COLUMNS.split(",").size, csv.split(",").size)
        assertFalse(csv.contains("\n"))
    }

    @Test
    fun `phase 2 columns are present and empty`() {
        val header = SampleLog.COLUMNS.split(",")
        val cells = SampleLog.toCsv(row()).split(",")
        for (c in listOf("near_ahead_id", "near_ahead_along_m", "shadow_alert_id", "shadow_reason", "eta_next_s")) {
            assertEquals(c, "", cells[header.indexOf(c)])
        }
    }
}
