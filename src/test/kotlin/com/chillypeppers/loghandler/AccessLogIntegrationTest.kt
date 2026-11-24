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
        val actual = Summary()
        val handler = object : LogHandler {
            override fun handle(entries: List<LogEntry>) {
                for (entry in entries) {
                    val minute = "${entry.timestamp.toLocalDate()} ${entry.timestamp.toLocalTime().toString().take(5)}"
                    actual.add(entry.ip, entry.url, entry.status, entry.responseTime, minute)
                }
            }
        }
        SequentialAnalyzer(listOf(handler), 7_001).analyze(path)
        assertEquals(1_500_000L, actual.count)
        assertEquals(expected, actual)
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
