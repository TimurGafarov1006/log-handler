package com.chillypeppers.loghandler

import java.time.LocalDateTime

data class LogEntry(
    val timestamp: LocalDateTime,
    val ip: String,
    val method: String,
    val url: String,
    val status: Int,
    val responseTime: Long
)
