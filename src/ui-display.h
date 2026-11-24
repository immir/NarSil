/**
 * \file ui-display.h
 * \brief Handles the setting up updating, and cleaning up of the game display.
 *
 * Copyright (c) 1997 Ben Harrison, James E. Wilson, Robert A. Koeneke
 * Copyright (c) 2007 Antony Sidwell
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

#ifndef INCLUDED_UI_DISPLAY_H
#define INCLUDED_UI_DISPLAY_H

#include "angband.h"
#include "cmd-core.h"

extern const char *stat_names[STAT_MAX];
extern const char *stat_names_reduced[STAT_MAX];
extern const char *window_flag_desc[32];

/* A front end may list a pile of floor objects itself (verb: "see", "feel" or "have no room for");
 * left NULL, the pile is drawn over the map and a key is awaited. */
extern void (*floor_pile_hook)(struct object **list, int num, const char *verb);

/* A front end may show a poem or note itself: a gamedata file's name (no ".txt") or, when that is
 * NULL, a textblock; left NULL, the verse is written to the screen and a key is awaited. */
extern void (*poem_hook)(const char *filename, struct textblock *text);

uint8_t health_attr(int current, int max);
void cnv_stat(int val, char *out_val, size_t out_len);
void allow_animations(void);
void disallow_animations(void);
void idle_update(void);
void toggle_inven_equip(void);
void subwindows_set_flags(uint32_t *new_flags, size_t n_subwindows);
void init_display(void);

#endif /* INCLUDED_UI_DISPLAY_H */
