package com.chillypeppers.loghandler

import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

class ParallelAnalyzer(
    private val workers: Int = Runtime.getRuntime().availableProcessors(),
    private val batchSize: Int = 10_000
) {
    init {
        require(workers > 0)
        require(batchSize > 0)
    }

    fun analyze(path: Path): AnalysisStatistics {
        val result = AnalysisStatistics()
        Executors.newFixedThreadPool(workers).use { executor ->
            LogReader.open(path, batchSize).use { reader ->
                val pending = ArrayDeque<Future<AnalysisStatistics>>()
                while (true) {
                    val entries = reader.readBatch()
                    if (entries.isEmpty()) break
                    pending.addLast(executor.submit<AnalysisStatistics> {
                        AnalysisStatistics().apply { handle(entries) }
                    })
                    if (pending.size >= workers) result.merge(await(pending.removeFirst()))
                }
                while (pending.isNotEmpty()) result.merge(await(pending.removeFirst()))
            }
        }
        return result
    }

    private fun await(task: Future<AnalysisStatistics>): AnalysisStatistics = try {
        task.get()
    } catch (error: ExecutionException) {
        throw error.cause ?: error
    }
}
