/**
 * \file narsil_internal.h
 * \brief Seams between the term bridge (narsil_bridge.c) and the native
 *        map/command layer (narsil_native.c).  Not part of the public API.
 */
#ifndef NARSIL_INTERNAL_H
#define NARSIL_INTERNAL_H

#include <stdbool.h>

/* Implemented by narsil_bridge.c: wake the game thread if it is waiting for input. */
void bridge_wake_game(void);

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
