/**
 * \file player-abilities.c 
 * \brief Player abilities
 *
 * Copyright (c) 1997-2020 Ben Harrison, James E. Wilson, Robert A. Koeneke,
 * Nick McConnell
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
#include "cmd-core.h"
#include "datafile.h"
#include "game-input.h"
#include "init.h"
#include "obj-tval.h"
#include "obj-util.h"
#include "player-abilities.h"
#include "player-calcs.h"
#include "player-util.h"

struct ability *abilities;

/**
 * ------------------------------------------------------------------------
 * Initialize abilities
 * ------------------------------------------------------------------------ */
/* Temporary list to store prerequisite details; 199 should be enough */
static struct {
	uint8_t skill;
	const char *name;
} prereq_list[100];
static unsigned int prereq_num = 1;

static unsigned int skill_index;

static enum parser_error parse_ability_skill(struct parser *p) {
	const char *name = parser_getstr(p, "name");
	int index = lookup_skill(name);
	if (index < 0)
		return PARSE_ERROR_UNRECOGNISED_SKILL;

	skill_index = (unsigned int) index;

	return PARSE_ERROR_NONE;
}

static enum parser_error parse_ability_name(struct parser *p) {
	const char *name = parser_getstr(p, "name");
	struct ability *last = parser_priv(p);
	struct ability *a = mem_zalloc(sizeof *a);

	if (last) {
		last->next = a;
	} else {
		abilities = a;
	}
	parser_setpriv(p, a);
	a->name = string_make(name);
	a->skill = skill_index;
	return PARSE_ERROR_NONE;
}

static enum parser_error parse_ability_level(struct parser *p) {
	struct ability *a = parser_priv(p);
	if (!a)
		return PARSE_ERROR_MISSING_RECORD_HEADER;

	a->level = parser_getint(p, "level");
	return PARSE_ERROR_NONE;
}

static enum parser_error parse_ability_prereq(struct parser *p) {
	int skill = lookup_skill(parser_getsym(p, "skill"));
	const char *name = parser_getsym(p, "ability");
	struct ability *a = parser_priv(p);
	int i;

	if (!a)
		return PARSE_ERROR_MISSING_RECORD_HEADER;
	if (skill < 0)
		return PARSE_ERROR_INVALID_SKILL;

	/* Store the prereq details locally */
	prereq_list[prereq_num].skill = skill;
	prereq_list[prereq_num].name = string_make(name);

	/* Store the index in the struct */
	for (i = 0; i < MAX_PREREQS; i++) {
		if (!a->prereq_index[i]) break;
	}
	if (i == MAX_PREREQS)
		return PARSE_ERROR_TOO_MANY_ABILITY_PREREQS;
	a->prereq_index[i] = prereq_num;
	prereq_num++;
	return PARSE_ERROR_NONE;
}

static enum parser_error parse_ability_type(struct parser *p) {
	struct poss_item *poss;
	int i;
	int tval = tval_find_idx(parser_getstr(p, "tval"));
	bool found_one_kind = false;

	struct ability *a = parser_priv(p);
	if (!a)
		return PARSE_ERROR_MISSING_RECORD_HEADER;
	if (tval < 0)
		return PARSE_ERROR_UNRECOGNISED_TVAL;

	/* Find all the right object kinds */
	for (i = 0; i < z_info->k_max; i++) {
		if (k_info[i].tval != tval) continue;
		poss = mem_zalloc(sizeof(struct poss_item));
		poss->kidx = i;
		poss->next = a->poss_items;
		a->poss_items = poss;
		found_one_kind = true;
	}

	if (!found_one_kind)
		return PARSE_ERROR_NO_KIND_FOR_ABILITY;
	return PARSE_ERROR_NONE;
}

static enum parser_error parse_ability_item(struct parser *p) {
	struct poss_item *poss;
	int tval = tval_find_idx(parser_getsym(p, "tval"));
	int sval = lookup_sval(tval, parser_getsym(p, "sval"));

	struct ability *a = parser_priv(p);
	if (!a)
		return PARSE_ERROR_MISSING_RECORD_HEADER;
	if (tval < 0)
		return PARSE_ERROR_UNRECOGNISED_TVAL;
	if (sval < 0)
		return PARSE_ERROR_UNRECOGNISED_SVAL;

	poss = mem_zalloc(sizeof(struct poss_item));
	poss->kidx = lookup_kind(tval, sval)->kidx;
	poss->next = a->poss_items;
	a->poss_items = poss;

	if (poss->kidx <= 0)
		return PARSE_ERROR_INVALID_ITEM_NUMBER;
	return PARSE_ERROR_NONE;
}

static enum parser_error parse_ability_desc(struct parser *p) {
	struct ability *a = parser_priv(p);
	if (!a)
		return PARSE_ERROR_MISSING_RECORD_HEADER;

	a->desc = string_append(a->desc, parser_getstr(p, "desc"));
	return PARSE_ERROR_NONE;
}

static struct parser *init_parse_ability(void) {
	struct parser *p = parser_new();
	parser_setpriv(p, NULL);
	prereq_num = 1;
	parser_reg(p, "skill str name", parse_ability_skill);
	parser_reg(p, "name str name", parse_ability_name);
	parser_reg(p, "level int level", parse_ability_level);
	parser_reg(p, "prerequisite sym skill sym ability", parse_ability_prereq);
	parser_reg(p, "type str tval", parse_ability_type);
	parser_reg(p, "item sym tval sym sval", parse_ability_item);
	parser_reg(p, "desc str desc", parse_ability_desc);
	return p;
}

static errr run_parse_ability(struct parser *p) {
	return parse_file_quit_not_found(p, "ability");
}

static errr finish_parse_ability(struct parser *p) {
	struct ability *a;

	/* Fill in prerequisite abilities */
	for (a = abilities; a; a = a->next) {
		int i = 0;
		while (a->prereq_index[i]) {
			int idx = a->prereq_index[i];
			struct ability *pre = mem_zalloc(sizeof *pre);
			struct ability *stored = lookup_ability(prereq_list[idx].skill,
													prereq_list[idx].name);
			if (!stored)
				return PARSE_ERROR_INVALID_ABILITY;
			memcpy(pre, stored, sizeof(*pre));
			pre->next = a->prerequisites;
			a->prerequisites = pre;
			i++;
		}
	}

	/* Done with prereq_list so release the resources allocated for it. */
	while (prereq_num > 1) {
		--prereq_num;
		string_free((char*)prereq_list[prereq_num].name);
		prereq_list[prereq_num].name = NULL;
	}

	parser_destroy(p);
	return 0;
}

static void cleanup_ability(void)
{
	struct ability *a = abilities;
	while (a) {
		struct ability *a_next = a->next;
		struct ability *pre = a->prerequisites;
		struct poss_item *poss = a->poss_items;
		while (poss) {
			struct poss_item *poss_next = poss->next;
			mem_free(poss);
			poss = poss_next;
		}
		while (pre) {
			struct ability *pre_next = pre->next;
			mem_free(pre);
			pre = pre_next;
		}
		string_free(a->name);
		string_free(a->desc);
		mem_free(a);
		a = a_next;
	}
}

struct file_parser ability_parser = {
	"ability",
	init_parse_ability,
	run_parse_ability,
	finish_parse_ability,
	cleanup_ability
};

/**
 * ------------------------------------------------------------------------
 * Ability utilities
 * ------------------------------------------------------------------------ */
/**
 * Find an ability given its name and skill
 */
struct ability *lookup_ability(int skill, const char *name)
{
	struct ability *ability = abilities;
	if (skill < 0) {
		msg("Invalid skill index passed to lookup_ability()!");
		return NULL;
	}
	while (ability) {
		if ((ability->skill == skill) && streq(ability->name, name)) {
			return ability;
		}
		ability = ability->next;
	}
	return NULL;
}

/**
 * Counts the abilities for a given skill in a set.
 * If the skill is SKILL_MAX, count all abilities.
 */
static int count_abilities(struct ability *ability, int skill)
{
	int count = 0;
	assert(0 <= skill && skill <= SKILL_MAX);
	while (ability) {
		if ((skill == SKILL_MAX) || (skill == ability->skill)) {
			count++;
		}
		ability = ability->next;
	}
	return count;
}

static bool ability_is_active(const struct ability *ability)
{
	return ability->active;
}

/**
 * The engine asks for abilities by NarSil's name; NarSil-Q's tree spells two of them differently.
 */
static const char *ability_alias(const char *name)
{
	if (streq(name, "Heavy Armour")) return "Heavy Armour Use";
	if (streq(name, "Focused Attack")) return "Focused attack";
	return NULL;
}

static int test_ability(const char *name, struct ability *test,
						ability_predicate pred)
{
	int skill, count = 0;
	bool found = false;

	if (!test) return 0;

	/* Look in every skill for an ability with the right name */
	for (skill = 0; skill < SKILL_MAX; skill++) {
		struct ability *ability = lookup_ability(skill, name);
		if (ability) {
			struct ability *thisa = test;

			/* Note that we have found an ability of that name */
			found = true;

			/* See if the provided ability list contains the named one... */
			while (thisa) {
				if (streq(thisa->name, name) && (thisa->skill == skill)) {
					/* ...and if so, if it satisfies any required condition */
					if (!pred || (pred && pred(thisa))) {
						count++;
					}
				}
				thisa = thisa->next;
			}
		}
	}
	if (!found) {
		/* A game whose tree lacks this ability (NarSil-Q lacks some of NarSil's) simply never has it */
		const char *alias = ability_alias(name);
		return alias ? test_ability(alias, test, pred) : 0;
	}
	return count;
}

/**
 * Does the given object type support the given ability type?
 */
bool applicable_ability(struct ability *ability, struct object *obj)
{
	struct poss_item *poss = ability->poss_items;

	for (poss = ability->poss_items; poss; poss = poss->next) {
		if (poss->kidx == obj->kind->kidx) return true;
	}		

	/* Throwing Mastery is OK for throwing items */
	if (of_has(obj->flags, OF_THROWING) && (ability->skill == SKILL_MELEE) &&
		streq(ability->name, "Throwing Mastery")) {
		return true;
	}

	return false;
}

/**
 * Reports if a given ability is already in a set of abilities.
 */
struct ability *locate_ability(struct ability *ability, struct ability *test)
{
	/* Look for the right one */
	while (ability) {
		if ((ability->skill == test->skill) &&
			streq(ability->name, test->name)) break;
		ability = ability->next;
	}
	return ability;
}

/**
 * Adds a given ability to a set of abilities.
 */
void add_ability(struct ability **set, struct ability *add)
{	
	struct ability *new;

	/* Check if we have it already */
	new = *set;
	if (locate_ability(new, add)) return;

	/* Not found, add the new one */
	new = mem_zalloc(sizeof(*new));
	memcpy(new, add, sizeof(*new));
	new->next = *set;
	*set = new;
}

/**
 * Activates a given ability in a set of abilities.
 */
void activate_ability(struct ability **set, struct ability *activate)
{
	struct ability *ability;
	for (ability = *set; ability; ability = ability->next) {
		if (streq(ability->name, activate->name)) {
			ability->active = true;
			break;
		}
	}
}

/**
 * Removes a given ability from a set of abilities.
 */
void remove_ability(struct ability **ability, struct ability *remove)
{
	struct ability *current = *ability, *prev = NULL, *next = NULL;

	/* Look for the ability to remove */
	while (current) {
		next = current->next;
		if ((current->skill == remove->skill) &&
			streq(current->name, remove->name)) {
			break;
		}
		prev = current;
		current = next;
	}

	/* Excise the ability if we have it */
	if (current) {
		if (prev) {
			/* We're removing an ability from the middle or end of the list */
			prev->next = next;
		} else {
			/* We're removing the head ability and replacing it with the next */
			*ability = next;
		}
		mem_free(current);
	}
}

bool player_has_ability(struct player *p, struct ability *ability)
{
	if (!ability) return false;
	if (locate_ability(p->abilities, ability)) return true;
	if (locate_ability(p->item_abilities, ability)) return true;
	return false;
}

int player_active_ability(struct player *p, const char *name)
{
	int count;
	if (!p) return 0;
	count = test_ability(name, p->abilities, ability_is_active);
	count += test_ability(name, p->item_abilities, ability_is_active);
	return count;
}

bool player_has_prereq_abilities(struct player *p, struct ability *ability)
{
	struct ability *prereqs = ability->prerequisites;
	if (player_active_ability(p, "Quick Study")) return true;
	if (prereqs) {
		while (prereqs) {
			struct ability *possessed = p->abilities;
			while (possessed) {
				if (streq(possessed->name, prereqs->name) &&
					(possessed->skill == prereqs->skill)) {
					return true;
				}
				possessed = possessed->next;
			}
			prereqs = prereqs->next;
		}
		return false;
	}
	return true;
}

/**
 * Ability cost is based on race and class affinity for the relevant skill,
 * the number of abilities already gained from that skill.
 */
int player_ability_cost(struct player *p, struct ability *ability)
{
	int skill = ability->skill;
	int num = count_abilities(p->abilities, skill);
	int cost = (num + 1) * z_info->ability_cost;
	int affinity = p->race->skill_adj[skill] + p->house->skill_adj[skill];
	cost -= affinity * z_info->ability_cost;
	return MAX(0, cost);
}

bool player_can_gain_ability(struct player *p, struct ability *ability)
{
	return player_ability_cost(p, ability) <= p->new_exp;
}

static const char *const oath_names[OATH_MAX] = { "", "mercy", "silence", "iron" };
static const char *const oath_vows[OATH_MAX] = { "",
	"never to attack Men or Elves (+1 Grace while kept)",
	"never to sing (+1 Strength while kept)",
	"never to go up stairs without a Silmaril (+2 Constitution while kept)" };

/**
 * Is the player bound by this oath and still keeping it?
 */
bool player_oath_active(const struct player *p, int oath)
{
	return (p->oath_type == oath) && !(p->oaths_broken & (1 << (oath - 1))) &&
		player_active_ability((struct player *) p, "Oath");
}

/**
 * The player does what this oath forbids.  With ask, a keeper is asked first (false if they decline).
 * The oath can never be sworn afterwards, whether or not it was sworn before.
 */
bool player_oath_break(struct player *p, int oath, bool ask)
{
	const bool keeping = player_oath_active(p, oath);

	if (keeping && ask && !get_check("Are you sure you wish to break your oath? ")) return false;
	if (keeping) {
		msg("You break your oath of %s.", oath_names[oath]);
		p->upkeep->update |= PU_BONUS;
	}
	p->oaths_broken |= (1 << (oath - 1));
	return true;
}

/**
 * Asks which oath to swear, one at a time; false if none was sworn.
 */
static bool player_choose_oath(struct player *p)
{
	for (int round = 0; round < 3; round++) {
		for (int oath = OATH_MERCY; oath < OATH_MAX; oath++) {
			char prompt[160];
			if (p->oaths_broken & (1 << (oath - 1))) continue;
			strnfmt(prompt, sizeof(prompt), "Swear an oath of %s: %s? ", oath_names[oath], oath_vows[oath]);
			if (get_check(prompt)) {
				p->oath_type = oath;
				return true;
			}
		}
	}
	return false;
}

/**
 * Buys an ability.  With ask, the game asks which oath to swear (for the Oath
 * ability) and for confirmation; without, it just buys (the oath is already set).
 */
static bool gain_ability(struct player *p, struct ability *ability, bool ask)
{
	struct ability *new;
	int cost = player_ability_cost(p, ability);

	if (cost > p->new_exp) {
		if (ask) msg("You do not have enough experience to acquire this ability.");
		return false;
	}
	if (ask && streq(ability->name, "Oath")) {
		if (p->oaths_broken == 7) {
			msg("Every oath has been broken before it was made.");
			return false;
		}
		if (!player_choose_oath(p)) return false;
	}
	if (ask && !get_check("Are you sure you wish to gain this ability? ")) {
		if (streq(ability->name, "Oath")) p->oath_type = OATH_NONE;
		return false;
	}
	p->new_exp -= cost;
	add_ability(&p->abilities, ability);
	new = locate_ability(p->abilities, ability);
	new->active = true;
	/*
	 * For some abilities, updating the bonuses is necessary; for some it
	 * is not.  Having that indicated in ability.txt seems like overkill
	 * to avoid an update_bonuses() call.
	 */
	p->upkeep->update |= (PU_BONUS);
	p->upkeep->redraw |= (PR_EXP);
	return true;
}

bool player_gain_ability(struct player *p, struct ability *ability)
{
	return gain_ability(p, ability, true);
}

/**
 * Release a linked list of abilities where each entry in the list is a
 * shallow copy, except for the next field, of an entry in the global abilities
 * list.
 *
 * \param head points to the first entry in the list.
 */
void release_ability_list(struct ability *head)
{
	while (head) {
		struct ability *tgt = head;

		head = head->next;
		mem_free(tgt);
	}
}

/**
 * Copy a linked list of abilities where each entry in the list is a
 * shallow copy, except for the next field, of an entry in the global abilities
 * list.
 *
 * \param head points to the first entry in the list.
 * \return the pointer to the first entry in the copied list.
 */
struct ability *copy_ability_list(const struct ability *head)
{
	struct ability *dest_head, *dest_tail;

	if (!head) {
		return NULL;
	}

	dest_head = mem_alloc(sizeof(*dest_head));
	memcpy(dest_head, head, sizeof(*dest_head));
	dest_tail = dest_head;
	while (head->next) {
		head = head->next;
		dest_tail->next = mem_alloc(sizeof(*(dest_tail->next)));
		dest_tail = dest_tail->next;
		memcpy(dest_tail, head, sizeof(*dest_tail));
	}
	dest_tail->next = NULL;
	return dest_head;
}


/**
 * The oath sworn ("mercy", "silence" or "iron"), its vow, and whether it is broken (none: "" and false).
 */
const char *player_oath_name(const struct player *p)
{
	return p->oath_type > OATH_NONE && p->oath_type < OATH_MAX ? oath_names[p->oath_type] : "";
}

const char *player_oath_vow(const struct player *p)
{
	return p->oath_type > OATH_NONE && p->oath_type < OATH_MAX ? oath_vows[p->oath_type] : "";
}

bool player_oath_broken(const struct player *p)
{
	return p->oath_type > OATH_NONE && p->oath_type < OATH_MAX && (p->oaths_broken & (1 << (p->oath_type - 1)));
}

/**
 * ------------------------------------------------------------------------
 * Saved builds
 * ------------------------------------------------------------------------ */

_Static_assert(BUILD_STATS == STAT_MAX, "a build holds one purchase per stat");
_Static_assert(BUILD_SKILLS == SKILL_MAX, "a build holds one purchase per skill");

/* What a build still has to buy once the character exists (set by build_queue_birth()) */
static struct player_build pending_build;
static bool have_pending;

/**
 * Records the character's build: its race, house and sex, the points bought in
 * each stat and skill, its abilities and its oath.
 *
 * \param p is the character; \param name is what to call the build.
 */
bool build_capture(const struct player *p, const char *name, struct player_build *b)
{
	memset(b, 0, sizeof(*b));
	my_strcpy(b->name, name, sizeof(b->name));
	my_strcpy(b->race, p->race->name, sizeof(b->race));
	my_strcpy(b->house, p->house->name, sizeof(b->house));
	my_strcpy(b->sex, p->sex->name, sizeof(b->sex));
	for (int i = 0; i < STAT_MAX; i++) {
		b->stat_buys[i] = p->stat_base[i] - (p->race->stat_adj[i] + p->house->stat_adj[i]);
	}
	for (int i = 0; i < SKILL_MAX; i++) b->skills[i] = p->skill_base[i];
	for (const struct ability *a = p->abilities; a && b->n_abilities < BUILD_MAX_ABILITIES; a = a->next) {
		b->ability[b->n_abilities].skill = a->skill;
		my_strcpy(b->ability[b->n_abilities].name, a->name, sizeof(b->ability[0].name));
		b->n_abilities++;
	}
	b->oath = p->oath_type;
	b->fixed_exp = OPT(p, birth_fixed_exp);
	return true;
}

static const char *builds_path(char *buf, size_t len)
{
	path_build(buf, len, ANGBAND_DIR_USER, "builds.txt");
	return buf;
}

/**
 * Reads the saved builds, oldest first.  \return how many.
 */
int builds_load(struct player_build *list, int max)
{
	char path[1024], line[256];
	ang_file *f = file_open(builds_path(path, sizeof(path)), MODE_READ, FTYPE_TEXT);
	int n = 0;
	struct player_build *b = NULL;

	if (!f) return 0;
	while (file_getl(f, line, sizeof(line))) {
		char *val = strchr(line, '=');
		if (val) *val++ = '\0';
		if (streq(line, "build")) {
			if (n >= max) break;
			b = &list[n++];
			memset(b, 0, sizeof(*b));
			my_strcpy(b->name, val ? val : "", sizeof(b->name));
		} else if (!b || !val) {
			continue;
		} else if (streq(line, "race")) {
			my_strcpy(b->race, val, sizeof(b->race));
		} else if (streq(line, "house")) {
			my_strcpy(b->house, val, sizeof(b->house));
		} else if (streq(line, "sex")) {
			my_strcpy(b->sex, val, sizeof(b->sex));
		} else if (streq(line, "stats")) {
			sscanf(val, "%d %d %d %d", &b->stat_buys[0], &b->stat_buys[1], &b->stat_buys[2], &b->stat_buys[3]);
		} else if (streq(line, "skills")) {
			sscanf(val, "%d %d %d %d %d %d %d %d", &b->skills[0], &b->skills[1], &b->skills[2], &b->skills[3],
				&b->skills[4], &b->skills[5], &b->skills[6], &b->skills[7]);
		} else if (streq(line, "oath")) {
			b->oath = (uint8_t) atoi(val);
		} else if (streq(line, "fixed")) {
			b->fixed_exp = atoi(val) != 0;
		} else if (streq(line, "ability") && b->n_abilities < BUILD_MAX_ABILITIES) {
			char *name = strchr(val, ':');
			if (!name) continue;
			*name++ = '\0';
			b->ability[b->n_abilities].skill = (uint8_t) atoi(val);
			my_strcpy(b->ability[b->n_abilities].name, name, sizeof(b->ability[0].name));
			b->n_abilities++;
		}
	}
	file_close(f);
	return n;
}

/**
 * Writes the builds, replacing the file.
 */
bool builds_save(const struct player_build *list, int n)
{
	char path[1024];
	ang_file *f = file_open(builds_path(path, sizeof(path)), MODE_WRITE, FTYPE_TEXT);

	if (!f) return false;
	file_putf(f, "# Saved character builds\n");
	for (int k = 0; k < n; k++) {
		const struct player_build *b = &list[k];
		file_putf(f, "build=%s\nrace=%s\nhouse=%s\nsex=%s\n", b->name, b->race, b->house, b->sex);
		file_putf(f, "stats=%d %d %d %d\n", b->stat_buys[0], b->stat_buys[1], b->stat_buys[2], b->stat_buys[3]);
		file_putf(f, "skills=%d %d %d %d %d %d %d %d\n", b->skills[0], b->skills[1], b->skills[2], b->skills[3],
			b->skills[4], b->skills[5], b->skills[6], b->skills[7]);
		file_putf(f, "oath=%d\nfixed=%d\n", b->oath, b->fixed_exp ? 1 : 0);
		for (int i = 0; i < b->n_abilities; i++) {
			file_putf(f, "ability=%d:%s\n", b->ability[i].skill, b->ability[i].name);
		}
	}
	return file_close(f);
}

static const struct player_race *build_race(const struct player_build *b)
{
	for (const struct player_race *r = races; r; r = r->next) if (streq(r->name, b->race)) return r;
	return NULL;
}

static const struct player_house *build_house(const struct player_build *b, const struct player_race *r)
{
	for (const struct player_house *h = houses; h; h = h->next) {
		if (h->race == r && streq(h->name, b->house)) return h;
	}
	return NULL;
}

/**
 * The experience a build spends: the skill points (the nth point in a skill
 * costs 100 n) and the abilities (the kth ability in a skill costs 500 k, less
 * 500 for each point of the race's and house's affinity), or -1 if the build
 * names a race or house the game does not have.
 */
int build_exp_cost(const struct player_build *b)
{
	const struct player_race *r = build_race(b);
	const struct player_house *h = r ? build_house(b, r) : NULL;
	int cost = 0, per_skill[SKILL_MAX] = { 0 };

	if (!r || !h) return -1;
	for (int i = 0; i < SKILL_MAX; i++) cost += 50 * b->skills[i] * (b->skills[i] + 1);
	for (int i = 0; i < b->n_abilities; i++) {
		const int skill = b->ability[i].skill;
		if (skill < 0 || skill >= SKILL_MAX) continue;
		per_skill[skill]++;
		cost += MAX(0, (per_skill[skill] - (r->skill_adj[skill] + h->skill_adj[skill])) * z_info->ability_cost);
	}
	return cost;
}

/**
 * Queues the birth commands that make the build's character, up to the name:
 * race, house and sex, the stat points and the skill points.  Its abilities are
 * bought when the character is accepted.  Call it after the birth reset.
 */
void build_queue_birth(const struct player_build *b)
{
	const struct player_race *r = build_race(b);
	const struct player_house *h = r ? build_house(b, r) : NULL;
	unsigned int first_house = UINT_MAX;
	const struct player_sex *s = NULL;

	if (!r || !h) return;
	for (const struct player_house *x = houses; x; x = x->next) {
		if (x->race == r && x->hidx < first_house) first_house = x->hidx;
	}
	for (const struct player_sex *x = sexes; x; x = x->next) if (streq(x->name, b->sex)) s = x;

	cmdq_push(CMD_CHOOSE_RACE);
	cmd_set_arg_choice(cmdq_peek(), "choice", r->ridx);
	cmdq_push(CMD_CHOOSE_HOUSE);
	cmd_set_arg_choice(cmdq_peek(), "choice", h->hidx - first_house);
	cmdq_push(CMD_CHOOSE_SEX);
	cmd_set_arg_choice(cmdq_peek(), "choice", s ? s->sidx : 0);
	for (int i = 0; i < STAT_MAX; i++) {
		for (int k = 0; k < ABS(b->stat_buys[i]); k++) {
			cmdq_push(b->stat_buys[i] > 0 ? CMD_BUY_STAT : CMD_SELL_STAT);
			cmd_set_arg_choice(cmdq_peek(), "choice", i);
		}
	}
	for (int i = 0; i < SKILL_MAX; i++) {
		for (int k = 0; k < b->skills[i]; k++) {
			cmdq_push(CMD_BUY_SKILL);
			cmd_set_arg_choice(cmdq_peek(), "choice", i);
		}
	}
	pending_build = *b;
	have_pending = true;
}

/**
 * After the commands queued by build_queue_birth() have run: how many stat and
 * skill points the character could not afford, 0 if it is the build.
 */
int build_shortfall(const struct player_build *b)
{
	int missing = 0;

	if (!player->race || !player->house) return -1;
	for (int i = 0; i < STAT_MAX; i++) {
		/* during birth stat_base holds just the points bought */
		missing += ABS(b->stat_buys[i] - player->stat_base[i]);
	}
	for (int i = 0; i < SKILL_MAX; i++) missing += MAX(0, b->skills[i] - player->skill_base[i]);
	return missing;
}

void build_clear_pending(void)
{
	have_pending = false;
}

/**
 * Buys the pending build's abilities for a character just accepted, as far as
 * its experience goes.
 */
void build_apply_pending(struct player *p)
{
	int missed = 0;

	if (!have_pending) return;
	have_pending = false;
	for (int i = 0; i < pending_build.n_abilities; i++) {
		struct ability *a = lookup_ability(pending_build.ability[i].skill, pending_build.ability[i].name);
		if (!a || !gain_ability(p, a, false)) {
			missed++;
		} else if (streq(a->name, "Oath")) {
			p->oath_type = pending_build.oath;	/* the oath sworn is the build's */
		}
	}
	if (missed) {
		msg("The build's experience ran short: %d abilit%s not bought.", missed, missed == 1 ? "y was" : "ies were");
	}
}
