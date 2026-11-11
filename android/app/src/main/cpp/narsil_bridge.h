/**
 * \file narsil_bridge.h
 * \brief Platform-neutral bridge between the NarSil core and a host UI.
 *
 * The game runs on its own thread and blocks inside TERM_XTRA_EVENT when it
 * needs input.  The host UI (Android, or the desktop test harness) never
 * blocks: it pushes keys, polls frames, and requests saves.
 *
 * Threading contract:
 *  - nb_start() is called once; everything else may be called from any
 *    thread after that.
 *  - Frames are double-buffered: the game thread draws into a private
 *    buffer and publishes it at TERM_XTRA_FRESH, so snapshots never tear.
 *  - Saves requested with nb_request_save() run on the game thread, and only
 *    at a safe point: while waiting for a top-level game command
 *    (cmd_get_hook(CTX_GAME)), when no command is half-executed.
 */
#ifndef NARSIL_BRIDGE_H
#define NARSIL_BRIDGE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct {
	const char *lib_dir;     /* read-only data: gamedata/ customize/ help/ screens/ */
	const char *data_dir;    /* writable: user/ (save/ scores/ panic/ archive/) */
	const char *player_name; /* savefile name */
	int cols, rows;          /* main term size; at least 80x24 */
	void (*log)(const char *msg); /* optional; receives plog() output */
} nb_config;

enum nb_state { NB_IDLE = 0, NB_RUNNING = 1, NB_EXITED = 2 };

bool nb_start(const nb_config *cfg);

/* `code` is an Angband keycode (ui-event.h): Unicode, or ESCAPE/KC_ and ARROW_ codes. */
void nb_push_key(uint32_t code, uint8_t mods);

/*
 * A mouse press on term cell (x, y): button 1 = left (tap), 2 = right (long
 * press); mods are KC_MOD_* flags.  Out-of-range cells are dropped.
 */
void nb_push_mouse(int x, int y, int button, uint8_t mods);

/* Asynchronous; completes at the next safe point.  See nb_save_count(). */
void nb_request_save(void);
uint64_t nb_save_count(void);

/*
 * Copies the last published frame.  `cells` must hold 2 * cols * rows ints,
 * laid out as (codepoint, attr) pairs in row-major order; attr is the raw
 * Angband attr (colour index + MULT_BG * background mode).
 * Returns the frame generation; 0 means nothing has been published yet.
 */
uint64_t nb_snapshot(int32_t *cells, size_t n_ints, int *cursor_x,
		int *cursor_y, bool *cursor_visible);
uint64_t nb_generation(void);

int nb_cols(void);
int nb_rows(void);

/* Writes 0xRRGGBB for each of the first n Angband colours; returns count written. */
size_t nb_palette(uint32_t *rgb, size_t n);

/* ------------------------------------------------------------------------
 * Native map / status / commands (narsil_native.c)
 *
 * The map snapshot covers the whole level, one (codepoint, attr | flags << 16)
 * pair per grid in row-major order; glyphs and attrs are exactly what the
 * textui would draw.  meta[] is indexed by enum nb_meta.  Commands and
 * previews take effect only at the top-level command prompt
 * (meta[NB_META_AT_PROMPT]); requests made elsewhere are dropped.
 * ------------------------------------------------------------------------ */

enum nb_meta {
	NB_META_MAP_MODE,   /* the textui is showing the main map (no overlay screen) */
	NB_META_AT_PROMPT,  /* waiting for a top-level command */
	NB_META_WIDTH, NB_META_HEIGHT,
	NB_META_PLAYER_X, NB_META_PLAYER_Y,
	NB_META_HP, NB_META_MAX_HP,
	NB_META_VOICE, NB_META_MAX_VOICE,
	NB_META_DEPTH,      /* dungeon level; 50 ft each */
	NB_META_EXP,
	NB_META_COUNT
};

enum nb_cell_flag {
	NB_CELL_IN_VIEW = 1 << 0,
	NB_CELL_PLAYER  = 1 << 1,
	NB_CELL_MONSTER = 1 << 2,
	NB_CELL_OBJECT  = 1 << 3,
	NB_CELL_KNOWN   = 1 << 4,
};

uint64_t nb_map_generation(void);

/*
 * Fills meta[NB_META_COUNT] and, if `cells` holds 2 * width * height ints,
 * the map.  Returns the generation, or 0 if cells was too small (call again
 * with a buffer sized from meta).
 */
uint64_t nb_map_snapshot(int32_t *meta, int32_t *cells, size_t n_ints);

/* Ghost path to (x, y), computed by the game's own pathfinder; see nb_path(). */
void nb_request_preview(int x, int y);
void nb_clear_preview(void);

/* Walk to an adjacent grid, pathfind to a far one, or pick up / hold on the player. */
void nb_request_travel(int x, int y);

/* Path as (x, y) pairs: the target first, then each step; 0 = no path. */
uint64_t nb_path_generation(void);
size_t nb_path(int32_t *xy, size_t max_pairs);

enum nb_state nb_state(void);
const char *nb_exit_message(void); /* valid once state is NB_EXITED */

/*
 * Test support: blocks until the game thread is waiting for input with an
 * empty key queue (or has exited).  Returns false on timeout.
 */
bool nb_wait_for_input(int timeout_ms);

#endif /* NARSIL_BRIDGE_H */
