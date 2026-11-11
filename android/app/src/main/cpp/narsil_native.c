/**
 * \file narsil_native.c
 * \brief Native map snapshot, player status, path preview and typed commands.
 *
 * The game thread builds a map snapshot from map_info()/grid_data_as_text()
 * (so glyphs match the textui exactly, including lighting and hallucination)
 * whenever EVENT_MAP-style events have marked it dirty, and publishes it with
 * a status block.  The UI asks for path previews and travel; the game thread
 * answers them only at the top-level command prompt, where the cave is stable
 * and no command is half-executed.
 */
#include "narsil_bridge.h"
#include "narsil_internal.h"

#include "angband.h"
#include "cave.h"
#include "cmd-core.h"
#include "game-event.h"
#include "game-world.h"
#include "player-path.h"
#include "player-timed.h"
#include "ui-map.h"
#include "message.h"
#include "ui-output.h"

#include <assert.h>
#include <pthread.h>
#include <string.h>

enum { PATH_MAX_STEPS = 500, MESSAGES_SHOWN = 6, MESSAGES_CAP = 4096 };

typedef struct { int32_t cp, attr_flags; } map_cell;

enum request_kind { REQ_NONE, REQ_PREVIEW, REQ_CLEAR_PREVIEW, REQ_TRAVEL };
typedef struct { enum request_kind kind; struct loc grid; } request;

/* Game thread only. */
static struct {
	map_cell *work;
	size_t work_cap;
	bool dirty;
	bool messages_dirty;
	request stashed;
} game;

/* Shared; guarded by `lock`. */
static struct {
	pthread_mutex_t lock;
	map_cell *front;
	size_t front_cap;
	int32_t meta[NB_META_COUNT];
	uint64_t map_gen;
	int32_t path[2 * (PATH_MAX_STEPS + 1)]; /* target, then steps */
	size_t path_pairs;
	uint64_t path_gen;
	request pending;
	char messages[MESSAGES_CAP];
	uint64_t messages_gen;
} shared = { .lock = PTHREAD_MUTEX_INITIALIZER };

/* ------------------------------------------------------------------------
 * Map snapshot (game thread)
 * ------------------------------------------------------------------------ */

static void mark_dirty(game_event_type type, game_event_data *data, void *user)
{
	(void)type; (void)data; (void)user;
	game.dirty = true;
}

static void mark_messages(game_event_type type, game_event_data *data, void *user)
{
	(void)type; (void)data; (void)user;
	game.messages_dirty = true;
}

/* Oldest first; tabs/newlines in message text flattened so records stay one per line. */
static void publish_messages(void)
{
	char buf[MESSAGES_CAP];
	size_t len = 0;
	const int n = MIN((int)messages_num(), MESSAGES_SHOWN);
	for (int age = n - 1; age >= 0; age--) {
		char text[512];
		my_strcpy(text, message_str((uint16_t)age), sizeof(text));
		for (char *p = text; *p; p++) if (*p == '\t' || *p == '\n') *p = ' ';
		len += (size_t)strnfmt(buf + len, sizeof(buf) - len, "%d\t%d\t%s\n",
			(int)message_color((uint16_t)age), (int)message_count((uint16_t)age), text);
	}
	pthread_mutex_lock(&shared.lock);
	memcpy(shared.messages, buf, len + 1);
	shared.messages_gen++;
	pthread_mutex_unlock(&shared.lock);
}

void native_init(void)
{
	event_add_handler(EVENT_MESSAGE, mark_messages, NULL);
	game.messages_dirty = true;
	static game_event_type dirtying[] = {
		EVENT_MAP, EVENT_PLAYERMOVED, EVENT_NEW_LEVEL_DISPLAY,
	};
	event_add_handler_set(dirtying, N_ELEMENTS(dirtying), mark_dirty, NULL);
	game.dirty = true;
}

static bool map_visible(void)
{
	return character_dungeon && cave && player && !player->is_dead
		&& screen_save_depth == 0;
}

static int32_t cell_flags(const struct grid_data *g, struct loc grid)
{
	return (g->in_view ? NB_CELL_IN_VIEW : 0)
		| (g->is_player ? NB_CELL_PLAYER : 0)
		| (g->m_idx > 0 ? NB_CELL_MONSTER : 0)
		| (g->first_kind ? NB_CELL_OBJECT : 0)
		| (square_isknown(cave, grid) ? NB_CELL_KNOWN : 0);
}

static void build_map(void)
{
	const size_t n = (size_t)cave->width * (size_t)cave->height;
	if (n > game.work_cap) {
		game.work = mem_realloc(game.work, n * sizeof(map_cell));
		game.work_cap = n;
	}
	for (int y = 0; y < cave->height; y++) {
		for (int x = 0; x < cave->width; x++) {
			const struct loc grid = loc(x, y);
			struct grid_data g;
			int a, ta;
			wchar_t c, tc;
			map_info(grid, &g);
			grid_data_as_text(&g, &a, &c, &ta, &tc);
			assert(a >= 0 && a < 0x10000);
			game.work[(size_t)y * (size_t)cave->width + (size_t)x] = (map_cell){
				.cp = (int32_t)c, .attr_flags = a | (cell_flags(&g, grid) << 16),
			};
		}
	}
}

void native_publish(bool at_prompt)
{
	if (game.messages_dirty) {
		game.messages_dirty = false;
		publish_messages();
	}

	const bool visible = map_visible();
	const bool rebuild = visible && game.dirty;
	if (rebuild) build_map();

	int32_t meta[NB_META_COUNT] = { 0 };
	meta[NB_META_MAP_MODE] = visible;
	meta[NB_META_AT_PROMPT] = visible && at_prompt;
	if (visible) {
		meta[NB_META_WIDTH] = cave->width;
		meta[NB_META_HEIGHT] = cave->height;
		meta[NB_META_PLAYER_X] = player->grid.x;
		meta[NB_META_PLAYER_Y] = player->grid.y;
		meta[NB_META_HP] = player->chp;
		meta[NB_META_MAX_HP] = player->mhp;
		meta[NB_META_VOICE] = player->csp;
		meta[NB_META_MAX_VOICE] = player->msp;
		meta[NB_META_DEPTH] = player->depth;
		meta[NB_META_EXP] = player->exp;
	}

	pthread_mutex_lock(&shared.lock);
	const bool meta_changed = memcmp(meta, shared.meta, sizeof(meta)) != 0;
	if (rebuild) {
		const size_t n = (size_t)cave->width * (size_t)cave->height;
		if (n > shared.front_cap) {
			shared.front = mem_realloc(shared.front, n * sizeof(map_cell));
			shared.front_cap = n;
		}
		memcpy(shared.front, game.work, n * sizeof(map_cell));
	}
	if (rebuild || meta_changed) {
		memcpy(shared.meta, meta, sizeof(meta));
		shared.map_gen++;
	}
	pthread_mutex_unlock(&shared.lock);
	if (rebuild) game.dirty = false;
}

/* ------------------------------------------------------------------------
 * Requests (game thread)
 * ------------------------------------------------------------------------ */

static void publish_path(struct loc target, const int *dirs, int n)
{
	pthread_mutex_lock(&shared.lock);
	size_t pairs = 0;
	if (n > 0) {
		shared.path[0] = target.x;
		shared.path[1] = target.y;
		struct loc at = player->grid;
		for (int i = 0; i < n; i++) {
			at = loc_sum(at, ddgrid[dirs[i]]);
			shared.path[2 * (i + 1)] = at.x;
			shared.path[2 * (i + 1) + 1] = at.y;
		}
		pairs = (size_t)n + 1;
	}
	shared.path_pairs = pairs;
	shared.path_gen++;
	pthread_mutex_unlock(&shared.lock);
}

static void preview_path(struct loc target)
{
	int dirs[PATH_MAX_STEPS];
	const bool found = square_in_bounds_fully(cave, target)
		&& !loc_eq(target, player->grid) && find_path(target);
	publish_path(target, dirs, found ? path_copy_steps(dirs, PATH_MAX_STEPS) : 0);
}

bool native_has_request(void)
{
	pthread_mutex_lock(&shared.lock);
	const bool any = shared.pending.kind != REQ_NONE;
	pthread_mutex_unlock(&shared.lock);
	return any;
}

bool native_service(bool at_prompt)
{
	pthread_mutex_lock(&shared.lock);
	const request r = shared.pending;
	shared.pending.kind = REQ_NONE;
	pthread_mutex_unlock(&shared.lock);

	if (!at_prompt || !map_visible()) return false;
	switch (r.kind) {
		case REQ_PREVIEW: preview_path(r.grid); return false;
		case REQ_CLEAR_PREVIEW: publish_path(r.grid, NULL, 0); return false;
		case REQ_TRAVEL: game.stashed = r; return true;
		case REQ_NONE: return false;
	}
	return false;
}

/* Mirrors textui_process_click() for a left click on the map. */
static void push_travel(struct loc target)
{
	if (!square_in_bounds_fully(cave, target)) return;
	if (loc_eq(target, player->grid)) {
		cmdq_push(square_object(cave, target) ? CMD_PICKUP : CMD_HOLD);
		return;
	}
	const bool adjacent = ABS(target.x - player->grid.x) <= 1
		&& ABS(target.y - player->grid.y) <= 1;
	if (adjacent || player->timed[TMD_CONFUSED]) {
		cmdq_push(CMD_WALK);
		cmd_set_arg_direction(cmdq_peek(), "direction", motion_dir(player->grid, target));
	} else {
		cmdq_push(CMD_PATHFIND);
		cmd_set_arg_point(cmdq_peek(), "point", target);
	}
}

void native_run_stashed(void)
{
	const request r = game.stashed;
	game.stashed.kind = REQ_NONE;
	if (r.kind != REQ_TRAVEL || !map_visible()) return;
	publish_path(r.grid, NULL, 0);
	push_travel(r.grid);
}

/* ------------------------------------------------------------------------
 * Public API (any thread)
 * ------------------------------------------------------------------------ */

static void post(request r)
{
	pthread_mutex_lock(&shared.lock);
	shared.pending = r;
	pthread_mutex_unlock(&shared.lock);
	bridge_wake_game();
}

void nb_request_preview(int x, int y)
{
	post((request){ .kind = REQ_PREVIEW, .grid = loc(x, y) });
}

void nb_clear_preview(void)
{
	post((request){ .kind = REQ_CLEAR_PREVIEW });
}

void nb_request_travel(int x, int y)
{
	post((request){ .kind = REQ_TRAVEL, .grid = loc(x, y) });
}

uint64_t nb_map_generation(void)
{
	pthread_mutex_lock(&shared.lock);
	const uint64_t gen = shared.map_gen;
	pthread_mutex_unlock(&shared.lock);
	return gen;
}

uint64_t nb_map_snapshot(int32_t *meta, int32_t *cells, size_t n_ints)
{
	pthread_mutex_lock(&shared.lock);
	memcpy(meta, shared.meta, sizeof(shared.meta));
	const size_t n = (size_t)shared.meta[NB_META_WIDTH] * (size_t)shared.meta[NB_META_HEIGHT];
	const bool fits = cells && n_ints >= 2 * n;
	if (fits) memcpy(cells, shared.front, n * sizeof(map_cell));
	const uint64_t gen = shared.map_gen;
	pthread_mutex_unlock(&shared.lock);
	return fits || n == 0 ? gen : 0;
}

uint64_t nb_path_generation(void)
{
	pthread_mutex_lock(&shared.lock);
	const uint64_t gen = shared.path_gen;
	pthread_mutex_unlock(&shared.lock);
	return gen;
}

size_t nb_path(int32_t *xy, size_t max_pairs)
{
	pthread_mutex_lock(&shared.lock);
	const size_t n = MIN(shared.path_pairs, max_pairs);
	memcpy(xy, shared.path, 2 * n * sizeof(int32_t));
	pthread_mutex_unlock(&shared.lock);
	return n;
}

uint64_t nb_messages_generation(void)
{
	pthread_mutex_lock(&shared.lock);
	const uint64_t gen = shared.messages_gen;
	pthread_mutex_unlock(&shared.lock);
	return gen;
}

size_t nb_messages(char *text, size_t cap)
{
	assert(cap > 0);
	pthread_mutex_lock(&shared.lock);
	my_strcpy(text, shared.messages, cap);
	pthread_mutex_unlock(&shared.lock);
	return strlen(text);
}
