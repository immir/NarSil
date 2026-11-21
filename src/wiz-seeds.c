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
 * Each seed ends with provisos for the uniques ("bosses"): what the other levels look like if one of them
 * has been killed (and so its loot, artefacts included, has been made).  They are exact: the other levels
 * are generated again with that state.
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
#include "obj-chest.h"
#include "obj-desc.h"
#include "obj-properties.h"
#include "obj-slays.h"
#include "project.h"
#include "player-abilities.h"
#include "obj-pile.h"
#include "obj-tval.h"
#include "obj-util.h"
#include "player.h"
#include "wizard.h"
#include "z-file.h"
#include "z-virt.h"

#include <stdarg.h>

/* How far into the dungeon an item or monster must be out of depth to be listed */
#define OOD_ITEM 3
#define OOD_MONSTER 5

/* The scratch floor a monster's drop is made on */
#define DROP_SIZE 21

/* The most artefacts, notable names or sites kept for one thing */
#define MAX_ARTS 16
#define MAX_NAMES 48
#define MAX_SITES 512

static bool notable_object(const struct object *obj, int depth)
{
	return obj->artifact || obj->ego || obj->kind->level >= depth + OOD_ITEM;
}

static bool notable_monster(const struct monster *mon, int depth)
{
	return rf_has(mon->race->flags, RF_UNIQUE) || mon->race->level >= depth + OOD_MONSTER;
}

/* ------------------------------------------------------------------------
 * Small text containers
 * ------------------------------------------------------------------------ */

/* The lines describing one level */
struct text {
	char **line;
	int n, cap;
	uint32_t layout;  /* a hash of the level's terrain (levels only) */
};

static void text_add(struct text *t, const char *fmt, ...)
{
	char buf[2048];
	va_list vp;

	va_start(vp, fmt);
	(void)vstrnfmt(buf, sizeof(buf), fmt, vp);
	va_end(vp);
	if (t->n == t->cap) {
		t->cap = t->cap ? 2 * t->cap : 16;
		t->line = mem_realloc(t->line, (size_t)t->cap * sizeof(*t->line));
	}
	t->line[t->n++] = string_make(buf);
}

static void text_free(struct text *t)
{
	for (int i = 0; i < t->n; i++) string_free(t->line[i]);
	mem_free(t->line);
	*t = (struct text){ 0 };
}

static bool text_has(const struct text *t, const char *line)
{
	for (int i = 0; i < t->n; i++) if (streq(t->line[i], line)) return true;
	return false;
}

/* Object names, joined with "; " by join_names() */
struct names {
	char *name[MAX_NAMES];
	int n;
};

static void names_add(struct names *l, const char *name)
{
	if (l->n < MAX_NAMES) l->name[l->n++] = string_make(name);
}

static void names_free(struct names *l)
{
	for (int i = 0; i < l->n; i++) string_free(l->name[i]);
	l->n = 0;
}

/* "a; b (x3); c": equal names next to each other are counted */
static void join_names(const struct names *l, char *buf, size_t size)
{
	buf[0] = '\0';
	for (int i = 0, j; i < l->n; i = j) {
		for (j = i; j < l->n && streq(l->name[j], l->name[i]); j++) ;
		if (i) my_strcat(buf, "; ", size);
		my_strcat(buf, l->name[i], size);
		if (j - i > 1) {
			char count[16];

			strnfmt(count, sizeof(count), " (x%d)", j - i);
			my_strcat(buf, count, size);
		}
	}
}

/**
 * Write an object's full name.  object_desc() describes the player's view of an object, which a freshly made
 * one lacks, so it describes a copy that is given a complete one; the object itself is not touched.
 */
static void object_name(char *name, size_t size, const struct object *obj)
{
	struct object view = *obj;
	struct object *known = object_new();

	object_copy(known, obj);
	view.known = known;
	object_desc(name, size, &view, ODESC_PREFIX | ODESC_FULL | ODESC_SPOIL, player);
	object_delete(NULL, NULL, &known);
}


/* Adds ", " and a trait to a list */
static void add_trait(char *buf, size_t size, const char *fmt, ...)
{
	char one[96];
	va_list vp;

	va_start(vp, fmt);
	(void)vstrnfmt(one, sizeof(one), fmt, vp);
	va_end(vp);
	if (buf[0]) my_strcat(buf, ", ", size);
	my_strcat(buf, one, size);
}

/**
 * What an object has that makes it better or worse than its plain kind: the modifiers it gives, flags (a sustain, free
 * action, a curse), resists and vulnerabilities, slays, brands and abilities, split into the good and the bad.
 * What the base kind has anyway (a lantern's light) is not repeated.
 */
static void object_traits(const struct object *obj, char *pros, char *cons, size_t size)
{
	pros[0] = cons[0] = '\0';
	for (int i = 0; i < OBJ_MOD_MAX; i++) {
		const struct obj_property *prop = lookup_obj_property(OBJ_PROPERTY_MOD, i);
		const random_value *base = &obj->kind->modifiers[i];
		const bool fixed = !base->dice && !base->sides && !base->m_bonus;
		const int v = obj->modifiers[i] - (fixed ? base->base : 0);

		if (!v || !prop || !prop->name || i == OBJ_MOD_DAMAGE_SIDES) continue;
		add_trait(v > 0 ? pros : cons, size, "%+d %s", v, prop->name);
	}
	for (int f = of_next(obj->flags, FLAG_START); f != FLAG_END; f = of_next(obj->flags, f + 1)) {
		const struct obj_property *prop = lookup_obj_property(OBJ_PROPERTY_FLAG, f);

		if (!prop || !prop->name || !prop->name[0] || of_has(obj->kind->flags, f)) continue;
		if (prop->subtype == OFT_BAD) add_trait(cons, size, "%s", prop->name);
		else if (prop->subtype != OFT_BASIC && prop->subtype != OFT_NONE) add_trait(pros, size, "%s", prop->name);
	}
	for (int i = 0; i < ELEM_MAX; i++) {
		const int level = obj->el_info[i].res_level, kind_level = obj->kind->el_info[i].res_level;

		if (level == kind_level) continue;
		if (level >= 3) add_trait(pros, size, "immune %s", projections[i].name);
		else if (level > 0) add_trait(pros, size, "resist %s", projections[i].name);
		else if (level < 0) add_trait(cons, size, "vulnerable to %s", projections[i].name);
	}
	char slaying[160] = "", branding[160] = "";
	for (int i = 1; i < z_info->slay_max; i++)
		if (obj->slays && obj->slays[i] && !(obj->kind->slays && obj->kind->slays[i])) {
			if (slaying[0]) my_strcat(slaying, "/", sizeof(slaying));
			my_strcat(slaying, slays[i].name, sizeof(slaying));
		}
	for (int i = 1; i < z_info->brand_max; i++)
		if (obj->brands && obj->brands[i] && !(obj->kind->brands && obj->kind->brands[i])) {
			if (branding[0]) my_strcat(branding, "/", sizeof(branding));
			my_strcat(branding, brands[i].name, sizeof(branding));
		}
	if (slaying[0]) add_trait(pros, size, "slays %s", slaying);
	if (branding[0]) add_trait(pros, size, "brand: %s", branding);
	for (const struct ability *a = obj->abilities; a; a = a->next)
		if (!obj->kind->abilities || !locate_ability(obj->kind->abilities, (struct ability *)a)) add_trait(pros, size, "%s", a->name);
}

/**
 * An object's full name, then what is good and bad about it: "a Hauberk of Blight (-2) [-4,2d5] <+2> = +2 stealth;
 * bad: hunger".
 */
static void object_line(char *line, size_t size, const struct object *obj)
{
	char pros[256], cons[256];

	object_name(line, size, obj);
	object_traits(obj, pros, cons, sizeof(pros));
	if (!pros[0] && !cons[0]) return;
	my_strcat(line, " = ", size);
	my_strcat(line, pros, size);
	if (pros[0] && cons[0]) my_strcat(line, "; ", size);
	if (cons[0]) {
		my_strcat(line, "bad: ", size);
		my_strcat(line, cons, size);
	}
}

/* A scratch floor to make drops on */
static struct chunk *scratch_floor(int depth)
{
	struct chunk *scratch = cave_new(DROP_SIZE, DROP_SIZE);

	scratch->depth = depth;
	for (int y = 0; y < DROP_SIZE; y++)
		for (int x = 0; x < DROP_SIZE; x++)
			square_set_feat(scratch, loc(x, y), FEAT_FLOOR);
	return scratch;
}

/* ------------------------------------------------------------------------
 * One level as text
 * ------------------------------------------------------------------------ */

/* A unique met on a level, and the artefacts in its loot: killing it makes those too */
struct site {
	const struct monster_race *race;
	int depth, visit;
	const struct artifact *art[MAX_ARTS];
	int n_art;
};

static void note_artefact(struct site *site, const struct object *obj)
{
	if (!site || !obj->artifact || site->n_art >= MAX_ARTS) return;
	site->art[site->n_art++] = obj->artifact;
}

/**
 * What the monster carries and what it would drop if it died now: made on a scratch floor from its drop seed.
 * The artefacts among them are noted in site.
 */
static void monster_loot(struct chunk *c, const struct monster *mon, struct names *carries, struct names *loot,
						 struct site *site)
{
	char name[160];
	struct monster copy = *mon;
	struct chunk *scratch = scratch_floor(c->depth);
	const struct loc centre = loc(DROP_SIZE / 2, DROP_SIZE / 2);

	for (const struct object *obj = mon->held_obj; obj; obj = obj->next) {
		if (!obj->kind) continue;
		object_line(name, sizeof(name), obj);
		names_add(carries, name);
		note_artefact(site, obj);
	}
	copy.held_obj = NULL;
	copy.grid = centre;
	drop_loot(scratch, &copy, centre, true);
	for (int y = 0; y < DROP_SIZE; y++)
		for (int x = 0; x < DROP_SIZE; x++)
			for (const struct object *obj = square_object(scratch, loc(x, y)); obj; obj = obj->next) {
				object_line(name, sizeof(name), obj);
				names_add(loot, name);
				note_artefact(site, obj);
			}
	cave_free(scratch);
}

/**
 * What a chest would hold if it were opened: made on a scratch floor from the chest's contents seed, which is the
 * same whenever it is opened.  Only what is notable is kept.
 */
static void chest_notables(const struct object *chest, int depth, struct names *held)
{
	char name[160];
	struct object copy = *chest;
	struct object *known = object_new();
	struct chunk *scratch = scratch_floor(depth);
	const struct loc centre = loc(DROP_SIZE / 2, DROP_SIZE / 2);

	object_copy(known, chest);
	copy.known = known;
	chest_make_contents(scratch, centre, &copy, chest->contents_seed);
	for (int y = 0; y < DROP_SIZE; y++)
		for (int x = 0; x < DROP_SIZE; x++)
			for (const struct object *obj = square_object(scratch, loc(x, y)); obj; obj = obj->next) {
				if (!notable_object(obj, depth)) continue;
				object_line(name, sizeof(name), obj);
				names_add(held, name);
			}
	object_delete(NULL, NULL, &known);
	cave_free(scratch);
}

/**
 * The notable contents of a level, one line each: items, chests (what is in them), forges, a greater vault, and
 * uniques ("boss") and out-of-depth monsters ("mon") with their loot.  The uniques are noted in sites.
 */
static void level_text(struct chunk *c, int depth, int visit, struct text *t, struct site *sites, int *n_sites)
{
	char name[160], carried[1024], dropped[1536];

	t->layout = 2166136261u;
	for (int y = 0; y < c->height; y++)
		for (int x = 0; x < c->width; x++)
			t->layout = (t->layout ^ (uint32_t)square(c, loc(x, y))->feat) * 16777619u;
	for (int y = 0; y < c->height; y++)
		for (int x = 0; x < c->width; x++)
			for (const struct object *obj = square_object(c, loc(x, y)); obj; obj = obj->next) {
				if (tval_is_chest(obj) && obj->pval != 0) {
					/* what is inside counts, whether or not the chest is itself notable */
					struct names held = { { NULL }, 0 };

					chest_notables(obj, depth, &held);
					if (held.n) {
						join_names(&held, dropped, sizeof(dropped));
						text_add(t, "chest (%d,%d): %s", x, y, dropped);
					} else if (notable_object(obj, depth)) {
						object_line(name, sizeof(name), obj);
						text_add(t, "item (%d,%d) %s", x, y, name);
					}
					names_free(&held);
					continue;
				}
				if (!notable_object(obj, depth)) continue;
				object_line(name, sizeof(name), obj);
				text_add(t, "item (%d,%d) %s", x, y, name);
			}
	for (int y = 0; y < c->height; y++)
		for (int x = 0; x < c->width; x++)
			if (square_isforge(c, loc(x, y))) text_add(t, "forge (%d,%d)", x, y);
	if (c->vault_name && c->vault_name[0]) text_add(t, "vault %s", c->vault_name);

	for (int i = 1; i < cave_monster_max(c); i++) {
		const struct monster *mon = cave_monster(c, i);
		struct names carries = { { NULL }, 0 }, loot = { { NULL }, 0 };
		const bool unique = mon->race && rf_has(mon->race->flags, RF_UNIQUE);
		struct site *site = NULL;

		if (!mon->race || !notable_monster(mon, depth)) continue;
		if (unique && sites && *n_sites < MAX_SITES) {
			site = &sites[(*n_sites)++];
			*site = (struct site){ .race = mon->race, .depth = depth, .visit = visit };
		}
		monster_loot(c, mon, &carries, &loot, site);
		join_names(&carries, carried, sizeof(carried));
		join_names(&loot, dropped, sizeof(dropped));
		names_free(&carries);
		names_free(&loot);
		text_add(t, "%s %s (%d,%d)%s%s%s%s%s", unique ? "boss" : "mon", mon->race->name, mon->grid.x, mon->grid.y,
				 carried[0] || dropped[0] ? ":" : "",
				 carried[0] ? " carries " : "", carried,
				 dropped[0] ? (carried[0] ? ". Drops " : " drops ") : "", dropped);
	}
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

/**
 * Generate the level for a depth and visit of the current seed and describe it.  With `killed`, the character has
 * already killed that unique: it is dead, and the artefacts in its loot have been made.
 */
static void make_level(const struct gen_baseline *baseline, int depth, int visit, const struct site *killed,
					   struct text *t, struct site *sites, int *n_sites)
{
	level_discard(player);
	gen_baseline_restore(player, baseline);
	if (killed) {
		r_info[killed->race->ridx].max_num = 0;
		for (int i = 0; i < killed->n_art; i++) mark_artifact_created(killed->art[i], true);
	}
	if (z_info->rule_forge_depths) player->fixed_forge_count = forges_given(depth, visit);
	player->depth = depth;
	player->level_visits[depth] = visit;
	player->turn = (depth == 1 && visit == 0) ? 0 : 1;
	prepare_next_level(player);
	level_text(cave, depth, visit, t, sites, n_sites);
}

/* ------------------------------------------------------------------------
 * A seed
 * ------------------------------------------------------------------------ */

/* Every level of a seed, depth 1 up and visit 1 up, as text */
struct seed_levels {
	struct text *level;
	int depths, visits;
};

static struct text *level_at(const struct seed_levels *s, int depth, int visit)
{
	return &s->level[(depth - 1) * s->visits + visit];
}

static void levels_free(struct seed_levels *s)
{
	for (int i = 0; i < s->depths * s->visits; i++) text_free(&s->level[i]);
	mem_free(s->level);
	*s = (struct seed_levels){ 0 };
}

static void put_level_text(ang_file *f, int depth, int visit, const struct text *t, const char *indent)
{
	file_putf(f, "%s%d ft v%d\n", indent, depth * 50, visit + 1);
	for (int i = 0; i < t->n; i++) file_putf(f, "%s  %s\n", indent, t->line[i]);
}

/* Two uniques' kills make the same changes if they are the same unique with the same artefacts in its loot */
static bool same_kill(const struct site *a, const struct site *b)
{
	if (a->race != b->race || a->n_art != b->n_art) return false;
	for (int i = 0; i < a->n_art; i++) if (a->art[i] != b->art[i]) return false;
	return true;
}

/* The levels as they are once a unique has been killed */
struct variant {
	struct site kill;
	struct seed_levels levels;
};

/* Whether a line describes the boss `name` */
static bool is_boss_line(const char *line, const char *name)
{
	const size_t n = strlen(name);

	return !strncmp(line, "boss ", 5) && !strncmp(line + 5, name, n) && line[5 + n] == ' ';
}

/**
 * What changes elsewhere if the unique met at `site` is killed there, against the levels as listed: for each
 * level, the lines that are gone (-) and the lines that are new (+), or, if the terrain itself is different
 * (a room that needs living uniques is made differently), the whole level as it then is.  The unique's own
 * appearances, which are gone, are not repeated.
 */
static void build_changes(const struct seed_levels *base, const struct variant *v, const struct site *site, struct text *out)
{
	for (int depth = 1; depth <= base->depths; depth++)
		for (int visit = 0; visit < base->visits; visit++) {
			const struct text *was = level_at(base, depth, visit), *now = level_at(&v->levels, depth, visit);
			const int mark = out->n;

			if (depth == site->depth && visit == site->visit) continue;
			if (was->layout != now->layout) {
				text_add(out, "%d ft v%d: different terrain, now", depth * 50, visit + 1);
				for (int i = 0; i < now->n; i++) text_add(out, "  %s", now->line[i]);
				if (!now->n) text_add(out, "  (nothing notable)");
				continue;
			}
			for (int i = 0; i < was->n; i++)
				if (!text_has(now, was->line[i]) && !is_boss_line(was->line[i], site->race->name)) {
					if (out->n == mark) text_add(out, "%d ft v%d", depth * 50, visit + 1);
					text_add(out, "  - %s", was->line[i]);
				}
			for (int i = 0; i < now->n; i++)
				if (!text_has(was, now->line[i])) {
					if (out->n == mark) text_add(out, "%d ft v%d", depth * 50, visit + 1);
					text_add(out, "  + %s", now->line[i]);
				}
		}
}

static bool same_text(const struct text *a, const struct text *b)
{
	if (a->n != b->n) return false;
	for (int i = 0; i < a->n; i++) if (!streq(a->line[i], b->line[i])) return false;
	return true;
}

/**
 * The provisos for a seed: for each unique met on a level, what the other levels become once it has been killed
 * (at any one of the levels it is met on), and whatever artefacts it carried or dropped have been made.
 */
static void put_provisos(ang_file *f, const struct gen_baseline *baseline, const struct seed_levels *base,
						 const struct site *sites, int n_sites)
{
	struct variant *variants = mem_zalloc((size_t)n_sites * sizeof(*variants));
	struct text *changes = mem_zalloc((size_t)n_sites * sizeof(*changes));
	int *variant_of = mem_zalloc((size_t)n_sites * sizeof(*variant_of));
	bool *done = mem_zalloc((size_t)n_sites * sizeof(*done));
	int n_variants = 0;
	bool heading = false;

	/* Each distinct kill is generated once */
	for (int s = 0; s < n_sites; s++) {
		int v;

		for (v = 0; v < n_variants; v++) if (same_kill(&variants[v].kill, &sites[s])) break;
		if (v == n_variants) {
			struct variant *nv = &variants[n_variants++];

			nv->kill = sites[s];
			nv->levels = (struct seed_levels){ .level = mem_zalloc((size_t)base->depths * base->visits * sizeof(struct text)),
											   .depths = base->depths, .visits = base->visits };
			for (int depth = 1; depth <= base->depths; depth++)
				for (int visit = 0; visit < base->visits; visit++)
					make_level(baseline, depth, visit, &sites[s], level_at(&nv->levels, depth, visit), NULL, NULL);
		}
		variant_of[s] = v;
		build_changes(base, &variants[v], &sites[s], &changes[s]);
	}

	/* By unique; kills with the same consequences are shown together */
	for (int s = 0; s < n_sites; s++) {
		bool any = false;

		if (done[s]) continue;
		for (int t = s; t < n_sites; t++) any = any || (sites[t].race == sites[s].race && changes[t].n);
		for (int t = s; t < n_sites; t++) if (sites[t].race == sites[s].race) done[t] = true;
		if (!any) continue;
		if (!heading) file_putf(f, "  If a boss is killed earlier (what it carries and drops made too), other levels change:\n");
		heading = true;
		file_putf(f, "    %s, met at", sites[s].race->name);
		for (int t = s, k = 0; t < n_sites; t++)
			if (sites[t].race == sites[s].race) file_putf(f, "%s %d ft v%d", k++ ? "," : "", sites[t].depth * 50, sites[t].visit + 1);
		file_putf(f, "; it is then gone from the others\n");
		for (int t = s; t < n_sites; t++) {
			bool listed = false;

			if (sites[t].race != sites[s].race || !changes[t].n) continue;
			for (int u = s; u < t; u++) listed = listed || (sites[u].race == sites[s].race && same_text(&changes[u], &changes[t]));
			if (listed) continue;
			file_putf(f, "      killed at %d ft v%d", sites[t].depth * 50, sites[t].visit + 1);
			for (int u = t + 1; u < n_sites; u++)
				if (sites[u].race == sites[s].race && same_text(&changes[u], &changes[t]))
					file_putf(f, " or %d ft v%d", sites[u].depth * 50, sites[u].visit + 1);
			file_putf(f, ":\n");
			for (int i = 0; i < changes[t].n; i++) file_putf(f, "        %s\n", changes[t].line[i]);
		}
	}

	for (int v = 0; v < n_variants; v++) levels_free(&variants[v].levels);
	for (int s = 0; s < n_sites; s++) text_free(&changes[s]);
	mem_free(variants);
	mem_free(changes);
	mem_free(variant_of);
	mem_free(done);
}

/**
 * Write the catalog for the seeds first_seed to last_seed, one section each.
 */
void spoil_seeds(const char *fname, uint32_t first_seed, uint32_t last_seed, int max_depth, int visits, bool provisos)
{
	ang_file *f = file_open(fname, MODE_WRITE, FTYPE_TEXT);
	struct gen_baseline *baseline = gen_baseline_save(player);
	struct site *sites = mem_zalloc(MAX_SITES * sizeof(*sites));

	assert(first_seed >= 1 && first_seed <= last_seed);
	if (!f) {
		printf("spoil_seeds: could not write '%s'\n", fname);
		gen_baseline_free(baseline);
		mem_free(sites);
		return;
	}
	max_depth = MIN(max_depth, MIN(z_info->max_depth - 1, SEED_DEPTHS - 1));

	file_putf(f, "%s %s seed catalog\n", game_title(), buildver);
	if (first_seed == last_seed) {
		file_putf(f, "Seed %u", first_seed);
	} else {
		file_putf(f, "Seeds %u to %u", first_seed, last_seed);
	}
	file_putf(f, ", levels at 50 ft to %d ft, %d visit%s each (v1 is the first level made at a depth, v2 the second, ...)\n",
			  max_depth * 50, visits, visits == 1 ? "" : "s");
	file_putf(f, "A seed gives the same levels only on the same game, version and data.  Each level is listed as for\n"
				 "a character diving straight down, with nothing found yet and no unique dead; levels with nothing\n"
				 "notable are left out.  An artefact can exist once per game, so the first one you see is the real one.\n"
				 "  item (x,y)   an artefact, ego item or out-of-depth item      forge (x,y)   a forge\n"
				 "  chest (x,y)  what is in a chest (only the notable things)    vault NAME    a greater vault\n"
				 "  boss NAME    a unique, with what it carries and drops          mon NAME      an out-of-depth monster\n"
				 "After \" = \": what the item gives you (modifiers, sustains, resists, slays, abilities); \"bad:\" its drawbacks.\n");
	if (provisos)
		file_putf(f, "Under \"If a boss is killed earlier\": the other levels of that seed generated again with that unique dead\n"
					 "and its loot (artefacts included) already made; - is what is gone, + what is new.\n");
	file_putf(f, "\n");

	for (uint32_t seed = first_seed; seed <= last_seed; seed++) {
		struct seed_levels levels = { .level = mem_zalloc((size_t)max_depth * visits * sizeof(struct text)),
									  .depths = max_depth, .visits = visits };
		int n_sites = 0;

		seed_game = seed;
		for (int depth = 1; depth <= max_depth; depth++)
			for (int visit = 0; visit < visits; visit++)
				make_level(baseline, depth, visit, NULL, level_at(&levels, depth, visit), sites, &n_sites);

		file_putf(f, "######## Seed %u\n", seed);
		for (int depth = 1; depth <= max_depth; depth++)
			for (int visit = 0; visit < visits; visit++) {
				const struct text *t = level_at(&levels, depth, visit);

				if (t->n) put_level_text(f, depth, visit, t, "");
			}
		if (provisos && n_sites) put_provisos(f, baseline, &levels, sites, n_sites);
		file_putf(f, "\n");
		levels_free(&levels);
	}
	file_close(f);
	level_discard(player);
	gen_baseline_restore(player, baseline);
	gen_baseline_free(baseline);
	mem_free(sites);
}
