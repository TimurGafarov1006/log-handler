package com.chillypeppers.loghandler

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.system.measureNanoTime

object BenchmarkRunner {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Usage: BenchmarkRunner input.log output-directory" }
        val input = Path.of(args[0])
        val output = Path.of(args[1])
        Files.createDirectories(output)
        Files.writeString(output.resolve("environment.txt"), """
            measuredAt=${java.time.OffsetDateTime.now()}
            java=${System.getProperty("java.runtime.version")}
            vm=${System.getProperty("java.vm.name")}
            os=${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}
            processors=${Runtime.getRuntime().availableProcessors()}
            maxHeapBytes=${Runtime.getRuntime().maxMemory()}
            input=${input.toAbsolutePath()}
            inputBytes=${Files.size(input)}
            warmups=2
            repeats=3
            inFlight=workers*2
            timing=read,parse,aggregate,merge,pool startup and shutdown
        """.trimIndent())
        Files.newBufferedWriter(output.resolve("runs.csv")).use { csv ->
            csv.appendLine("rows,batch,workers,repeat,milliseconds")
            for (rows in listOf(10_000, 100_000, 1_500_000)) {
                val sample = Files.createTempFile("log-benchmark-", ".log")
                try {
                    Files.newBufferedReader(input).use { reader ->
                        Files.newBufferedWriter(sample).use { writer ->
                            repeat(rows) {
                                val line = reader.readLine() ?: error("Input has fewer than $rows rows")
                                writer.appendLine(line)
                            }
                        }
                    }
                    for (batch in listOf(1_000, 10_000, 100_000)) {
                        val expected = AnalysisStatistics()
                        SequentialAnalyzer(listOf(expected), batch).analyze(sample)
                        for (workers in listOf(0, 1, 2, 4, 8, 16)) {
                            repeat(5) { iteration ->
                                lateinit var actual: AnalysisStatistics
                                val elapsed = measureNanoTime {
                                    actual = if (workers == 0) {
                                        AnalysisStatistics().also { SequentialAnalyzer(listOf(it), batch).analyze(sample) }
                                    } else {
                                        ParallelAnalyzer(workers, batch).analyze(sample)
                                    }
                                }
                                check(actual == expected) { "Statistics differ: rows=$rows batch=$batch workers=$workers" }
                                if (iteration >= 2) {
                                    csv.appendLine("$rows,$batch,$workers,${iteration - 1},${String.format(Locale.ROOT, "%.3f", elapsed / 1_000_000.0)}")
                                    csv.flush()
                                }
                            }
                            println("Finished rows=$rows batch=$batch workers=$workers")
                        }
                    }
                } finally {
                    Files.deleteIfExists(sample)
                }
            }
        }
    }
}
