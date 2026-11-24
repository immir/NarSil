/**
 * \file player-path.h
 * \brief Pathfinding and running code.
 *
 * Copyright (c) 1988 Christopher J Stuart (running code)
 * Copyright (c) 2004-2007 Christophe Cavalaria, Leon Marrick (pathfinding)
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

#ifndef PLAYER_PATH_H
#define PLAYER_PATH_H

#include "z-type.h"

int pathfind_direction_to(struct loc from, struct loc to);
bool find_path(struct loc grid);
int path_copy_steps(int *dirs, int max);
void run_step(int dir);

/**
 * Why a run (or a walk along a path) stopped.
 */
enum run_stop {
	RUN_STOP_NONE,		/* still running, or never ran */
	RUN_STOP_MONSTER,	/* a visible monster ahead or alongside */
	RUN_STOP_NOTICE,	/* a trap, an object or an interesting feature beside the way */
	RUN_STOP_JUNCTION,	/* the way divides, opens out or ends */
	RUN_STOP_ARRIVED,	/* the path's end */
	RUN_STOP_DISTURBED	/* anything else that disturbed the player (a monster came into view, a noise, damage) */
};

/**
 * The latest run: steps taken, why it stopped, whether it followed a path; seq counts the runs that have ended.
 */
struct run_report {
	uint32_t seq;
	int steps;
	enum run_stop stop;
	bool pathfind;
};
extern struct run_report run_report;

#endif /* !PLAYER_PATH_H */
