package com.chillypeppers.loghandler

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

data class MinuteStatistics(
    var requests: Long = 0,
    var errors: Long = 0,
    var totalTime: Long = 0,
    var maxTime: Long = 0
) {
    val averageTime: Double get() = if (requests == 0L) 0.0 else totalTime.toDouble() / requests

    fun merge(other: MinuteStatistics) {
        requests += other.requests
        errors += other.errors
        totalTime += other.totalTime
        maxTime = maxOf(maxTime, other.maxTime)
    }
}

data class AnalysisStatistics(
    var requests: Long = 0,
    var totalTime: Long = 0,
    val statuses: MutableMap<Int, Long> = mutableMapOf(),
    val urls: MutableMap<String, Long> = mutableMapOf(),
    val ips: MutableMap<String, Long> = mutableMapOf(),
    val minutes: MutableMap<LocalDateTime, MinuteStatistics> = mutableMapOf()
) : LogHandler {
    val averageTime: Double get() = if (requests == 0L) 0.0 else totalTime.toDouble() / requests

    override fun handle(entries: List<LogEntry>) {
        for (entry in entries) {
            requests++
            totalTime += entry.responseTime
            statuses.merge(entry.status, 1L, Long::plus)
            urls.merge(entry.url, 1L, Long::plus)
            ips.merge(entry.ip, 1L, Long::plus)
            minutes.getOrPut(entry.timestamp.truncatedTo(ChronoUnit.MINUTES)) { MinuteStatistics() }.apply {
                requests++
                if (entry.status >= 500) errors++
                totalTime += entry.responseTime
                maxTime = maxOf(maxTime, entry.responseTime)
            }
        }
    }

    fun merge(other: AnalysisStatistics) {
        requests += other.requests
        totalTime += other.totalTime
        other.statuses.forEach { (key, value) -> statuses.merge(key, value, Long::plus) }
        other.urls.forEach { (key, value) -> urls.merge(key, value, Long::plus) }
        other.ips.forEach { (key, value) -> ips.merge(key, value, Long::plus) }
        other.minutes.forEach { (key, value) -> minutes.getOrPut(key) { MinuteStatistics() }.merge(value) }
    }
}
