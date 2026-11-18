package org.narsil.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ScumTest {
    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L

    private fun withSave(body: (save: File, dir: File) -> Unit) {
        val root = Files.createTempDirectory("scum").toFile()
        try {
            val save = File(root, "save").apply { writeText("state") }
            body(save, File(root, "scum").apply { mkdirs() })
        } finally { root.deleteRecursively() }
    }

    private fun kept(dir: File) = dir.listFiles().orEmpty().map { it.name }.sorted()

    @Test fun savesCloserThanFiveMinutesAreNotKept() = withSave { save, dir ->
        Scum.keepRecent(save, dir, "P", t0)
        Scum.keepRecent(save, dir, "P", t0 + 2 * minute)
        Scum.keepRecent(save, dir, "P", t0 + 4 * minute)
        assertEquals(1, kept(dir).size)
    }

    @Test fun onlyTheLastThreeAreKept() = withSave { save, dir ->
        (0..5).forEach { Scum.keepRecent(save, dir, "P", t0 + it * 6 * minute) }
        val names = kept(dir)
        assertEquals(3, names.size)
        assertTrue(names.all { it.startsWith("auto-P-") })
    }

    @Test fun aSaveIdenticalToAManualCopyIsNotKeptAgain() = withSave { save, dir ->
        File(dir, "P-20260101-000000").writeText("state")
        Scum.keepRecent(save, dir, "P", t0)
        assertEquals(listOf("P-20260101-000000"), kept(dir))
    }

    @Test fun otherPlayersAndManualCopiesAreLeftAlone() = withSave { save, dir ->
        File(dir, "P-20260101-000000").writeText("manual")
        File(dir, "auto-Q-20260101-000000").writeText("other")
        (0..5).forEach { Scum.keepRecent(save, dir, "P", t0 + it * 6 * minute) }
        assertTrue(File(dir, "P-20260101-000000").isFile && File(dir, "auto-Q-20260101-000000").isFile)
        assertEquals(5, kept(dir).size)
    }

    @Test fun noSavefileKeepsNothing() = withSave { save, dir ->
        save.delete()
        Scum.keepRecent(save, dir, "P", t0)
        assertEquals(0, kept(dir).size)
    }

    @Test fun onlyFilesStartingWithTheSavefileHeaderAreSavefiles() = withSave { save, _ ->
        assertTrue(!Scum.isSavefile(save))
        save.writeBytes("SaveUSILdescription".toByteArray())
        assertTrue(Scum.isSavefile(save))
        save.writeBytes("SaveV".toByteArray())
        assertTrue(!Scum.isSavefile(save))
        assertTrue(!Scum.isSavefile(File(save.parentFile, "missing")))
    }
}
