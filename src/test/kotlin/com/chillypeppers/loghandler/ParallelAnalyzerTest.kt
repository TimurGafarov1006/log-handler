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
    @Test
    fun `matches sequential statistics for different batch and pool sizes`() {
        val path = directory.resolve("input.log")
        Files.write(path, (0 until 103).map {
            "2026-11-01 00:${(it % 3).toString().padStart(2, '0')}:00 ip${it % 5} GET /${it % 7} ${if (it % 4 == 0) 503 else 200} $it"
        })
        val expected = AnalysisStatistics()
        SequentialAnalyzer(listOf(expected), 7).analyze(path)
        assertEquals(103L, expected.requests)
        assertEquals(5253L, expected.totalTime)
        assertEquals(26L, expected.statuses[503])
        for (workers in listOf(1, 2, 4)) {
            for (batch in listOf(1, 7, 200)) {
                for (limit in listOf(1, 3)) {
                    assertEquals(expected, ParallelAnalyzer(workers, batch, limit).analyze(path))
                }
            }
        }
        assertEquals(expected.averageTime, expected.minutes.values.sumOf { it.totalTime }.toDouble() / 103)
    }

    @Test
    fun `matches sequential statistics on complete supplied file`() {
        val path = Path.of("access_large.log")
        val expected = AnalysisStatistics()
        SequentialAnalyzer(listOf(expected)).analyze(path)
        assertEquals(1_500_000L, expected.requests)
        assertEquals(expected, ParallelAnalyzer(4, 7001, 3).analyze(path))
    }}

