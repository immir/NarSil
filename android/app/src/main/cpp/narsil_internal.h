/**
 * \file narsil_internal.h
 * \brief Seams between the term bridge (narsil_bridge.c) and the native
 *        map/command layer (narsil_native.c).  Not part of the public API.
 */
#ifndef NARSIL_INTERNAL_H
#define NARSIL_INTERNAL_H

#include "narsil_bridge.h"

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

struct object;
struct smithing_cost;

/* Implemented by narsil_bridge.c: wake the game thread if it is waiting for input. */
void bridge_wake_game(void);

/*
 * Implemented by narsil_bridge.c: report whether the game thread is blocked
 * waiting for the UI outside the term (a native prompt), for nb_wait_for_input().
 */
void bridge_set_idle(bool idle);

/* Implemented by narsil_prompt.c (game thread): install the native prompt hooks. */
void native_prompt_init(void);

/*
 * Implemented by narsil_prompt.c (game thread): the next direction prompt was
 * started by this native action; if exactly one neighbour fits it, answer
 * without asking.  Cleared by the prompt, or by the next command prompt.
 */
void native_prompt_set_intent(int nb_action);

/* Implemented by narsil_prompt.c (game thread, command prompt): spend experience on skills. */
void native_gain_skills(void);
/* gain_skills() natively, in either context: 1 accepted, -1 cancelled. */
int native_skill_points(int cmd_context, bool reset);

/* Implemented by narsil_birth.c: birth_hook and death_hook (ui-game.h); game thread. */
void native_birth_init(void);
void native_do_birth(void);
void native_death_screen(void);
/* The splash screen (news.txt) and its pause, natively. */
void native_welcome(void);
void native_abilities(void);

/* Implemented by narsil_prompt.c (game thread, command prompt): the pack.  True if a command was queued. */
bool native_pack(int focus_section);
/* At the next command prompt after a pack action: reopen the pack.  True if it queued another command. */
bool native_pack_resume(void);

/* Implemented by narsil_prompt.c (game thread): the character panel; true if a command was queued. */
bool native_panel(int tab_or_last, int pack_focus);
/* A panel tab switch or close is unwinding the open screens. */
bool native_panel_leaving(void);
/* Implemented by narsil_native.c: the character sheet, info format. */
const char *native_character_text(void);
/* Implemented by narsil_smith.c: the panel's smithing tab; true if CMD_SMITH was queued to make the design. */
bool native_smith_tab(void);

/*
 * Implemented by narsil_tiles.c (game thread): the MicroChasm tile for a map
 * grid, (row << 8) | column in 16x16.png, or -1 (no tiles installed).
 */
struct grid_data;
int32_t native_tile(const struct grid_data *g);

/* Implemented by narsil_knowledge.c (game thread): the knowledge browser. */
void native_knowledge(void);
/* Just the history or the hall of fame (the death screen). */
void native_knowledge_history(void);
void native_knowledge_scores(void);

/* Implemented by narsil_options.c (game thread): the options screens. */
void native_options(void);
void native_options_birth(void);

/*
 * Implemented by narsil_prompt.c (game thread): build a prompt's text between
 * begin (takes the prompt lock, clears the text) and end, then ask (blocks for
 * the answer).  Text is truncated rather than overflowing.
 */
void native_prompt_begin(void);
void native_prompt_add(const char *fmt, ...);
void native_prompt_end(void);
int32_t native_ask(enum nb_prompt_kind kind);
/* Tabs and newlines to spaces, so a field stays one field. */
void native_sanitise(char *s);
/* The text of the last NB_PROMPT_TEXT answered with 1. */
void native_answer_text(char *buf, size_t cap);

/* Implemented by narsil_smith.c: smith_object_hook. */
struct object *native_smith_object(struct smithing_cost *cost);

/* Implemented by narsil_native.c; all game-thread only. */
void native_init(void);

/*
 * Publish map/status if changed.  `at_prompt`: the game is at the top-level
 * command prompt with no overlay screen, so native commands may run.
 */
void native_publish(bool at_prompt);

/* Any request waiting for the game thread?  Any thread. */
bool native_has_request(void);

/*
 * Service requests while waiting for input.  Previews are answered in place.
 * Returns true if a command was stashed and the prompt must be woken (the
 * caller delivers a null keypress so textui_get_cmd returns).  Requests that
 * arrive away from the prompt are dropped: they were aimed at a stale screen.
 */
bool native_service(bool at_prompt);

/* After textui_get_cmd returns: queue the stashed command, if any. */
void native_run_stashed(void);

#endif /* NARSIL_INTERNAL_H */
