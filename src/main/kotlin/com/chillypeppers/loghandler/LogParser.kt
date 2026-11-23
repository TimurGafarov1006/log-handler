package com.chillypeppers.loghandler

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

object LogParser {
    private val separator = Regex("\\s+")
    private val timestampFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
        .withResolverStyle(ResolverStyle.STRICT)

    fun parse(line: String): LogEntry {
        val parts = line.trim().split(separator)
        require(parts.size == 7) { "Expected 7 fields, got ${parts.size}" }
        val timestamp = try {
            LocalDateTime.parse("${parts[0]} ${parts[1]}", timestampFormat)
        } catch (error: DateTimeParseException) {
            throw IllegalArgumentException("Invalid timestamp", error)
        }
        val status = parts[5].toIntOrNull()
        require(status != null && status in 100..599) { "Invalid HTTP status" }
        val responseTime = parts[6].toLongOrNull()
        require(responseTime != null && responseTime >= 0) { "Invalid response time" }
        return LogEntry(timestamp, parts[2], parts[3], parts[4], status, responseTime)
    }
}
