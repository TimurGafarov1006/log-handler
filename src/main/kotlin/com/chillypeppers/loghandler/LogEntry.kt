package com.chillypeppers.loghandler

import java.time.OffsetDateTime

data class LogEntry(
    val timestamp: OffsetDateTime,
    val ip: String,
    val method: String,
    val url: String,
    val status: Int,
    val responseTime: Long
)