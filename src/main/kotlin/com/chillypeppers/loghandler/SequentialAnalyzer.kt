package com.chillypeppers.loghandler

import java.nio.file.Path

class SequentialAnalyzer(
    private val logHandlers: List<LogHandler>,
    private val batchSize: Int = 10_000
) {
    init {
        require(batchSize > 0) { "Batch size must be positive" }
    }

    fun analyze(path: Path) {
        LogReader.open(path, batchSize).use { reader ->
            while (true) {
                val entries = reader.readBatch()
                if (entries.isEmpty()) break
                for (handler in logHandlers) {
                    handler.handle(entries)
                }
            }
        }
    }
}
