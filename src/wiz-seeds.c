/**
 * \file wiz-seeds.c
 * \brief The seed catalog: what a game's seed puts on each level
 *
 * For every depth and every visit up to a limit, the level the seed would give is generated and its notable
 * contents listed: artefacts, ego and out-of-depth items, forges, greater vaults, uniques and out-of-depth
 * monsters, and what those monsters carry and would drop.  The same seed on the same game, version and data
 * gives the same catalog, and the same levels in play.
 *
 * Levels are generated independently, as for a character who dives straight down: what a particular game
 * changes along the way (artefacts it has found, uniques it has killed, the forge drought) is not tracked.
 *
 * This work is free software; you can redistribute it and/or modify it
 * under the terms of either:
 *
 * a) the GNU General Public License as published by the Free Software
 *    Foundation, version 2, or
 *
 * b) the "Angband licence":
 *    This software may be copied and distributed for educational, research,
 *    and not for profit purposes provided that this copyright and statement
 *    are included in all such copies.  Other copyrights may also apply.
 */
#include "angband.h"
#include "buildid.h"
#include "cave.h"
#include "game-world.h"
#include "gen-seed.h"
#include "generate.h"
#include "init.h"
#include "mon-util.h"
#include "monster.h"
#include "obj-desc.h"
#include "obj-pile.h"
#include "obj-util.h"
#include "player.h"
#include "wizard.h"
#include "z-file.h"

/* How far into the dungeon an item or monster must be out of depth to be listed */
#define OOD_ITEM 3
#define OOD_MONSTER 5

/* The scratch floor a monster's drop is made on */
#define DROP_SIZE 21

static bool notable_object(const struct object *obj, int depth)
{
	return obj->artifact || obj->ego || obj->kind->level >= depth + OOD_ITEM;
}

static bool notable_monster(const struct monster *mon, int depth)
{
	return rf_has(mon->race->flags, RF_UNIQUE) || mon->race->level >= depth + OOD_MONSTER;
}

/**
 * Write an object's full name.  object_desc() describes the player's view of an object, which a freshly made
 * one lacks, so it describes a copy that is given a complete one; the object itself is not touched.
 */
static void put_object(ang_file *f, const char *indent, const struct object *obj)
{
	char name[160];
	struct object view = *obj;
	struct object *known = object_new();

	object_copy(known, obj);
	view.known = known;
	object_desc(name, sizeof(name), &view, ODESC_PREFIX | ODESC_FULL | ODESC_SPOIL, player);
	object_delete(NULL, NULL, &known);
	file_putf(f, "%s%s\n", indent, name);
}

/**
 * What the monster would drop if it died now, made on a scratch floor from its drop seed, and what it carries
 */
static void put_monster_items(ang_file *f, struct chunk *c, const struct monster *mon)
{
	struct monster copy = *mon;
	struct chunk *scratch = cave_new(DROP_SIZE, DROP_SIZE);
	const struct loc centre = loc(DROP_SIZE / 2, DROP_SIZE / 2);

	for (const struct object *obj = mon->held_obj; obj; obj = obj->next) {
		if (!obj->kind) continue;
		file_putf(f, "      carries ");
		put_object(f, "", obj);
	}

	scratch->depth = c->depth;
	for (int y = 0; y < DROP_SIZE; y++)
		for (int x = 0; x < DROP_SIZE; x++)
			square_set_feat(scratch, loc(x, y), FEAT_FLOOR);
	copy.held_obj = NULL;
	copy.grid = centre;
	drop_loot(scratch, &copy, centre, true);
	for (int y = 0; y < DROP_SIZE; y++)
		for (int x = 0; x < DROP_SIZE; x++)
			for (const struct object *obj = square_object(scratch, loc(x, y)); obj; obj = obj->next) {
				file_putf(f, "      drops ");
				put_object(f, "", obj);
			}
	cave_free(scratch);
}

static void put_level(ang_file *f, struct chunk *c, int depth, int visit)
{
	int items = 0, listed = 0, forges = 0;

	file_putf(f, "== %d ft (level %d), visit %d ==\n", depth * 50, depth, visit + 1);

	for (int y = 0; y < c->height; y++)
		for (int x = 0; x < c->width; x++) {
			const struct loc grid = loc(x, y);

			for (const struct object *obj = square_object(c, grid); obj; obj = obj->next) {
				items++;
				if (!notable_object(obj, depth)) continue;
				if (!listed++) file_putf(f, "  Items\n");
				file_putf(f, "    (%d,%d) ", x, y);
				put_object(f, "", obj);
			}
			if (square_isforge(c, grid)) {
				if (!forges++) file_putf(f, "  Features\n");
				file_putf(f, "    forge at (%d,%d)\n", x, y);
			}
		}
	if (c->vault_name && c->vault_name[0]) {
		if (!forges) file_putf(f, "  Features\n");
		file_putf(f, "    greater vault: %s\n", c->vault_name);
	}

	int monsters = 0;
	for (int i = 1; i < cave_monster_max(c); i++) {
		const struct monster *mon = cave_monster(c, i);

		if (!mon->race || !notable_monster(mon, depth)) continue;
		if (!monsters++) file_putf(f, "  Monsters\n");
		file_putf(f, "    %s%s at (%d,%d)\n", mon->race->name,
				  rf_has(mon->race->flags, RF_UNIQUE) ? " (unique)" : "", mon->grid.x, mon->grid.y);
		put_monster_items(f, c, mon);
	}
	file_putf(f, "  (%d items on the floor, %d listed)\n\n", items, listed);
}

/**
 * How many of NarSil-Q's guaranteed forges (rules:forge-depths) a character diving straight down has
 * already been given when reaching this visit: one at the first level at or below 100', 300' and 500'.
 */
static int forges_given(int depth, int visit)
{
	int given = 0;

	for (int t = 2; t <= 10; t += 4) given += visit == 0 ? t < depth : t <= depth;
	return given;
}

void spoil_seeds(const char *fname, uint32_t seed, int max_depth, int visits)
{
	ang_file *f = file_open(fname, MODE_WRITE, FTYPE_TEXT);
	struct gen_baseline *baseline = gen_baseline_save(player);

	if (!f) {
		printf("spoil_seeds: could not write '%s'\n", fname);
		gen_baseline_free(baseline);
		return;
	}
	max_depth = MIN(max_depth, MIN(z_info->max_depth - 1, SEED_DEPTHS - 1));

	file_putf(f, "%s %s seed catalog\n", game_title(), buildver);
	file_putf(f, "Seed %u, levels at 50 ft to %d ft, %d visit%s each\n", seed, max_depth * 50, visits,
			  visits == 1 ? "" : "s");
	file_putf(f, "A seed gives the same levels only on the same game, version and data.  Each level is shown as\n"
				 "for a character diving straight down: artefacts found and uniques killed in a real game\n"
				 "change what later levels hold.\n\n");

	seed_game = seed;
	for (int depth = 1; depth <= max_depth; depth++) {
		for (int visit = 0; visit < visits; visit++) {
			level_discard(player);
			gen_baseline_restore(player, baseline);
			if (z_info->rule_forge_depths) player->fixed_forge_count = forges_given(depth, visit);
			player->depth = depth;
			player->level_visits[depth] = visit;
			player->turn = (depth == 1 && visit == 0) ? 0 : 1;
			prepare_next_level(player);
			put_level(f, cave, depth, visit);
		}
	}
	file_close(f);
	level_discard(player);
	gen_baseline_restore(player, baseline);
	gen_baseline_free(baseline);
}
