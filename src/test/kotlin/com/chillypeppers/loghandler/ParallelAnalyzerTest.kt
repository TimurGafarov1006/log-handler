package com.chillypeppers.loghandler

import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Timeout(30)
class ParallelAnalyzerTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `rejects invalid limits`() {
        assertFailsWith<IllegalArgumentException> { ParallelAnalyzer(0) }
        assertFailsWith<IllegalArgumentException> { ParallelAnalyzer(1, 0) }
        assertFailsWith<IllegalArgumentException> { ParallelAnalyzer(1, 1, 0) }
    }

    @Test
    fun `propagates read and parse errors and releases file`() {
        assertFailsWith<java.io.IOException> { ParallelAnalyzer().analyze(directory.resolve("missing")) }
        val path = directory.resolve("bad.log")
        Files.writeString(path, "2026-11-01 00:00:00 ::1 GET / 200 1\nbad")
        val error = assertFailsWith<IllegalArgumentException> { ParallelAnalyzer(2, 1, 1).analyze(path) }
        assertTrue(error.message!!.contains("line 2"))
        Files.delete(path)
    }

    @Test
    fun `empty input produces empty statistics`() {
        val path = Files.createFile(directory.resolve("empty.log"))
        assertEquals(AnalysisStatistics(), ParallelAnalyzer(2, 3, 1).analyze(path))
    }
}
