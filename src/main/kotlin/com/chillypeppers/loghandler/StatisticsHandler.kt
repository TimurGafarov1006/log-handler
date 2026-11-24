package com.chillypeppers.loghandler

/** Supports both SequentialAnalyzer and concurrent calls from LogEngine. */
class StatisticsHandler : LogHandler {
    private val statistics = LogStatistics()

    override fun handle(entries: List<LogEntry>) {
        // Aggregate outside the lock; only merging the batch needs shared-state access.
        val local = LogStatistics()
        entries.forEach(local::add)
        val batch = local.snapshot()
        synchronized(statistics) {
            statistics.merge(batch)
        }
    }

    fun snapshot(): StatisticsSnapshot = synchronized(statistics) {
        statistics.snapshot()
    }
}
