package com.chillypeppers.loghandler

import org.junit.jupiter.api.Timeout
import java.io.BufferedReader
import java.io.StringReader
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Timeout(10)
class LogEngineTest {
    @Test
    fun `finishes empty input without calling handlers and closes reader`() {
        val input = TrackingReader("")
        val engine = LogEngine(listOf(handler { error("Unexpected batch") }), LogReader(input, 2))
        engine.process("unused")
        assertTrue(input.closed)
    }

    @Test
    fun `waits for every batch including partial final batch`() {
        for (size in listOf(4, 5)) {
            val input = TrackingReader((1..size).joinToString("\n", transform = ::line))
            val actual = ConcurrentLinkedQueue<Long>()
            val engine = LogEngine(listOf(handler { entries ->
                assertTrue(entries.isNotEmpty())
                Thread.sleep(20)
                actual.addAll(entries.map { it.responseTime })
            }), LogReader(input, 2))
            engine.process("unused")
            assertEquals((1..size).map(Int::toLong), actual.sorted())
            assertTrue(input.closed)
        }
    }

    @Test
    fun `propagates handler failure and closes reader`() {
        val input = TrackingReader(line(1))
        val failure = IllegalStateException("Handler failed")
        val engine = LogEngine(listOf(handler { throw failure }), LogReader(input, 1))
        assertSame(failure, assertFailsWith<IllegalStateException> { engine.process("unused") })
        assertTrue(input.closed)
    }

    @Test
    fun `propagates malformed input and waits for submitted work`() {
        val input = TrackingReader("${line(1)}\nbad")
        val actual = ConcurrentLinkedQueue<Long>()
        val engine = LogEngine(listOf(handler { entries ->
            Thread.sleep(20)
            actual.addAll(entries.map { it.responseTime })
        }), LogReader(input, 1))
        assertFailsWith<IllegalArgumentException> { engine.process("unused") }
        assertEquals(listOf(1L), actual.toList())
        assertTrue(input.closed)
    }

    private fun line(duration: Int) = "2026-11-01 00:00:00 ::1 GET / 200 $duration"

    private fun handler(action: (List<LogEntry>) -> Unit) = object : LogHandler {
        override fun handle(entries: List<LogEntry>) = action(entries)
    }

    private class TrackingReader(text: String) : BufferedReader(StringReader(text)) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }
}
