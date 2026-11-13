/**
 * \file narsil_knowledge.c
 * \brief Native knowledge browser: the most used parts of the knowledge menu
 *        (ui-knowledge.c) as NB_PROMPT_KNOWLEDGE screens: creatures with their
 *        recall, objects, artefacts, special items, runes, terrain, traps,
 *        the character's history and the hall of fame.
 *
 * Serialised (one record per line):
 *   Knowledge \t view \t title \t expanded entry (-1 none)
 *   #G \t group heading
 *   #E \t glyph \t attr \t name \t note           entries, answered by index
 *   #T \t line                                   the expanded entry's text
 * Answers: an entry index (opens a view from the main page, else expands it),
 * NB_KNOW_COLLAPSE, or -1 back.
 */
#include "narsil_internal.h"

#include "angband.h"
#include "init.h"
#include "mon-lore.h"
#include "obj-pile.h"
#include "monster.h"
#include "obj-desc.h"
#include "obj-info.h"
#include "obj-knowledge.h"
#include "obj-make.h"
#include "obj-tval.h"
#include "obj-util.h"
#include "object.h"
#include "player-history.h"
#include "score.h"
#include "trap.h"
#include "ui-history.h"
#include "ui-mon-lore.h"
#include "ui-object.h"
#include "ui-prefs.h"
#include "z-textblock.h"

#include <assert.h>
#include <stdlib.h>
#include <string.h>

enum view { VIEW_MAIN, VIEW_CREATURES, VIEW_OBJECTS, VIEW_ARTEFACTS, VIEW_SPECIALS, VIEW_RUNES, VIEW_FEATURES, VIEW_TRAPS,
	VIEW_HISTORY, VIEW_SCORES, VIEW_COUNT };
static const char *const view_keys[VIEW_COUNT] = { "main", "creatures", "objects", "artefacts", "specials", "runes",
	"features", "traps", "history", "scores" };
static const char *const view_titles[VIEW_COUNT] = { "Knowledge", "Creatures", "Objects", "Artefacts", "Special items",
	"Runes", "Terrain", "Traps and locks", "Character history", "Hall of fame" };

/* An entry: what it is (a race, kind or artefact index), and its group. */
typedef struct { int id; const char *group; int order; } entry;

static void add_text(textblock *tb)
{
	native_prompt_add("#T\t");
	for (const wchar_t *p = textblock_text(tb); p && *p; p++) {
		if (*p == L'\n') { native_prompt_add("\n#T\t"); continue; }
		char mb[8];
		const int k = text_wctomb(mb, *p == L'\t' ? L' ' : *p);
		if (k > 0) { mb[k] = '\0'; native_prompt_add("%s", mb); }
	}
	native_prompt_add("\n");
}

static void add_entry(wchar_t glyph, int attr, const char *name, const char *note)
{
	char n[160], t[160];
	my_strcpy(n, name, sizeof(n));
	my_strcpy(t, note ? note : "", sizeof(t));
	native_sanitise(n);
	native_sanitise(t);
	native_prompt_add("#E\t%d\t%d\t%s\t%s\n", (int)glyph, attr, n, t);
}

static int by_order(const void *a, const void *b)
{
	return ((const entry *)a)->order - ((const entry *)b)->order;
}

static int by_group_then_order(const void *a, const void *b)
{
	const entry *x = a, *y = b;
	const int g = strcmp(x->group, y->group);
	return g ? g : x->order - y->order;
}

/* Lists entries under their group headings; the expanded one gets its text from describe(). */
static void add_grouped(const entry *e, int n, int expanded, void (*row)(const entry *), void (*describe)(const entry *))
{
	const char *group = NULL;
	for (int i = 0; i < n; i++) {
		if (!group || strcmp(group, e[i].group)) native_prompt_add("#G\t%s\n", group = e[i].group);
		row(&e[i]);
		if (i == expanded) describe(&e[i]);
	}
}

/* ---- Creatures ---- */

static int collect_creatures(entry *e, int cap)
{
	int n = 0;
	for (int i = 0; i < z_info->r_max && n < cap; i++) {
		const struct monster_race *race = &r_info[i];
		if (!race->name || (!l_list[i].all_known && !l_list[i].tsights)) continue;
		e[n++] = (entry){ i, race->base && race->base->text ? race->base->text : "Other", race->level };
	}
	qsort(e, (size_t)n, sizeof(*e), by_group_then_order);
	return n;
}

static void creature_row(const entry *e)
{
	const struct monster_race *race = &r_info[e->id];
	const struct monster_lore *lore = &l_list[e->id];
	char note[60];
	if (rf_has(race->flags, RF_UNIQUE)) my_strcpy(note, lore->pkills ? "slain" : "", sizeof(note));
	else strnfmt(note, sizeof(note), lore->pkills ? "%d slain" : "", lore->pkills);
	add_entry(monster_x_char[race->ridx], monster_x_attr[race->ridx], race->name, note);
}

static void creature_text(const entry *e)
{
	const struct monster_race *race = &r_info[e->id];
	textblock *const tb = textblock_new();
	lore_description(tb, race, get_lore(race), false);
	add_text(tb);
	textblock_free(tb);
}

/* ---- Objects ---- */

static int collect_objects(entry *e, int cap)
{
	int n = 0;
	for (int i = 0; i < z_info->k_max && n < cap; i++) {
		const struct object_kind *kind = &k_info[i];
		if (!kind->name || !(kind->everseen || kind->flavor) || kf_has(kind->kind_flags, KF_INSTA_ART)) continue;
		const char *const group = tval_find_name(kind->tval);
		if (!group) continue;
		e[n++] = (entry){ i, group, (kind->aware || !kind->flavor ? 0 : 10000) + kind->sval };
	}
	qsort(e, (size_t)n, sizeof(*e), by_group_then_order);
	return n;
}

static void object_row(const entry *e)
{
	const struct object_kind *kind = &k_info[e->id];
	char name[120];
	object_kind_name(name, sizeof(name), kind, false);
	const bool aware = kind->aware || !kind->flavor;
	if (!aware) my_strcat(name, format(" %s", tval_find_name(kind->tval)), sizeof(name));
	add_entry(object_kind_char(kind), object_kind_attr(kind), name, aware ? "" : kind->tried ? "tried" : "unknown");
}

/* desc_obj_fake() */
static void object_text(const entry *e)
{
	struct object_kind *const kind = &k_info[e->id];
	struct object *obj = object_new(), *known = object_new();
	object_prep(obj, kind, 0, EXTREMIFY);
	if (kind->aware || !kind->flavor) object_copy(known, obj);
	obj->known = known;
	textblock *const tb = object_info(obj, OINFO_FAKE);
	add_text(tb);
	textblock_free(tb);
	object_delete(NULL, NULL, &known);
	object_delete(NULL, NULL, &obj);
}

/* ---- Artefacts ---- */

static int collect_artefacts(entry *e, int cap)
{
	int n = 0;
	for (int i = 0; i < z_info->a_max && n < cap; i++) {
		const struct artifact *art = &a_info[i];
		if (!art->name || !(player->wizard || (is_artifact_created(art) && history_is_artifact_known(player, art)))) continue;
		const char *const group = tval_find_name(art->tval);
		e[n++] = (entry){ i, group ? group : "Other", art->sval };
	}
	qsort(e, (size_t)n, sizeof(*e), by_group_then_order);
	return n;
}

static void artefact_row(const entry *e)
{
	const struct artifact *art = &a_info[e->id];
	const struct object_kind *kind = lookup_kind(art->tval, art->sval);
	char name[160];
	strnfmt(name, sizeof(name), "%s %s", kind ? kind->name : "", art->name);
	add_entry(kind ? object_kind_char(kind) : L'?', kind ? object_kind_attr(kind) : COLOUR_WHITE, name, NULL);
}

/* desc_art_fake(), for an artefact known through the history. */
static void artefact_text(const entry *e)
{
	struct object obj = OBJECT_NULL, known = OBJECT_NULL;
	if (!make_fake_artifact(&obj, &a_info[e->id])) return;
	object_copy(&known, &obj);
	obj.known = &known;
	textblock *const tb = object_info(&obj, OINFO_NONE);
	add_text(tb);
	textblock_free(tb);
	object_wipe(&known);
	object_wipe(&obj);
}

/* ---- Special items (ego kinds) ---- */

static int collect_specials(entry *e, int cap)
{
	int n = 0;
	for (int i = 0; i < z_info->e_max && n < cap; i++) {
		const struct ego_item *ego = &e_info[i];
		if (!ego->name || !ego->everseen) continue;
		const char *group = ego->poss_items ? tval_find_name(k_info[ego->poss_items->kidx].tval) : NULL;
		e[n++] = (entry){ i, group ? group : "Other", i };
	}
	qsort(e, (size_t)n, sizeof(*e), by_group_then_order);
	return n;
}

static void special_row(const entry *e)
{
	const struct ego_item *ego = &e_info[e->id];
	const struct object_kind *kind = ego->poss_items ? &k_info[ego->poss_items->kidx] : NULL;
	add_entry(kind ? object_kind_char(kind) : L'?', kind ? object_kind_attr(kind) : COLOUR_WHITE, ego->name, NULL);
}

static void special_text(const entry *e)
{
	textblock *const tb = object_info_ego(&e_info[e->id]);
	add_text(tb);
	textblock_free(tb);
}

/* ---- Runes ---- */

static const char *const rune_groups[] = { "Combat", "Modifiers", "Resists", "Brands", "Slays", "Curses", "Other" };

static int collect_runes(entry *e, int cap)
{
	int n = 0;
	for (int i = 0; i < max_runes() && n < cap; i++) {
		if (!player_knows_rune(player, (size_t)i)) continue;
		const int v = (int)rune_variety((size_t)i);
		e[n++] = (entry){ i, rune_groups[MIN(v, (int)N_ELEMENTS(rune_groups) - 1)], v * 1000 + i };
	}
	return n;  /* already in variety order, which by_group_then_order would make alphabetical */
}

static void rune_row(const entry *e)
{
	char name[80];
	my_strcpy(name, rune_name((size_t)e->id), sizeof(name));
	my_strcap(name);
	const quark_t note = rune_note((size_t)e->id);
	add_entry(L'*', COLOUR_L_BLUE, name, note ? format("{%s}", quark_str(note)) : NULL);
}

static void rune_text(const entry *e)
{
	textblock *const tb = textblock_new();
	textblock_append(tb, "%s", rune_desc((size_t)e->id));
	add_text(tb);
	textblock_free(tb);
}

/* ---- Terrain and traps ---- */

static const char *const feature_groups[] = { "Floors", "Doors", "Stairs", "Walls", "Obstructions", "Forges", "Pits", "Other" };

/* feat_order() */
static int feature_group(const struct feature *f)
{
	if (tf_has(f->flags, TF_FLOOR)) return 0;
	if (tf_has(f->flags, TF_STAIR) || tf_has(f->flags, TF_SHAFT)) return 2;
	if (tf_has(f->flags, TF_DOOR_ANY)) return 1;
	if (tf_has(f->flags, TF_PIT)) return 6;
	if (tf_has(f->flags, TF_CHASM)) return 4;
	if (tf_has(f->flags, TF_FORGE)) return 5;
	if (tf_has(f->flags, TF_QUARTZ)) return 4;
	if (tf_has(f->flags, TF_WALL)) return 3;
	if (tf_has(f->flags, TF_ROCK)) return 4;
	return 7;
}

static int collect_features(entry *e, int cap)
{
	int n = 0;
	for (int i = FEAT_NONE + 1; i < FEAT_MAX && n < cap; i++) {
		const struct feature *f = &f_info[i];
		if (!f->name || f->mimic) continue;
		const int g = feature_group(f);
		e[n++] = (entry){ i, feature_groups[g], g * 1000 + i };
	}
	qsort(e, (size_t)n, sizeof(*e), by_order);
	return n;
}

static void feature_row(const entry *e)
{
	char name[80];
	my_strcpy(name, f_info[e->id].name, sizeof(name));
	my_strcap(name);
	add_entry(feat_x_char[LIGHTING_LIT][e->id], feat_x_attr[LIGHTING_LIT][e->id], name, NULL);
}

static void feature_text(const entry *e)
{
	textblock *const tb = textblock_new();
	textblock_append(tb, "%s", f_info[e->id].desc ? f_info[e->id].desc : "");
	add_text(tb);
	textblock_free(tb);
}

static const char *const trap_groups[] = { "Runes", "Locks", "Traps", "Other" };

static int collect_traps(entry *e, int cap)
{
	int n = 0;
	for (int g = 0; g < (int)N_ELEMENTS(trap_groups); g++)
		for (int i = 0; i < z_info->trap_max && n < cap; i++) {
			const struct trap_kind *t = &trap_info[i];
			if (!t->name) continue;
			const int tg = trf_has(t->flags, TRF_GLYPH) ? 0 : trf_has(t->flags, TRF_LOCK) ? 1 : trf_has(t->flags, TRF_TRAP) ? 2 : 3;
			if (tg == g) e[n++] = (entry){ i, trap_groups[g], i };
		}
	return n;
}

static void trap_row(const entry *e)
{
	const struct trap_kind *t = &trap_info[e->id];
	char name[80];
	my_strcpy(name, t->desc ? t->desc : t->name, sizeof(name));
	my_strcap(name);
	add_entry(trap_x_char[LIGHTING_LIT][e->id], trap_x_attr[LIGHTING_LIT][e->id], name, NULL);
}

static void trap_text(const entry *e)
{
	textblock *const tb = textblock_new();
	textblock_append(tb, "%s", trap_info[e->id].text ? trap_info[e->id].text : "");
	add_text(tb);
	textblock_free(tb);
}

/* ---- Hall of fame ---- */

static void add_scores(void)
{
	static struct high_score scores[MAX_HISCORES];
	const size_t n = highscore_read(scores, N_ELEMENTS(scores));
	for (size_t i = 0; i < n; i++) {
		const struct high_score *h = &scores[i];
		const struct player_race *race = player_id2race(atoi(h->p_r));
		const struct player_house *house = player_id2house(atoi(h->p_h));
		const bool escaped = h->escaped[0] == 't';
		char name[160], note[60];
		strnfmt(name, sizeof(name), "%s, %s%s%s — %s %s at %d ft", h->who, race ? race->name : "", house ? " of " : "",
			house ? house->short_name : "", escaped ? "escaped," : "killed by", escaped ? "" : h->how, atoi(h->max_dun) * 50);
		strnfmt(note, sizeof(note), "%d pts", atoi(h->pts));
		add_entry(L'0' + (wchar_t)MIN(i + 1, 9), i == 0 ? COLOUR_YELLOW : COLOUR_WHITE, name, note);
	}
}

/* ---- Views ---- */

static void publish(enum view v, int expanded)
{
	native_prompt_begin();
	native_prompt_add("Knowledge\t%s\t%s\t%d\n", view_keys[v], view_titles[v], expanded);
	enum { CAP = 2048 };
	static entry e[CAP];
	switch (v) {
		case VIEW_MAIN:
			add_entry(L'C', COLOUR_L_GREEN, "Creatures", "everything you have seen, with what you have learnt");
			add_entry(L'!', COLOUR_L_BLUE, "Objects", "kinds of item, and which flavours you know");
			add_entry(L'|', COLOUR_YELLOW, "Artefacts", "the ones you have found");
			add_entry(L']', COLOUR_L_UMBER, "Special items", "the kinds of special item you have seen");
			add_entry(L'*', COLOUR_L_BLUE, "Runes", "the properties you can recognise");
			add_entry(L'#', COLOUR_SLATE, "Terrain", "what the dungeon is made of");
			add_entry(L'^', COLOUR_RED, "Traps and locks", "and glyphs");
			add_entry(L'@', COLOUR_WHITE, "Character history", "the story so far");
			add_entry(L'0', COLOUR_YELLOW, "Hall of fame", "past characters");
			break;
		case VIEW_CREATURES: add_grouped(e, collect_creatures(e, CAP), expanded, creature_row, creature_text); break;
		case VIEW_OBJECTS: add_grouped(e, collect_objects(e, CAP), expanded, object_row, object_text); break;
		case VIEW_ARTEFACTS: add_grouped(e, collect_artefacts(e, CAP), expanded, artefact_row, artefact_text); break;
		case VIEW_SPECIALS: add_grouped(e, collect_specials(e, CAP), expanded, special_row, special_text); break;
		case VIEW_RUNES: add_grouped(e, collect_runes(e, CAP), expanded, rune_row, rune_text); break;
		case VIEW_FEATURES: add_grouped(e, collect_features(e, CAP), expanded, feature_row, feature_text); break;
		case VIEW_TRAPS: add_grouped(e, collect_traps(e, CAP), expanded, trap_row, trap_text); break;
		case VIEW_SCORES: add_scores(); break;
		case VIEW_HISTORY: {
			struct history_info *h = NULL;
			const size_t n = history_get_list(player, &h);
			for (size_t i = 0; i < n; i++) {
				char when[60];
				strnfmt(when, sizeof(when), "%d ft · turn %d", h[i].dlev * 50, h[i].turn);
				add_entry(L'·', COLOUR_SLATE, h[i].event, when);
			}
			break;
		}
		default: assert(false);
	}
	native_prompt_end();
}

static void browse(enum view v)
{
	int expanded = -1;
	for (;;) {
		publish(v, expanded);
		const int32_t a = native_ask(NB_PROMPT_KNOWLEDGE);
		if (a < 0) return;
		expanded = a == NB_KNOW_COLLAPSE || a == expanded || v == VIEW_HISTORY || v == VIEW_SCORES ? -1 : a;
	}
}

void native_knowledge(void)
{
	for (;;) {
		publish(VIEW_MAIN, -1);
		const int32_t a = native_ask(NB_PROMPT_KNOWLEDGE);
		if (a < 0) return;
		if (a + 1 < VIEW_COUNT) browse((enum view)(a + 1));
	}
}
