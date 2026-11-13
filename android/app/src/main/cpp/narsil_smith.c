/**
 * \file narsil_smith.c
 * \brief Native smithing: textui_smith_object() (ui-smith.c) with each of its
 *        menus published as an NB_PROMPT_SMITH view.
 *
 * The design state, its bookkeeping (the special bonus "pval" kept apart from
 * the object and folded in only for calculations) and every rule come from
 * ui-smith.c and obj-smith.c; only the presentation differs.  Where the textui
 * previews the entry under the cursor, the native views state each option's
 * difficulty and affordability up front, and choosing applies it.
 *
 * Serialised view (one record per line):
 *   Smith \t view \t title         view: main tval sval special artefact property ability numbers melt
 *   #N \t note                     zero or more
 *   #I \t name \t difficulty \t max \t drains \t turns \t turns left    (only once a base item is chosen)
 *   #C \t cost \t state            0 short, 1 affordable, 2 needs an ability, 3 drains smithing
 *   #D \t text                     the item's description, a line each
 *   #O \t state \t label \t detail options, answered by index (-1 = back)
 * Option states: 0 unavailable, 1 available, 2 needs an ability (still
 * selectable, as in the textui), 3 chosen (toggles), 4 not affordable, 5 needs Artistry.
 */
#include "narsil_internal.h"

#include "angband.h"
#include "cave.h"
#include "game-input.h"
#include "init.h"
#include "obj-desc.h"
#include "obj-info.h"
#include "obj-knowledge.h"
#include "obj-pile.h"
#include "obj-smith.h"
#include "obj-tval.h"
#include "obj-util.h"
#include "object.h"
#include "player-abilities.h"
#include "player-calcs.h"
#include "trap.h"
#include "ui-options.h"
#include "z-textblock.h"

#include <assert.h>
#include <string.h>

enum opt_state { OPT_OFF = 0, OPT_ON = 1, OPT_MAYBE = 2, OPT_CHOSEN = 3, OPT_DEAR = 4, OPT_ARTISTRY = 5 };

/* ------------------------------------------------------------------------
 * The design, as ui-smith.c keeps it
 * ------------------------------------------------------------------------ */

static struct object smith_obj_body, smith_obj_known_body, smith_obj_backup_body;
static struct object *const smith_obj = &smith_obj_body;
static struct object *const smith_obj_known = &smith_obj_known_body;
static struct object *const smith_obj_backup = &smith_obj_backup_body;
static struct artifact smith_art_body;
static struct artifact *const smith_art = &smith_art_body;
static char smith_art_name[50];
static struct smithing_cost current_cost;
static bool numbers_changed;
static int pval;
static bool pval_included;

/* include_pval()/exclude_pval(): note that both act on smith_obj's backup, whatever obj is. */
static void include_pval(struct object *obj)
{
	if (!pval_included) {
		object_wipe(smith_obj_backup);
		object_copy(smith_obj_backup, smith_obj);
	}
	if (pval_valid(obj)) {
		obj->pval = pval;
		for (int i = 0; i < OBJ_MOD_MAX; i++)
			if (obj->modifiers[i]) {
				obj->modifiers[i] = obj->modifiers[i] < 0 ? -pval : pval;
				obj->known->modifiers[i] = obj->modifiers[i];
			}
	}
	pval_included = true;
}

static void exclude_pval(void)
{
	if (pval_included) {
		object_wipe(smith_obj);
		object_copy(smith_obj, smith_obj_backup);
	}
	pval_included = false;
}

static void wipe_smithing_objects(void)
{
	object_wipe(smith_obj);
	object_wipe(smith_obj_backup);
	mem_free(smith_art->slays);
	mem_free(smith_art->brands);
	release_ability_list(smith_art->abilities);
	memset(smith_art, 0, sizeof(*smith_art));
}

static void know_smith_obj(void)
{
	object_wipe(smith_obj_known);
	object_copy(smith_obj_known, smith_obj);
	smith_obj_known->known = NULL;
	smith_obj->known = smith_obj_known;
}

static void reset_smithing_objects(struct object_kind *kind)
{
	wipe_smithing_objects();
	create_base_object(kind, smith_obj);
	know_smith_obj();
	object_copy(smith_obj_backup, smith_obj);
	pval = pval_valid(smith_obj) ? pval_default(smith_obj) : 0;
}

/* The design's difficulty, refreshing current_cost; true if it can be made. */
static bool evaluate(int *difficulty)
{
	include_pval(smith_obj);
	const int dif = object_difficulty(smith_obj, &current_cost);
	const bool ok = smith_affordable(smith_obj, &current_cost);
	exclude_pval();
	if (difficulty) *difficulty = dif;
	return ok;
}

/* Difficulty and affordability of a candidate object (its own known copy is made and freed). */
static bool evaluate_other(struct object *obj, int *difficulty)
{
	struct object known;
	struct smithing_cost cost;
	object_copy(&known, obj);
	known.known = NULL;
	obj->known = &known;
	include_pval(obj);
	*difficulty = object_difficulty(obj, &cost);
	const bool ok = smith_affordable(obj, &cost);
	exclude_pval();
	obj->known = NULL;
	object_wipe(&known);
	return ok;
}

/* ------------------------------------------------------------------------
 * Publishing
 * ------------------------------------------------------------------------ */

static const char *const stat_short[STAT_MAX] = { "Str", "Dex", "Con", "Gra" };

static void add_cost(const char *label, int state)
{
	native_prompt_add("#C\t%s\t%d\n", label, state);
}

/* show_smith_obj(): difficulty, costs, turns and the item's description. */
static void add_item_panel(void)
{
	if (!smith_obj->kind) return;
	int dif;
	(void)evaluate(&dif);
	const int skill = player->state.skill_use[SKILL_SMITHING] + square_forge_bonus(cave, player->grid);

	char name[120];
	int mode = ODESC_FULL | ODESC_CAPITAL | ODESC_SPOIL;
	if (smith_obj->number > 1) mode |= ODESC_PREFIX;
	include_pval(smith_obj);
	know_smith_obj();
	object_desc(name, sizeof(name), smith_obj, mode, player);
	const int weight = smith_obj->weight * smith_obj->number;
	textblock *tb = object_info(smith_obj, OINFO_SMITH);
	exclude_pval();
	native_sanitise(name);
	native_prompt_add("#I\t%s   %d.%d lb\t%d\t%d\t%d\t%d\t%d\n", name, weight / 10, weight % 10, dif, skill,
		current_cost.drain > 0, MAX(10, dif * 10), player->smithing_leftover);

	const struct { bool need; const char *name; } abilities[] = {
		{ current_cost.weaponsmith, "Weaponsmith" }, { current_cost.armoursmith, "Armoursmith" },
		{ current_cost.jeweller, "Jeweller" }, { current_cost.enchantment, "Enchantment" },
		{ current_cost.artistry, "Artistry" }, { current_cost.artifice, "Artifice" },
	};
	for (size_t i = 0; i < N_ELEMENTS(abilities); i++)
		if (abilities[i].need) add_cost(abilities[i].name, 2);
	const int uses = square_forge_uses(cave, player->grid);
	if (current_cost.uses > 0)
		add_cost(format("%d use%s (of %d)", current_cost.uses, current_cost.uses == 1 ? "" : "s", uses),
			uses >= current_cost.uses);
	if (current_cost.drain > 0)
		add_cost(format("%d Smithing", current_cost.drain), current_cost.drain <= player->skill_base[SKILL_SMITHING] ? 3 : 0);
	if (current_cost.mithril > 0)
		add_cost(format("%d.%d lb Mithril", current_cost.mithril / 10, current_cost.mithril % 10),
			current_cost.mithril <= mithril_carried(player));
	for (int s = 0; s < STAT_MAX; s++)
		if (current_cost.stat[s] > 0)
			add_cost(format("%d %s", current_cost.stat[s], stat_short[s]),
				player->stat_base[s] + player->stat_drain[s] - current_cost.stat[s] >= -5);
	if (current_cost.exp > 0)
		add_cost(format("%d Exp", current_cost.exp), player->new_exp >= current_cost.exp);

	/* The description, a line at a time. */
	char line[512];
	size_t n = 0;
	for (const wchar_t *w = textblock_text(tb); ; w++) {
		if (!*w || *w == L'\n') {
			line[n] = '\0';
			native_sanitise(line);
			if (n) native_prompt_add("#D\t%s\n", line);
			n = 0;
			if (!*w) break;
			continue;
		}
		char mb[8];
		const int k = text_wctomb(mb, *w);
		if (k > 0 && n + (size_t)k < sizeof(line)) { memcpy(line + n, mb, (size_t)k); n += (size_t)k; }
	}
	textblock_free(tb);
}

static bool no_forge, exhausted;

static void begin_view(const char *view, const char *title)
{
	native_prompt_begin();
	native_prompt_add("Smith\t%s\t%s\n", view, title);
	if (no_forge) native_prompt_add("#N\tExploration mode: smithing requires a forge.\n");
	else if (exhausted) native_prompt_add("#N\tExploration mode: this forge's resources are exhausted.\n");
}

static void add_option(enum opt_state st, const char *label, const char *detail)
{
	char l[120], d[200];
	my_strcpy(l, label, sizeof(l));
	my_strcpy(d, detail ? detail : "", sizeof(d));
	native_sanitise(l);
	native_sanitise(d);
	native_prompt_add("#O\t%d\t%s\t%s\n", (int)st, l, d);
}

static int32_t ask_view(void)
{
	add_item_panel();
	native_prompt_end();
	return native_ask(NB_PROMPT_SMITH);
}

/* ------------------------------------------------------------------------
 * Base item (tval_menu, sval menu)
 * ------------------------------------------------------------------------ */

static bool tval_has_ability(int i)
{
	const int cat = smithing_tvals[i].category;
	return (cat == SMITH_TYPE_WEAPON && player_active_ability(player, "Weaponsmith"))
		|| (cat == SMITH_TYPE_JEWELRY && player_active_ability(player, "Jeweller"))
		|| (cat == SMITH_TYPE_ARMOUR && player_active_ability(player, "Armoursmith"));
}

static int smithing_svals(int tval, struct object_kind **out, int max)
{
	int n = 0;
	for (int i = 0; i < z_info->k_max && n < max; i++) {
		struct object_kind *kind = &k_info[i];
		if (kind->tval != tval || kf_has(kind->kind_flags, KF_INSTA_ART) || of_has(kind->flags, OF_NO_SMITHING)) continue;
		out[n++] = kind;
	}
	return n;
}

/* True once a kind is chosen. */
static bool sval_view(int t)
{
	struct object_kind *kinds[40];
	const int n = smithing_svals(smithing_tvals[t].tval, kinds, (int)N_ELEMENTS(kinds));
	begin_view("sval", smithing_tvals[t].desc);
	for (int i = 0; i < n; i++) {
		struct object obj;
		create_base_object(kinds[i], &obj);
		int dif;
		const bool ok = evaluate_other(&obj, &dif);
		object_wipe(&obj);
		char name[60];
		object_kind_name(name, sizeof(name), kinds[i], true);
		add_option(ok ? OPT_ON : OPT_DEAR, name, format("difficulty %d", dif));
	}
	const int32_t a = ask_view();
	if (a < 0 || a >= n) return false;
	reset_smithing_objects(kinds[a]);
	return true;
}

static void base_item_view(void)
{
	wipe_smithing_objects();
	pval = 0;
	numbers_changed = false;
	for (;;) {
		begin_view("tval", "Base item");
		for (int i = 0; i < MAX_SMITHING_TVALS; i++)
			add_option(tval_has_ability(i) ? OPT_ON : OPT_MAYBE, smithing_tvals[i].desc, NULL);
		const int32_t a = ask_view();
		if (a < 0 || a >= MAX_SMITHING_TVALS) return;
		if (sval_view(a)) return;
	}
}

/* ------------------------------------------------------------------------
 * Enchant (special_menu)
 * ------------------------------------------------------------------------ */

static void special_view(void)
{
	struct object_kind *kind = smith_obj->kind;
	if (!kind) return;
	if (smith_obj->artifact) reset_smithing_objects(kind);
	struct ego_item **egos = mem_zalloc(z_info->e_max * sizeof(*egos));
	int n = 0;
	begin_view("special", "Enchant");
	const int pval_old = pval;
	for (int i = 0; i < z_info->e_max; i++) {
		struct ego_item *ego = &e_info[i];
		const struct poss_item *poss = ego->poss_items;
		while (poss && poss->kidx != kind->kidx) poss = poss->next;
		if (!poss) continue;
		struct object obj;
		object_copy(&obj, smith_obj);
		create_special(&obj, ego);
		pval = pval_valid(&obj) ? pval_default(&obj) : 0;
		int dif;
		const bool ok = evaluate_other(&obj, &dif);
		object_wipe(&obj);
		egos[n++] = ego;
		add_option(ok ? OPT_ON : OPT_DEAR, strip_ego_name(ego->name), format("difficulty %d", dif));
	}
	pval = pval_old;
	const int32_t a = ask_view();
	if (a >= 0 && a < n) {
		create_special(smith_obj, egos[a]);
		know_smith_obj();
		pval = pval_valid(smith_obj) ? smith_obj->pval : 0;
		object_wipe(smith_obj_backup);
		object_copy(smith_obj_backup, smith_obj);
		smith_obj->ego = egos[a];
	}
	mem_free(egos);
}

/* ------------------------------------------------------------------------
 * Artifice (artefact_menu)
 * ------------------------------------------------------------------------ */

static const char *const art_cats[] = {
	"Stat bonuses", "Sustains", "Skill bonuses", "Melee powers", "Slays", "Resistances", "Curses", "Misc",
	#define SKILL(a, b) b,
	#include "list-skills.h"
	#undef SKILL
	"Name artefact",
};
enum { ART_CAT_COUNT = SMITH_CAT_MAX + SKILL_MAX + 1, MAX_ART_ABILITIES = 100 };

struct property_info { struct obj_property *prop; bool negative; };

static int smith_properties(enum smithing_category cat, struct property_info *out)
{
	int n = 0;
	for (int i = 0; i < z_info->property_max; i++) {
		struct obj_property *prop = &obj_properties[i];
		if (prop->smith_cat != (int)cat || !applicable_property(prop, smith_obj)) continue;
		out[n++] = (struct property_info){ prop, false };
		if (cat == SMITH_CAT_STAT) out[n++] = (struct property_info){ prop, true };
	}
	return n;
}

static int smith_art_abilities(int skill, struct ability **out)
{
	int n = 0;
	for (struct ability *a = abilities; a && n < MAX_ART_ABILITIES; a = a->next) {
		if (a->skill != skill) continue;
		const struct poss_item *poss = a->poss_items;
		while (poss && poss->kidx != smith_obj->kind->kidx) poss = poss->next;
		if (poss) out[n++] = a;
	}
	return n;
}

static void property_view(enum smithing_category cat)
{
	struct property_info *props = mem_zalloc(2 * z_info->property_max * sizeof(*props));
	for (;;) {
		const int n = smith_properties(cat, props);
		if (!n) break;
		begin_view("property", art_cats[cat]);
		for (int i = 0; i < n; i++) {
			const bool chosen = object_has_property(props[i].prop, smith_obj, props[i].negative);
			const char *name = cat == SMITH_CAT_STAT
				? format("%s %s", props[i].prop->name, props[i].negative ? "penalty" : "bonus")
				: props[i].prop->name;
			add_option(chosen ? OPT_CHOSEN : OPT_ON, name, NULL);
		}
		const int32_t a = ask_view();
		if (a < 0 || a >= n) break;
		if (!object_has_property(props[a].prop, smith_obj, props[a].negative))
			add_object_property(props[a].prop, smith_obj, props[a].negative);
		else
			remove_object_property(props[a].prop, smith_obj);
	}
	mem_free(props);
}

/* skill_display(): would adding this ability keep the design affordable? */
static bool ability_affordable(struct ability *ab)
{
	include_pval(smith_obj);
	struct object backup;
	object_copy(&backup, smith_obj);
	add_ability(&smith_obj->abilities, ab);
	const bool ok = smith_affordable(smith_obj, &current_cost);
	object_wipe(smith_obj);
	object_copy(smith_obj, &backup);
	object_wipe(&backup);
	(void)smith_affordable(smith_obj, &current_cost);
	exclude_pval();
	return ok;
}

static void ability_view(int skill)
{
	struct ability *abs[MAX_ART_ABILITIES];
	for (;;) {
		const int n = smith_art_abilities(skill, abs);
		if (!n) break;
		begin_view("ability", art_cats[SMITH_CAT_MAX + skill]);
		for (int i = 0; i < n; i++) {
			const bool chosen = !!locate_ability(smith_obj->abilities, abs[i]);
			const enum opt_state st = !applicable_ability(abs[i], smith_obj) ? OPT_OFF
				: chosen ? OPT_CHOSEN : ability_affordable(abs[i]) ? OPT_ON : OPT_DEAR;
			add_option(st, abs[i]->name, abs[i]->desc);
		}
		const int32_t a = ask_view();
		if (a < 0 || a >= n) break;
		if (!applicable_ability(abs[a], smith_obj)) continue;
		if (!locate_ability(smith_obj->abilities, abs[a])) add_ability(&smith_obj->abilities, abs[a]);
		else remove_ability(&smith_obj->abilities, abs[a]);
	}
}

/* rename_artefact(): an empty or cancelled name falls back to "of <name>". */
static void rename_view(void)
{
	char base[60];
	object_desc(base, sizeof(base), smith_obj, ODESC_BASE, player);
	native_prompt_begin();
	native_prompt_add("Name your %s\n%s\n19\n0\n", base, smith_art->name ? smith_art->name : "");
	native_prompt_end();
	char name[20] = "";
	if (native_ask(NB_PROMPT_TEXT) == 1) native_answer_text(name, sizeof(name));
	if (name[0]) {
		my_strcpy(smith_art_name, name, sizeof(smith_art_name));
		player->upkeep->redraw |= PR_MISC;
	} else {
		strnfmt(smith_art_name, sizeof(smith_art_name), "of %s", player->full_name);
	}
	smith_art->name = smith_art_name;
}

static void artefact_view(void)
{
	if (!smith_obj->kind) return;
	struct object_kind *kind = lookup_selfmade_kind(smith_obj->kind->tval);
	if (!kind) kind = smith_obj->kind;
	if (smith_obj->ego || kind != smith_obj->kind) reset_smithing_objects(kind);
	smith_obj->artifact = smith_art;
	strnfmt(smith_art_name, sizeof(smith_art_name), "of %s", player->full_name);
	smith_art->name = smith_art_name;

	struct property_info *props = mem_zalloc(2 * z_info->property_max * sizeof(*props));
	struct ability *abs[MAX_ART_ABILITIES];
	for (;;) {
		begin_view("artefact", "Artifice");
		for (int i = 0; i < ART_CAT_COUNT; i++) {
			const int count = i < SMITH_CAT_MAX ? smith_properties(i, props)
				: i < SMITH_CAT_MAX + SKILL_MAX ? smith_art_abilities(i - SMITH_CAT_MAX, abs) : 1;
			add_option(count > 0 ? OPT_ON : OPT_OFF, art_cats[i], i == ART_CAT_COUNT - 1 ? smith_art->name : NULL);
		}
		const int32_t a = ask_view();
		if (a < 0 || a >= ART_CAT_COUNT) break;
		if (a < SMITH_CAT_MAX) property_view(a);
		else if (a < SMITH_CAT_MAX + SKILL_MAX) ability_view(a - SMITH_CAT_MAX);
		else rename_view();
	}
	mem_free(props);
}

/* ------------------------------------------------------------------------
 * Numbers (numbers_menu)
 * ------------------------------------------------------------------------ */

static const char *const numbers_names[SMITH_NUM_MAX] = {
	"Increase attack bonus", "Decrease attack bonus", "Increase damage sides", "Decrease damage sides",
	"Increase evasion bonus", "Decrease evasion bonus", "Increase protection sides", "Decrease protection sides",
	"Increase special bonus", "Decrease special bonus", "Increase weight", "Decrease weight",
};

static void numbers_validity(bool valid[SMITH_NUM_MAX], bool afford[SMITH_NUM_MAX], bool artistry[SMITH_NUM_MAX])
{
	struct object *o = smith_obj;
	valid[SMITH_NUM_INC_ATT] = att_valid(o) && o->att < att_max(o, true);
	artistry[SMITH_NUM_INC_ATT] = att_valid(o) && !(o->att < att_max(o, false));
	valid[SMITH_NUM_DEC_ATT] = att_valid(o) && o->att > att_min(o);
	valid[SMITH_NUM_INC_DS] = ds_valid(o) && o->ds < ds_max(o, true);
	artistry[SMITH_NUM_INC_DS] = ds_valid(o) && !(o->ds < ds_max(o, false));
	valid[SMITH_NUM_DEC_DS] = ds_valid(o) && o->ds > ds_min(o);
	valid[SMITH_NUM_INC_EVN] = evn_valid(o) && o->evn < evn_max(o, true);
	artistry[SMITH_NUM_INC_EVN] = evn_valid(o) && !(o->evn < evn_max(o, false));
	valid[SMITH_NUM_DEC_EVN] = evn_valid(o) && o->evn > evn_min(o);
	valid[SMITH_NUM_INC_PS] = ps_valid(o) && o->ps < ps_max(o, true);
	artistry[SMITH_NUM_INC_PS] = ps_valid(o) && !(o->ps < ps_max(o, false));
	valid[SMITH_NUM_DEC_PS] = ps_valid(o) && o->ps > ps_min(o);
	valid[SMITH_NUM_INC_PVAL] = pval_valid(o) && pval < pval_max(o);
	valid[SMITH_NUM_DEC_PVAL] = pval_valid(o) && pval > pval_min(o);
	valid[SMITH_NUM_INC_WGT] = wgt_valid(o) && o->weight < wgt_max(o);
	valid[SMITH_NUM_DEC_WGT] = wgt_valid(o) && o->weight > wgt_min(o);
	const int old_pval = pval;
	for (int i = 0; i < SMITH_NUM_MAX; i++) {
		if (!valid[i]) continue;
		struct object backup;
		object_copy(&backup, smith_obj);
		modify_numbers(smith_obj, i, &pval);
		include_pval(smith_obj);
		afford[i] = smith_affordable(smith_obj, &current_cost);
		pval = old_pval;
		exclude_pval();
		object_wipe(smith_obj);
		object_copy(smith_obj, &backup);
		object_wipe(&backup);
	}
}

/* The value an increase/decrease pair changes, for the increase line's detail. */
static const char *numbers_value(int i)
{
	const struct object *o = smith_obj;
	switch (i) {
		case SMITH_NUM_INC_ATT: return format("%+d", o->att);
		case SMITH_NUM_INC_DS: return format("%dd%d", o->dd, o->ds);
		case SMITH_NUM_INC_EVN: return format("%+d", o->evn);
		case SMITH_NUM_INC_PS: return format("%dd%d", o->pd, o->ps);
		case SMITH_NUM_INC_PVAL: return format("%+d", pval);
		case SMITH_NUM_INC_WGT: return format("%d.%d lb", o->weight / 10, o->weight % 10);
		default: return "";
	}
}

static void numbers_view(void)
{
	if (!smith_obj->kind) return;
	for (;;) {
		bool valid[SMITH_NUM_MAX] = { false }, afford[SMITH_NUM_MAX] = { false }, artistry[SMITH_NUM_MAX] = { false };
		numbers_validity(valid, afford, artistry);
		begin_view("numbers", "Numbers");
		for (int i = 0; i < SMITH_NUM_MAX; i++) {
			const enum opt_state st = !valid[i] ? OPT_OFF : artistry[i] ? OPT_ARTISTRY : afford[i] ? OPT_ON : OPT_DEAR;
			add_option(st, numbers_names[i], numbers_value(i));
		}
		const int32_t a = ask_view();
		if (a < 0 || a >= SMITH_NUM_MAX) return;
		if (valid[a]) {
			modify_numbers(smith_obj, a, &pval);
			numbers_changed = true;
		}
	}
}

/* ------------------------------------------------------------------------
 * Melt (melt_menu), accept (accept_item), the main menu
 * ------------------------------------------------------------------------ */

static void melt_view(void)
{
	struct object *items[64];
	int n = 0;
	for (struct object *obj = player->gear; obj && n < (int)N_ELEMENTS(items); obj = obj->next)
		if (object_is_mithril(obj)) items[n++] = obj;
	if (!n) return;
	begin_view("melt", "Melt mithril");
	for (int i = 0; i < n; i++) {
		char name[120];
		object_desc(name, sizeof(name), items[i], ODESC_PREFIX | ODESC_FULL, player);
		add_option(OPT_ON, name, NULL);
	}
	const int32_t a = ask_view();
	if (a >= 0 && a < n) melt_mithril_item(player, items[a]);
}

static bool accept_item(void)
{
	if (!evaluate(NULL) || !square_isforge(cave, player->grid) || !square_forge_uses(cave, player->grid)) return false;
	if (current_cost.drain > 0
			&& !get_check(format("This will drain your smithing skill by %d points. Proceed? ", current_cost.drain)))
		return false;
	include_pval(smith_obj);
	if (smith_obj->artifact) add_artefact_details(smith_art, smith_obj);
	exclude_pval();
	return true;
}

/* check_smithing_menu_row_colors() and smithing_menu_browser(); states into st[6]. */
static void main_view_options(enum opt_state st[6])
{
	const bool any_smith = player_active_ability(player, "Weaponsmith") || player_active_ability(player, "Armoursmith")
		|| player_active_ability(player, "Jeweller");
	st[0] = any_smith ? OPT_ON : OPT_MAYBE;
	add_option(st[0], "Base item", "Start with a new base item.");

	const struct object *o = smith_obj;
	const bool no_enchant = !o->kind || o->artifact || numbers_changed || tval_is_jewelry(o) || tval_is_horn(o)
		|| strstr(o->kind->name, "Shovel");
	st[1] = no_enchant ? OPT_OFF : player_active_ability(player, "Enchantment") ? OPT_ON : OPT_MAYBE;
	add_option(st[1], "Enchant",
		numbers_changed ? "Enchantment cannot be changed after using Numbers."
			: "Add a special enchantment to the base item (not with Artifice).");

	const bool no_art = !o->kind || o->ego || tval_is_horn(o) || player->self_made_arts >= z_info->self_arts_max;
	st[2] = no_art ? OPT_OFF : player_active_ability(player, "Artifice") ? OPT_ON : OPT_MAYBE;
	add_option(st[2], "Artifice",
		"Design your own artefact (not with Enchant).");

	st[3] = o->kind ? OPT_ON : OPT_OFF;
	add_option(st[3], "Numbers", "Change the item's key numbers.");

	const bool can_melt = mithril_items_carried(player) && square_isforge(cave, player->grid)
		&& square_forge_uses(cave, player->grid);
	st[4] = can_melt ? OPT_ON : OPT_OFF;
	add_option(st[4], "Melt", "Melt down a mithril item.");

	const bool can_make = o->kind && evaluate(NULL) && square_isforge(cave, player->grid)
		&& square_forge_uses(cave, player->grid);
	st[5] = can_make ? OPT_ON : OPT_OFF;
	add_option(st[5], "Make it",
		no_forge ? "You are not at a forge, so you cannot create items."
			: exhausted ? "This forge has no resources, so you cannot create items."
			: "Create the item you have designed.");
}

/*
 * A design left by switching panel tabs is picked up again; one finished in
 * the panel ("Make it") waits for the CMD_SMITH it queued.
 */
static bool design_live, ready_to_make;

static void start_design(void)
{
	if (design_live) { design_live = false; return; }
	wipe_smithing_objects();
	pval = 0;
	pval_included = false;
	numbers_changed = false;
	no_forge = !square_isforge(cave, player->grid);
	exhausted = !no_forge && !square_forge_uses(cave, player->grid);
}

/* The menus; true once "Make it" is chosen.  Leaving the panel keeps the design. */
static bool design(void)
{
	for (;;) {
		begin_view("main", "Smithing");
		enum opt_state st[6];
		main_view_options(st);
		const int32_t a = ask_view();
		if (a >= 0 && a < 6 && st[a] == OPT_OFF) continue;
		switch (a) {
			case 0: base_item_view(); break;
			case 1: special_view(); break;
			case 2: artefact_view(); break;
			case 3: numbers_view(); break;
			case 4: melt_view(); break;
			case 5: if (accept_item()) return true; break;
			default:
				if (native_panel_leaving() && smith_obj->kind) design_live = true;
				else wipe_smithing_objects();
				return false;
		}
	}
}

/* Unfinished work: false if the player would rather abandon it and browse. */
static bool resume_unfinished(void)
{
	/* The design is not saved, so after a restart there is nothing to resume. */
	if (player->smithing_leftover > 0 && !smith_obj->kind) {
		msg("Your unfinished work did not survive your absence.");
		player->smithing_leftover = 0;
	}
	if (player->smithing_leftover <= 0) return false;
	if (square_isforge(cave, player->grid)) return true;
	if (!get_check(format("A forge has an unfinished %s.  Abandon it to see smithing options? ",
			smith_obj->artifact ? "artifact" : "item")))
		return true;
	player->smithing_leftover = 0;
	return false;
}

struct object *native_smith_object(struct smithing_cost *cost)
{
	if (ready_to_make) {
		ready_to_make = false;
		*cost = current_cost;
		return smith_obj;
	}
	if (resume_unfinished()) {
		if (!square_isforge(cave, player->grid)) return NULL;
		*cost = current_cost;
		return smith_obj;
	}
	start_design();
	if (!design()) return NULL;
	include_pval(smith_obj);
	*cost = current_cost;
	return smith_obj;
}

bool native_smith_tab(void)
{
	if (resume_unfinished()) {
		if (!square_isforge(cave, player->grid)) return false;
		cmdq_push(CMD_SMITH);
		return true;
	}
	start_design();
	if (!design()) return false;
	include_pval(smith_obj);
	ready_to_make = true;
	cmdq_push(CMD_SMITH);
	return true;
}
