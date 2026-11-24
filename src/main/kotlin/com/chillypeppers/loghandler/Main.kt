package com.chillypeppers.loghandler

import java.io.IOException
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(AnalysisCli.run(args, PrintWriter(System.out, true), PrintWriter(System.err, true)))
}

internal data class CliOptions(
    val input: Path,
    val mode: String = "sequential",
    val batchSize: Int = 10_000,
    val top: Int = 10,
    val output: Path? = null
)

private class UsageException(message: String) : IllegalArgumentException(message)

object AnalysisCli {
    private val help = """
        Usage: log-handler <file> [options]
          --mode sequential|parallel  Analysis mode (default: sequential)
          --batch-size N              Positive batch size (default: 10000)
          --top N                     Number of URLs, IPs and peaks (default: 10; 0 hides lists)
          --output FILE               Save the complete report as UTF-8 JSON
          --help                      Show this help
        Use -- before a file name starting with '-'. Quote paths containing spaces.
    """.trimIndent()

    fun run(args: Array<String>, out: PrintWriter, err: PrintWriter): Int {
        if ("--help" in args.takeWhile { it != "--" }) {
            out.println(help)
            return 0
        }
        val options = try {
            parse(args)
        } catch (error: IllegalArgumentException) {
            err.println("Error: ${error.message}")
            err.println(help)
            return 2
        }
        return try {
            options.output?.let { output ->
                require(output.toAbsolutePath().normalize() != options.input.toAbsolutePath().normalize() &&
                    !(Files.exists(output) && Files.isSameFile(options.input, output))) {
                    "Output must differ from the input log file"
                }
            }
            val handler = StatisticsHandler()
            val started = System.nanoTime()
            if (options.mode == "parallel") {
                LogEngine(listOf(handler), LogReader.open(options.input, options.batchSize))
                    .process(options.input.toString())
            } else {
                SequentialAnalyzer(listOf(handler), options.batchSize).analyze(options.input)
            }
            val result = handler.snapshot()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
            options.output?.let { JsonReport.write(it, options, result, elapsedMs) }
            printReport(out, options, result, elapsedMs)
            options.output?.let { out.println("JSON report: $it") }
            0
        } catch (error: IOException) {
            err.println("Error: ${error.message}")
            1
        } catch (error: IllegalArgumentException) {
            err.println("Error: ${error.message}")
            1
        } catch (error: SecurityException) {
            err.println("Error: ${error.message}")
            1
        }
    }

    internal fun parse(args: Array<String>): CliOptions {
        var input: Path? = null
        val values = mutableMapOf<String, String>()
        var positionalOnly = false
        var index = 0
        while (index < args.size) {
            val argument = args[index++]
            if (!positionalOnly && argument == "--") {
                positionalOnly = true
            } else if (!positionalOnly && argument.startsWith("-")) {
                if (argument !in setOf("--mode", "--batch-size", "--top", "--output")) {
                    throw UsageException("Unknown option: $argument")
                }
                if (values.containsKey(argument)) throw UsageException("Duplicate option: $argument")
                if (index == args.size || args[index].startsWith("--")) {
                    throw UsageException("Missing value for $argument")
                }
                values[argument] = args[index++]
            } else {
                if (input != null) throw UsageException("Expected one input file")
                input = Path.of(argument)
            }
        }
        val mode = values["--mode"] ?: "sequential"
        if (mode !in setOf("sequential", "parallel")) throw UsageException("Invalid mode: $mode")
        fun number(key: String, default: Int, minimum: Int): Int {
            val raw = values[key] ?: return default
            val value = raw.toIntOrNull()
            if (value == null || value < minimum) throw UsageException("$key must be an integer >= $minimum")
            return value
        }
        return CliOptions(
            input ?: throw UsageException("Input file is required"), mode,
            number("--batch-size", 10_000, 1), number("--top", 10, 0),
            values["--output"]?.let(Path::of)
        )
    }

    private fun printReport(out: PrintWriter, options: CliOptions, result: StatisticsSnapshot, elapsedMs: Double) {
        fun decimal(value: Double) = String.format(Locale.ROOT, "%.3f", value)
        out.println("File: ${options.input}")
        out.println("Mode: ${options.mode}; batch size: ${options.batchSize}")
        out.println("Requests: ${result.requestCount}")
        out.println("Average response time (ms): ${decimal(result.averageResponseTime)}")
        out.println("Maximum response time (ms): ${result.maxResponseTime}")
        out.println("Analysis time (ms): ${decimal(elapsedMs)}")
        out.println("HTTP statuses:")
        result.statusCounts.toSortedMap().forEach { (status, count) -> out.println("  $status: $count") }
        if (options.top == 0) return
        out.println("Top URLs:")
        result.topUrls(options.top).forEach { out.println("  ${it.value}: ${it.count}") }
        out.println("Top IPs:")
        result.topIps(options.top).forEach { out.println("  ${it.value}: ${it.count}") }
        out.println("Peak minutes:")
        result.peakMinutes(options.top).forEach { (minute, stats) ->
            out.println("  $minute: requests=${stats.requestCount}, 5xx=${stats.serverErrorCount}, " +
                "averageMs=${decimal(stats.averageResponseTime)}, maxMs=${stats.maxResponseTime}")
        }
    }
}
