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

    /**
     * The two games one engine plays: each has its own lib dir (NarSil-Q's is lib with assets/lib-silq copied
     * over it) and its own data dir, so characters, saves, scores and scum copies never mix.  [tag] is the
     * savefile header (src/savefile.c).
     */
    enum class Game(val id: String, val title: String, val lib: String, val data: String, val tag: String) {
        NARSIL("narsil", "NarSil", "lib", "data", "SaveUSIL"),
        SILQ("silq", "NarSil-Q", "lib-silq", "data-silq", "SaveUSLQ");
        val other get() = if (this == NARSIL) SILQ else NARSIL
    }

    private fun prefs(context: Context) = context.getSharedPreferences("narsil", Context.MODE_PRIVATE)

    /** The game chosen on the welcome screen; read before the game starts, so not a Compose state. */
    fun game(context: Context): Game = Game.entries.firstOrNull { it.id == prefs(context).getString("game", null) } ?: Game.NARSIL

    fun dataDir(context: Context) = File(context.filesDir, game(context).data)

    /** The welcome screen's "Play the other game": remember it and start the app again (the engine can't restart in-process). */
    fun switchGame(context: Context) {
        prefs(context).edit().putString("game", game(context).other.id).commit()
        val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        context.startActivity(android.content.Intent.makeRestartActivityTask(launch.component))
        kotlin.system.exitProcess(0)
    }

    /** Set once the game has shown a native screen or the map; until then its textui start-up is hidden. */
    @Volatile var started = false

    @Synchronized
    fun ensureStarted(context: Context) {
        if (NarSilNative.nativeState() != NarSilNative.STATE_IDLE) return
        val game = game(context)
        val libDir = File(context.filesDir, game.lib)
        val dataDir = dataDir(context).apply { mkdirs() }
        installIfStale(context, libDir, overlay = if (game == Game.SILQ) "lib-silq" else null)
        Scum.applyPendingRestore(context)
        NarSilNative.silq = game == Game.SILQ
        check(NarSilNative.nativeStart(libDir.path, dataDir.path, PLAYER, COLS, ROWS)) { "nativeStart failed" }
    }

    /** Re-extracts whenever the APK changes; game data is small (~700 KB).  [overlay]'s files replace lib's. */
    private fun installIfStale(context: Context, libDir: File, overlay: String?) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val stamp = "${info.lastUpdateTime}"
        val stampFile = File(libDir, ".installed")
        if (stampFile.isFile && stampFile.readText() == stamp) return
        libDir.deleteRecursively()
        copyAssetTree(context.assets, "lib", libDir)
        overlay?.let { copyAssetTree(context.assets, it, libDir) }
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
