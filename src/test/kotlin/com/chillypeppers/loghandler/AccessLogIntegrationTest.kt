package com.chillypeppers.loghandler

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class AccessLogIntegrationTest {
    @Test
    fun `analyzes complete supplied file without losing records`() {
        val path = Path.of("access_large.log")
        val expected = Summary()
        Files.newBufferedReader(path).useLines { lines ->
            lines.forEach { line ->
                val fields = line.split(' ')
                expected.add(
                    fields[2], fields[4], fields[5].toInt(), fields[6].toLong(),
                    "${fields[0]} ${fields[1].take(5)}"
                )
            }
        }
        val statistics = StatisticsHandler()
        SequentialAnalyzer(listOf(statistics), 7_001).analyze(path)
        val result = statistics.snapshot()
        assertEquals(1_500_000L, result.requestCount)
        assertEquals(expected.count, result.requestCount)
        assertEquals(expected.responseTime.toBigInteger(), result.totalResponseTime)
        assertEquals(expected.maxResponseTime, result.maxResponseTime)
        assertEquals(expected.statuses, result.statusCounts)
        assertEquals(expected.urls, result.urlCounts)
        assertEquals(expected.ips, result.ipCounts)
        assertEquals(expected.minutes.mapKeys { (key, _) ->
            java.time.LocalDateTime.parse(key.replace(' ', 'T'))
        }.mapValues { (_, value) ->
            MinuteStatistics(value.count, value.errors, value.responseTime.toBigInteger(), value.maxResponseTime)
        }, result.minutes)
        val parallel = StatisticsHandler()
        LogEngine(listOf(parallel), LogReader.open(path, 7_001)).process(path.toString())
        assertEquals(result, parallel.snapshot())
    }

    private data class Summary(
        var count: Long = 0,
        var responseTime: Long = 0,
        var maxResponseTime: Long = 0,
        val statuses: MutableMap<Int, Long> = mutableMapOf(),
        val urls: MutableMap<String, Long> = mutableMapOf(),
        val ips: MutableMap<String, Long> = mutableMapOf(),
        val minutes: MutableMap<String, Minute> = mutableMapOf()
    ) {
        fun add(ip: String, url: String, status: Int, duration: Long, minute: String) {
            count++
            responseTime += duration
            maxResponseTime = maxOf(maxResponseTime, duration)
            statuses[status] = statuses.getOrDefault(status, 0) + 1
            urls[url] = urls.getOrDefault(url, 0) + 1
            ips[ip] = ips.getOrDefault(ip, 0) + 1
            minutes.getOrPut(minute) { Minute() }.apply {
                count++
                responseTime += duration
                maxResponseTime = maxOf(maxResponseTime, duration)
                if (status >= 500) errors++
            }
        }
    }

    private data class Minute(
        var count: Long = 0,
        var errors: Long = 0,
        var responseTime: Long = 0,
        var maxResponseTime: Long = 0
    )
}
