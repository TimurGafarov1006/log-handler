package com.chillypeppers.loghandler

import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Timeout(30)
class StatisticsHandlerTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `sequential and parallel engines agree across batch boundaries and empty files`() {
        val lines = (0 until 301).map { index ->
            "2026-11-01 00:0${index % 3}:59 ip${index % 5} GET /url${index % 7} ${if (index % 4 == 0) 503 else 200} ${index % 101}"
        }
        val expected = LogStatistics().apply { lines.map(LogParser::parse).forEach(::add) }.snapshot()
        for (text in listOf("", lines.joinToString("\n"))) {
            val path = Files.writeString(directory.resolve("access.log"), text)
            for (size in listOf(1, 7, 301, 1000)) {
                val sequential = StatisticsHandler()
                SequentialAnalyzer(listOf(sequential), size).analyze(path)
                val parallel = StatisticsHandler()
                LogEngine(listOf(parallel), LogReader.open(path, size)).process(path.toString())
                assertEquals(if (text.isEmpty()) LogStatistics().snapshot() else expected, sequential.snapshot())
                assertEquals(sequential.snapshot(), parallel.snapshot())
            }
        }
    }

    @Test
    fun `concurrent handler calls never lose updates and snapshots remain stable`() {
        val batch = (0 until 25).map {
            LogParser.parse("2026-11-01 00:00:00 ::1 GET / 500 $it")
        }
        val handler = StatisticsHandler()
        val before = handler.snapshot()
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(4).use { executor ->
            val tasks = (0 until 100).map {
                executor.submit {
                    start.await()
                    handler.handle(batch)
                    val snapshot = handler.snapshot()
                    assertEquals(snapshot.requestCount, snapshot.statusCounts.values.sum())
                    assertEquals(snapshot.requestCount, snapshot.minutes.values.sumOf { it.requestCount })
                }
            }
            start.countDown()
            tasks.forEach { it.get() }
        }
        val expected = LogStatistics().apply { repeat(100) { batch.forEach(::add) } }.snapshot()
        assertEquals(expected, handler.snapshot())
        assertEquals(LogStatistics().snapshot(), before)
        handler.handle(emptyList())
        assertEquals(expected, handler.snapshot())
    }

    @Test
    fun `malformed input preserves only completed batches in either engine`() {
        val path = Files.writeString(directory.resolve("invalid.log"),
            "2026-11-01 00:00:00 ::1 GET / 200 10\nbad")
        for (parallel in listOf(false, true)) {
            for (batchSize in listOf(1, 2)) {
                val handler = StatisticsHandler()
                assertFailsWith<IllegalArgumentException> {
                    if (parallel) LogEngine(listOf(handler), LogReader.open(path, batchSize)).process(path.toString())
                    else SequentialAnalyzer(listOf(handler), batchSize).analyze(path)
                }
                assertEquals(if (batchSize == 1) 1L else 0L, handler.snapshot().requestCount)
            }
        }
    }
}
