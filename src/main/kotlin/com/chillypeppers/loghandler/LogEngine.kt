package com.chillypeppers.loghandler

import java.util.concurrent.Executors

class LogEngine(
    private val logHandlers: List<LogHandler>,
    private val logReader: LogReader,
) {
    fun process(filePath: String) {
        val executor = Executors.newCachedThreadPool()
        while (true) {
            val listOfEntries = logReader.readBatch(filePath)
            executor.submit {
                for (handler in logHandlers) {
                    handler.handle(listOfEntries)
                }
            }
        }
    }
}