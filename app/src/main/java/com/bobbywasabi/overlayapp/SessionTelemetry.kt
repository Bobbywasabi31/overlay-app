package com.bobbywasabi.overlayapp

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.Locale

/**
 * Item 8 (ring-detection debugging for #13): opt-in, local-only, numeric-only
 * per-session stats log.
 *
 * Disabled by default; the user flips it on from the main screen. While a
 * capture session runs, every analyzed frame appends one CSV row: relative
 * timestamp, analysis time, candidate count, per-reason rejection counts, and
 * the analyzer + tracker ring-radius series. Numbers only — no pixels, no
 * frame content, no screenshots — written to the app-private files directory
 * (never backed up, never leaves the device; the app has no INTERNET
 * permission, enforced in CI). Old sessions are pruned to [MAX_FILES];
 * turning the switch off deletes all saved logs.
 */
object SessionTelemetry {
    private const val PREFS = "throw_assistant_prefs"
    private const val KEY_ENABLED = "telemetry_enabled"
    private const val DIR_NAME = "telemetry"

    /** Rejection reasons [ScreenAnalyzer] can report, in fixed CSV column order. */
    val REASON_COLUMNS: List<String> = listOf(
        "grayscale", "tiny", "edge", "aspect", "center", "radius", "fill", "shape", "disk",
    )

    /** Rows kept per session file: ~10 minutes at 60fps. */
    internal const val MAX_ROWS_PER_FILE = 36_000

    /** Session files kept on device; the oldest are pruned when a new one starts. */
    internal const val MAX_FILES = 10

    /** Flush every N rows so a killed session still keeps its log. */
    private const val FLUSH_EVERY = 60

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) clearFiles(context)
    }

    fun clearFiles(context: Context) {
        try {
            File(context.filesDir, DIR_NAME).deleteRecursively()
        } catch (_: SecurityException) {
            // Private dir should always be deletable; never break the opt-out path.
        }
    }

    /** Starts a session when the user opted in, else returns null. Never throws. */
    fun beginIfEnabled(context: Context, clock: () -> Long = System::currentTimeMillis): Session? =
        if (isEnabled(context)) begin(File(context.filesDir, DIR_NAME), clock) else null

    /** Starts a session writing into [dir]; the [clock] supplies epoch ms. Never throws. */
    fun begin(dir: File, clock: () -> Long = System::currentTimeMillis): Session {
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) return Session(null, clock)
            pruneOld(dir)
            val timestamp = clock()
            val file = File(dir, "session-%tY%tm%td-%tH%tM%tS-%tL.csv".format(
                Locale.US, timestamp, timestamp, timestamp, timestamp, timestamp, timestamp, timestamp))
            file.createNewFile()
            val writer = BufferedWriter(FileWriter(file, true))
            writer.write(header())
            writer.newLine()
            Session(writer, clock)
        } catch (_: Exception) {
            // Telemetry must never break a capture session.
            Session(null, clock)
        }
    }

    private fun header(): String = buildString {
        append("tMs,analysisMs,candidates,")
        REASON_COLUMNS.forEach { append("rej_").append(it).append(',') }
        append("radius,trackedRadius")
    }

    private fun pruneOld(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("session-") && f.name.endsWith(".csv") }
            ?.sortedBy { it.name } ?: return
        val excess = files.size - MAX_FILES + 1
        for (i in 0 until excess.coerceAtLeast(0)) runCatching { files[i].delete() }
    }

    class Session internal constructor(
        private var writer: BufferedWriter?,
        private val clock: () -> Long,
    ) {
        private val startMs = clock()
        private var rows = 0

        /** Rows are kept bounded; visible to tests. */
        internal var maxRowsPerFile: Int = MAX_ROWS_PER_FILE

        /**
         * Appends one frame's numeric stats. Safe to call from the analysis
         * worker thread; [radius] is the analyzer's raw detection (-1 when none),
         * [trackedRadius] the tracker's confirmed radius (-1 when none).
         */
        fun logFrame(stats: ScreenAnalyzer.Stats, radius: Float?, trackedRadius: Float?) {
            val out = writer ?: return
            if (rows >= maxRowsPerFile) return
            try {
                val sb = StringBuilder(128)
                sb.append(clock() - startMs).append(',')
                sb.append(stats.analysisMs).append(',')
                sb.append(stats.candidates)
                for (reason in REASON_COLUMNS) {
                    sb.append(',').append(stats.rejected[reason] ?: 0)
                }
                sb.append(',').append(radius?.toString() ?: "-1")
                sb.append(',').append(trackedRadius?.toString() ?: "-1")
                out.write(sb.toString())
                out.newLine()
                rows++
                if (rows % FLUSH_EVERY == 0) out.flush()
            } catch (_: Exception) {
                runCatching { out.close() }
                writer = null
            }
        }

        /** Flushes and closes the log. Idempotent. */
        fun end() {
            val out = writer ?: return
            writer = null
            runCatching {
                out.flush()
                out.close()
            }
        }
    }
}
