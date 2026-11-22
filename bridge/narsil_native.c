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
#include "cmds.h"
#include "combat.h"
#include "player-abilities.h"
#include "project.h"
#include "game-event.h"
#include "game-world.h"
#include "obj-chest.h"
#include "obj-gear.h"
#include "gen-seed.h"
#include "init.h"
#include "obj-knowledge.h"
#include "obj-make.h"
#include "obj-pile.h"
#include "obj-tval.h"
#include "obj-util.h"
#include "player-attack.h"
#include "player-calcs.h"
#include "player-path.h"
#include "player-util.h"
#include "player-timed.h"
#include "ui-abilities.h"
#include "ui-display.h"
#include "ui-help.h"
#include "ui-knowledge.h"
#include "ui-map.h"
#include "ui-player.h"
#include "message.h"
#include "mon-desc.h"
#include "mon-lore.h"
#include "mon-move.h"
#include "mon-predicate.h"
#include "monster.h"
#include "obj-desc.h"
#include "obj-ignore.h"
#include "target.h"
#include "ui-mon-lore.h"
#include "z-textblock.h"
#include "ui-output.h"

#include <assert.h>
#include <pthread.h>
#include <stdarg.h>
#include <string.h>
#include <time.h>
#include "ui-object.h"

enum { PATH_MAX_STEPS = 500, MESSAGES_SHOWN = 300, INFO_CAP = 32 * 1024, LOOK_MAX = 40 };

/* NB_MAP_CELL_INTS per grid: glyph, attr | flags << 16, tile, terrain tile beneath it (each or -1), then the
 * overlay bits: NB_TILE_ALERT, NB_TILE_GLOW. */
typedef struct { int32_t cp, attr_flags, tile, tile_bg, tile_flags; } map_cell;

enum request_kind { REQ_NONE, REQ_PREVIEW, REQ_CLEAR_PREVIEW, REQ_TRAVEL, REQ_ACTION, REQ_MOVE, REQ_INSPECT, REQ_TARGET, REQ_LOOK_LIST, REQ_TEST_DIE, REQ_TEST_MSG, REQ_TEST_DEPTH, REQ_TEST_CHEST, REQ_REST, REQ_AUTO_MORE, REQ_USE_KIND, REQ_TEST_ANIM };
typedef struct { enum request_kind kind; struct loc grid; enum nb_action action; int dir; bool run; } request;

/* Game thread only. */
static struct {
	map_cell *work;
	size_t work_cap;
	bool dirty;
	bool messages_dirty;
	bool built_tiles;       /* whether the map was last built with tiles */
	struct loc stairs_down, stairs_up;  /* nearest known staircases as of the last map build, or (-1, -1) */
	request stashed;
	/* A walk to a distant grid (CMD_PATHFIND): who was in view when it began, so a stop can say why. */
	struct { bool active, hit_wall; struct loc target; int n_seen; int16_t seen[64]; } travel;
	/* Exploring: legs of CMD_PATHFIND to the nearest unexplored grid, queued between commands (native_explore_step). */
	struct {
		bool active, single, opening;
		struct loc target, from;   /* this leg's goal and where it began */
		int hp, legs, depth;
		int level;                 /* level_serial when it began: a different level ends it */
		bool *skip;                /* grids not to go to again (reached, or unreachable) on skip_level, one per grid; kept between explorations */
		int skip_level;
		size_t skip_cap;
	} explore;
} game;

static int level_serial;  /* counts the levels entered, so that a level is told from the next at the same depth */
static void count_level(game_event_type type, game_event_data *data, void *user) { (void)type; (void)data; (void)user; level_serial++; }

static void animate_bolt(game_event_type type, game_event_data *data, void *user);
static void animate_missile(game_event_type type, game_event_data *data, void *user);
static void animate_explosion(game_event_type type, game_event_data *data, void *user);
static void animate_hit(game_event_type type, game_event_data *data, void *user);
static bool tiles_on(void);
static void threat_counts(int32_t *count, int32_t *alert, int32_t *nearest);
static int32_t light_turns_left(void);

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
	char messages[NB_MESSAGES_CAP];
	uint64_t messages_gen;
	char info[INFO_CAP];
	uint64_t info_gen;
	char foe_name[80];
	bool tiles;             /* nb_set_tiles() */
	bool item_details;      /* nb_set_item_details() */
	int32_t quick_kinds[NB_QUICK_MAX]; /* nb_set_quick_kinds() */
	int quick_n;
	char quick[NB_QUICK_MAX * 96];     /* nb_quick_slots() */
	int anim_frames;                   /* frames built with an animation laid over the map, for the tests */
} shared = { .lock = PTHREAD_MUTEX_INITIALIZER };

/* ------------------------------------------------------------------------
 * Map snapshot (game thread)
 * ------------------------------------------------------------------------ */

static void mark_dirty(game_event_type type, game_event_data *data, void *user)
{
	(void)type; (void)data; (void)user;
	game.dirty = true;
}

/* How the player's attacks went, from the game's message types; accumulated until the next published update. */
static int32_t feel_pending, feel_bits, feel_seq;

static int32_t feel_of(const int msg_type)
{
	switch (msg_type) {
		case MSG_HIT: case MSG_HIT_GOOD: case MSG_HIT_GREAT: case MSG_HIT_SUPERB:
		case MSG_HIT_HI_GREAT: case MSG_HIT_HI_SUPERB: case MSG_SHOOT_HIT: return NB_FEEL_HIT;
		case MSG_MISS: return NB_FEEL_MISS;
		case MSG_KILL: case MSG_KILL_UNIQUE: case MSG_KILL_KING: return NB_FEEL_KILL;
		default: return 0;
	}
}

static void mark_messages(game_event_type type, game_event_data *data, void *user)
{
	(void)type; (void)user;
	game.messages_dirty = true;
	if (data) feel_pending |= feel_of(data->message.type);
	if (data && data->message.type == MSG_HITWALL) game.travel.hit_wall = true;
}

/*
 * Wraps the first mention of each special item's added name (artefact name or ego name) that the player carries,
 * wears or can see underfoot in MARK_BEGIN, tier digit, name, MARK_END; the app colours what is between.
 */
static void mark_special(char *text, const size_t cap)
{
	const struct object *candidates[64];
	size_t n = 0;
	if (!cave || !player) return;       /* a message from before the first level exists */
	for (const struct object *o = player->gear; o && n < N_ELEMENTS(candidates); o = o->next) candidates[n++] = o;
	struct object *floor[16];
	const int k = scan_floor(floor, (int)N_ELEMENTS(floor), player, OFLOOR_SENSE | OFLOOR_VISIBLE, NULL);
	for (int i = 0; i < k && n < N_ELEMENTS(candidates); i++) candidates[n++] = floor[i];
	for (size_t i = 0; i < n; i++) {
		const char *const name = native_special_name(candidates[i]);
		if (!name) continue;
		const int tier = native_special_tier(candidates[i]);
		/* An ego or artefact name is marked once; every "{??}" is (a pile can hold several unknown items). */
		for (size_t from = 0; from <= strlen(text); ) {
			char *const at = strstr(text + from, name);
			if (!at) break;
			if (at >= text + 2 && at[-2] == NB_MARK_BEGIN) { from = (size_t)(at - text) + strlen(name); continue; }
			char marked[512 + 32];
			const size_t before = (size_t)(at - text);
			const char *const after = at + strlen(name);
			my_strcpy(marked, text, MIN(sizeof(marked), before + 1));
			size_t len = before;
			strnfcat(marked, sizeof(marked), &len, "%c%d%s%c%s", NB_MARK_BEGIN, tier, name, NB_MARK_END, after);
			my_strcpy(text, marked, cap);
			if (tier != 3) break;
			from = before + 3 + strlen(name);
		}
	}
}

/*
 * Oldest first; tabs/newlines in message text flattened so records stay one per line.
 * As many of the newest messages as fit (a record is at most ~530 bytes).
 */
static void publish_messages(void)
{
	static char buf[NB_MESSAGES_CAP];
	enum { RECORD_MAX = 512 + 24 };
	size_t len = 0;
	buf[0] = '\0';  /* the log is empty at startup, before a savefile loads */
	const int n = MIN(MIN((int)messages_num(), MESSAGES_SHOWN), (int)(sizeof(buf) / RECORD_MAX));
	for (int age = n - 1; age >= 0; age--) {
		char text[512 + 32];
		my_strcpy(text, message_str((uint16_t)age), sizeof(text));
		for (char *p = text; *p; p++) if (*p == '\t' || *p == '\n') *p = ' ';
		mark_special(text, sizeof(text));
		len += (size_t)strnfmt(buf + len, sizeof(buf) - len, "%d\t%d\t%s\n",
			(int)message_color((uint16_t)age), (int)message_count((uint16_t)age), text);
		assert(len < sizeof(buf));
	}
	pthread_mutex_lock(&shared.lock);
	memcpy(shared.messages, buf, len + 1);
	shared.messages_gen++;
	pthread_mutex_unlock(&shared.lock);
}

/* The game would draw the pile over the map and wait for a key; here it is one line in the message strip
 * (the action bar's Pick up takes it from there). */
static void native_floor_pile(struct object **const list, const int num, const char *const verb)
{
	enum { NAMED = 3 };
	char names[NAMED][80];
	const int shown = MIN(num, NAMED);
	for (int i = 0; i < shown; i++) {
		object_desc(names[i], sizeof(names[i]), list[i], ODESC_PREFIX | ODESC_BASE, player);
		/* The "??" the full description would carry: properties still to find, so worth a look. */
		if (native_special_tier(list[i]) == 3) my_strcat(names[i], " {??}", sizeof(names[i]));
	}
	const int more = num - shown;
	event_signal(EVENT_MESSAGE_FLUSH);
	if (more > 0) msg("You %s %s, %s, %s and %d more.", verb, names[0], names[1], names[2], more);
	else if (shown == 3) msg("You %s %s, %s and %s.", verb, names[0], names[1], names[2]);
	else msg("You %s %s and %s.", verb, names[0], names[1]);
}

void native_init(void)
{
	native_birth_init();
	floor_pile_hook = native_floor_pile;
	event_add_handler(EVENT_MESSAGE, mark_messages, NULL);
	game.messages_dirty = true;
	static game_event_type dirtying[] = {
		EVENT_MAP, EVENT_PLAYERMOVED, EVENT_NEW_LEVEL_DISPLAY,
	};
	event_add_handler_set(dirtying, N_ELEMENTS(dirtying), mark_dirty, NULL);
	event_add_handler(EVENT_NEW_LEVEL_DISPLAY, count_level, NULL);
	/* After the terminal's own handlers, so that these run first (handlers are called latest first). */
	event_add_handler(EVENT_BOLT, animate_bolt, NULL);
	event_add_handler(EVENT_MISSILE, animate_missile, NULL);
	event_add_handler(EVENT_EXPLOSION, animate_explosion, NULL);
	event_add_handler(EVENT_HIT, animate_hit, NULL);
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


/* ------------------------------------------------------------------------
 * Spell, missile and hit animation (game thread)
 *
 * The engine draws these on its terminal, which the apps never see (they draw the map from the level's state), so
 * each step is also laid over the published map, shown for a moment, then taken off again.  The engine's own
 * delay for the step is then skipped (native_absorb_delay) so that each step is waited for once.
 * ------------------------------------------------------------------------ */

enum { ANIM_MAX = 256, ANIM_MIN_MS = 35, ANIM_ABSORB_MAX = 8 };
enum { ANIM_HEAD, ANIM_TRAIL, ANIM_MARK };

static struct {
	struct { struct loc grid; int32_t cp; int attr; int kind; } cell[ANIM_MAX];
	int n, absorb;
	struct loc last;
} anim;

static int anim_ms(void)
{
	const int d = player ? player->opts.delay_factor : 0;
	return d <= 0 ? 0 : MAX(d, ANIM_MIN_MS);
}

static void anim_clear(void)
{
	if (anim.n) game.dirty = true;
	anim.n = 0;
}

static void anim_add(const struct loc grid, const wchar_t cp, const int attr, const int kind)
{
	if (anim.n >= ANIM_MAX || !square_in_bounds(cave, grid)) return;
	anim.cell[anim.n++] = (typeof(anim.cell[0])){ grid, (int32_t)cp, attr, kind };
}

static void nap_ms(const int ms)
{
	const struct timespec ts = { .tv_sec = ms / 1000, .tv_nsec = (long)(ms % 1000) * 1000000L };
	nanosleep(&ts, NULL);
}

/* Shows the overlay as it now stands for `ms`; the engine's own delay for this step is not waited for as well. */
static void anim_frame(const int ms, const int absorbs)
{
	game.dirty = true;
	native_publish(false);
	nap_ms(ms);
	anim.absorb = MIN(anim.absorb + absorbs, ANIM_ABSORB_MAX);
}

bool native_absorb_delay(void)
{
	if (anim.absorb <= 0) return false;
	anim.absorb--;
	return true;
}

/* Mirrors bolt_pict() in ui-display.c for text: * | / - \ by the way it is moving. */
static wchar_t bolt_glyph(const struct loc from, const struct loc to)
{
	static const wchar_t glyphs[] = L"*|/-\\";
	int motion = 0;
	if (loc_eq(from, to)) motion = 0;
	else if (to.x == from.x) motion = 1;
	else if (to.y - from.y == from.x - to.x) motion = 2;
	else if (to.y == from.y) motion = 3;
	else if (to.y - from.y == to.x - from.x) motion = 4;
	return glyphs[motion];
}

/* A head moves on a step: the old head fades to a trail (a beam keeps its whole trail, a bolt only the last grid). */
static void anim_step(const struct loc from, const struct loc to, const wchar_t cp, const int attr, const bool beam)
{
	if (anim.n && distance(anim.last, from) > 1) anim_clear();  /* a new projection */
	int kept = 0;
	for (int i = 0; i < anim.n; i++) {
		if (anim.cell[i].kind == ANIM_HEAD) anim.cell[i].kind = ANIM_TRAIL;
		else if (!beam) continue;
		anim.cell[kept++] = anim.cell[i];
	}
	anim.n = kept;
	anim_add(to, cp, attr, ANIM_HEAD);
	anim.last = to;
}

static void animate_bolt(game_event_type type, game_event_data *data, void *user)
{
	(void)type; (void)user;
	const int ms = anim_ms();
	if (!ms || !data->bolt.seen || !map_visible()) return;
	const struct loc from = loc(data->bolt.ox, data->bolt.oy), to = loc(data->bolt.x, data->bolt.y);
	anim_step(from, to, bolt_glyph(from, to), projections[data->bolt.proj_type].color, data->bolt.beam);
	anim_frame(ms, 1);
}

static void animate_missile(game_event_type type, game_event_data *data, void *user)
{
	(void)type; (void)user;
	const int ms = anim_ms();
	if (!ms || !data->missile.seen || !map_visible()) return;
	const struct loc to = loc(data->missile.x, data->missile.y);
	anim_step(anim.n ? anim.last : to, to, object_char(data->missile.obj), object_attr(data->missile.obj), false);
	anim_frame(ms, 1);
}

static void animate_explosion(game_event_type type, game_event_data *data, void *user)
{
	(void)type; (void)user;
	const int ms = anim_ms();
	if (!ms || !map_visible()) return;
	anim_clear();
	const int colour = projections[data->explosion.proj_type].color;
	int frames = 0;
	for (int i = 0; i < data->explosion.num_grids; i++) {
		if (data->explosion.player_sees_grid[i]) anim_add(data->explosion.blast_grid[i], L'*', colour, ANIM_TRAIL);
		const bool ring_done = i == data->explosion.num_grids - 1
			|| data->explosion.distance_to_grid[i + 1] > data->explosion.distance_to_grid[i];
		if (ring_done && anim.n) { anim_frame(ms, 1); frames++; }
	}
	(void)frames;
	anim_clear();
	anim.last = loc(-100, -100);
}

/* Mirrors hit_pict() in ui-display.c. */
static int hit_attr(const int dam, const int type, const bool fatal)
{
	if (fatal) return COLOUR_RED;
	if (!dam) return type == PROJ_SOUND ? COLOUR_L_UMBER : COLOUR_L_WHITE;
	if (type == PROJ_POIS) return COLOUR_GREEN;
	return type == PROJ_SOUND ? COLOUR_L_UMBER : COLOUR_L_RED;
}

static void animate_hit(game_event_type type, game_event_data *data, void *user)
{
	(void)type; (void)user;
	const int ms = anim_ms();
	if (!ms || !map_visible() || !OPT(player, display_hits)) return;
	const int dam = MIN(MAX(data->hit.dam, 0), 99);
	const int attr = hit_attr(data->hit.dam, data->hit.dam_type, data->hit.fatal);
	anim_clear();
	anim_add(data->hit.grid, (wchar_t)(L'0' + dam % 10), attr, ANIM_MARK);
	if (dam >= 10) anim_add(loc(data->hit.grid.x - 1, data->hit.grid.y), (wchar_t)(L'0' + dam / 10), attr, ANIM_MARK);
	anim_frame(3 * ms, 1);
	anim_clear();
}

static size_t grid_cell(const struct loc grid) { return (size_t)grid.y * (size_t)cave->width + (size_t)grid.x; }

static void apply_animation(void)
{
	if (anim.n) { pthread_mutex_lock(&shared.lock); shared.anim_frames++; pthread_mutex_unlock(&shared.lock); }
	static const int32_t kind_flag[] = { NB_CELL_ANIM, NB_CELL_TRAIL, NB_CELL_MARK };
	for (int i = 0; i < anim.n; i++) {
		map_cell *const c = &game.work[grid_cell(anim.cell[i].grid)];
		c->cp = anim.cell[i].cp;
		c->attr_flags = (anim.cell[i].attr & 0xFFFF) | ((((c->attr_flags >> 16) & ~(NB_CELL_MONSTER | NB_CELL_OBJECT | NB_CELL_PLAYER)) | kind_flag[anim.cell[i].kind]) << 16);
		c->tile = -1;
		c->tile_flags = 0;
	}
}

static void build_map(void)
{
	pthread_mutex_lock(&shared.lock);
	const bool tiles = shared.tiles;
	pthread_mutex_unlock(&shared.lock);
	game.built_tiles = tiles;
	game.stairs_down = game.stairs_up = loc(-1, -1);
	int best_down = INT_MAX, best_up = INT_MAX;
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
			if (square_isknown(cave, grid)) {
				const int d = distance(player->grid, grid);
				if (square_isdownstairs(player->cave, grid) && d < best_down) { best_down = d; game.stairs_down = grid; }
				if (square_isupstairs(player->cave, grid) && d < best_up) { best_up = d; game.stairs_up = grid; }
			}
			map_info(grid, &g);
			struct native_tile_cell t = { -1, -1, false, false, false };
			if (tiles) (void)native_tile(&g, &t);
			grid_data_as_text(&g, &a, &c, &ta, &tc);
			assert(a >= 0 && a < 0x10000);
			game.work[(size_t)y * (size_t)cave->width + (size_t)x] = (map_cell){
				.cp = (int32_t)c, .attr_flags = a | (cell_flags(&g, grid) << 16), .tile = t.fg, .tile_bg = t.bg,
				.tile_flags = (t.alert ? NB_TILE_ALERT : 0) | (t.glow ? NB_TILE_GLOW : 0) | (t.rage ? NB_TILE_RAGE : 0),
			};
		}
	}
	apply_animation();
}

/* What is around the player, for context actions.  Only what the player knows. */
static int32_t context_flags(void)
{
	int32_t f = 0;
	const struct loc g = player->grid;
	if (square_isupstairs(cave, g)) f |= NB_CTX_UP_STAIRS;
	if (square_isdownstairs(cave, g)) f |= NB_CTX_DOWN_STAIRS;
	if (square_object(cave, g)) f |= NB_CTX_FLOOR_ITEMS;
	if (square_object(cave, g)) {
		struct object *floor[16];
		const int k = scan_floor(floor, (int)N_ELEMENTS(floor), player, OFLOOR_SENSE | OFLOOR_VISIBLE, NULL);
		for (int i = 0; i < k; i++) if (native_special_tier(floor[i])) f |= NB_CTX_FLOOR_SPECIAL;
	}
	if (square_isforge(cave, g)) f |= NB_CTX_FORGE;
	if (game.stairs_down.x >= 0 && !square_isdownstairs(cave, g)) f |= NB_CTX_DOWN_KNOWN;
	if (game.stairs_up.x >= 0 && !square_isupstairs(cave, g)) f |= NB_CTX_UP_KNOWN;
	if (player_can_fire_quiver1(player, false)) f |= NB_CTX_CAN_FIRE;
	if (player_has_throwable(player, false)) f |= NB_CTX_CAN_THROW;
	if (player_can_fletch(player)) f |= NB_CTX_CAN_FLETCH;
	if (player->stealth_mode) f |= NB_CTX_STEALTH;
	if (player->song[SONG_MAIN]) f |= NB_CTX_SINGING;
	for (int d = 0; d < 8; d++) {
		const struct loc n = loc_sum(g, ddgrid_ddd[d]);
		if (!square_in_bounds(cave, n) || !square_isknown(cave, n)) continue;
		if (square_iscloseddoor(cave, n)) f |= NB_CTX_ADJ_CLOSED_DOOR;
		if (square_isopendoor(cave, n)) f |= NB_CTX_ADJ_OPEN_DOOR;
		if (square_isrubble(cave, n)) f |= NB_CTX_ADJ_RUBBLE;
		if (square_isdisarmabletrap(cave, n) && square_isvisibletrap(cave, n)) f |= NB_CTX_ADJ_TRAP;
	}
	for (int d = 0; d < 9; d++) {
		const struct loc n = loc_sum(g, ddgrid_ddd[d]);
		if (!square_in_bounds(cave, n) || !square_isknown(cave, n)) continue;
		if (chest_check(player, n, CHEST_OPENABLE)) f |= NB_CTX_ADJ_CHEST;
		if (chest_check(player, n, CHEST_TRAPPED)) f |= NB_CTX_ADJ_TRAP;
		const struct object *const skeleton = skeleton_check(n);
		if (skeleton && skeleton->pval > 0 && skeleton->known) f |= NB_CTX_ADJ_SKELETON;
	}
	return f;
}

/* Mirrors prt_health_aux() in ui-display.c: only a visible, living, real monster. */
static struct monster *tracked_foe(void)
{
	struct monster *const mon = player->upkeep->health_who;
	if (!mon || !mon->race || mon->hp <= 0 || !monster_is_visible(mon) || player->timed[TMD_IMAGE]) return NULL;
	return mon;
}

static enum nb_stance foe_stance(const struct monster *mon)
{
	if (mon->alertness < ALERTNESS_UNWARY) return NB_STANCE_SLEEPING;
	if (mon->alertness < ALERTNESS_ALERT) return NB_STANCE_UNWARY;
	if (rf_has(mon->race->flags, RF_MINDLESS)) return NB_STANCE_MINDLESS;
	switch (mon->stance) {
		case STANCE_FLEEING: return NB_STANCE_FLEEING;
		case STANCE_CONFIDENT: return NB_STANCE_CONFIDENT;
		case STANCE_AGGRESSIVE: return NB_STANCE_AGGRESSIVE;
		default: return NB_STANCE_NONE;
	}
}

void native_messages_changed(void) { game.messages_dirty = true; }

/* The first carried, unequipped object of kind `kidx`, and how many of that kind are carried. */
static struct object *carried_kind(const int kidx, int *const count)
{
	struct object *first = NULL; /* mutable: queued commands take the pointer */
	*count = 0;
	for (struct object *obj = player->gear; obj; obj = obj->next) {
		if (!obj->kind || (int)obj->kind->kidx != kidx || object_is_equipped(player->body, obj)) continue;
		if (!first) first = obj;
		*count += obj->number;
	}
	return first;
}

/* One line per pinned kind: "kind \t number carried \t short name" (the flavour and base while unaware). */
static void quick_slots(char *const out, const size_t cap)
{
	pthread_mutex_lock(&shared.lock);
	int32_t kinds[NB_QUICK_MAX];
	const int n = shared.quick_n;
	memcpy(kinds, shared.quick_kinds, sizeof(kinds));
	pthread_mutex_unlock(&shared.lock);
	out[0] = '\0';
	size_t len = 0;
	for (int i = 0; i < n; i++) {
		const struct object_kind *const kind = objkind_byid(kinds[i]);
		if (!kind) continue;
		int count;
		(void)carried_kind(kinds[i], &count);
		char name[80];
		object_kind_name(name, sizeof(name), kind, false);
		if (!kind->aware && kind->flavor) {
			char base[40];
			object_base_name(base, sizeof(base), kind->tval, false);
			my_strcat(name, format(" %s", base), sizeof(name));
		}
		len += strnfmt(out + len, cap - len, "%d\t%d\t%s\n", (int)kinds[i], count, name);
		assert(len < cap);
	}
}

void native_publish(bool at_prompt)
{
	if (game.messages_dirty) {
		game.messages_dirty = false;
		publish_messages();
	}

	const bool visible = map_visible();
	pthread_mutex_lock(&shared.lock);
	const bool tiles = shared.tiles;
	pthread_mutex_unlock(&shared.lock);
	if (tiles != game.built_tiles) game.dirty = true;
	if (at_prompt) { anim_clear(); anim.absorb = 0; }
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
		meta[NB_META_CONTEXT] = context_flags();
		meta[NB_META_NEW_EXP] = player->new_exp;
		threat_counts(&meta[NB_META_THREATS], &meta[NB_META_THREATS_ALERT], &meta[NB_META_THREAT_NEAREST]);
		meta[NB_META_LIGHT_TURNS] = light_turns_left();
		meta[NB_META_MIN_DEPTH] = player_min_depth(player);
	}
	struct loc target = loc(-1, -1);
	if (visible && target_okay(0)) target_get(&target);
	meta[NB_META_TARGET_X] = target.x;
	meta[NB_META_TARGET_Y] = target.y;
	if (feel_pending) { feel_bits = feel_pending; feel_pending = 0; feel_seq++; }
	meta[NB_META_FEEL_SEQ] = feel_seq;
	meta[NB_META_FEEL_BITS] = feel_bits;
	meta[NB_META_HP_WARN] = player ? player->opts.hitpoint_warn : 0;
	meta[NB_META_AUTO_MORE] = player && OPT(player, auto_more) ? 1 : 0;
	meta[NB_META_TILE_ALERT] = meta[NB_META_TILE_GLOW] = -1;
	if (tiles_on()) {
		meta[NB_META_TILE_ALERT] = native_tile_overlay(false);
		meta[NB_META_TILE_GLOW] = native_tile_overlay(true);
	}
	char quick[sizeof(shared.quick)] = "";
	if (visible) quick_slots(quick, sizeof(quick));
	struct monster *const foe = visible ? tracked_foe() : NULL;
	char foe_name[80] = "";
	meta[NB_META_FOE_X] = meta[NB_META_FOE_Y] = -1;
	if (foe) {
		meta[NB_META_FOE_X] = foe->grid.x;
		meta[NB_META_FOE_Y] = foe->grid.y;
		meta[NB_META_FOE_HEALTH] = (100 * foe->hp + foe->maxhp - 1) / foe->maxhp;
		meta[NB_META_FOE_STANCE] = foe_stance(foe);
		meta[NB_META_FOE_MORALE] = foe->morale >= 0 ? (foe->morale + 9) / 10 : foe->morale / 10;
		monster_desc(foe_name, sizeof(foe_name), foe, MDESC_CAPITAL);
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
	if (rebuild || meta_changed || strcmp(foe_name, shared.foe_name) != 0 || strcmp(quick, shared.quick) != 0) {
		memcpy(shared.meta, meta, sizeof(meta));
		my_strcpy(shared.foe_name, foe_name, sizeof(shared.foe_name));
		my_strcpy(shared.quick, quick, sizeof(shared.quick));
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

/* ------------------------------------------------------------------------
 * Inspect / look list (game thread; read-only, so answered wherever the map shows)
 * ------------------------------------------------------------------------ */

static size_t info_len;
static char info_buf[INFO_CAP];

static void info_add(const char *fmt, ...)
{
	if (info_len >= sizeof(info_buf) - 1) return;
	va_list vp;
	va_start(vp, fmt);
	info_len += vstrnfmt(info_buf + info_len, sizeof(info_buf) - info_len, fmt, vp);
	va_end(vp);
}

static void info_add_textblock(textblock *tb)
{
	char *const text = native_textblock_marked(tb);
	info_add("%s", text);
	mem_free(text);
}

static void flatten(char *s)
{
	for (; *s; s++) if (*s == '\t' || *s == '\n') *s = ' ';
}

static void publish_info(void)
{
	pthread_mutex_lock(&shared.lock);
	memcpy(shared.info, info_buf, info_len + 1);
	shared.info_gen++;
	pthread_mutex_unlock(&shared.lock);
}

static struct monster *visible_monster(struct loc g)
{
	struct monster *const mon = square_monster(cave, g);
	return mon && monster_is_visible(mon) ? mon : NULL;
}

/* One-line summary of the most interesting thing the player knows to be at g. */
static void summarise(struct loc g, char *out, size_t cap)
{
	struct monster *const mon = visible_monster(g);
	if (mon) {
		char name[80], state[80] = "";
		monster_desc(name, sizeof(name), mon, MDESC_IND_VIS | MDESC_CAPITAL);
		look_mon_desc(state, sizeof(state), square(cave, g)->mon);
		strnfmt(out, cap, state[0] ? "%s (%s)" : "%s", name, state);
	} else if (square_object(player->cave, g)) {
		const struct object *const known = square_object(player->cave, g);
		object_desc(out, cap, cave->objects[known->oidx] ? cave->objects[known->oidx] : known,
			ODESC_PREFIX | ODESC_FULL, player);
	} else {
		square_apparent_name(cave, g, out, cap);
		my_strcap(out);
	}
	flatten(out);
}

/*
 * Serialised: "Inspect" \t x \t y; then blocks, each a header line starting
 * with '#' followed by free text lines:
 *   #M \t name \t state   (monster recall follows)
 *   #O \t name            (one per known object)
 *   #F \t terrain
 */
static void inspect(struct loc g)
{
	info_len = 0;
	info_buf[0] = '\0';
	if (!square_in_bounds(cave, g)) return;
	struct monster *const mon = visible_monster(g);
	info_add("Inspect\t%d\t%d\t%d\t%d\n", g.x, g.y, mon && target_able(mon),
		mon && target_okay(0) && target_get_monster() == mon);

	if (mon) {
		char name[80], state[80] = "";
		monster_desc(name, sizeof(name), mon, MDESC_IND_VIS | MDESC_CAPITAL);
		look_mon_desc(state, sizeof(state), square(cave, g)->mon);
		flatten(name);
		flatten(state);
		info_add("#M\t%s\t%s\n", name, state);
		monster_race_track(player->upkeep, mon->race);
		health_track(player->upkeep, mon);
		textblock *const tb = textblock_new();
		lore_description(tb, mon->race, get_lore(mon->race), false);
		info_add_textblock(tb);
		textblock_free(tb);
		info_add("\n");
	}
	for (const struct object *known = square_object(player->cave, g); known; known = known->next) {
		if (ignore_known_item_ok(player, (struct object *)known)) continue;
		const struct object *const real = cave->objects[known->oidx] ? cave->objects[known->oidx] : known;
		char name[160];
		object_desc(name, sizeof(name), real, ODESC_PREFIX | ODESC_FULL, player);
		flatten(name);
		info_add("#O\t%s\n", name);
	}
	if (square_isknown(cave, g)) {
		char terrain[80];
		square_apparent_name(cave, g, terrain, sizeof(terrain));
		my_strcap(terrain);
		info_add("#F\t%s\n", terrain);
	}
}

/* Monsters matter most in Sil, then objects, then features (doors, stairs...). */
static int look_rank(struct loc g)
{
	if (visible_monster(g)) return 0;
	if (square_object(player->cave, g)) return 1;
	return 2;
}

/* What the look list would show as monsters: how many, how many awake to the player, and the nearest one's distance. */
static void threat_counts(int32_t *const count, int32_t *const alert, int32_t *const nearest)
{
	*count = *alert = 0;
	*nearest = -1;
	struct point_set *const pts = target_get_monsters(TARGET_LOOK, NULL, false);
	for (int i = 0; i < point_set_size(pts); i++) {
		const struct loc g = pts->pts[i];
		const struct monster *const mon = loc_eq(g, player->grid) ? NULL : visible_monster(g);
		if (!mon) continue;
		(*count)++;
		if (mon->alertness >= ALERTNESS_ALERT) (*alert)++;
		const int d = distance(player->grid, g);
		if (*nearest < 0 || d < *nearest) *nearest = d;
	}
	point_set_dispose(pts);
}

/* Turns left on the wielded light, or -1 when it never burns out or there is none. */
static int32_t light_turns_left(void)
{
	for (int i = 0; i < player->body.count; i++) {
		const struct object *const obj = slot_object(player, i);
		if (obj && tval_is_light(obj) && !of_has(obj->flags, OF_NO_FUEL)) return obj->timeout;
	}
	return -1;
}

/*
 * Serialised: "Look"; then per interesting grid: x \t y \t summary; monsters
 * first, then objects, then features, each nearest first.
 */
static void look_list(const bool monsters_only)
{
	info_len = 0;
	info_buf[0] = '\0';
	info_add(monsters_only ? "Monsters\n" : "Look\n");
	struct point_set *const pts = target_get_monsters(TARGET_LOOK, NULL, false);
	int listed = 0;
	for (int rank = 0; rank <= (monsters_only ? 0 : 2); rank++)
		for (int i = 0; i < point_set_size(pts) && listed < LOOK_MAX; i++) {
			const struct loc g = pts->pts[i];
			if (loc_eq(g, player->grid) || look_rank(g) != rank) continue;
			char what[200];
			summarise(g, what, sizeof(what));
			info_add("%d\t%d\t%s\n", g.x, g.y, what);
			listed++;
		}
	point_set_dispose(pts);
}

/*
 * The character sheet: display_player(0) (ui-player.c) as sections of
 * label/value rows.  Serialised: "Character"; then "#S" \t section title;
 * rows: label \t value \t attr \t detail; "#T" \t a paragraph.
 */
static void character_sheet(void);

/* The character sheet in the info format (see narsil_bridge.h), for the panel's Character tab. */
const char *native_character_text(void)
{
	handle_stuff(player);
	character_sheet();
	return info_buf;
}

static void character_sheet(void)
{
	static const char *skills[] = {
		#define SKILL(a, b) b,
		#include "list-skills.h"
		#undef SKILL
	};
	const struct player_state *st = &player->state;
	info_len = 0;
	info_buf[0] = '\0';
	info_add("Character\n");

	info_add("#S\t%s\n", player->full_name);
	info_add("Race\t%s\t%d\t\n", player->race->name, COLOUR_L_BLUE);
	info_add("House\t%s\t%d\t\n", player->house->short_name, COLOUR_L_BLUE);
	info_add("Sex\t%s\t%d\t\n", player->sex->name, COLOUR_L_BLUE);
	info_add("Age · height · weight\t%d · %d'%d\" · %d lb\t%d\t\n", player->age, player->ht / 12, player->ht % 12,
		player->wt, COLOUR_L_BLUE);

	info_add("#S\tStats\n");
	for (int i = 0; i < STAT_MAX; i++) {
		char detail[80] = "";
		const bool drained = player->stat_drain[i] < 0;
		if (st->stat_equip_mod[i] || player->stat_drain[i] || st->stat_misc_mod[i]) {
			my_strcat(detail, format("base %d", player->stat_base[i]), sizeof(detail));
			if (st->stat_equip_mod[i]) my_strcat(detail, format(" · equipment %+d", st->stat_equip_mod[i]), sizeof(detail));
			if (player->stat_drain[i]) my_strcat(detail, format(" · drained %+d", player->stat_drain[i]), sizeof(detail));
			if (st->stat_misc_mod[i]) my_strcat(detail, format(" · other %+d", st->stat_misc_mod[i]), sizeof(detail));
		}
		static const char *const full[STAT_MAX] = { "Strength", "Dexterity", "Constitution", "Grace" };
		if (drained) my_strcat(detail, " (drained)", sizeof(detail));
		info_add("%s\t%d\t%d\t%s\n", full[i], st->stat_use[i],
			drained ? COLOUR_YELLOW : COLOUR_L_GREEN, detail);
	}

	info_add("#S\tSkills\n");
	for (int s = 0; s < SKILL_MAX; s++) {
		char detail[80];
		strnfmt(detail, sizeof(detail), "base %d", player->skill_base[s]);
		if (st->skill_stat_mod[s]) my_strcat(detail, format(" · stats %+d", st->skill_stat_mod[s]), sizeof(detail));
		if (st->skill_equip_mod[s]) my_strcat(detail, format(" · equipment %+d", st->skill_equip_mod[s]), sizeof(detail));
		if (st->skill_misc_mod[s]) my_strcat(detail, format(" · other %+d", st->skill_misc_mod[s]), sizeof(detail));
		info_add("%s\t%d\t%d\t%s\n", skills[s], st->skill_use[s], COLOUR_L_GREEN, detail);
	}

	info_add("#S\tCombat\n");
	const int mel = st->skill_use[SKILL_MELEE], arc = st->skill_use[SKILL_ARCHERY];
	const int blows = player_active_ability(player, "Rapid Attack") ? 2 : 1;
	info_add("Melee\t(%+d, %dd%d)%s\t%d\t\n", mel, st->mdd, st->mds, blows > 1 ? " ×2" : "", COLOUR_L_BLUE);
	if (st->mds2 > 0)
		info_add("Off-hand\t(%+d, %dd%d)\t%d\t\n", mel + st->offhand_mel_mod, st->mdd2, st->mds2, COLOUR_L_BLUE);
	if (st->ads > 0)
		info_add("Bows\t(%+d, %dd%d)%s\t%d\t\n", arc, st->add, st->ads,
			player_active_ability(player, "Rapid Fire") ? " ×2" : "", COLOUR_L_BLUE);
	info_add("Armour\t[%+d, %d-%d]\t%d\tevasion, protection\n", st->skill_use[SKILL_EVASION],
		protection_roll(player, PROJ_HURT, true, MINIMISE), protection_roll(player, PROJ_HURT, true, MAXIMISE), COLOUR_L_BLUE);
	info_add("Health\t%d / %d\t%d\t\n", player->chp, player->mhp, player->chp < player->mhp ? COLOUR_YELLOW : COLOUR_L_GREEN);
	info_add("Voice\t%d / %d\t%d\t\n", player->csp, player->msp, player->csp < player->msp ? COLOUR_YELLOW : COLOUR_L_GREEN);
	if (player->song[SONG_MAIN])
		info_add("Song\t%s%s%s\t%d\t\n", player->song[SONG_MAIN]->name, player->song[SONG_MINOR] ? ", " : "",
			player->song[SONG_MINOR] ? player->song[SONG_MINOR]->name : "", COLOUR_L_BLUE);

	info_add("#S\tProgress\n");
	info_add("Experience to spend\t%d\t%d\t\n", player->new_exp, COLOUR_L_GREEN);
	info_add("Total experience\t%d\t%d\t\n", player->exp, COLOUR_L_GREEN);
	const int limit = weight_limit(player->state);
	info_add("Burden\t%.1f / %.1f lb\t%d\t\n", player->upkeep->total_weight / 10.0, limit / 10.0,
		player->upkeep->total_weight > limit ? COLOUR_YELLOW : COLOUR_L_GREEN);
	info_add("Depth\t%d ft (min %d ft)\t%d\t\n", player->depth * 50, player_min_depth(player) * 50,
		player->depth < player_min_depth(player) ? COLOUR_YELLOW : COLOUR_L_GREEN);
	info_add("Light radius\t%d\t%d\t\n", player->upkeep->cur_light, COLOUR_L_GREEN);
	info_add("Game turn\t%d\t%d\t\n", player->turn, COLOUR_L_GREEN);
	info_add("Seed\t%u\t%d\t%s\n", seed_game, COLOUR_L_BLUE, game_title());

	if (player->history && player->history[0]) {
		char history[1024];
		my_strcpy(history, player->history, sizeof(history));
		for (char *p = history; *p; p++) if (*p == '\t' || *p == '\n') *p = ' ';
		info_add("#S\tHistory\n#T\t%s\n", history);
	}
}

bool native_service(bool at_prompt)
{
	pthread_mutex_lock(&shared.lock);
	const request r = shared.pending;
	shared.pending.kind = REQ_NONE;
	pthread_mutex_unlock(&shared.lock);

	if (!map_visible()) return false;
	if (r.kind == REQ_INSPECT) { inspect(r.grid); publish_info(); return false; }
	if (r.kind == REQ_TARGET) {
		if (r.grid.x < 0) {
			target_set_monster(NULL);
			native_publish(at_prompt);
			return false;
		}
		if (target_set_monster(visible_monster(r.grid))) player->upkeep->redraw |= PR_HEALTH;
		inspect(r.grid);
		publish_info();
		return false;
	}
	if (r.kind == REQ_LOOK_LIST) { look_list(r.run); publish_info(); return false; }
	if (r.kind == REQ_AUTO_MORE) {
		OPT(player, auto_more) = r.run;
		native_publish(at_prompt);
		return false;
	}
	if (r.kind == REQ_TEST_CHEST && at_prompt && r.dir == 3) {
		/* A short sword of Hador's House, known, into the pack with its "You have" message. */
		struct object *sword = object_new(); /* mutable: inven_carry() takes the pointer */
		object_prep(sword, lookup_kind(TV_SWORD, lookup_sval(TV_SWORD, "Shortsword")), 5, RANDOMISE);
		for (struct ego_item *e = e_info; e < e_info + z_info->e_max; e++)
			if (e->name && strstr(e->name, "Hador")) sword->ego = e;
		assert(sword->ego);
		for (struct ability *a = sword->ego->abilities; a; a = a->next) add_ability(&sword->abilities, a);  /* as make_ego_item() */
		sword->known = object_new();
		sword->known->kind = sword->kind;
		sword->known->tval = sword->tval;
		sword->known->sval = sword->sval;
		sword->known->number = sword->number;
		sword->known->ego = sword->ego;
		sword->ego->aware = true;  /* as learning the ego does: its abilities are then known */
		inven_carry(player, sword, true, false);
		char name[160];
		object_desc(name, sizeof(name), sword, ODESC_PREFIX | ODESC_FULL, player);
		message_add(format("You have %s.", name), MSG_GENERIC); /* straight into the log: msg() would wait for -more- here */
		game.messages_dirty = true;
		native_publish(at_prompt);
		return false;
	}
	if (r.kind == REQ_TEST_CHEST && at_prompt && r.dir == 4) {
		/* Underfoot: a known Hador's House sword and a plain one with a property still to find, as stepping on them reports it. */
		for (int i = 0; i < 2; i++) {
			struct object *sword = object_new(); /* mutable: drop_near() takes the pointer and clears it */
			object_prep(sword, lookup_kind(TV_SWORD, lookup_sval(TV_SWORD, "Shortsword")), 5, RANDOMISE);
			if (i == 0) {
				for (struct ego_item *e = e_info; e < e_info + z_info->e_max; e++)
					if (e->name && strstr(e->name, "Hador")) sword->ego = e;
				assert(sword->ego);
				for (struct ability *a = sword->ego->abilities; a; a = a->next) add_ability(&sword->abilities, a);  /* as make_ego_item() */
			} else {
				sword->modifiers[OBJ_MOD_STEALTH] = 1;
			}
			drop_near(cave, &sword, 0, player->grid, false, true);
			square_know_pile(cave, player->grid);
		}
		for (struct object *o = square_object(player->cave, player->grid); o; o = o->next) {
			if (o->ego) o->ego->aware = true;
			if (o->ego && o->known) o->known->ego = o->ego;
			else if (o->known) o->known->notice |= OBJ_NOTICE_ASSESSED;
		}
		event_signal(EVENT_SEEFLOOR);
		native_publish(at_prompt);
		return false;
	}
	if (r.kind == REQ_TEST_CHEST && at_prompt && r.dir == 6) {
		(void)player_inc_timed(player, TMD_RAGE, 20, false, false, false);
		native_publish(at_prompt);
		return false;
	}
	if (r.kind == REQ_TEST_CHEST && at_prompt && r.dir == 5) {
		struct object *bones = object_new(); /* mutable: drop_near() takes the pointer and clears it */
		object_prep(bones, lookup_kind(TV_USELESS, lookup_sval(TV_USELESS, "Orc Skeleton")), 5, RANDOMISE);
		drop_near(cave, &bones, 0, player->grid, false, true);
		square_know_pile(cave, player->grid);
		native_publish(at_prompt);
		return false;
	}
	if (r.kind == REQ_TEST_CHEST && at_prompt) {
		const int count = r.dir == 2 ? 4 : 1; /* 2: a pile, as stepping on one reports it */
		for (int i = 0; i < count; i++) {
			struct object *chest = object_new(); /* mutable: drop_near() takes the pointer and clears it */
			object_prep(chest, lookup_kind(TV_CHEST, lookup_sval(TV_CHEST, "Small wooden chest")), 5, RANDOMISE);
			chest->pval = r.dir == 1 ? 5 : 1; /* trapped, or merely locked: see obj-chest.c */
			drop_near(cave, &chest, 0, player->grid, false, true);
			square_know_pile(cave, player->grid);
			if (chest->known) chest->known->pval = chest->pval; /* a trap the player has noticed */
		}
		if (count > 1) event_signal(EVENT_SEEFLOOR);
		native_publish(at_prompt);
		return false;
	}
	if (!at_prompt && (r.kind == REQ_TRAVEL || r.kind == REQ_ACTION || r.kind == REQ_MOVE || r.kind == REQ_REST || r.kind == REQ_USE_KIND)) native_explore_cancel();
	if (!at_prompt) return false;
	switch (r.kind) {
		case REQ_PREVIEW: preview_path(r.grid); return false;
		case REQ_CLEAR_PREVIEW: publish_path(r.grid, NULL, 0); return false;
		case REQ_TRAVEL:
		case REQ_ACTION:
		case REQ_MOVE:
		case REQ_REST:
		case REQ_USE_KIND:
		case REQ_TEST_DIE:
		case REQ_TEST_MSG:
		case REQ_TEST_DEPTH:
		case REQ_TEST_ANIM: game.stashed = r; return true;
		case REQ_TEST_CHEST:
		case REQ_INSPECT:
		case REQ_TARGET:
		case REQ_LOOK_LIST:
		case REQ_AUTO_MORE:
		case REQ_NONE: return false;
	}
	return false;
}

/*
 * do_cmd_fire_at_nearest(), except that a chosen target still in view is kept
 * rather than replaced by the closest monster.
 */
static void fire_at_target_or_nearest(void)
{
	if (!target_okay(0) || !target_get_monster()) { do_cmd_fire_at_nearest(); return; }
	const struct object *const bow = equipped_item_by_slot_name(player, "shooting");
	struct object *const ammo1 = equipped_item_by_slot_name(player, "first quiver");
	struct object *const ammo = ammo1 ? ammo1 : equipped_item_by_slot_name(player, "second quiver");
	if (!bow || !player->state.ammo_tval || !ammo) { do_cmd_fire_at_nearest(); return; }  /* it explains */
	cmdq_push(CMD_FIRE);
	cmd_set_arg_item(cmdq_peek(), "item", ammo);
	cmd_set_arg_target(cmdq_peek(), "target", DIR_TARGET);
}

/* Mirrors textui_process_click() for a left click on the map. */
static void begin_travel(const struct loc target)
{
	game.travel.active = true;
	game.travel.hit_wall = false;
	game.travel.target = target;
	game.travel.n_seen = 0;
	for (int i = 1; i < cave_monster_max(cave) && game.travel.n_seen < (int)N_ELEMENTS(game.travel.seen); i++) {
		const struct monster *const mon = cave_monster(cave, i);
		if (mon && mon->race && monster_is_visible(mon)) game.travel.seen[game.travel.n_seen++] = (int16_t)i;
	}
}

/* A walk that ended short of its goal says why: a wall already said so; otherwise the newcomer in view (now tracked as the foe). */
void native_travel_report(void)
{
	if (!game.travel.active) return;
	game.travel.active = false;
	if (game.explore.active) return;  /* native_explore_step says why */
	if (!map_visible() || loc_eq(player->grid, game.travel.target) || game.travel.hit_wall) return;
	const struct monster *const at_goal = square_monster(cave, game.travel.target);
	if (at_goal && monster_is_visible(at_goal)) return;  /* walking up to a monster ends in an attack, not a stop */
	struct monster *newcomer = NULL;
	for (int i = 1; i < cave_monster_max(cave); i++) {
		struct monster *const mon = cave_monster(cave, i);
		if (!mon || !mon->race || !monster_is_visible(mon)) continue;
		bool known = false;
		for (int k = 0; k < game.travel.n_seen; k++) known = known || game.travel.seen[k] == i;
		if (!known && (!newcomer || distance(player->grid, mon->grid) < distance(player->grid, newcomer->grid))) newcomer = mon;
	}
	if (!newcomer) { msg("You stop: something disturbed you."); return; }
	char name[80];
	monster_desc(name, sizeof(name), newcomer, MDESC_IND_VIS);
	health_track(player->upkeep, newcomer);
	msg("You stop: you see %s.", name);
}

static void push_travel(struct loc target)
{
	if (!square_in_bounds_fully(cave, target)) return;
	if (loc_eq(target, player->grid)) {
		if (square_object(cave, target)) cmdq_push(CMD_PICKUP);
		else if (square_isforge(cave, target)) (void)native_panel(NB_TAB_SMITH, 0);
		else cmdq_push(CMD_HOLD);
		return;
	}
	const bool adjacent = ABS(target.x - player->grid.x) <= 1
		&& ABS(target.y - player->grid.y) <= 1;
	if (adjacent || player->timed[TMD_CONFUSED]) {
		cmdq_push(CMD_WALK);
		cmd_set_arg_direction(cmdq_peek(), "direction", motion_dir(player->grid, target));
	} else {
		begin_travel(target);
		cmdq_push(CMD_PATHFIND);
		cmd_set_arg_point(cmdq_peek(), "point", target);
	}
}


/* ------------------------------------------------------------------------
 * Exploring (game thread)
 * ------------------------------------------------------------------------ */

enum { EXPLORE_REACH = 45, EXPLORE_MAX_LEGS = 600 };

static size_t grid_index(const struct loc grid) { return (size_t)grid.y * (size_t)cave->width + (size_t)grid.x; }

/* Mirrors is_valid_pf() in player-path.c for known grids: what the game's pathfinder will walk through. */
static bool explore_walkable(const struct loc grid)
{
	if (!square_isknown(player->cave, grid) || square_isvisibletrap(player->cave, grid) || square_ischasm(player->cave, grid)) return false;
	return square_ispassable(player->cave, grid);
}

static bool explore_has_unseen_item(const struct loc grid)
{
	for (const struct object *o = square_object(player->cave, grid); o; o = o->next)
		if (!ignore_item_ok(player, o)) return true;
	return false;
}

static bool explore_next_to_unknown(const struct loc grid)
{
	for (int d = 0; d < 8; d++) {
		const struct loc n = loc_sum(grid, ddgrid_ddd[d]);
		if (square_in_bounds(cave, n) && !square_isknown(player->cave, n)) return true;
	}
	return false;
}

/* Items first: what has been seen on the floor is fetched before the unknown is entered. */
static bool explore_is_goal(const struct loc grid, const bool items_only)
{
	if (game.explore.skip[grid_index(grid)]) return false;
	if (items_only) return explore_walkable(grid) && explore_has_unseen_item(grid);
	if (square_iscloseddoor(player->cave, grid)) return true;
	return explore_walkable(grid) && explore_next_to_unknown(grid);
}

/*
 * Breadth-first over the grids the player knows; the first goal found is the nearest by steps.  Returns the grid to
 * walk to next: the goal itself, or the last grid of the way there within the pathfinder's reach.
 */
static bool explore_choose(const bool items_only, struct loc *const next, struct loc *const goal)
{
	const size_t n = (size_t)cave->width * (size_t)cave->height;
	int32_t *const parent = mem_alloc(n * sizeof(*parent));
	int32_t *const queue = mem_alloc(n * sizeof(*queue));
	for (size_t i = 0; i < n; i++) parent[i] = -2;
	size_t head = 0, tail = 0;
	const size_t start = grid_index(player->grid);
	parent[start] = -1;
	queue[tail++] = (int32_t)start;
	bool found = false;
	size_t at = 0;
	while (head < tail && !found) {
		const size_t cur = (size_t)queue[head++];
		const struct loc here = loc((int)(cur % (size_t)cave->width), (int)(cur / (size_t)cave->width));
		if (cur != start && explore_is_goal(here, items_only)) { found = true; at = cur; break; }
		if (cur != start && square_iscloseddoor(player->cave, here)) continue;
		for (int d = 0; d < 8; d++) {
			const struct loc to = loc_sum(here, ddgrid_ddd[d]);
			if (!square_in_bounds_fully(cave, to)) continue;
			const size_t t = grid_index(to);
			if (parent[t] != -2) continue;
			if (!explore_walkable(to) && !square_iscloseddoor(player->cave, to)) continue;
			parent[t] = (int32_t)cur;
			queue[tail++] = (int32_t)t;
		}
	}
	if (found) {
		/* Walk the way back, then forward again to the last grid still within reach of the pathfinder. */
		size_t depth = 0;
		for (size_t g = at; g != start; g = (size_t)parent[g]) queue[depth++] = (int32_t)g;
		struct loc step = loc((int)(at % (size_t)cave->width), (int)(at / (size_t)cave->width));
		*goal = step;
		const size_t last = square_iscloseddoor(player->cave, *goal) ? 1 : 0;  /* a shut door is opened, not walked into */
		for (size_t k = depth; k-- > last;) {
			const size_t g = (size_t)queue[k];
			const struct loc here = loc((int)(g % (size_t)cave->width), (int)(g / (size_t)cave->width));
			if (distance(here, player->grid) > EXPLORE_REACH) break;
			step = here;
		}
		*next = step;
	}
	mem_free(parent);
	mem_free(queue);
	return found;
}

static const struct monster *explore_visible_monster(void)
{
	const struct monster *nearest = NULL;
	for (int i = 1; i < cave_monster_max(cave); i++) {
		const struct monster *const mon = cave_monster(cave, i);
		if (!mon || !mon->race || !monster_is_visible(mon)) continue;
		if (!nearest || distance(player->grid, mon->grid) < distance(player->grid, nearest->grid)) nearest = mon;
	}
	return nearest;
}

static void explore_end(const char *why)
{
	if (!game.explore.active) return;
	game.explore.active = false;
	if (why) msg("%s", why);
}

void native_explore_cancel(void)
{
	explore_end(NULL);
}

static void explore_begin(const bool single)
{
	explore_end(NULL);
	if (!map_visible()) return;
	game.explore.active = true;
	game.explore.single = single;
	game.explore.opening = false;
	game.explore.legs = 0;
	game.explore.depth = player->depth;
	game.explore.level = level_serial;
	game.explore.hp = player->chp;
	if (!game.explore.skip || game.explore.skip_level != level_serial) {
		mem_free(game.explore.skip);
		game.explore.skip_cap = (size_t)cave->width * (size_t)cave->height;
		game.explore.skip = mem_zalloc(game.explore.skip_cap * sizeof(bool));
		game.explore.skip_level = level_serial;
	}
	game.explore.skip[grid_index(player->grid)] = true; /* what lies underfoot has been seen */
	game.explore.from = game.explore.target = player->grid;
}

bool native_explore_step(void)
{
	if (!game.explore.active) return false;
	if (!map_visible() || game.explore.level != level_serial || player->depth != game.explore.depth) { explore_end(NULL); return false; }

	const struct monster *const foe = explore_visible_monster();
	if (foe) {
		char name[80];
		monster_desc(name, sizeof(name), foe, MDESC_IND_VIS);
		health_track(player->upkeep, (struct monster *)foe);
		char why[120];
		strnfmt(why, sizeof(why), "You stop: you see %s.", name);
		explore_end(why);
		return false;
	}
	if (game.explore.legs > 0 && !game.explore.opening) {
		const bool arrived = loc_eq(player->grid, game.explore.target);
		const bool moved = !loc_eq(player->grid, game.explore.from);
		if (arrived) game.explore.skip[grid_index(player->grid)] = true;
		else if (!moved) game.explore.skip[grid_index(game.explore.target)] = true;  /* no way there after all */
		if (arrived && square_object(player->cave, player->grid)) { explore_end("You stop: there is something here."); return false; }
		if (moved && !arrived && !game.travel.hit_wall) { explore_end("You stop: something disturbed you."); return false; }
	}
	game.explore.opening = false;

	if (player->chp < game.explore.hp) { explore_end("You stop: you are hurt."); return false; }
	if (player->chp * 2 < player->mhp) { explore_end("You stop: rest first, you are too hurt."); return false; }
	if (game.explore.legs >= EXPLORE_MAX_LEGS) { explore_end("You stop: that is enough for now."); return false; }
	if (game.explore.single && game.explore.legs > 0) { explore_end(NULL); return false; }

	struct loc next, goal;
	int tries = 0;
	for (;;) {
		if (!explore_choose(true, &next, &goal) && !explore_choose(false, &next, &goal)) {
			const bool stairs = game.stairs_down.x >= 0;
			explore_end(game.explore.legs == 0 ? "Nothing left to explore here."
				: stairs ? "Explored: nothing more to find (the way down is known)." : "Explored: nothing more to find.");
			return false;
		}
		/* A shut door: open it from the next grid along rather than walking into it. */
		if (square_iscloseddoor(player->cave, goal) && distance(player->grid, goal) <= 1) {
			game.explore.skip[grid_index(goal)] = true;
			game.explore.opening = true;
			game.explore.legs++;
			game.explore.hp = player->chp;
			cmdq_push(CMD_OPEN);
			cmd_set_arg_direction(cmdq_peek(), "direction", motion_dir(player->grid, goal));
			return true;
		}
		if (!loc_eq(next, player->grid)) break;
		game.explore.skip[grid_index(goal)] = true;  /* cannot be approached */
		if (++tries > 64) { explore_end("Explored: the rest cannot be reached."); return false; }
	}
	game.explore.from = player->grid;
	game.explore.target = next;
	game.explore.hp = player->chp;
	game.explore.legs++;
	push_travel(next);
	return true;
}

static void push_choice(cmd_code cmd, int choice)
{
	cmdq_push(cmd);
	cmd_set_arg_choice(cmdq_peek(), "choice", choice);
}

/*
 * Runs at the command prompt, as the corresponding key would: game commands
 * are queued, textui screens are opened in place (they are UI commands).
 */
static void run_action(enum nb_action a)
{
	native_prompt_set_intent(a);
	switch (a) {
		case NB_ACT_REST: push_choice(CMD_REST, REST_COMPLETE); break;
		case NB_ACT_FLETCH: cmdq_push(CMD_FLETCH); break;
		case NB_ACT_REPEAT: cmdq_push(CMD_REPEAT); break;
		case NB_ACT_TRAVEL_DOWN: if (game.stairs_down.x >= 0) push_travel(game.stairs_down); break;
		case NB_ACT_TRAVEL_UP: if (game.stairs_up.x >= 0) push_travel(game.stairs_up); break;
		case NB_ACT_HOLD: cmdq_push(CMD_HOLD); break;
		case NB_ACT_GO_DOWN: cmdq_push(CMD_GO_DOWN); break;
		case NB_ACT_GO_UP: cmdq_push(CMD_GO_UP); break;
		case NB_ACT_PICKUP: cmdq_push(CMD_PICKUP); break;
		case NB_ACT_STEALTH: cmdq_push(CMD_TOGGLE_STEALTH); break;
		case NB_ACT_SONG: cmdq_push(CMD_SING); break;
		case NB_ACT_FIRE_NEAREST: if (player_can_fire_quiver1(player, true)) fire_at_target_or_nearest(); break;
		case NB_ACT_THROW_NEAREST: if (player_has_throwable(player, true)) do_cmd_automatic_throw(); break;
		case NB_ACT_OPEN: cmdq_push(CMD_OPEN); break;
		case NB_ACT_SEARCH:  /* the direction prompt picks the skeleton */
			if (context_flags() & NB_CTX_ADJ_SKELETON) cmdq_push(CMD_ALTER);
			break;
		case NB_ACT_CLOSE: cmdq_push(CMD_CLOSE); break;
		case NB_ACT_DISARM: cmdq_push(CMD_DISARM); break;
		case NB_ACT_TUNNEL: cmdq_push(CMD_TUNNEL); break;
		case NB_ACT_BASH: cmdq_push(CMD_BASH); break;
		case NB_ACT_EXCHANGE: cmdq_push(CMD_EXCHANGE); break;
		case NB_ACT_INVENTORY: (void)native_panel(NB_TAB_PACK, NB_ITEM_INVENTORY); break;
		case NB_ACT_FLOOR: (void)native_panel(NB_TAB_PACK, NB_ITEM_FLOOR); break;
		case NB_ACT_EXPLORE:
		case NB_ACT_EXPLORE_STEP:
			explore_begin(a == NB_ACT_EXPLORE_STEP);
			(void)native_explore_step();
			break;
		case NB_ACT_EQUIPMENT: (void)native_panel(NB_TAB_PACK, NB_ITEM_EQUIPMENT); break;
		case NB_ACT_PANEL: (void)native_panel(-1, NB_ITEM_INVENTORY); break;
		case NB_ACT_MAP: do_cmd_view_map(); break;
		case NB_ACT_LOOK: do_cmd_look(); break;
		case NB_ACT_MESSAGES: do_cmd_messages(); break;
		case NB_ACT_CHARACTER: (void)native_panel(NB_TAB_CHARACTER, 0); break;
		case NB_ACT_CHARACTER_SCREEN: do_cmd_change_name(); break;
		case NB_ACT_SMITH: (void)native_panel(NB_TAB_SMITH, 0); break;
		case NB_ACT_ABILITIES: (void)native_panel(NB_TAB_ABILITIES, 0); break;
		case NB_ACT_HELP: do_cmd_help(); break;
		case NB_ACT_OPTIONS: (void)native_panel(NB_TAB_OPTIONS, 0); break;
		case NB_ACT_KNOWLEDGE: (void)native_panel(NB_TAB_KNOWLEDGE, 0); break;
		case NB_ACT_SKILLS: (void)native_panel(NB_TAB_SKILLS, 0); break;
		case NB_ACT_COUNT: break;
	}
}

void native_run_stashed(void)
{
	native_prompt_set_intent(-1);
	const request r = game.stashed;
	game.stashed.kind = REQ_NONE;
	game.travel.active = false;
	if (!map_visible()) return;
	if (r.kind == REQ_TRAVEL) {
		publish_path(r.grid, NULL, 0);
		push_travel(r.grid);
	} else if (r.kind == REQ_ACTION) {
		publish_path(r.grid, NULL, 0);
		/* nb_request_action_at: the shot or throw is at the monster on that grid, if one is in view. */
		if (r.dir == 1) {
			struct monster *const mon = visible_monster(r.grid);
			if (mon && target_set_monster(mon)) player->upkeep->redraw |= PR_HEALTH;
		}
		run_action(r.action);
	} else if (r.kind == REQ_REST) {
		push_choice(CMD_REST, r.dir);
	} else if (r.kind == REQ_USE_KIND) {
		int count;
		struct object *const obj = carried_kind(r.dir, &count); /* mutable: the command takes the pointer */
		if (obj) native_use_object(obj);
	} else if (r.kind == REQ_TEST_DIE) {
		take_hit(player, player->chp + 100, "the test harness");
	} else if (r.kind == REQ_TEST_MSG) {
		msgt(r.dir, "Test message.");
	} else if (r.kind == REQ_TEST_ANIM) {
		/* A bolt along the row to the east, a hit on its last grid, and a blast of three grids there. */
		const struct loc at = player->grid;
		for (int i = 1; i <= 5; i++)
			event_signal_bolt(EVENT_BOLT, PROJ_FIRE, true, true, false, at.y, at.x + i - 1, at.y, at.x + i);
		event_signal_hit(EVENT_HIT, 12, PROJ_HURT, false, loc(at.x + 5, at.y));
		int dist[3] = { 0, 1, 1 };
		bool sees[3] = { true, true, true };
		struct loc grids[3] = { loc(at.x + 5, at.y), loc(at.x + 6, at.y), loc(at.x + 4, at.y) };
		event_signal_blast(EVENT_EXPLOSION, PROJ_FIRE, 3, dist, true, sees, grids, grids[0]);
	} else if (r.kind == REQ_TEST_DEPTH) {
		dungeon_change_level(player, r.dir);
	} else if (r.kind == REQ_MOVE) {
		publish_path(r.grid, NULL, 0);
		/* Running confused would be refused; the game makes walking stagger instead. */
		cmdq_push(r.run && !player->timed[TMD_CONFUSED] ? CMD_RUN : CMD_WALK);
		cmd_set_arg_direction(cmdq_peek(), "direction", r.dir);
	}
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

void nb_request_action_at(enum nb_action a, int x, int y)
{
	if (a != NB_ACT_FIRE_NEAREST && a != NB_ACT_THROW_NEAREST) return;
	post((request){ .kind = REQ_ACTION, .action = a, .grid = loc(x, y), .dir = 1 });
}

void nb_request_action(enum nb_action a)
{
	if (a < 0 || a >= NB_ACT_COUNT) return;
	post((request){ .kind = REQ_ACTION, .action = a });
}

void nb_request_move(int dir, bool run)
{
	if (dir < 1 || dir > 9 || dir == 5) return;
	post((request){ .kind = REQ_MOVE, .dir = dir, .run = run });
}

#ifdef NB_TEST_HOOKS
void nb_test_die(void)
{
	post((request){ .kind = REQ_TEST_DIE });
}

/* Test hook: the game thread reads this only after the host's next answer, which is handed over under the lock. */
void nb_test_game_seed(uint32_t seed)
{
	seed_game_requested = seed;
}

void nb_test_message(int type)
{
	post((request){ .kind = REQ_TEST_MSG, .dir = type });
}

int nb_test_anim_frames(void)
{
	pthread_mutex_lock(&shared.lock);
	const int n = shared.anim_frames;
	pthread_mutex_unlock(&shared.lock);
	return n;
}

void nb_test_anim(void)
{
	post((request){ .kind = REQ_TEST_ANIM });
}

void nb_test_depth(int level)
{
	post((request){ .kind = REQ_TEST_DEPTH, .dir = level });
}

int nb_test_forges(int *fixed_made, int *depth)
{
	int n = 0;
	for (int y = 0; y < cave->height; y++)
		for (int x = 0; x < cave->width; x++)
			if (square_isforge(cave, loc(x, y))) n++;
	*fixed_made = player->fixed_forge_count;
	*depth = player->depth;
	return n;
}

void nb_test_chest(int kind)
{
	post((request){ .kind = REQ_TEST_CHEST, .dir = kind });
}
#endif

void nb_set_quick_kinds(const int32_t *kinds, int n)
{
	assert(n >= 0 && (n == 0 || kinds));
	pthread_mutex_lock(&shared.lock);
	shared.quick_n = 0;
	for (int i = 0; i < n && shared.quick_n < NB_QUICK_MAX; i++)
		if (kinds[i] > 0 && kinds[i] < z_info->k_max) shared.quick_kinds[shared.quick_n++] = kinds[i];
	pthread_mutex_unlock(&shared.lock);
	bridge_wake_game();
}

size_t nb_quick_slots(char *text, size_t cap)
{
	assert(cap > 0);
	pthread_mutex_lock(&shared.lock);
	my_strcpy(text, shared.quick, cap);
	pthread_mutex_unlock(&shared.lock);
	return strlen(text);
}

void nb_request_use_kind(int kind)
{
	post((request){ .kind = REQ_USE_KIND, .dir = kind });
}

void nb_request_inspect(int x, int y)
{
	post((request){ .kind = REQ_INSPECT, .grid = loc(x, y) });
}

void nb_request_rest(int turns_or_mode)
{
	assert(turns_or_mode > 0 || turns_or_mode == NB_REST_AS_NEEDED || turns_or_mode == NB_REST_HP_AND_VOICE
		|| turns_or_mode == NB_REST_HP_OR_VOICE);
	post((request){ .kind = REQ_REST, .dir = turns_or_mode });
}

static bool tiles_on(void)
{
	pthread_mutex_lock(&shared.lock);
	const bool on = shared.tiles;
	pthread_mutex_unlock(&shared.lock);
	return on;
}

void nb_set_tiles(bool on)
{
	pthread_mutex_lock(&shared.lock);
	shared.tiles = on;
	pthread_mutex_unlock(&shared.lock);
	bridge_wake_game();
}

void nb_set_item_details(bool on)
{
	pthread_mutex_lock(&shared.lock);
	shared.item_details = on;
	pthread_mutex_unlock(&shared.lock);
}

bool native_item_details_on(void)
{
	pthread_mutex_lock(&shared.lock);
	const bool on = shared.item_details;
	pthread_mutex_unlock(&shared.lock);
	return on;
}

void nb_request_target(int x, int y)
{
	post((request){ .kind = REQ_TARGET, .grid = loc(x, y) });
}

void nb_set_auto_more(bool on)
{
	post((request){ .kind = REQ_AUTO_MORE, .run = on });
}

void nb_request_look_list(void)
{
	post((request){ .kind = REQ_LOOK_LIST });
}

void nb_request_look_monsters(void)
{
	post((request){ .kind = REQ_LOOK_LIST, .run = true });
}

size_t nb_foe_name(char *text, size_t cap)
{
	assert(cap > 0);
	pthread_mutex_lock(&shared.lock);
	my_strcpy(text, shared.foe_name, cap);
	pthread_mutex_unlock(&shared.lock);
	return strlen(text);
}

uint64_t nb_info_generation(void)
{
	pthread_mutex_lock(&shared.lock);
	const uint64_t gen = shared.info_gen;
	pthread_mutex_unlock(&shared.lock);
	return gen;
}

size_t nb_info(char *text, size_t cap)
{
	assert(cap > 0);
	pthread_mutex_lock(&shared.lock);
	my_strcpy(text, shared.info, cap);
	pthread_mutex_unlock(&shared.lock);
	return strlen(text);
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
	const bool fits = cells && n_ints >= NB_MAP_CELL_INTS * n;
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
