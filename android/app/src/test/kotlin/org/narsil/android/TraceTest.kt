package org.narsil.android

import org.junit.Assert.assertEquals
import org.junit.Test

/** The same records are in TraceTests.swift. */
class TraceTest {
    @Test fun aRecordIsTimeEventAndFieldsSeparatedByTabs() =
        assertEquals("1500\tbar.tap\tact=Fire\n", Trace.line(1500, "bar.tap", listOf("act" to "Fire")))

    @Test fun anEventWithNoFieldsIsJustTimeAndEvent() = assertEquals("0\tapp.start\n", Trace.line(0, "app.start", emptyList()))

    @Test fun tabsAndNewlinesInAValueCannotSplitARecord() =
        assertEquals("7\tbar.tap\tact=a b c\n", Trace.line(7, "bar.tap", listOf("act" to "a\tb\nc")))

    @Test fun numbersAndFlagsKeepTheirOrder() =
        assertEquals("42\tpanel.close\topen_ms=4900\tacted=false\n", Trace.line(42, "panel.close", listOf("open_ms" to 4900, "acted" to false)))
}
