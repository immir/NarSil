package org.narsil.android

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Hands a file to another app (mail, Drive, a messenger…) through the system share sheet. */
object Share {
    fun file(context: Context, file: File, mime: String, title: String) {
        check(file.isFile) { "nothing to share at $file" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, title))
    }

    /**
     * A zip for bug reports: this app's log (logcat for our process), the
     * savefile, and the character dump if there is one.
     */
    suspend fun bugReport(context: Context): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
        val zip = File(dir, "NarSil-report-$stamp.zip")
        val log = runCatching {
            ProcessBuilder("logcat", "-d", "-v", "time", "--pid=${android.os.Process.myPid()}").redirectErrorStream(true)
                .start().inputStream.use { it.readBytes() }
        }.getOrElse { "logcat failed: $it".toByteArray() }
        ZipOutputStream(zip.outputStream()).use { out ->
            fun put(name: String, bytes: ByteArray) { out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry() }
            put("logcat.txt", log)
            File(GameLauncher.dataDir(context), "save/${GameLauncher.PLAYER}").takeIf { it.isFile }?.let { put("save/${it.name}", it.readBytes()) }
            crashKept(context).takeIf { it.isFile }?.let { put("crash.txt", it.readBytes()) }
            characterDump(context).takeIf { it.isFile }?.let { put("character-dump.txt", it.readBytes()) }
            Trace.files(GameLauncher.dataDir(context)).forEach { put(it.name, it.readBytes()) }
        }
        zip
    }

    private fun crashFile(context: Context) = File(GameLauncher.dataDir(context), "crash.txt")
    private fun crashKept(context: Context) = File(GameLauncher.dataDir(context), "crash.previous.txt")

    /** The note the bridge's signal handler left if the last run died natively (narsil_crash.c); null if none. */
    fun pendingCrash(context: Context): String? = crashFile(context).takeIf { it.isFile }?.readText()

    /** Keeps the note for the next bug report and stops asking about it. */
    fun acknowledgeCrash(context: Context) { crashFile(context).takeIf { it.isFile }?.let { it.copyTo(crashKept(context), overwrite = true); it.delete() } }

    /** The character dump the game writes on request (narsil_prompt.c, NB_CHARACTER_DUMP). */
    fun characterDump(context: Context) = File(GameLauncher.dataDir(context), "user/character-dump.txt")
}
