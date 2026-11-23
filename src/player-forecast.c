/**
 * \file player-forecast.c
 * \brief Combat forecasts: the chance to hit, criticals and damage of an attack
 *
 * An attack hits when d20 + attack beats d20 + evasion, by the margin that
 * decides its critical dice (crit_bonus()); a hit rolls its damage dice
 * against the monster's protection dice.  With only 400 pairs of d20s the
 * odds are worked out exactly, from what the player knows: the monster's
 * defence once its lore records it, and an object's known runes.
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
#include "cave.h"
#include "combat.h"
#include "init.h"
#include "mon-desc.h"
#include "mon-lore.h"
#include "mon-move.h"
#include "monster.h"
#include "obj-desc.h"
#include "obj-gear.h"
#include "obj-knowledge.h"
#include "obj-slays.h"
#include "obj-tval.h"
#include "obj-util.h"
#include "player-abilities.h"
#include "player-attack.h"
#include "player-calcs.h"
#include "player-forecast.h"
#include "songs.h"
#include "target.h"

#define D20 20
#define MARGINS (2 * D20 - 1)	/* a - e runs from -19 to 19 */

/**
 * The equipment and state an attack is forecast with.  trial_begin() puts the
 * object in the named slot and works out the state from known runes alone;
 * trial_end() puts both back.  The player's body slot and state are changed in
 * between (and only there): nothing is signalled, learnt or redrawn.
 */
struct trial {
	int slot;
	struct object *was;
	struct player_state state;
};

static void trial_begin(struct player *p, const char *slot_name,
		const struct object *obj, struct trial *t)
{
	t->slot = slot_by_name(p, slot_name);
	t->was = t->slot >= 0 ? p->body.slots[t->slot].obj : NULL;
	t->state = p->state;
	if (t->slot >= 0 && obj) p->body.slots[t->slot].obj = (struct object *) obj;
	calc_bonuses(p, &p->state, true, false);
}

static void trial_end(struct player *p, const struct trial *t)
{
	if (t->slot >= 0) p->body.slots[t->slot].obj = t->was;
	p->state = t->state;
}

/* Whether the player knows the monster has the flag */
static bool known_flag(const struct monster_race *race,
		const struct monster_lore *lore, bool full, int flag)
{
	return rf_has(race->flags, flag) && (full || rf_has(lore->flags, flag));
}

static bool known_object_flag(const struct object *obj, int flag)
{
	return obj && obj->known && of_has(obj->known->flags, flag);
}

/**
 * The slay and brand dice of the object's known runes against the monster, as
 * far as the player knows it (slay_bonus() without its learning and scaring).
 */
static int known_slay_dice(const struct object *obj,
		const struct monster_race *race, const struct monster_lore *lore,
		bool full)
{
	int dice = 0;

	if (!obj || !obj->known) return 0;
	for (int i = 1; i < z_info->brand_max; i++) {
		const struct brand *b = &brands[i];
		if (!obj->known->brands || !obj->known->brands[i]) continue;
		if (known_flag(race, lore, full, b->resist_flag)) continue;
		dice += b->dice;
		if (b->vuln_flag && known_flag(race, lore, full, b->vuln_flag)) dice += b->vuln_dice;
	}
	for (int i = 1; i < z_info->slay_max; i++) {
		const struct slay *s = &slays[i];
		if (!obj->known->slays || !obj->known->slays[i]) continue;
		if (s->name && known_flag(race, lore, full, s->race_flag)) dice += s->dice;
	}
	return dice;
}

/* prt_after_sharpness() from the object's known runes */
static int known_prt_percent(struct player *p, const struct object *obj)
{
	struct song *const sharp = lookup_song("Sharpness");
	int protection = 100;

	if (!obj) return 0;
	if (known_object_flag(obj, OF_SHARPNESS)) protection = 50;
	if (known_object_flag(obj, OF_SHARPNESS2)) protection = 0;
	if (sharp && player_is_singing(p, sharp) && tval_is_sharp(obj)) {
		protection -= song_bonus(p, p->state.skill_use[SKILL_SONG], sharp);
	}
	return MAX(protection, 0);
}

/* The chance of each a - e, for d20 rolls a and e (a the worse of two if cursed) */
static void margin_odds(bool cursed, double odds[MARGINS])
{
	for (int k = 0; k < MARGINS; k++) odds[k] = 0.0;
	for (int a = 1; a <= D20; a++) {
		const double pa = cursed ? (2.0 * (D20 - a) + 1.0) / (D20 * D20) : 1.0 / D20;
		for (int e = 1; e <= D20; e++) odds[a - e + D20 - 1] += pa / D20;
	}
}

/* The distribution of the sum of n dice of s sides into out[0 .. n s] */
static void dice_odds(int n, int s, double *out)
{
	out[0] = 1.0;
	for (int i = 1; i <= n * s; i++) out[i] = 0.0;
	for (int die = 0; die < n; die++) {
		for (int total = (die + 1) * s; total >= 0; total--) {
			double sum = 0.0;
			for (int face = 1; face <= s && face <= total; face++) sum += out[total - face];
			out[total] = sum / s;
		}
	}
}

/* The mean of max(0, damage - protection) for n dice of s sides (5 instead of 0 with puncture) */
static double mean_net(int n, int s, const double *prot, int prot_max, bool puncture)
{
	double mean = 0.0;

	if (n <= 0 || s <= 0) return puncture ? 5.0 : 0.0;
	double *const dmg = mem_zalloc((n * s + 1) * sizeof(*dmg));
	dice_odds(n, s, dmg);
	for (int d = n; d <= n * s; d++) {
		for (int q = 0; q <= prot_max; q++) {
			const int net = MAX(d - q, 0);
			mean += dmg[d] * prot[q] * (net == 0 && puncture ? 5 : net);
		}
	}
	mem_free(dmg);
	return mean;
}

/**
 * The odds of one attack, given the critical dice of a hit by each margin
 * (crits[m] for m from 1 to att - evn + 19).  This is the arithmetic alone,
 * so that the unit tests can check it against simulated attacks.
 */
void forecast_odds(const struct forecast_roll *r, const int *crits,
		struct attack_forecast *f)
{
	double odds[MARGINS], miss = 0.0;
	const int base = r->att - r->evn;
	const int pd = r->ps > 0 ? r->pd : 0;
	const int pmax = pd * MAX(r->ps, 0);
	double *const prot = mem_zalloc((pmax + 1) * sizeof(*prot));
	double *const raw = mem_zalloc((pmax + 1) * sizeof(*raw));
	double net_by_dice[200];
	int scaled_max = 0;

	assert(r->prt_pct >= 0 && r->prt_pct <= 100);

	/* The protection roll, cut by sharpness */
	dice_odds(pd, MAX(r->ps, 1), raw);
	for (int q = 0; q <= pmax; q++) {
		const int cut = (q * r->prt_pct) / 100;
		prot[cut] += raw[q];
		scaled_max = MAX(scaled_max, cut);
	}
	mem_free(raw);

	margin_odds(r->cursed, odds);
	for (int k = 0; k < MARGINS; k++) {
		if (k - (D20 - 1) + base <= 0) miss += odds[k];
	}
	for (size_t i = 0; i < N_ELEMENTS(net_by_dice); i++) net_by_dice[i] = -1.0;

	f->att = r->att;
	f->evn = r->evn;
	f->hit = f->crit = f->dmg = 0.0;
	f->max_dmg = 0;
	for (int k = 0; k < MARGINS; k++) {
		const int margin = k - (D20 - 1) + base;
		if (margin <= 0 || odds[k] == 0.0) continue;

		/* An accurate weapon's second roll comes after a miss */
		const double chance = odds[k] * (r->accurate ? 1.0 + miss : 1.0);
		const int crit = r->cumbersome ? 0 : crits[margin];
		const int dice = MIN(r->dd + r->slay + crit + r->extra_crit, (int) N_ELEMENTS(net_by_dice) - 1);
		if (net_by_dice[dice] < 0.0) net_by_dice[dice] = mean_net(dice, r->ds, prot, scaled_max, r->puncture);
		f->hit += chance;
		if (crit > 0) f->crit += chance;
		f->dmg += chance * net_by_dice[dice];
		f->max_dmg = MAX(f->max_dmg, MAX(dice * r->ds, r->puncture ? 5 : 0));
	}
	f->dmg_hit = f->hit > 0.0 ? f->dmg / f->hit : 0.0;
	mem_free(prot);
}

static void forecast_roll(struct player *p, const struct monster_race *race,
		const struct forecast_roll *r, struct attack_forecast *f)
{
	const int top = MAX(r->att - r->evn + D20, 1);
	int *const crits = mem_zalloc((top + 1) * sizeof(*crits));

	for (int m = 1; m <= top; m++) crits[m] = crit_bonus(p, m, r->weight, race, r->skill, false);
	forecast_odds(r, crits, f);
	mem_free(crits);
}

/* The monster's defence is only forecast once the player knows it */
static bool defence_known(const struct monster_race *race, const struct monster_lore *lore)
{
	return lore->armour_known || lore_shown_in_full(race, lore);
}

/**
 * Forecasts a melee attack at the monster with the weapon (wielded or not; NULL
 * for the one wielded).  \return false if the monster's defence is unknown.
 */
bool forecast_melee(struct player *p, const struct object *weapon,
		struct monster *mon, struct attack_forecast *f)
{
	const struct monster_race *race = mon->race;
	const struct monster_lore *lore = get_lore(race);
	const bool full = lore_shown_in_full(race, lore);
	struct trial t;
	struct forecast_roll r = { 0 };

	if (!defence_known(race, lore)) return false;
	trial_begin(p, "weapon", weapon, &t);
	const struct object *w = equipped_item_by_slot_name(p, "weapon");
	r.att = total_player_attack_preview(p, mon, p->state.skill_use[SKILL_MELEE] + stealth_melee_bonus(mon));
	r.evn = total_monster_evasion(p, mon, false);
	r.cursed = p->cursed;
	r.accurate = known_object_flag(w, OF_ACCURATE);
	r.cumbersome = known_object_flag(w, OF_CUMBERSOME);
	r.weight = w ? w->weight : 0;
	r.skill = SKILL_MELEE;
	r.dd = p->state.mdd;
	r.ds = p->state.mds;
	r.slay = known_slay_dice(w, race, lore, full);
	r.pd = race->pd;
	r.ps = race->ps;
	r.prt_pct = known_prt_percent(p, w);
	f->attacks = 1 + (player_active_ability(p, "Rapid Attack") ? 1 : 0) + (p->state.mds2 > 0 ? 1 : 0);
	forecast_roll(p, race, &r, f);
	trial_end(p, &t);
	return true;
}

/**
 * Forecasts a shot at the monster with the ammunition from the bow (wielded or
 * not; NULL for the one wielded).  \return false if the monster's defence is
 * unknown, the ammunition does not suit the bow, or the monster is out of range.
 */
bool forecast_shot(struct player *p, const struct object *bow,
		const struct object *ammo, struct monster *mon,
		struct attack_forecast *f)
{
	const struct monster_race *race = mon->race;
	const struct monster_lore *lore = get_lore(race);
	const bool full = lore_shown_in_full(race, lore);
	struct trial t;
	struct forecast_roll r = { 0 };
	bool ok = false;

	if (!defence_known(race, lore) || !ammo) return false;
	trial_begin(p, "shooting", bow, &t);
	const struct object *b = equipped_item_by_slot_name(p, "shooting");
	if (b && p->state.ammo_tval && ammo->tval == p->state.ammo_tval
			&& distance(p->grid, mon->grid) <= archery_range(b)) {
		const int att = ammo->known ? ammo->known->att : 0;
		const int rout = player_active_ability(p, "Rout") && mon->stance == STANCE_FLEEING ? 5 : 0;
		r.att = total_player_attack_preview(p, mon, p->state.skill_use[SKILL_ARCHERY] + att + rout);
		r.evn = total_monster_evasion(p, mon, true);
		r.cursed = p->cursed;
		r.accurate = known_object_flag(b, OF_ACCURATE);
		r.cumbersome = known_object_flag(b, OF_CUMBERSOME);
		r.weight = b->weight;
		r.skill = SKILL_ARCHERY;
		r.extra_crit = player_active_ability(p, "Ambush") && mon->alertness < 0 ? 1 : 0;
		r.dd = b->dd;
		r.ds = MAX(total_ads(p, &p->state, b, false), 0);
		r.slay = known_slay_dice(ammo, race, lore, full) + known_slay_dice(b, race, lore, full);
		if (player_active_ability(p, "Flaming Arrows") && !known_flag(race, lore, full, RF_RES_FIRE)) {
			r.slay += 1 + (known_flag(race, lore, full, RF_HURT_FIRE) ? 1 : 0);
		}
		r.pd = race->pd;
		r.ps = race->ps;
		r.prt_pct = known_prt_percent(p, ammo);
		r.puncture = player_active_ability(p, "Puncture");
		f->attacks = player_active_ability(p, "Rapid Fire") && ammo->number > 1 ? 2 : 1;
		forecast_roll(p, race, &r, f);
		ok = true;
	}
	trial_end(p, &t);
	return ok;
}

static void describe(textblock *tb, const char *what, const struct attack_forecast *f)
{
	textblock_append(tb, "%s: ", what);
	textblock_append_c(tb, COLOUR_L_GREEN, "%d%%", (int) (100.0 * f->hit + 0.5));
	textblock_append(tb, " to hit (%+d against %+d), ", f->att, f->evn);
	textblock_append_c(tb, COLOUR_YELLOW, "%d%%", (int) (100.0 * f->crit + 0.5));
	textblock_append(tb, " to crit, ");
	textblock_append_c(tb, COLOUR_L_RED, "%.1f", f->dmg);
	textblock_append(tb, " damage an attack (%.1f a hit, at most %d)", f->dmg_hit, f->max_dmg);
	if (f->attacks > 1) textblock_append(tb, ", %d attacks a turn", f->attacks);
	textblock_append(tb, ".\n");
}

static void object_label(char *buf, size_t len, const struct object *obj, struct player *p)
{
	if (obj) object_desc(buf, len, obj, ODESC_BASE, p);
	else my_strcpy(buf, "Unarmed", len);
	my_strcap(buf);
}

/**
 * The forecasts of attacking the monster with what is wielded: the weapon and
 * the bow with the first quiver's ammunition.  Only with the show_forecast
 * option.
 */
void forecast_describe_monster(textblock *tb, struct player *p, struct monster *mon)
{
	struct attack_forecast f;
	char name[80];

	if (!OPT(p, show_forecast) || !mon || !mon->race) return;
	if (!defence_known(mon->race, get_lore(mon->race))) {
		textblock_append(tb, "Forecast: you do not yet know its defence.\n");
		return;
	}
	const struct object *weapon = equipped_item_by_slot_name(p, "weapon");
	object_label(name, sizeof(name), weapon, p);
	if (forecast_melee(p, NULL, mon, &f)) describe(tb, name, &f);
	const struct object *bow = equipped_item_by_slot_name(p, "shooting");
	const struct object *ammo = equipped_item_by_slot_name(p, "first quiver");
	if (bow && ammo) {
		char label[160];
		object_desc(name, sizeof(name), ammo, ODESC_BASE | ODESC_PLURAL, p);
		strnfmt(label, sizeof(label), "Shooting %s", name);
		if (forecast_shot(p, NULL, ammo, mon, &f)) describe(tb, label, &f);
		else textblock_append(tb, "%s: out of range.\n", label);
	}
}

/**
 * The forecast of using the object against the current target: a melee weapon
 * as if wielded, a bow with the first quiver's ammunition, or ammunition from
 * the wielded bow.  Only with the show_forecast option and a target.
 */
void forecast_describe_object(textblock *tb, struct player *p, const struct object *obj)
{
	struct attack_forecast f;
	char name[80];

	if (!OPT(p, show_forecast) || !obj || !target_okay(0)) return;
	struct monster *mon = target_get_monster();
	if (!mon) return;
	monster_desc(name, sizeof(name), mon, MDESC_DEFAULT);
	if (!defence_known(mon->race, get_lore(mon->race))) {
		textblock_append(tb, "\nForecast against %s: you do not yet know its defence.\n", name);
		return;
	}
	if (tval_is_melee_weapon(obj)) {
		textblock_append(tb, "\nForecast against %s:\n", name);
		if (forecast_melee(p, obj, mon, &f)) describe(tb, object_is_equipped(p->body, obj) ? "Wielded" : "If wielded", &f);
		const struct object *wielded = equipped_item_by_slot_name(p, "weapon");
		if (wielded != obj && forecast_melee(p, NULL, mon, &f)) {
			char label[80];
			object_label(label, sizeof(label), wielded, p);
			describe(tb, format("Now (%s)", label), &f);
		}
	} else if (tval_is_launcher(obj)) {
		const struct object *ammo = equipped_item_by_slot_name(p, "first quiver");
		textblock_append(tb, "\nForecast against %s:\n", name);
		if (!ammo) textblock_append(tb, "No ammunition in the first quiver.\n");
		else if (forecast_shot(p, obj, ammo, mon, &f)) describe(tb, object_is_equipped(p->body, obj) ? "Shooting" : "If wielded", &f);
		else textblock_append(tb, "Out of range, or the ammunition does not suit it.\n");
	} else if (tval_is_ammo(obj)) {
		textblock_append(tb, "\nForecast against %s:\n", name);
		if (forecast_shot(p, NULL, obj, mon, &f)) describe(tb, "Shooting these", &f);
		else textblock_append(tb, "No bow that shoots these, or out of range.\n");
	}
}
