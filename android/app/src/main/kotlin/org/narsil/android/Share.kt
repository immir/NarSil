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
            File(context.filesDir, "data/save/${GameLauncher.PLAYER}").takeIf { it.isFile }?.let { put("save/${it.name}", it.readBytes()) }
            characterDump(context).takeIf { it.isFile }?.let { put("character-dump.txt", it.readBytes()) }
        }
        zip
    }

    /** The character dump the game writes on request (narsil_prompt.c, NB_CHARACTER_DUMP). */
    fun characterDump(context: Context) = File(context.filesDir, "data/user/character-dump.txt")
}
