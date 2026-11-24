package com.chillypeppers.loghandler

import com.google.gson.JsonParser
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalysisCliTest {
    @TempDir
    lateinit var directory: Path

    private data class Run(val code: Int, val out: String, val err: String)

    private fun run(vararg args: String): Run {
        val out = StringWriter()
        val err = StringWriter()
        val code = AnalysisCli.run(arrayOf(*args), PrintWriter(out, true), PrintWriter(err, true))
        return Run(code, out.toString(), err.toString())
    }

    @Test
    fun `help succeeds without opening files and missing input fails with usage`() {
        val help = run("--help")
        assertEquals(0, help.code)
        assertTrue(help.out.contains("Usage:"))
        assertEquals("", help.err)
        assertEquals(2, run().code)
        assertTrue(run().err.contains("Input file is required"))
    }

    @Test
    fun `rejects invalid options values duplicates and multiple inputs`() {
        val invalid = listOf(
            listOf("--unknown"), listOf("--mode"), listOf("--mode", "fast"),
            listOf("--batch-size", "0"), listOf("--batch-size", "-1"),
            listOf("--batch-size", "2147483648"), listOf("--top", "abc"),
            listOf("--top", "-1"), listOf("--output"), listOf("--top", "--mode", "parallel"),
            listOf("--top", "1", "--top", "2"), listOf("second.log")
        )
        for (options in invalid) {
            val result = run("missing.log", *options.toTypedArray())
            assertEquals(2, result.code, options.toString())
            assertTrue(result.err.contains("Usage:"))
            assertEquals("", result.out)
        }
        assertEquals(Path.of("-access.log"), AnalysisCli.parse(arrayOf("--", "-access.log")).input)
    }

    @Test
    fun `both modes export identical complete statistics and print limited tops`() {
        val path = directory.resolve("access with spaces.log")
        Files.writeString(path, listOf(
            "2026-11-01 00:00:59 ::1 GET /a 200 10",
            "2026-11-01 00:01:00 ::2 GET /b 503 30",
            "2026-11-01 00:01:59 ::1 GET /a 500 20"
        ).joinToString("\n"))
        val reports = listOf("sequential", "parallel").map { mode ->
            val output = directory.resolve("$mode.json")
            val result = run(path.toString(), "--mode", mode, "--batch-size", "2", "--top", "1", "--output", output.toString())
            assertEquals(0, result.code, result.err)
            assertEquals("", result.err)
            assertTrue(result.out.contains("Requests: 3"))
            assertTrue(result.out.contains("Average response time (ms): 20.000"))
            assertTrue(result.out.contains("Maximum response time (ms): 30"))
            assertTrue(result.out.contains("Analysis time (ms):"))
            assertTrue(result.out.contains("5xx=2"))
            val json = JsonParser.parseString(Files.readString(output)).asJsonObject
            assertEquals(mode, json["mode"].asString)
            assertEquals(2, json["batchSize"].asInt)
            assertTrue(json["elapsedMs"].asDouble >= 0)
            json.getAsJsonObject("statistics")
        }
        assertEquals(reports[0], reports[1])
        val statistics = reports.first()
        assertEquals(3, statistics["requestCount"].asInt)
        assertEquals(60, statistics["totalResponseTime"].asInt)
        assertEquals(20.0, statistics["averageResponseTime"].asDouble)
        assertEquals(2, statistics.getAsJsonObject("urlCounts").size())
        assertEquals(1, statistics.getAsJsonArray("topUrls").size())
        assertEquals("/a", statistics.getAsJsonArray("topUrls")[0].asJsonObject["value"].asString)
        val minute = statistics.getAsJsonObject("minutes").getAsJsonObject("2026-11-01T00:01")
        assertEquals(2, minute["serverErrorCount"].asInt)
        assertEquals(25.0, minute["averageResponseTime"].asDouble)
    }

    @Test
    fun `json preserves unicode escaping and integer sums beyond long range`() {
        val input = directory.resolve("unicode.log")
        val url = "/путь\\test\"quote"
        Files.writeString(input, List(2) { "2026-11-01 00:00:00 ::1 GET $url 200 ${Long.MAX_VALUE}" }.joinToString("\n"))
        val output = directory.resolve("report.json")
        assertEquals(0, run(input.toString(), "--output", output.toString()).code)
        val json = JsonParser.parseString(Files.readString(output)).asJsonObject.getAsJsonObject("statistics")
        assertEquals(Long.MAX_VALUE.toBigInteger() * 2.toBigInteger(), json["totalResponseTime"].asBigInteger)
        assertEquals(2, json.getAsJsonObject("urlCounts")[url].asInt)
    }

    @Test
    fun `empty input and zero top produce empty lists and zero averages`() {
        val input = Files.writeString(directory.resolve("empty.log"), "")
        for (mode in listOf("sequential", "parallel")) {
            val output = directory.resolve("empty-$mode.json")
            val result = run(input.toString(), "--mode", mode, "--top", "0", "--output", output.toString())
            assertEquals(0, result.code, result.err)
            assertFalse(result.out.contains("Top URLs:"))
            val stats = JsonParser.parseString(Files.readString(output)).asJsonObject.getAsJsonObject("statistics")
            assertEquals(0, stats["requestCount"].asInt)
            assertEquals(0.0, stats["averageResponseTime"].asDouble)
            assertEquals(0, stats.getAsJsonArray("topUrls").size())
            assertEquals(0, stats.getAsJsonObject("minutes").size())
        }
    }

    @Test
    fun `input errors do not replace reports and missing output directories fail cleanly`() {
        val input = Files.writeString(directory.resolve("bad.log"), "invalid")
        val output = Files.writeString(directory.resolve("existing.json"), "previous report")
        for (mode in listOf("sequential", "parallel")) {
            val result = run(input.toString(), "--mode", mode, "--output", output.toString())
            assertEquals(1, result.code)
            assertTrue(result.err.contains("line 1"))
            assertEquals("", result.out)
            assertEquals("previous report", Files.readString(output))
        }
        assertEquals(1, run(directory.resolve("missing.log").toString()).code)
        Files.writeString(input, "")
        assertEquals(1, run(input.toString(), "--output", directory.resolve("missing/report.json").toString()).code)
        assertEquals(1, run(input.toString(), "--output", directory.toString()).code)
        Files.list(directory).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().startsWith(".log-report-") }) }
    }

    @Test
    fun `export replaces existing reports but cannot overwrite input or its hard link`() {
        val input = Files.writeString(directory.resolve("input.log"), "2026-11-01 00:00:00 ::1 GET / 200 1")
        val original = Files.readString(input)
        assertEquals(1, run(input.toString(), "--output", input.toString()).code)
        assertEquals(original, Files.readString(input))
        val alias = Files.createLink(directory.resolve("alias.log"), input)
        assertEquals(1, run(input.toString(), "--output", alias.toString()).code)
        assertEquals(original, Files.readString(input))
        val output = Files.writeString(directory.resolve("report.json"), "old")
        assertEquals(0, run(input.toString(), "--output", output.toString()).code)
        assertTrue(JsonParser.parseString(Files.readString(output)).isJsonObject)
    }
}
