package org.narsil.android

import org.narsil.android.GameState.*
import org.narsil.android.NarSilNative as N

/**
 * Reads what the game publishes (the formats documented in narsil_bridge.h and
 * the narsil_*.c files) into the UI's types.  Pure, so the JVM tests in src/test
 * can feed it the harness's real output.
 */
object Parse {
    /** See nb_prompt() in narsil_bridge.h for each kind's layout. */
    fun prompt(raw: String): Prompt? {
        val kind = raw.substringBefore('\n').toIntOrNull() ?: return null
        val lines = raw.substringAfter('\n').split('\n')
        fun fields(line: String, n: Int) = line.split('\t', limit = n)
        return when (kind) {
            N.PROMPT_ITEM -> Prompt.Items(lines[0].trim(), lines.drop(1).filter { it.isNotEmpty() }.mapIndexed { i, line ->
                val f = fields(line, 6)
                Item(i, f[0].toInt(), f[1].first(), f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5])
            })
            N.PROMPT_CHECK -> Prompt.Check(lines.joinToString(" ").trim())
            N.PROMPT_AIM -> {
                val (range, cx, cy) = fields(lines[1], 3).map(String::toInt)
                Prompt.Aim(range, if (cx >= 0) cx to cy else null,
                    lines.drop(2).filter { it.isNotEmpty() }.map { fields(it, 4).let { f -> Target(f[0].toInt(), f[1].toInt(), f[2] == "1", f[3]) } })
            }
            N.PROMPT_CHOICE -> Prompt.Choice(lines[0].trim(), lines.drop(1).filter { it.isNotEmpty() }.mapIndexed { i, l ->
                fields(l, 2).let { f -> Option(i, f[0], f.getOrElse(1) { "" }) }
            })
            N.PROMPT_SKILLS -> Prompt.Skills(fields(lines[0], 2).getOrNull(1)?.toIntOrNull() ?: 0,
                lines.drop(1).filter { it.isNotEmpty() }.mapIndexed { i, l ->
                    fields(l, 5).let { f -> Skill(i, f[0], f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt()) }
                })
            N.PROMPT_SMITH -> {
                val head = lines[0].split('\t')
                val recs = lines.drop(1).filter { it.isNotEmpty() }.map { it.split('\t') }
                fun of(tag: String) = recs.filter { it[0] == tag }
                Prompt.Smith(head.getOrElse(1) { "" }, head.getOrElse(2) { "" },
                    of("#N").map { it.getOrElse(1) { "" } },
                    of("#I").firstOrNull()?.let { f -> SmithItem(f[1], f[2].toInt(), f[3].toInt(), f[4] == "1", f[5].toInt(), f[6].toInt()) },
                    of("#C").map { SmithCost(it[1], it[2].toInt()) },
                    of("#D").map { it.getOrElse(1) { "" } },
                    of("#O").mapIndexed { i, f -> SmithOption(i, f[1].toInt(), f[2], f.getOrElse(3) { "" }) })
            }
            N.PROMPT_TEXT -> Prompt.Text(lines[0], lines.getOrElse(1) { "" }, lines.getOrNull(2)?.toIntOrNull() ?: 19,
                                         lines.getOrNull(3) == "1")
            N.PROMPT_KNOWLEDGE -> {
                val head = lines[0].split('\t')
                var n = 0
                Prompt.Knowledge(head[1], head[2], head[3].toInt(),
                    lines.drop(1).mapNotNull { l ->
                        val f = l.split('\t', limit = 5)
                        when (f[0]) {
                            "#G" -> KnowRow.Group(f[1].replaceFirstChar { it.uppercase() })
                            "#E" -> KnowRow.Entry(n++, f[1].toInt(), f[2].toInt(), f[3], f.getOrElse(4) { "" })
                            else -> null
                        }
                    },
                    lines.filter { it.startsWith("#T\t") }.joinToString("\n") { it.removePrefix("#T\t") }.trim(),
                    lines.filter { it.startsWith("#A\t") }.map { it.split('\t', limit = 3) }.map { f -> f[1].toInt() to f.getOrElse(2) { "" } })
            }
            N.PROMPT_PACK -> {
                val head = lines[0].split('\t')
                val recs = lines.drop(1).filter { it.isNotEmpty() }.map { it.split('\t', limit = 7) }
                Prompt.Pack(head[1].toInt(), head[2].toInt(), head[3].toInt(), head[4].toInt(),
                    recs.filter { it[0] == "#I" }.mapIndexed { i, f -> Item(i, f[1].toInt(), f[2].first(), f[3].toInt(), f[4].toInt(), f[5].toInt(), f[6]) },
                    recs.filter { it[0] == "#A" }.mapIndexed { i, f -> Action(i, f[1] == "1", f[2]) },
                    lines.filter { it.startsWith("#D\t") }.joinToString("\n") { it.removePrefix("#D\t") }.trim(),
                    head.getOrNull(5) == "1")
            }
            N.PROMPT_QUANTITY -> Prompt.Quantity(lines[0], lines.getOrNull(1)?.toIntOrNull() ?: 1, lines.getOrNull(2)?.toIntOrNull() ?: 1)
            N.PROMPT_CHARACTER -> (info(raw.substringAfter('\n')) as? Info.Character)?.let { Prompt.Character(it) }
            N.PROMPT_OPTIONS -> {
                val head = lines[0].split('\t')
                Prompt.Options(head.getOrElse(1) { "" }, head.getOrElse(2) { "" },
                    lines.drop(1).filter { it.isNotEmpty() }.map { it.split('\t') }.mapNotNull { f ->
                        when (f[0]) {
                            "#H" -> OptionRow.Heading(f[1])
                            "#O" -> OptionRow.Toggle(f[1].toInt(), f[2] == "1", f[3] == "1", f[4], f.getOrElse(5) { "" })
                            "#N" -> OptionRow.Number(f[1].toInt(), f[2], f[3].toInt(), f[4].toInt(), f[5].toInt(), f.getOrElse(6) { "" })
                            "#L" -> OptionRow.Link(f[1].toInt(), f[2], f.getOrElse(3) { "" })
                            else -> null
                        }
                    })
            }
            N.PROMPT_SCREEN -> {
                val head = lines[0].split('\t')
                val recs = lines.drop(1).filter { it.isNotEmpty() }.map { it.split('\t') }
                fun of(tag: String) = recs.filter { it[0] == tag }
                val points = of("#P").firstOrNull()
                Prompt.Screen(head.getOrElse(1) { "" }, head.getOrElse(2) { "" },
                    of("#H").map { it.getOrElse(1) { "" } },
                    of("#O").mapIndexed { i, f -> ScreenOption(i, f[1].toInt(), f[2], f.getOrElse(3) { "" }, f.getOrElse(4) { "" }) },
                    of("#S").mapIndexed { i, f -> BirthStat(i, f[1], f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5] == "1", f[6] == "1") },
                    points?.get(1)?.toInt() ?: 0, points?.get(2)?.toInt() ?: 0,
                    of("#K").map { it[1] to it.getOrElse(2) { "" } }, of("#C").map { it[1] to it.getOrElse(2) { "" } },
                    of("#V").map { it.getOrElse(1) { "" } })
            }
            N.PROMPT_ABILITIES -> Prompt.Abilities(fields(lines[0], 2).getOrNull(1)?.toIntOrNull() ?: 0,
                lines[1].split('\t').map { it.split('/') }.map { f -> AbilitySkill(f[0], f.getOrNull(1)?.toIntOrNull() ?: 0, f.getOrNull(2)?.toIntOrNull() ?: 0) },
                lines.drop(2).filter { it.isNotEmpty() }.mapIndexed { i, l ->
                    fields(l, 7).let { f -> Ability(i, f[0].toInt(), f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4], f[5], f.getOrElse(6) { "" }) }
                })
            N.PROMPT_DIRECTION -> Prompt.Direction(lines.getOrNull(1)?.trim() == "1",
                lines.drop(2).filter { it.isNotEmpty() }.map { fields(it, 4).let { f -> Neighbour(f[0].toInt(), f[1].toInt(), f[2].toInt(), f[3]) } })
            else -> null
        }
    }

    /** See nb_request_inspect() in narsil_bridge.h. */
    fun info(raw: String): Info? {
        val lines = raw.split('\n')
        val head = lines.firstOrNull()?.split('\t') ?: return null
        return when (head[0]) {
            "Look" -> Info.LookList(lines.drop(1).filter { it.isNotEmpty() }.map { l ->
                l.split('\t', limit = 3).let { LookEntry(it[0].toInt(), it[1].toInt(), it[2]) }
            })
            "Character" -> Info.Character(buildList {
                lines.drop(1).filter { it.isNotEmpty() }.forEach { l ->
                    val f = l.split('\t')
                    when (f[0]) {
                        "#S" -> add(Section(f.getOrElse(1) { "" }, emptyList(), null))
                        "#T" -> if (isNotEmpty()) set(lastIndex, last().copy(text = f.getOrElse(1) { "" }))
                        else -> if (isNotEmpty() && f.size >= 3) set(lastIndex, last().copy(rows = last().rows +
                            SheetRow(f[0], f[1], f[2].toIntOrNull() ?: 1, f.getOrElse(3) { "" })))
                    }
                }
            })
            "Inspect" -> {
                var monster: Monster? = null
                val objects = mutableListOf<String>()
                var terrain: String? = null
                var i = 1
                while (i < lines.size) {
                    val f = lines[i].split('\t')
                    when (f[0]) {
                        "#M" -> {
                            val body = lines.drop(i + 1).takeWhile { !it.startsWith("#") }
                            monster = Monster(f.getOrElse(1) { "" }, f.getOrElse(2) { "" }, body.joinToString("\n").trim())
                            i += body.size
                        }
                        "#O" -> objects += f.getOrElse(1) { "" }
                        "#F" -> terrain = f.getOrElse(1) { "" }
                    }
                    i++
                }
                Info.Inspect(head[1].toInt(), head[2].toInt(), monster, objects, terrain, head.getOrNull(3) == "1", head.getOrNull(4) == "1")
            }
            else -> null
        }
    }

    /** Drops the textui's blank and "=====" separator lines. */
    fun messages(raw: String): List<Message> = raw.split('\n').mapNotNull { line ->
        val f = line.split('\t', limit = 3)
        val text = f.getOrNull(2)?.trim().orEmpty()
        if (text.isEmpty() || text.all { it == '=' }) null else Message(f[0].toInt(), f[1].toInt(), text)
    }
}
