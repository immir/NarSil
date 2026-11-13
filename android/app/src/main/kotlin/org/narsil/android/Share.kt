package org.narsil.android

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Hands a file to another app (mail, Drive, a messenger…) through the system share sheet. */
object Share {
    fun file(context: Context, file: File, mime: String, title: String) {
        check(file.isFile) { "nothing to share at $file" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, title))
    }

    /** The character dump the game writes on request (narsil_prompt.c, NB_CHARACTER_DUMP). */
    fun characterDump(context: Context) = File(context.filesDir, "data/user/character-dump.txt")
}
