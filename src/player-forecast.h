/**
 * \file player-forecast.h
 * \brief Combat forecasts: the chance to hit, criticals and damage of an attack
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

#ifndef INCLUDED_PLAYER_FORECAST_H
#define INCLUDED_PLAYER_FORECAST_H

#include "z-textblock.h"

struct monster;
struct object;
struct player;

/**
 * What one attack at a monster is expected to do, as the player knows it: the
 * exact odds over the d20 rolls and the damage and protection dice.
 */
struct attack_forecast {
	int attacks;		/* attacks a turn (Rapid Attack, a second weapon, Rapid Fire) */
	int att, evn;		/* the scores each side adds to its d20 */
	double hit;			/* chance one attack hits */
	double crit;		/* chance one attack hits with at least one critical die */
	double dmg;			/* mean damage of one attack after protection, a miss counting 0 */
	double dmg_hit;		/* mean damage of a hit after protection */
	int max_dmg;		/* the most one hit can do */
};

/**
 * One attack: the scores, the dice and what changes them.
 */
struct forecast_roll {
	int att, evn;
	bool cursed;		/* the worse of two attack rolls */
	bool accurate;		/* a miss is rolled again */
	bool cumbersome;	/* no critical dice */
	int weight, skill;	/* for crit_bonus() */
	int extra_crit;		/* critical dice every hit gets (Ambush) */
	int dd, ds, slay;	/* damage dice and sides, slay and brand dice */
	int pd, ps, prt_pct;	/* protection dice and sides, the percentage sharpness leaves */
	bool puncture;		/* a blocked shot still does 5 */
};

void forecast_odds(const struct forecast_roll *r, const int *crits,
		struct attack_forecast *f);
bool forecast_melee(struct player *p, const struct object *weapon,
		struct monster *mon, struct attack_forecast *f);
bool forecast_shot(struct player *p, const struct object *bow,
		const struct object *ammo, struct monster *mon,
		struct attack_forecast *f);
void forecast_describe_monster(textblock *tb, struct player *p,
		struct monster *mon);
void forecast_describe_object(textblock *tb, struct player *p,
		const struct object *obj);

#endif /* !INCLUDED_PLAYER_FORECAST_H */
