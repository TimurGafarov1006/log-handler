package com.chillypeppers.loghandler

import java.io.BufferedReader
import java.time.OffsetDateTime

class LogReader(
    private val reader: BufferedReader
) {
    fun readBatch(filePath: String): List<LogEntry> {
        var count = 0
        var listOfEntries = ArrayList<LogEntry>()
        reader.use {
            while (count++ < BATCH_SIZE) {
                val line = it.readLine() ?: break
                listOfEntries.add(convertToEntry(line))
            }
        }
        return listOfEntries
    }

    private fun convertToEntry(line: String): LogEntry {
        val parts = line.split(" ")
        return LogEntry(
            timestamp = OffsetDateTime.parse(parts[0]),
            ip = parts[1],
            method = parts[2],
            url = parts[3],
            status = parts[4].toInt(),
            responseTime = parts[5].toLong()
        )
    }

    private val BATCH_SIZE = 10000;

}