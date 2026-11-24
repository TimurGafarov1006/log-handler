package com.chillypeppers.loghandler

import java.math.BigInteger
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Collections

/** Mutable, thread-confined accumulator. Merge snapshots to combine independent batches. */
class LogStatistics {
    private var requests = 0L
    private var duration = BigInteger.ZERO
    private var maximum = 0L
    private val statuses = mutableMapOf<Int, Long>()
    private val urls = mutableMapOf<String, Long>()
    private val ips = mutableMapOf<String, Long>()
    private val minutes = mutableMapOf<LocalDateTime, MinuteStatistics>()

    fun add(entry: LogEntry) {
        requests++
        duration += entry.responseTime.toBigInteger()
        maximum = maxOf(maximum, entry.responseTime)
        statuses.increment(entry.status, 1)
        urls.increment(entry.url, 1)
        ips.increment(entry.ip, 1)
        val minute = entry.timestamp.truncatedTo(ChronoUnit.MINUTES)
        val current = minutes[minute] ?: MinuteStatistics()
        minutes[minute] = MinuteStatistics(
            current.requestCount + 1,
            current.serverErrorCount + if (entry.status in 500..599) 1 else 0,
            current.totalResponseTime + entry.responseTime.toBigInteger(),
            maxOf(current.maxResponseTime, entry.responseTime)
        )
    }

    fun merge(other: StatisticsSnapshot) {
        requests += other.requestCount
        duration += other.totalResponseTime
        maximum = maxOf(maximum, other.maxResponseTime)
        other.statusCounts.forEach { (key, value) -> statuses.increment(key, value) }
        other.urlCounts.forEach { (key, value) -> urls.increment(key, value) }
        other.ipCounts.forEach { (key, value) -> ips.increment(key, value) }
        other.minutes.forEach { (minute, value) ->
            val current = minutes[minute] ?: MinuteStatistics()
            minutes[minute] = MinuteStatistics(
                current.requestCount + value.requestCount,
                current.serverErrorCount + value.serverErrorCount,
                current.totalResponseTime + value.totalResponseTime,
                maxOf(current.maxResponseTime, value.maxResponseTime)
            )
        }
    }

    /** Independent snapshot: subsequent additions or merges cannot change its maps. */
    fun snapshot(): StatisticsSnapshot = StatisticsSnapshot(
        requests, duration, maximum,
        immutableCopy(statuses), immutableCopy(urls), immutableCopy(ips),
        immutableCopy(minutes.toSortedMap())
    )

    private fun <K> MutableMap<K, Long>.increment(key: K, amount: Long) {
        this[key] = getOrDefault(key, 0L) + amount
    }

    private fun <K, V> immutableCopy(values: Map<K, V>): Map<K, V> =
        Collections.unmodifiableMap(LinkedHashMap(values))
}

data class MinuteStatistics(
    val requestCount: Long = 0,
    val serverErrorCount: Long = 0,
    val totalResponseTime: BigInteger = BigInteger.ZERO,
    val maxResponseTime: Long = 0
) {
    val averageResponseTime: Double
        get() = if (requestCount == 0L) 0.0 else totalResponseTime.toDouble() / requestCount
}

data class RequestFrequency(val value: String, val count: Long)

data class StatisticsSnapshot(
    val requestCount: Long,
    val totalResponseTime: BigInteger,
    val maxResponseTime: Long,
    val statusCounts: Map<Int, Long>,
    val urlCounts: Map<String, Long>,
    val ipCounts: Map<String, Long>,
    val minutes: Map<LocalDateTime, MinuteStatistics>
) {
    val averageResponseTime: Double
        get() = if (requestCount == 0L) 0.0 else totalResponseTime.toDouble() / requestCount

    fun topUrls(limit: Int = 10): List<RequestFrequency> = top(urlCounts, limit)

    fun topIps(limit: Int = 10): List<RequestFrequency> = top(ipCounts, limit)

    /** Highest request count first; ties are resolved by the earliest minute. */
    fun peakMinutes(limit: Int = 10): List<Pair<LocalDateTime, MinuteStatistics>> {
        require(limit >= 0) { "Limit must be nonnegative" }
        return minutes.entries.sortedWith(
            compareByDescending<Map.Entry<LocalDateTime, MinuteStatistics>> { it.value.requestCount }
                .thenBy { it.key }
        ).take(limit).map { it.key to it.value }
    }

    private fun top(counts: Map<String, Long>, limit: Int): List<RequestFrequency> {
        require(limit >= 0) { "Limit must be nonnegative" }
        return counts.entries.sortedWith(
            compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }
        ).take(limit).map { RequestFrequency(it.key, it.value) }
    }
}
