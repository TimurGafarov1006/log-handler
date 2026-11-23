package com.chillypeppers.loghandler

import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SequentialAnalyzerTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `preserves all entries order and caller thread for every handler`() {
        val lines = listOf(
            "2026-11-01 00:00:00 ::1 GET /users 200 15",
            "2026-11-01 00:00:59 ::1 POST /users 500 0",
            "2026-11-01 00:01:00 192.168.1.1 GET / 404 80",
            "2026-11-01 00:00:00 ::1 GET /users 200 15",
            "2026-11-02 00:00:00 ::1 DELETE /users 204 5"
        )
        val path = write(lines.joinToString("\n"))
        val expected = lines.map(LogParser::parse)
        val caller = Thread.currentThread()
        for (batchSize in listOf(1, 2, 5, 10)) {
            val first = mutableListOf<LogEntry>()
            val second = mutableListOf<LogEntry>()
            val handlers = listOf(first, second).map { result ->
                handler { entries ->
                    assertEquals(caller, Thread.currentThread())
                    assertTrue(entries.size in 1..batchSize)
                    result.addAll(entries)
                }
            }
            SequentialAnalyzer(handlers, batchSize).analyze(path)
            assertEquals(expected, first)
            assertEquals(expected, second)
        }
    }

    @Test
    fun `does not call handlers for empty files`() {
        SequentialAnalyzer(listOf(handler { error("Unexpected batch") })).analyze(write(""))
    }

    @Test
    fun `propagates handler failure and stops analysis`() {
        var calls = 0
        val failure = IllegalStateException("Handler failed")
        val analyzer = SequentialAnalyzer(listOf(handler { calls++; throw failure }), 1)
        val error = assertFailsWith<IllegalStateException> {
            analyzer.analyze(write("2026-11-01 00:00:00 ::1 GET / 200 1\n2026-11-01 00:00:00 ::1 GET / 200 2"))
        }
        assertEquals(failure, error)
        assertEquals(1, calls)
    }

    @Test
    fun `propagates malformed input and missing files`() {
        val analyzer = SequentialAnalyzer(emptyList())
        assertFailsWith<IllegalArgumentException> { analyzer.analyze(write("bad")) }
        assertFailsWith<IOException> { analyzer.analyze(directory.resolve("missing.log")) }
    }

    @Test
    fun `rejects nonpositive batch sizes`() {
        for (size in listOf(0, -1)) {
            assertFailsWith<IllegalArgumentException> { SequentialAnalyzer(emptyList(), size) }
        }
    }

    private fun write(text: String): Path = Files.writeString(directory.resolve("access.log"), text)

    private fun handler(action: (List<LogEntry>) -> Unit) = object : LogHandler {
        override fun handle(entries: List<LogEntry>) = action(entries)
    }
}
