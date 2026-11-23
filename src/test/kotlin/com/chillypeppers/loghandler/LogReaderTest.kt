package com.chillypeppers.loghandler

import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogReaderTest {
    @Test
    fun `reads consecutive batches and final line without newline`() {
        val input = TrackingReader((1..5).joinToString("\n", transform = ::line))
        LogReader(input, 2).use { reader ->
            val first = reader.readBatch()
            assertEquals(listOf(1L, 2L), first.map { it.responseTime })
            assertFalse(input.closed)
            assertEquals(listOf(3L, 4L), reader.readBatch().map { it.responseTime })
            assertEquals(listOf(5L), reader.readBatch().map { it.responseTime })
            assertEquals(emptyList(), reader.readBatch())
            assertEquals(emptyList(), reader.readBatch())
            assertEquals(listOf(1L, 2L), first.map { it.responseTime })
        }
        assertTrue(input.closed)
    }

    @Test
    fun `handles empty input and exact batch boundary`() {
        for (size in listOf(0, 2, 4)) {
            LogReader((1..size).joinToString("\n", transform = ::line).reader().buffered(), 2).use { reader ->
                repeat(size / 2) { assertEquals(2, reader.readBatch().size) }
                assertTrue(reader.readBatch().isEmpty())
            }
        }
    }

    @Test
    fun `reports global line number and closes on parse failure`() {
        val input = TrackingReader("${line(1)}\n${line(2)}\nbad")
        val error = assertFailsWith<IllegalArgumentException> {
            LogReader(input, 2).use { reader ->
                reader.readBatch()
                reader.readBatch()
            }
        }
        assertTrue(error.message.orEmpty().contains("line 3"))
        assertTrue(error.cause is IllegalArgumentException)
        assertTrue(input.closed)
    }

    @Test
    fun `propagates input errors and closes reader`() {
        val input = object : TrackingReader("") {
            override fun readLine(): String = throw IOException("Read failed")
        }
        assertFailsWith<IOException> { LogReader(input).use { it.readBatch() } }
        assertTrue(input.closed)
    }

    @Test
    fun `rejects nonpositive batch sizes`() {
        for (size in listOf(0, -1)) {
            StringReader("").buffered().use { input ->
                assertFailsWith<IllegalArgumentException> { LogReader(input, size) }
            }
        }
    }

    private fun line(duration: Int) = "2026-11-01 00:00:00 ::1 GET / 200 $duration"

    private open class TrackingReader(text: String) : BufferedReader(StringReader(text)) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }
}
