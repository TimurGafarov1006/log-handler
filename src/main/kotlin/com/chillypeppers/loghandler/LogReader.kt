package com.chillypeppers.loghandler

import java.io.BufferedReader
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path

class LogReader(
    private val reader: BufferedReader,
    private val batchSize: Int = 10_000
) : Closeable {
    private var lineNumber = 0L

    init {
        require(batchSize > 0) { "Batch size must be positive" }
    }

    fun readBatch(): List<LogEntry> {
        val entries = ArrayList<LogEntry>()
        repeat(batchSize) {
            val line = reader.readLine() ?: return entries
            lineNumber++
            try {
                entries.add(LogParser.parse(line))
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid log at line $lineNumber: ${error.message}", error)
            }
        }
        return entries
    }

    override fun close() = reader.close()

    companion object {
        fun open(path: Path, batchSize: Int = 10_000): LogReader {
            require(batchSize > 0) { "Batch size must be positive" }
            return LogReader(Files.newBufferedReader(path), batchSize)
        }
    }
}
