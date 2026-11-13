package org.narsil.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.narsil.android.GameState.Info
import org.narsil.android.GameState.KnowRow
import org.narsil.android.GameState.OptionRow
import org.narsil.android.GameState.Prompt

/**
 * The app's side of the bridge: every fixture is what the game really published
 * (./gen-fixtures.sh runs the host harness); the parsers must read it into the UI's types.
 */
class ParseTest {
    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) { "no fixture $name" }.use { String(it.readBytes()) }

    private inline fun <reified T : Prompt> prompt(name: String): T {
        val p = Parse.prompt(fixture("$name.prompt"))
        assertNotNull("$name parsed to nothing", p)
        assertTrue("$name is ${p!!::class.simpleName}, wanted ${T::class.simpleName}", p is T)
        return p as T
    }

    @Test fun everyPromptFixtureParses() {
        val dir = checkNotNull(javaClass.classLoader?.getResource("fixtures")).path
        val names = java.io.File(dir).list().orEmpty().filter { it.endsWith(".prompt") }
        assertTrue("fixtures missing (run ./gen-fixtures.sh)", names.size >= 25)
        names.forEach { assertNotNull("$it did not parse", Parse.prompt(fixture(it))) }
    }

    @Test fun welcome() = prompt<Prompt.Screen>("welcome").let {
        assertEquals("welcome", it.stage)
        assertTrue(it.verse.isNotEmpty())
        assertEquals(listOf("Begin", "Tutorial"), it.options.map { o -> o.label })
    }

    @Test fun birthStages() {
        assertEquals("race", prompt<Prompt.Screen>("birth_race").stage)
        assertTrue(prompt<Prompt.Screen>("birth_race").options.size >= 3)
        assertEquals("house", prompt<Prompt.Screen>("birth_house").stage)
        assertEquals("sex", prompt<Prompt.Screen>("birth_sex").stage)
        prompt<Prompt.Text>("birth_name")
        prompt<Prompt.Skills>("birth_skills")
    }

    @Test fun birthStatsShowWhatTheyFeed() = prompt<Prompt.Screen>("birth_stats").let {
        assertEquals("stats", it.stage)
        assertEquals(4, it.stats.size)
        assertTrue(it.pointsTotal > 0)
        assertTrue(it.skills.any { (name, _) -> name == "Melee" })
        assertEquals(listOf("Attack", "Defence", "Health", "Voice"), it.combat.map { (name, _) -> name })
    }

    @Test fun deathScreen() = prompt<Prompt.Screen>("death").let {
        assertEquals("death", it.stage)
        assertTrue(it.options.any { o -> o.label == "New character" })
    }

    @Test fun packAndItsExpansion() {
        val closed = prompt<Prompt.Pack>("pack")
        assertEquals(-1, closed.expanded)
        assertTrue(closed.items.isNotEmpty() && closed.weight > 0 && closed.limit > closed.weight)
        val open = prompt<Prompt.Pack>("pack_expanded")
        assertTrue(open.expanded >= 0)
        assertTrue(open.actions.any { it.label == "Drop" })
        assertTrue(open.description.isNotEmpty())
    }

    @Test fun commandPrompts() {
        assertTrue(prompt<Prompt.Items>("items").items.isNotEmpty())
        assertTrue(prompt<Prompt.Check>("check").question.contains("retire"))
        assertTrue(prompt<Prompt.Choice>("choice_ignore").options.size >= 2)
        prompt<Prompt.Aim>("aim")
        prompt<Prompt.Direction>("direction")
        val q = prompt<Prompt.Quantity>("quantity")
        assertTrue(q.max >= 2 && q.initial == 1)
        val t = prompt<Prompt.Text>("text")
        assertTrue(t.title.contains("Inscribe") && t.maxBytes > 0)
    }

    @Test fun panelTabs() {
        assertTrue(prompt<Prompt.Character>("character").info.sections.any { it.title == "Skills" })
        assertTrue(prompt<Prompt.Skills>("skills").skills.size == 8)
        val ab = prompt<Prompt.Abilities>("abilities")
        assertEquals(8, ab.skills.size)
        assertTrue(ab.abilities.size > 40)
        val smith = prompt<Prompt.Smith>("smith_main")
        assertEquals("main", smith.view)
        assertEquals(6, smith.options.size)
    }

    @Test fun knowledge() {
        val main = prompt<Prompt.Knowledge>("knowledge_main")
        assertEquals("main", main.view)
        assertTrue(main.rows.count { it is KnowRow.Entry } >= 8)
        val objects = prompt<Prompt.Knowledge>("knowledge_objects")
        assertEquals("objects", objects.view)
        assertTrue(objects.rows.any { it is KnowRow.Group } && objects.rows.any { it is KnowRow.Entry })
        assertEquals("scores", prompt<Prompt.Knowledge>("knowledge_scores").view)
    }

    @Test fun options() = prompt<Prompt.Options>("options_main").let {
        assertEquals("main", it.page)
        assertTrue(it.rows.any { r -> r is OptionRow.Toggle } && it.rows.any { r -> r is OptionRow.Number })
        assertTrue(it.rows.any { r -> r is OptionRow.Link })
    }

    @Test fun infoRecords() {
        val inspect = Parse.info(fixture("inspect.info"))
        assertTrue(inspect is Info.Inspect && inspect.terrain == "Open floor")
        assertTrue(Parse.info(fixture("look.info")) is Info.LookList)
    }

    @Test fun messagesDropSeparators() {
        val m = Parse.messages("1\t1\tYou hit it.\n1\t2\t====\n1\t1\t\n7\t3\tIt dies.")
        assertEquals(listOf("You hit it.", "It dies."), m.map { it.text })
        assertEquals(7, m.last().attr)
    }

    @Test fun unknownOrEmptyInputIsNothing() {
        assertEquals(null, Parse.prompt(""))
        assertEquals(null, Parse.prompt("0\n"))
        assertEquals(null, Parse.info("Nonsense\t1"))
    }
}
