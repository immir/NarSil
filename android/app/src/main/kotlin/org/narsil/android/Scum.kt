package org.narsil.android

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Save scumming, for testing: copies of the savefile taken on demand, and a way
 * back to one.  The native game cannot be restarted in-process, so a restore
 * leaves a marker, restarts the app, and [applyPendingRestore] swaps the copy in
 * before the game thread starts.
 */
object Scum {
    private const val KEEP = 12
    private const val MARKER = "restore"

    data class Copy(val file: File, val label: String)

    private fun dataDir(context: Context) = File(context.filesDir, "data")
    private fun saveFile(context: Context) = File(dataDir(context), "save/${GameLauncher.PLAYER}")
    private fun dir(context: Context) = File(dataDir(context), "scum").apply { mkdirs() }

    fun copies(context: Context): List<Copy> =
        dir(context).listFiles { f -> f.name.startsWith("${GameLauncher.PLAYER}-") }.orEmpty()
            .sortedByDescending { it.name }
            .map { Copy(it, label(it)) }

    private fun label(f: File) =
        SimpleDateFormat("EEE d MMM, HH:mm:ss", Locale.getDefault()).format(Date(f.lastModified()))

    /**
     * Saves the game (only possible at the command prompt), then copies the
     * savefile.  Returns a line for the user either way.
     */
    /** Asks the game to save (it can only at the command prompt); true once it has. */
    private suspend fun saveNow(): Boolean {
        val before = NarSilNative.nativeSaveCount()
        NarSilNative.nativeRequestSave()
        return withTimeoutOrNull(3000) { while (NarSilNative.nativeSaveCount() == before) delay(50) } != null
    }

    private const val CANT_SAVE = "Can't save right now: finish what you're doing first."

    suspend fun take(context: Context): String {
        if (!saveNow()) return CANT_SAVE
        return withContext(Dispatchers.IO) {
            val src = saveFile(context)
            check(src.isFile) { "no savefile at $src" }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            src.copyTo(File(dir(context), "${GameLauncher.PLAYER}-$stamp"), overwrite = true)
            copies(context).drop(KEEP).forEach { it.file.delete() }
            "Scum save taken."
        }
    }

    /**
     * Saves, then copies the savefile to a dated export file for sharing; null
     * (with [onError] told why) if the game can't save right now.
     */
    suspend fun export(context: Context, onError: (String) -> Unit): File? {
        if (!saveNow()) { onError(CANT_SAVE); return null }
        return withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles().orEmpty().forEach { it.delete() }
            val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
            saveFile(context).copyTo(File(dir, "NarSil-save-$stamp.sav"), overwrite = true)
        }
    }

    /** Copies a savefile chosen by the user in as a scum copy, ready for [restore]. */
    suspend fun import(context: Context, uri: android.net.Uri): Copy = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dest = File(dir(context), "${GameLauncher.PLAYER}-$stamp")
        checkNotNull(context.contentResolver.openInputStream(uri)) { "can't read $uri" }.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        Copy(dest, "imported ${label(dest)}")
    }

    /** Marks [copy] to be restored, and restarts the app to load it. */
    fun restore(context: Context, copy: Copy) {
        File(dir(context), MARKER).writeText(copy.file.name)
        val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        context.startActivity(Intent.makeRestartActivityTask(launch.component))
        exitProcess(0)
    }

    /** Before the game starts: swap in a copy marked by [restore]. */
    fun applyPendingRestore(context: Context) {
        val marker = File(dir(context), MARKER)
        if (!marker.isFile) return
        val copy = File(dir(context), marker.readText().trim())
        if (copy.isFile) copy.copyTo(saveFile(context).apply { parentFile?.mkdirs() }, overwrite = true)
        marker.delete()
    }
}

/** The scum copies, newest first: tap one to go back to it. */
@Composable
fun ScumSheet(context: Context, onDismiss: () -> Unit) {
    var copies by remember { mutableStateOf(Scum.copies(context)) }
    var chosen by remember { mutableStateOf<Scum.Copy?>(null) }
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), color = Color(0xFF1C1C1E),
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Restore a scum save", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                         modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                if (copies.isEmpty())
                    Text("No scum saves yet. Use the Scum button while playing.", color = Color(0xFF9A9AA0), fontSize = 14.sp,
                         modifier = Modifier.padding(16.dp))
                LazyColumn {
                    items(copies, key = { it.file.name }) { c ->
                        Row(Modifier.fillMaxWidth().clickable { chosen = c }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(c.label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { c.file.delete(); copies = Scum.copies(context) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
    chosen?.let { c ->
        AlertDialog(
            onDismissRequest = { chosen = null },
            title = { Text("Restore this save?") },
            text = { Text("${c.label}\n\nThe game restarts from this copy; anything since is lost.") },
            confirmButton = { TextButton(onClick = { Scum.restore(context, c) }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { chosen = null }) { Text("Cancel") } },
        )
    }
}
