package com.chillypeppers.loghandler

import com.google.gson.GsonBuilder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal object JsonReport {
    fun write(output: Path, options: CliOptions, statistics: StatisticsSnapshot, elapsedMs: Double) {
        val report = linkedMapOf(
            "input" to options.input.toString(),
            "mode" to options.mode,
            "batchSize" to options.batchSize,
            "elapsedMs" to elapsedMs,
            "statistics" to linkedMapOf(
                "requestCount" to statistics.requestCount,
                "totalResponseTime" to statistics.totalResponseTime,
                "averageResponseTime" to statistics.averageResponseTime,
                "maxResponseTime" to statistics.maxResponseTime,
                "statusCounts" to statistics.statusCounts.toSortedMap(),
                "urlCounts" to statistics.urlCounts,
                "ipCounts" to statistics.ipCounts,
                "minutes" to statistics.minutes.mapKeys { it.key.toString() }
                    .mapValues { minute(it.value) },
                "topUrls" to statistics.topUrls(options.top),
                "topIps" to statistics.topIps(options.top),
                "peakMinutes" to statistics.peakMinutes(options.top).map { (time, value) ->
                    linkedMapOf("minute" to time.toString(), "statistics" to minute(value))
                }
            )
        )
        // Write beside the destination, then replace it only after a complete export.
        val destination = output.toAbsolutePath().normalize()
        val temporary = Files.createTempFile(destination.parent, ".log-report-", ".tmp")
        try {
            Files.newBufferedWriter(temporary).use { writer ->
                GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(report, writer)
                writer.append('\n')
            }
            try {
                Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun minute(value: MinuteStatistics) = linkedMapOf(
        "requestCount" to value.requestCount,
        "serverErrorCount" to value.serverErrorCount,
        "totalResponseTime" to value.totalResponseTime,
        "averageResponseTime" to value.averageResponseTime,
        "maxResponseTime" to value.maxResponseTime
    )
}
