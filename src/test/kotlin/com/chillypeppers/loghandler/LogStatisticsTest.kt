package com.chillypeppers.loghandler

import java.math.BigInteger
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LogStatisticsTest {
    private val entries = listOf(
        "2026-11-01 00:01:59 z GET /b 599 50",
        "2026-11-01 00:00:59 a GET /a 200 10",
        "2026-11-01 00:01:00 a GET /a 500 30",
        "2026-11-01 00:00:00 z GET /b 404 0",
        "2026-11-02 00:00:00 x POST /c 499 10"
    ).map(LogParser::parse)

    @Test
    fun `counts requests distributions durations and calendar minute boundaries`() {
        val result = collect(entries)
        assertEquals(5L, result.requestCount)
        assertEquals(BigInteger.valueOf(100), result.totalResponseTime)
        assertEquals(20.0, result.averageResponseTime)
        assertEquals(50L, result.maxResponseTime)
        assertEquals(mapOf(200 to 1L, 404 to 1L, 499 to 1L, 500 to 1L, 599 to 1L), result.statusCounts)
        assertEquals(mapOf("/a" to 2L, "/b" to 2L, "/c" to 1L), result.urlCounts)
        assertEquals(mapOf("a" to 2L, "z" to 2L, "x" to 1L), result.ipCounts)
        assertEquals(mapOf(
            minute("2026-11-01T00:00") to MinuteStatistics(2, 0, BigInteger.TEN, 10),
            minute("2026-11-01T00:01") to MinuteStatistics(2, 2, BigInteger.valueOf(80), 50),
            minute("2026-11-02T00:00") to MinuteStatistics(1, 0, BigInteger.TEN, 10)
        ), result.minutes)
        assertEquals(40.0, result.minutes.getValue(minute("2026-11-01T00:01")).averageResponseTime)
    }

    @Test
    fun `top results and peaks have deterministic ties and validate limits`() {
        val result = collect(entries)
        assertEquals(listOf(RequestFrequency("/a", 2), RequestFrequency("/b", 2)), result.topUrls(2))
        assertEquals(listOf(RequestFrequency("a", 2), RequestFrequency("z", 2)), result.topIps(2))
        assertEquals(listOf(minute("2026-11-01T00:00"), minute("2026-11-01T00:01")),
            result.peakMinutes(2).map { it.first })
        assertEquals(3, result.topUrls(100).size)
        assertEquals(emptyList(), result.topUrls(0))
        assertEquals(emptyList(), result.topIps(0))
        assertEquals(emptyList(), result.peakMinutes(0))
        assertFailsWith<IllegalArgumentException> { result.topUrls(-1) }
        assertFailsWith<IllegalArgumentException> { result.topIps(-1) }
        assertFailsWith<IllegalArgumentException> { result.peakMinutes(-1) }
        assertEquals(result.topUrls(), collect(entries.reversed()).topUrls())
        assertEquals(result.peakMinutes(), collect(entries.reversed()).peakMinutes())
    }

    @Test
    fun `empty statistics have zero averages and no peaks`() {
        val result = collect(emptyList())
        assertEquals(0L, result.requestCount)
        assertEquals(BigInteger.ZERO, result.totalResponseTime)
        assertEquals(0L, result.maxResponseTime)
        assertEquals(0.0, result.averageResponseTime)
        assertEquals(0.0, MinuteStatistics().averageResponseTime)
        assertEquals(emptyMap(), result.statusCounts)
        assertEquals(emptyMap(), result.urlCounts)
        assertEquals(emptyMap(), result.ipCounts)
        assertEquals(emptyMap(), result.minutes)
        assertEquals(emptyList(), result.topUrls())
        assertEquals(emptyList(), result.topIps())
        assertEquals(emptyList(), result.peakMinutes())
    }

    @Test
    fun `merges are independent of partition size and completion order`() {
        val expected = collect(entries)
        for (size in 1..entries.size + 1) {
            val parts = entries.chunked(size).map(::collect)
            for (ordered in listOf(parts, parts.reversed())) {
                val combined = LogStatistics()
                combined.merge(collect(emptyList()))
                ordered.forEach(combined::merge)
                assertEquals(expected, combined.snapshot())
            }
        }
        val nested = LogStatistics()
        nested.merge(collect(entries.take(2)))
        val tail = LogStatistics()
        tail.merge(collect(entries.subList(2, 4)))
        tail.merge(collect(entries.takeLast(1)))
        nested.merge(tail.snapshot())
        assertEquals(expected, nested.snapshot())
    }

    @Test
    fun `snapshots remain independent and maps reject mutation`() {
        val statistics = LogStatistics()
        entries.forEach(statistics::add)
        val before = statistics.snapshot()
        statistics.add(entries.first())
        statistics.merge(before)
        assertEquals(5L, before.requestCount)
        assertEquals(collect(entries), before)
        assertEquals(11L, statistics.snapshot().requestCount)
        assertFailsWith<UnsupportedOperationException> { (before.statusCounts as MutableMap)[200] = 999 }
        assertFailsWith<UnsupportedOperationException> { (before.urlCounts as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (before.ipCounts as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (before.minutes as MutableMap).clear() }
    }

    @Test
    fun `sums of valid long durations do not overflow`() {
        val entry = LogParser.parse("2026-11-01 00:00:00 ::1 GET / 200 ${Long.MAX_VALUE}")
        val result = collect(listOf(entry, entry))
        val expected = Long.MAX_VALUE.toBigInteger() * BigInteger.TWO
        assertEquals(expected, result.totalResponseTime)
        assertEquals(expected, result.minutes.values.single().totalResponseTime)
        assertEquals(Long.MAX_VALUE.toDouble(), result.averageResponseTime)
        val merged = LogStatistics()
        repeat(2) { merged.merge(collect(listOf(entry))) }
        assertEquals(result, merged.snapshot())
    }

    private fun collect(values: List<LogEntry>): StatisticsSnapshot = LogStatistics().apply {
        values.forEach(::add)
    }.snapshot()

    private fun minute(value: String) = LocalDateTime.parse(value)
}
