package com.chillypeppers.loghandler

import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

class LogEngine(
    private val logHandlers: List<LogHandler>,
    private val logReader: LogReader,
) {
    fun process(filePath: String) {
        val workers = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        Executors.newFixedThreadPool(workers).use { executor ->
            logReader.use { reader ->
                val pending = ArrayDeque<Future<*>>()
                while (true) {
                    val entries = reader.readBatch()
                    if (entries.isEmpty()) break
                    pending.addLast(executor.submit {
                        for (handler in logHandlers) {
                            handler.handle(entries)
                        }
                    })
                    if (pending.size >= workers) await(pending.removeFirst())
                }
                while (pending.isNotEmpty()) await(pending.removeFirst())
            }
        }
    }

    private fun await(task: Future<*>) {
        try {
            task.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }
}
