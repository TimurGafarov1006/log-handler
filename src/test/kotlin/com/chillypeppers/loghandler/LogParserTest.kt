package com.chillypeppers.loghandler

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LogParserTest {
    @Test
    fun `parses actual input format`() {
        assertEquals(
            LogEntry(LocalDateTime.of(2026, 11, 1, 0, 0), "192.29.12.190", "GET", "/api/orders", 200, 28),
            LogParser.parse("2026-11-01 00:00:00 192.29.12.190 GET /api/orders 200 28")
        )
    }

    @Test
    fun `accepts whitespace leap day and zero response time`() {
        val entry = LogParser.parse("  2024-02-29\t23:59:59  ::1 PATCH /api/users?id=1 599 0  ")
        assertEquals(LocalDateTime.of(2024, 2, 29, 23, 59, 59), entry.timestamp)
        assertEquals("::1", entry.ip)
        assertEquals("PATCH", entry.method)
        assertEquals("/api/users?id=1", entry.url)
        assertEquals(599, entry.status)
        assertEquals(0L, entry.responseTime)
    }

    @Test
    fun `accepts maximum response time and minimum status`() {
        val entry = LogParser.parse("2026-11-01 00:00:00 ::1 GET / 100 ${Long.MAX_VALUE}")
        assertEquals(100, entry.status)
        assertEquals(Long.MAX_VALUE, entry.responseTime)
    }

    @Test
    fun `rejects missing and extra fields`() {
        val lines = listOf(
            "", "   ", "2026-11-01",
            "2026-11-01 00:00:00 ::1 GET / 200",
            "2026-11-01 00:00:00 ::1 GET / 200 1 extra"
        )
        for (line in lines) {
            assertFailsWith<IllegalArgumentException>(line) { LogParser.parse(line) }
        }
    }

    @Test
    fun `rejects invalid timestamps`() {
        val timestamps = listOf(
            "2026-02-29 00:00:00", "2026-11-31 00:00:00", "2026-13-01 00:00:00",
            "2026-11-01 24:00:00", "2026-11-01 00:60:00", "2026-11-01 00:00:60",
            "2026-11-01 00:00:00Z"
        )
        for (timestamp in timestamps) {
            assertFailsWith<IllegalArgumentException>(timestamp) {
                LogParser.parse("$timestamp ::1 GET / 200 1")
            }
        }
    }

    @Test
    fun `rejects invalid statuses and response times`() {
        for (status in listOf("99", "600", "OK", "2147483648")) {
            assertFailsWith<IllegalArgumentException>(status) {
                LogParser.parse("2026-11-01 00:00:00 ::1 GET / $status 1")
            }
        }
        for (duration in listOf("-1", "1.5", "slow", "9223372036854775808")) {
            assertFailsWith<IllegalArgumentException>(duration) {
                LogParser.parse("2026-11-01 00:00:00 ::1 GET / 200 $duration")
            }
        }
    }
}
