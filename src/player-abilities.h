/**
 * \file player-abilities.h
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

#ifndef INCLUDED_PLAYER_ABILITIES_H
#define INCLUDED_PLAYER_ABILITIES_H

/**
 * Ability
 */
struct ability {
	struct ability *next;
	char *name;
	char *desc;
	uint8_t skill;
	uint8_t level;
	bool active;
	bool last;
	struct ability *prerequisites;
	int prereq_index[MAX_PREREQS];		/* Temporary field for parsing */
	struct poss_item *poss_items;
};

extern struct ability *abilities;

/**
 * ability_predicate is a function pointer which tests a given ability to
 * see if the predicate in question is true.
 */
typedef bool (*ability_predicate)(const struct ability *test);

struct ability *lookup_ability(int skill, const char *name);
bool applicable_ability(struct ability *ability, struct object *obj);
struct ability *locate_ability(struct ability *ability, struct ability *test);
void add_ability(struct ability **set, struct ability *add);
void activate_ability(struct ability **set, struct ability *activate);
void remove_ability(struct ability **ability, struct ability *remove);
bool player_has_ability(struct player *p, struct ability *ability);
int player_active_ability(struct player *p, const char *name);
bool player_has_prereq_abilities(struct player *p, struct ability *ability);
int player_ability_cost(struct player *p, struct ability *ability);
bool player_can_gain_ability(struct player *p, struct ability *ability);
/* NarSil-Q's oaths of the "Oath" ability; the flag of an oath is 1 << (oath - 1) */
enum { OATH_NONE, OATH_MERCY, OATH_SILENCE, OATH_IRON, OATH_MAX };
bool player_oath_active(const struct player *p, int oath);
const char *player_oath_name(const struct player *p);
const char *player_oath_vow(const struct player *p);
bool player_oath_broken(const struct player *p);
bool player_oath_break(struct player *p, int oath, bool ask);
bool player_gain_ability(struct player *p, struct ability *ability);

/**
 * A saved build: the choices that make a character at the start of a game
 * (race, house, sex, the points bought in each stat and skill, the abilities
 * and the oath), kept in builds.txt so the same character can be made again.
 */
#define BUILD_NAME_LEN 48
#define BUILD_MAX 24
#define BUILD_MAX_ABILITIES 64
#define BUILD_STATS 4
#define BUILD_SKILLS 8
struct player_build {
	char name[BUILD_NAME_LEN];
	char race[32], house[32], sex[16];
	int stat_buys[BUILD_STATS];		/* points bought in each stat, beyond what race and house give */
	int skills[BUILD_SKILLS];		/* skill points bought */
	int n_abilities;
	struct { uint8_t skill; char name[40]; } ability[BUILD_MAX_ABILITIES];
	uint8_t oath;				/* the oath sworn, if the Oath ability is in the build */
	bool fixed_exp;				/* made with the fixed experience birth option */
};
bool build_capture(const struct player *p, const char *name, struct player_build *b);
int builds_load(struct player_build *list, int max);
bool builds_save(const struct player_build *list, int n);
int build_exp_cost(const struct player_build *b);
void build_run_birth(const struct player_build *b);
int build_shortfall(const struct player_build *b);
void build_clear_pending(void);
void build_apply_pending(struct player *p);
void release_ability_list(struct ability *head);
struct ability *copy_ability_list(const struct ability *head);

extern struct file_parser ability_parser;

#endif /* !INCLUDED_PLAYER_ABILITIES_H */
