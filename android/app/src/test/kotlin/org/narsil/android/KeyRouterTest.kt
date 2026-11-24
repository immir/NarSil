package org.narsil.android

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyRouterTest {
    private val sent = mutableListOf<Int>()
    private val router = KeyRouter({ code, _ -> sent += code }, { _, _, _, _ -> })

    @Test fun `a sheet over a panel gives Back to the panel when it closes`() {
        val seenByPanel = mutableListOf<Int>()
        router.intercepting { code -> seenByPanel += code; true }
        val release = router.intercepting { true }  // the sheet
        release()
        router.special(Keys.ESCAPE)
        assertEquals(listOf(Keys.ESCAPE), seenByPanel)
        assertEquals(emptyList<Int>(), sent)
    }

    @Test fun `a handler replaced meanwhile stays replaced`() {
        val first = router.intercepting { true }
        val seen = mutableListOf<Int>()
        router.intercepting { code -> seen += code; true }
        first()
        router.special(Keys.ESCAPE)
        assertEquals(listOf(Keys.ESCAPE), seen)
    }
}
