package org.narsil.android

import org.junit.Assert.assertEquals
import org.junit.Test

/** The same row and expectation are in StatusRowTests.swift. */
class StatusRowTest {
    @Test fun rowIsCutAtRunsOfTwoBlanksAndTakesTheFirstAttr() {
        val text = "Light 3  Fed   Open floor  50'"
        val term = IntArray(2 * 40) { 0 }
        text.forEachIndexed { x, c -> term[2 * x] = c.code; term[2 * x + 1] = 100 + x }
        assertEquals(listOf("Light 3" to 100, "Fed" to 109, "Open floor" to 115, "50'" to 127), splitStatusRow(term, 0, 40))
    }

    @Test fun anEmptyRowHasNoPieces() = assertEquals(emptyList<Pair<String, Int>>(), splitStatusRow(IntArray(2 * 10), 0, 10))
}
