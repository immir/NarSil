package org.narsil.android

import android.content.Context
import android.content.res.AssetManager
import java.io.File

/**
 * Installs the read-only game data (packaged as assets/lib by Gradle) and starts the
 * game thread.  The native game is process-global and cannot be restarted, so a
 * recreated Activity simply reattaches to the running game.
 */
object GameLauncher {
    const val COLS = 80
    const val ROWS = 24
    const val PLAYER = "PLAYER"

    @Synchronized
    fun ensureStarted(context: Context) {
        if (NarSilNative.nativeState() != NarSilNative.STATE_IDLE) return
        val libDir = File(context.filesDir, "lib")
        val dataDir = File(context.filesDir, "data").apply { mkdirs() }
        installIfStale(context, libDir)
        Scum.applyPendingRestore(context)
        check(NarSilNative.nativeStart(libDir.path, dataDir.path, PLAYER, COLS, ROWS)) { "nativeStart failed" }
    }

    /** Re-extracts whenever the APK changes; game data is small (~700 KB). */
    private fun installIfStale(context: Context, libDir: File) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val stamp = "${info.lastUpdateTime}"
        val stampFile = File(libDir, ".installed")
        if (stampFile.isFile && stampFile.readText() == stamp) return
        libDir.deleteRecursively()
        copyAssetTree(context.assets, "lib", libDir)
        stampFile.writeText(stamp)
    }

    private fun copyAssetTree(assets: AssetManager, path: String, dest: File) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
        } else {
            dest.mkdirs()
            children.forEach { copyAssetTree(assets, "$path/$it", File(dest, it)) }
        }
    }
}
