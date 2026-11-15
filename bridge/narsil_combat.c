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
#include "mon-lore.h"
#include "monster.h"
#include "obj-gear.h"
#include "obj-knowledge.h"
#include "obj-tval.h"
#include "player-abilities.h"
#include "player-attack.h"
#include "player-calcs.h"
#include "player-util.h"

#include <assert.h>
#include <string.h>

enum { MAX_DAM = 512 };

/* The probability of each total of n dice with s sides: pmf[0..n*s] (clamped to MAX_DAM). */
static void dice_pmf(double pmf[MAX_DAM], const int n, const int s)
{
	memset(pmf, 0, sizeof(double) * MAX_DAM);
	pmf[0] = 1.0;
	int top = 0;
	for (int i = 0; i < n && s > 0; i++) {
		double next[MAX_DAM] = { 0 };
		for (int v = 0; v <= top; v++)
			for (int f = 1; f <= s && v + f < MAX_DAM; f++) next[v + f] += pmf[v] / s;
		top = MIN(top + s, MAX_DAM - 1);
		memcpy(pmf, next, sizeof(next));
	}
}

/* The expected damage of n dice of s sides after the monster's protection dice (each reduced to prt_percent). */
static double expected_net(const int n, const int s, const struct monster_race *const race, const int prt_percent)
{
	double dam[MAX_DAM], prt[MAX_DAM];
	dice_pmf(dam, n, s);
	dice_pmf(prt, race->pd, race->ps);
	double total = 0;
	for (int d = 0; d < MAX_DAM; d++) {
		if (dam[d] == 0) continue;
		for (int p = 0; p < MAX_DAM; p++)
			if (prt[p] > 0) total += dam[d] * prt[p] * MAX(d - p * prt_percent / 100, 0);
	}
	return total;
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
static void state_with_weapon(struct object *const weapon, struct player_state *const out)
{
	const int slot = slot_by_name(player, "weapon");
	assert(slot >= 0);
	struct object *const worn = player->body.slots[slot].obj;
	player->body.slots[slot].obj = weapon;
	calc_bonuses(player, out, true, false);
	player->body.slots[slot].obj = worn;
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
	for (const struct ability *a = obj->abilities; a; a = a->next) {
		const bool known = (known_kind && locate_ability(obj->kind->abilities, a))
			|| (known_ego && locate_ability(obj->ego->abilities, a))
			|| (obj->known && locate_ability(obj->known->abilities, a));
		if (known && a->desc) add_line(buf, cap, "%s: %s", a->name, a->desc);
	}
}

static void weapon_text(struct object *const obj, char *const buf, const size_t cap)
{
	if (!tval_is_melee_weapon(obj)) return;
	const struct monster_race *const race = player->upkeep->monster_race;
	if (!race) {
		add_line(buf, cap, "Look at or fight a monster to see hit and damage numbers for this weapon against it.");
		return;
	}
	const int slot = slot_by_name(player, "weapon");
	struct object *const wielded = slot >= 0 ? player->body.slots[slot].obj : NULL;
	if (obj != wielded && equipped_item_slot(player->body, obj) < player->body.count) return;  /* worn in another slot (off hand) */

	const melee_odds mine = melee_with(obj, race);
	add_line(buf, cap, "Against the %s (the last foe you looked at or fought), about:", race->name);
	add_line(buf, cap, "hit %.0f%%, critical %.0f%% of blows; %.1f damage per blow, %.1f per round.",
		mine.main.hit * 100, mine.main.crit * 100, mine.main.per_blow, mine.per_round);
	if (obj == wielded) return;
	const melee_odds now = melee_with(wielded, race);
	add_line(buf, cap, "Your %s now: hit %.0f%%, %.1f per blow, %.1f per round.", wielded ? "weapon" : "bare hands",
		now.main.hit * 100, now.main.per_blow, now.per_round);
	if (now.per_round >= 1.0) add_line(buf, cap, "That is %+.0f%% damage per round.", (mine.per_round / now.per_round - 1) * 100);
	else add_line(buf, cap, "That is %+.1f damage per round.", mine.per_round - now.per_round);
}

size_t native_item_details(struct object *const obj, char *const buf, const size_t cap)
{
	assert(cap > 0);
	buf[0] = '\0';
	abilities_text(obj, buf, cap);
	weapon_text(obj, buf, cap);
	return strlen(buf);
}
