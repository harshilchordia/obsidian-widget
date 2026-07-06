package com.obsidianwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ObsidianDateFormatTest {

    private val date = LocalDate.of(2026, 7, 6)
    private val dateTime = LocalDateTime.of(2026, 7, 6, 14, 30)

    @Test
    fun format_resolvesObsidianPathTokens() {
        assertEquals(
            "2026/07/2026-07-06",
            ObsidianDateFormat.format("YYYY/MM/yyyy-MM-dd", date)
        )
    }

    @Test
    fun format_preservesStaticFolderNamesWithTokenSubstrings() {
        assertEquals(
            "SUMMARY/2026-07-06",
            ObsidianDateFormat.format("SUMMARY/yyyy-MM-dd", date)
        )
    }

    @Test
    fun format_preservesNestedStaticFolders() {
        assertEquals(
            "Daily Notes/2026/07/2026-07-06",
            ObsidianDateFormat.format("Daily Notes/YYYY/MM/yyyy-MM-dd", date)
        )
    }

    @Test
    fun format_preservesStandaloneStaticWords() {
        // A run of letters is only replaced when the whole run decomposes
        // into tokens; "Journal" (which contains token letters) stays literal.
        assertEquals(
            "Journal 2026-07-06",
            ObsidianDateFormat.format("Journal YYYY-MM-DD", date)
        )
    }

    @Test
    fun format_resolvesContiguousTokens() {
        assertEquals("20260706", ObsidianDateFormat.format("YYYYMMDD", date))
    }

    @Test
    fun format_resolvesLegacyJavaSingleLetterTokens() {
        assertEquals("6.7.2026", ObsidianDateFormat.format("d.M.yyyy", date))
    }

    @Test
    fun format_resolvesLegacyJavaDayOfWeek() {
        val weekday = DateTimeFormatter.ofPattern("EEEE").format(date)
        assertEquals(
            "2026-07-06 $weekday",
            ObsidianDateFormat.format("yyyy-MM-dd EEEE", date)
        )
    }

    @Test
    fun format_treatsBracketedTextAsLiteral() {
        assertEquals(
            "Week 2026-07-06",
            ObsidianDateFormat.format("[Week] YYYY-MM-DD", date)
        )
    }

    @Test
    fun formatDateTime_replacesTimeTokens() {
        assertEquals("14:30", ObsidianDateFormat.formatDateTime("HH:mm", dateTime))
    }

    @Test
    fun formatDateTime_supportsTwelveHourFormats() {
        val meridiem = DateTimeFormatter.ofPattern("a").format(dateTime)
        assertEquals("02:30 $meridiem", ObsidianDateFormat.formatDateTime("hh:mm A", dateTime))
        assertEquals("2:30 $meridiem", ObsidianDateFormat.formatDateTime("h:mm a", dateTime))
    }

    @Test
    fun formatDateTime_returnsNullWhenNoTokensMatch() {
        assertNull(ObsidianDateFormat.formatDateTime("Note:", dateTime))
        assertNull(ObsidianDateFormat.formatDateTime("", dateTime))
    }
}
