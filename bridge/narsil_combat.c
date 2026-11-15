/**
 * \file narsil_combat.c
 * \brief Extra lines for the pack's item description, behind an option (nb_set_item_details()):
 *        what an ability the item grants does, and for a melee weapon the expected hit chance,
 *        crit chance and damage against the foe you last looked at or fought, beside your wielded weapon.
 *
 * The numbers come from the game's own rules, not a copy of them: the character's state is recalculated
 * with the weapon in the weapon slot (calc_bonuses), and a hit roll is (1d10 + attack) - (1d10 + evasion),
 * so all 100 rolls are enumerated.  Left out, so the figures are "about": slays and brands, charging,
 * focus and concentration bonuses, and the monster's state (asleep, afraid, in light).
 */
#include "narsil_internal.h"

#include "angband.h"
#include "combat.h"
#include "mon-blows.h"
#include "mon-lore.h"
#include "monster.h"
#include "obj-gear.h"
#include "obj-knowledge.h"
#include "obj-tval.h"
#include "player-abilities.h"
#include "player-attack.h"
#include "player-calcs.h"
#include "player-util.h"
#include "monster.h"
#include "init.h"

#include <assert.h>
#include <string.h>

enum { MAX_DAM = 512 };

/* A distribution of dice totals: pmf[0..top], starting as "0 with certainty", grown one group of dice at a time. */
typedef struct { double p[MAX_DAM]; int top; } dice_dist;

static void dist_start(dice_dist *const d)
{
	memset(d, 0, sizeof(*d));
	d->p[0] = 1.0;
}

static void dist_add(dice_dist *const d, const int n, const int s)
{
	for (int i = 0; i < n && s > 0; i++) {
		double next[MAX_DAM] = { 0 };
		for (int v = 0; v <= d->top; v++)
			for (int f = 1; f <= s && v + f < MAX_DAM; f++) next[v + f] += d->p[v] / s;
		d->top = MIN(d->top + s, MAX_DAM - 1);
		memcpy(d->p, next, sizeof(next));
	}
}

/* E[max(damage - protection * percent / 100, 0)] for two distributions. */
static double expected_after(const dice_dist *const dam, const dice_dist *const prt, const int prt_percent)
{
	double total = 0;
	for (int d = 0; d <= dam->top; d++) {
		if (dam->p[d] == 0) continue;
		for (int p = 0; p <= prt->top; p++)
			if (prt->p[p] > 0) total += dam->p[d] * prt->p[p] * MAX(d - p * prt_percent / 100, 0);
	}
	return total;
}

/* The expected damage of n dice of s sides after the monster's protection dice (each reduced to prt_percent). */
static double expected_net(const int n, const int s, const struct monster_race *const race, const int prt_percent)
{
	dice_dist dam, prt;
	dist_start(&dam); dist_add(&dam, n, s);
	dist_start(&prt); dist_add(&prt, race->pd, race->ps);
	return expected_after(&dam, &prt, prt_percent);
}

/* The race the weapon numbers are against: the one chosen in the pack, else the last looked at or fought. Game thread. */
static const struct monster_race *chosen_foe;

void native_set_compare_foe(const unsigned ridx)
{
	chosen_foe = ridx > 0 && ridx < z_info->r_max ? &r_info[ridx] : NULL;
}

static const struct monster_race *foe(void)
{
	return chosen_foe ? chosen_foe : player->upkeep->monster_race;
}

typedef struct { double hit, crit, per_blow; } blow_odds;

/* One blow with [weapon] (NULL: fists) of dice [mdd]d[mds], attack score [att], against the race at its base evasion. */
static blow_odds blow_vs(const struct object *const weapon, const int att, const int mdd, const int mds,
	const struct monster_race *const race)
{
	assert(race);
	enum { MAX_CRIT = 12 };
	double by_crit[MAX_CRIT + 1] = { 0 };   /* the probability of a hit with that many crit dice */
	blow_odds o = { 0, 0, 0 };
	for (int a = 1; a <= 10; a++)
		for (int b = 1; b <= 10; b++) {
			const int margin = att + a - race->evn - b;
			if (margin <= 0) continue;
			const int crit = MIN(crit_bonus(player, margin, weapon ? weapon->weight : 0, race, SKILL_MELEE, false), MAX_CRIT);
			by_crit[crit] += 0.01;
			o.hit += 0.01;
			if (crit > 0) o.crit += 0.01;
		}
	int flag = 0;
	const int prt_percent = weapon ? prt_after_sharpness(player, weapon, &flag) : 100;
	for (int c = 0; c <= MAX_CRIT; c++)
		if (by_crit[c] > 0) o.per_blow += by_crit[c] * expected_net(mdd + c, mds, race, prt_percent);
	return o;
}

typedef struct { blow_odds main; double per_round; } melee_odds;

/*
 * The character's state with [weapon] (NULL: bare hands) in the weapon slot.  MUTABLE: the slot's pointer is swapped
 * for the duration of calc_bonuses() and restored before returning (it recomputes only into [out]; with known_only,
 * unknown runes do not leak).  Game thread, at a prompt: nothing else reads the slot meanwhile.
 */
static void state_with_item(const int slot, struct object *const item, struct player_state *const out)
{
	assert(slot >= 0 && slot < player->body.count);
	struct object *const worn = player->body.slots[slot].obj;
	player->body.slots[slot].obj = item;
	calc_bonuses(player, out, true, false);
	player->body.slots[slot].obj = worn;
}

static void state_with_weapon(struct object *const weapon, struct player_state *const out)
{
	state_with_item(slot_by_name(player, "weapon"), weapon, out);
}

static melee_odds melee_with(struct object *const weapon, const struct monster_race *const race)
{
	struct player_state st;
	state_with_weapon(weapon, &st);
	melee_odds m;
	m.main = blow_vs(weapon, st.skill_use[SKILL_MELEE], st.mdd, st.mds, race);
	const int main_blows = 1 + (player_active_ability(player, "Rapid Attack") ? 1 : 0);
	m.per_round = main_blows * m.main.per_blow;
	const struct object *const off = equipped_item_by_slot_name(player, "arm");
	if (st.mds2 > 0 && off)
		m.per_round += blow_vs(off, st.skill_use[SKILL_MELEE] + st.offhand_mel_mod, st.mdd2, st.mds2, race).per_blow;
	return m;
}

static void add_line(char *const buf, const size_t cap, const char *const fmt, ...) __attribute__((format(printf, 3, 4)));
static void add_line(char *const buf, const size_t cap, const char *const fmt, ...)
{
	va_list args;
	va_start(args, fmt);
	char line[256];
	vstrnfmt(line, sizeof(line), fmt, args);
	va_end(args);
	my_strcat(buf, line, cap);
	my_strcat(buf, "\n", cap);
}

/* What each ability the item is known to grant does: its name, then the game's description. */
static void abilities_text(const struct object *const obj, char *const buf, const size_t cap)
{
	const bool known_kind = obj->kind && obj->kind->aware;
	const bool known_ego = obj->ego && obj->ego->aware;
	for (struct ability *a = obj->abilities; a; a = a->next) {
		const bool known = (known_kind && locate_ability(obj->kind->abilities, a))
			|| (known_ego && locate_ability(obj->ego->abilities, a))
			|| (obj->known && locate_ability(obj->known->abilities, a));
		if (known && a->desc) add_line(buf, cap, "%s: %s", a->name, a->desc);
	}
}

static void weapon_text(struct object *const obj, char *const buf, const size_t cap)
{
	if (!tval_is_melee_weapon(obj)) return;
	const struct monster_race *const race = foe();
	if (!race) {
		add_line(buf, cap, "Look at or fight a monster to see hit and damage numbers for this weapon against it.");
		return;
	}
	const int slot = slot_by_name(player, "weapon");
	struct object *const wielded = slot >= 0 ? player->body.slots[slot].obj : NULL;
	if (obj != wielded && equipped_item_slot(player->body, obj) < player->body.count) return;  /* worn in another slot (off hand) */

	const melee_odds mine = melee_with(obj, race);
	add_line(buf, cap, "Against the %s %s, about:", race->name, chosen_foe ? "(your choice)" : "(the last foe you looked at or fought)");
	add_line(buf, cap, "hit %.0f%%, critical %.0f%% of blows; %.1f damage per blow, %.1f per round.",
		mine.main.hit * 100, mine.main.crit * 100, mine.main.per_blow, mine.per_round);
	if (obj == wielded) return;
	const melee_odds now = melee_with(wielded, race);
	add_line(buf, cap, "Your %s now: hit %.0f%%, %.1f per blow, %.1f per round.", wielded ? "weapon" : "bare hands",
		now.main.hit * 100, now.main.per_blow, now.per_round);
	if (now.per_round >= 1.0) add_line(buf, cap, "That is %+.0f%% damage per round.", (mine.per_round / now.per_round - 1) * 100);
	else add_line(buf, cap, "That is %+.1f damage per round.", mine.per_round - now.per_round);
}


/* What a set of worn items does against melee: evasion, stealth, and the protection dice (as protection_roll() adds them). */
enum { MAX_GROUPS = 24 };
typedef struct { int evasion, stealth; int pd[MAX_GROUPS], ps[MAX_GROUPS], groups; } armour_profile;


static armour_profile profile_with(const int slot, struct object *const item)
{
	struct player_state st;
	armour_profile a = { 0 };
	struct object *const worn = player->body.slots[slot].obj;
	player->body.slots[slot].obj = item;   /* MUTABLE for this block, see state_with_item() */
	calc_bonuses(player, &st, true, false);
	a.evasion = st.skill_use[SKILL_EVASION];
	a.stealth = st.skill_use[SKILL_STEALTH];
	for (int i = 0; i < player->body.count && a.groups < MAX_GROUPS; i++) {
		const struct object *const o = player->body.slots[i].obj;
		if (!o || (slot_type_is(player, i, EQUIP_SHIELD) && tval_is_weapon(o))) continue;
		const int pd = o->pd, ps = o->ps;
		if (ps > 0 && (slot_type_is(player, i, EQUIP_SHIELD) ? pd > 0 : true)) { a.pd[a.groups] = pd; a.ps[a.groups] = ps; a.groups++; }
	}
	player->body.slots[slot].obj = worn;
	return a;
}

/* The foe's melee as it lands on this armour: the chance a blow hits, and the average damage of one attack after protection. */
typedef struct { double hit, per_attack, protection; } defence;

static defence defence_vs(const armour_profile *const a, const struct monster_race *const race)
{
	dice_dist prt;
	dist_start(&prt);
	defence d = { 0, 0, 0 };
	for (int g = 0; g < a->groups; g++) { dist_add(&prt, a->pd[g], a->ps[g]); d.protection += a->pd[g] * (a->ps[g] + 1) / 2.0; }
	/* One blow per attack: the second kind a third of the time when the race has one (make_attack_normal()). */
	const int kinds = race->blow[1].method ? 2 : 1;
	for (int k = 0; k < kinds; k++) {
		const struct monster_blow *const b = &race->blow[k];
		if (!b->method) continue;
		const double weight = kinds == 2 ? (k == 0 ? 2.0 / 3 : 1.0 / 3) : 1.0;
		const int att = b->dice.base, dd = b->dice.dice, ds = b->dice.sides;
		double hit = 0, dam = 0;
		for (int x = 1; x <= 10; x++)
			for (int y = 1; y <= 10; y++) {
				const int margin = att + x - a->evasion - y;
				if (margin <= 0) continue;
				hit += 0.01;
				const int crit = b->method->prt ? MIN(crit_bonus(player, margin, 20 * dd, NULL, SKILL_MELEE, false), 12) : 0;
				dice_dist roll;
				dist_start(&roll);
				dist_add(&roll, dd + crit, ds);
				dam += 0.01 * (b->method->prt ? expected_after(&roll, &prt, 100) : (double)((dd + crit) * (ds + 1)) / 2);
			}
		d.hit += weight * hit;
		d.per_attack += weight * dam;
	}
	return d;
}

static void armour_text(struct object *const obj, char *const buf, const size_t cap)
{
	if (!tval_is_armor(obj) && !tval_is_jewelry(obj)) return;
	const struct monster_race *const race = foe();
	if (!race) {
		add_line(buf, cap, "Look at or fight a monster to see what this does against its attacks.");
		return;
	}
	const int slot = wield_slot(obj);
	if (slot < 0 || slot >= player->body.count) return;
	struct object *const worn = player->body.slots[slot].obj;
	if (obj != worn && equipped_item_slot(player->body, obj) < player->body.count) return;  /* worn in another slot */

	const armour_profile with = profile_with(slot, obj);
	const defence mine = defence_vs(&with, race);
	add_line(buf, cap, "Against the %s %s, about:", race->name, chosen_foe ? "(your choice)" : "(the last foe you looked at or fought)");
	add_line(buf, cap, "its attacks hit you %.0f%% of the time for %.1f damage each (your protection averages %.1f).",
		mine.hit * 100, mine.per_attack, mine.protection);
	if (obj == worn) return;
	const armour_profile without = profile_with(slot, worn);
	const defence now = defence_vs(&without, race);
	add_line(buf, cap, "%s: hit %.0f%%, %.1f damage each (protection averages %.1f).",
		worn ? "What you wear there now" : "With nothing worn there", now.hit * 100, now.per_attack, now.protection);
	if (now.per_attack >= 0.5) add_line(buf, cap, "Damage taken per attack: %+.0f%%.", (mine.per_attack / now.per_attack - 1) * 100);
	else add_line(buf, cap, "Damage taken per attack: %+.1f.", mine.per_attack - now.per_attack);
	add_line(buf, cap, "Evasion %d (now %d), stealth %d (now %d).", with.evasion, without.evasion, with.stealth, without.stealth);
}

size_t native_item_details(struct object *const obj, char *const buf, const size_t cap)
{
	assert(cap > 0);
	buf[0] = '\0';
	abilities_text(obj, buf, cap);
	weapon_text(obj, buf, cap);
	armour_text(obj, buf, cap);
	return strlen(buf);
}

typedef struct { unsigned ridx; bool in_view; int distance; } foe_choice;

static int by_nearness(const void *const a, const void *const b)
{
	const foe_choice *const x = a, *const y = b;
	if (x->in_view != y->in_view) return x->in_view ? -1 : 1;
	return x->distance != y->distance ? x->distance - y->distance : (int)x->ridx - (int)y->ridx;
}

/*
 * Foes the weapon numbers can be compared against: "#F <chosen race or 0> <name>", then "#R <race> <name> <level> <in view>"
 * for the monsters in view, then those seen before, nearest to the current depth first.
 */
size_t native_foe_choices(char *const buf, const size_t cap)
{
	assert(cap > 0);
	foe_choice *const all = mem_zalloc(sizeof(*all) * z_info->r_max);
	unsigned n = 0;
	for (unsigned i = 1; i < z_info->r_max; i++) {
		const struct monster_race *const r = &r_info[i];
		if (!r->name || !(l_list[i].all_known || l_list[i].tsights)) continue;
		all[n++] = (foe_choice){ i, false, abs(r->level - player->depth) };
	}
	for (int m = 1; m < cave_monster_max(cave); m++) {
		const struct monster *const mon = cave_monster(cave, m);
		if (!mon || !mon->race || !monster_is_visible(mon)) continue;
		bool listed = false;
		for (unsigned i = 0; i < n; i++) if (all[i].ridx == mon->race->ridx) { all[i].in_view = true; listed = true; }
		if (!listed) all[n++] = (foe_choice){ mon->race->ridx, true, 0 };
	}
	qsort(all, n, sizeof(*all), by_nearness);
	buf[0] = '\0';
	const struct monster_race *const cur = chosen_foe;
	add_line(buf, cap, "#F\t%u\t%s", cur ? cur->ridx : 0, foe() ? foe()->name : "");
	for (unsigned i = 0; i < n && i < 150; i++)
		add_line(buf, cap, "#R\t%u\t%s\t%d\t%d", all[i].ridx, r_info[all[i].ridx].name, r_info[all[i].ridx].level, all[i].in_view ? 1 : 0);
	mem_free(all);
	return strlen(buf);
}
