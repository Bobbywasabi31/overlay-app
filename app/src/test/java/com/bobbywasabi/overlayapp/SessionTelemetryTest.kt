package com.bobbywasabi.overlayapp

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SessionTelemetryTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun tempDir(): File =
        File.createTempFile("telemetry-test", "").also { it.delete() }.also { it.mkdirs() }

    private fun stats(ms: Long = 12, candidates: Int = 3, rejected: Map<String, Int> = emptyMap()) =
        ScreenAnalyzer.Stats(ms, candidates, rejected)

    @Test fun disabledByDefault() {
        assertFalse(SessionTelemetry.isEnabled(context))
    }

    @Test fun optInPersists() {
        SessionTelemetry.setEnabled(context, true)
        assertTrue(SessionTelemetry.isEnabled(context))
        SessionTelemetry.setEnabled(context, false)
        assertFalse(SessionTelemetry.isEnabled(context))
    }

    @Test fun headerAndRowFormat() {
        val dir = tempDir()
        var now = 1_000L
        val session = SessionTelemetry.begin(dir, clock = { now })
        now = 1_050L
        session.logFrame(stats(12, 3, mapOf("tiny" to 2, "shape" to 1)), 0.0425f, 0.043f)
        session.end()
        val lines = dir.listFiles()!!.single().readLines()
        assertEquals(2, lines.size)
        assertEquals(
            "tMs,analysisMs,candidates,rej_grayscale,rej_tiny,rej_edge,rej_aspect,rej_center," +
                "rej_radius,rej_fill,rej_shape,rej_disk,radius,trackedRadius",
            lines[0],
        )
        val cols = lines[1].split(',')
        assertEquals("50", cols[0])
        assertEquals("12", cols[1])
        assertEquals("3", cols[2])
        assertEquals(listOf("0", "2", "0", "0", "0", "0", "0", "1", "0"), cols.subList(3, 12))
        assertEquals("0.0425", cols[12])
        assertEquals("0.043", cols[13])
    }

    @Test fun nullRadiiWrittenAsNegativeOne() {
        val dir = tempDir()
        val session = SessionTelemetry.begin(dir, clock = { 0L })
        session.logFrame(stats(5, 0, mapOf("grayscale" to 1)), null, null)
        session.end()
        val row = dir.listFiles()!!.single().readLines()[1]
        assertTrue(row.endsWith(",-1,-1"))
    }

    @Test fun unknownRejectionReasonsIgnored() {
        val dir = tempDir()
        val session = SessionTelemetry.begin(dir, clock = { 0L })
        session.logFrame(stats(5, 1, mapOf("tiny" to 1, "future_reason" to 9)), 0.05f, null)
        session.end()
        val cols = dir.listFiles()!!.single().readLines()[1].split(',')
        assertEquals(14, cols.size)
        assertEquals("1", cols[4]) // rej_tiny
    }

    @Test fun rowsBoundedPerFile() {
        val dir = tempDir()
        val session = SessionTelemetry.begin(dir, clock = { 0L })
        session.maxRowsPerFile = 3
        repeat(5) { session.logFrame(stats(), 0.05f, 0.05f) }
        session.end()
        assertEquals(4, dir.listFiles()!!.single().readLines().size) // header + 3 rows
    }

    @Test fun endIsIdempotent() {
        val dir = tempDir()
        val session = SessionTelemetry.begin(dir, clock = { 0L })
        session.logFrame(stats(), 0.05f, 0.05f)
        session.end()
        session.end() // must not throw
        session.logFrame(stats(), 0.05f, 0.05f) // no-op after end
    }

    @Test fun oldestSessionsPruned() {
        val dir = tempDir()
        repeat(SessionTelemetry.MAX_FILES + 3) { i ->
            File(dir, "session-202601%02d-000000.csv".format(i)).writeText("x")
        }
        SessionTelemetry.begin(dir, clock = { 0L }).end()
        val remaining = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(SessionTelemetry.MAX_FILES, remaining.size)
        assertFalse(remaining.any { it.contains("20260100-") })
        assertFalse(remaining.any { it.contains("20260101-") })
        assertFalse(remaining.any { it.contains("20260102-") })
    }

    @Test fun unwritableDirNeverThrows() {
        val parent = tempDir()
        val blocker = File(parent, "telemetry").also { it.writeText("not a dir") }
        val session = SessionTelemetry.begin(blocker, clock = { 0L })
        session.logFrame(stats(), 0.05f, 0.05f)
        session.end()
    }

    @Test fun beginIfEnabledRespectsOptIn() {
        assertNull(SessionTelemetry.beginIfEnabled(context))
        SessionTelemetry.setEnabled(context, true)
        assertNotNull(SessionTelemetry.beginIfEnabled(context))
        SessionTelemetry.setEnabled(context, false)
        assertNull(SessionTelemetry.beginIfEnabled(context))
    }

    @Test fun optingOutDeletesSavedLogs() {
        SessionTelemetry.setEnabled(context, true)
        val session = SessionTelemetry.beginIfEnabled(context)!!
        session.logFrame(stats(), 0.05f, 0.05f)
        session.end()
        val telemetryDir = File(context.filesDir, "telemetry")
        assertTrue(telemetryDir.listFiles()?.isNotEmpty() == true)
        SessionTelemetry.setEnabled(context, false)
        assertFalse(telemetryDir.exists())
    }

    @Test fun clearFilesIsSafeWhenNothingSaved() {
        SessionTelemetry.clearFiles(context) // must not throw
    }
}
