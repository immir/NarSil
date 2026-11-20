package org.narsil.android

import java.io.File
import java.util.concurrent.Executors

/**
 * The usage trace (Options → Record usage trace): one line per interaction, kept on the device and shared only by
 * Send logs.  Lines are `ms-since-start TAB event TAB key=value TAB key=value`; the vocabulary is shared with the iOS
 * app (ios/NarSil/Trace.swift) and read by tools/trace-summary.py.  Never text the player typed, no names or messages.
 */
object Trace {
    private const val ROTATE_BYTES = 8L shl 20
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "trace").apply { isDaemon = true } }
    private var file: File? = null
    private var started = 0L

    @Volatile var enabled = false
        private set

    /** One record: milliseconds since the session began, the event, then its fields in the order given. */
    fun line(ms: Long, event: String, fields: List<Pair<String, Any>>): String =
        (listOf(ms.toString(), event) + fields.map { (k, v) -> "$k=${clean(v.toString())}" }).joinToString("\t") + "\n"

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    /** Opens the trace file (rotating a big one aside) and starts a session record if recording is on. */
    fun start(dir: File, on: Boolean, header: List<Pair<String, Any>>) {
        file = File(dir, "trace.log").also { f ->
            f.parentFile?.mkdirs()
            if (f.length() > ROTATE_BYTES) f.renameTo(File(dir, "trace.previous.log"))
        }
        started = android.os.SystemClock.elapsedRealtime()
        enabled = on
        if (on) log("session", *header.toTypedArray())
    }

    fun setEnabled(on: Boolean, header: List<Pair<String, Any>>) {
        if (on == enabled) return
        enabled = on
        if (on) { started = android.os.SystemClock.elapsedRealtime(); log("session", *header.toTypedArray()) }
    }

    fun log(event: String, vararg fields: Pair<String, Any>) {
        if (!enabled) return
        val target = file ?: return
        val text = line(android.os.SystemClock.elapsedRealtime() - started, event, fields.toList())
        writer.execute { runCatching { target.appendText(text) } }
    }

    /** The trace files that exist, for the bug-report zip. */
    fun files(dir: File): List<File> = listOf("trace.previous.log", "trace.log").map { File(dir, it) }.filter { it.isFile }
}
