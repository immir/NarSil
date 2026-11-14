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
#include "combat.h"
#include "player-abilities.h"
#include "project.h"
#include "game-event.h"
#include "game-world.h"
#include "obj-chest.h"
#include "obj-gear.h"
#include "obj-make.h"
#include "obj-pile.h"
#include "obj-util.h"
#include "player-attack.h"
#include "player-calcs.h"
#include "player-path.h"
#include "player-util.h"
#include "player-timed.h"
#include "ui-abilities.h"
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

enum { PATH_MAX_STEPS = 500, MESSAGES_SHOWN = 300, INFO_CAP = 32 * 1024, LOOK_MAX = 40 };

/* NB_MAP_CELL_INTS per grid: glyph, attr | flags << 16, tile, terrain tile beneath it (each or -1), then the
 * overlay bits: NB_TILE_ALERT, NB_TILE_GLOW. */
typedef struct { int32_t cp, attr_flags, tile, tile_bg, tile_flags; } map_cell;

enum request_kind { REQ_NONE, REQ_PREVIEW, REQ_CLEAR_PREVIEW, REQ_TRAVEL, REQ_ACTION, REQ_MOVE, REQ_INSPECT, REQ_TARGET, REQ_LOOK_LIST, REQ_TEST_DIE, REQ_TEST_CHEST, REQ_REST, REQ_AUTO_MORE };
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
} game;

static bool tiles_on(void);

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
		char text[512];
		my_strcpy(text, message_str((uint16_t)age), sizeof(text));
		for (char *p = text; *p; p++) if (*p == '\t' || *p == '\n') *p = ' ';
		len += (size_t)strnfmt(buf + len, sizeof(buf) - len, "%d\t%d\t%s\n",
			(int)message_color((uint16_t)age), (int)message_count((uint16_t)age), text);
		assert(len < sizeof(buf));
	}
	pthread_mutex_lock(&shared.lock);
	memcpy(shared.messages, buf, len + 1);
	shared.messages_gen++;
	pthread_mutex_unlock(&shared.lock);
}

void native_init(void)
{
	native_birth_init();
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
			struct native_tile_cell t = { -1, -1, false, false };
			if (tiles) (void)native_tile(&g, &t);
			grid_data_as_text(&g, &a, &c, &ta, &tc);
			assert(a >= 0 && a < 0x10000);
			game.work[(size_t)y * (size_t)cave->width + (size_t)x] = (map_cell){
				.cp = (int32_t)c, .attr_flags = a | (cell_flags(&g, grid) << 16), .tile = t.fg, .tile_bg = t.bg,
				.tile_flags = (t.alert ? NB_TILE_ALERT : 0) | (t.glow ? NB_TILE_GLOW : 0),
			};
		}
	}
}

/* What is around the player, for context actions.  Only what the player knows. */
static int32_t context_flags(void)
{
	int32_t f = 0;
	const struct loc g = player->grid;
	if (square_isupstairs(cave, g)) f |= NB_CTX_UP_STAIRS;
	if (square_isdownstairs(cave, g)) f |= NB_CTX_DOWN_STAIRS;
	if (square_object(cave, g)) f |= NB_CTX_FLOOR_ITEMS;
	if (square_isforge(cave, g)) f |= NB_CTX_FORGE;
	if (game.stairs_down.x >= 0 && !square_isdownstairs(cave, g)) f |= NB_CTX_DOWN_KNOWN;
	if (game.stairs_up.x >= 0 && !square_isupstairs(cave, g)) f |= NB_CTX_UP_KNOWN;
	if (player_can_fire_quiver1(player, false)) f |= NB_CTX_CAN_FIRE;
	if (player_has_throwable(player, false)) f |= NB_CTX_CAN_THROW;
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
	}
	struct loc target = loc(-1, -1);
	if (visible && target_okay(0)) target_get(&target);
	meta[NB_META_TARGET_X] = target.x;
	meta[NB_META_TARGET_Y] = target.y;
	meta[NB_META_HP_WARN] = player ? player->opts.hitpoint_warn : 0;
	meta[NB_META_AUTO_MORE] = player && OPT(player, auto_more) ? 1 : 0;
	meta[NB_META_TILE_ALERT] = meta[NB_META_TILE_GLOW] = -1;
	if (tiles_on()) {
		meta[NB_META_TILE_ALERT] = native_tile_overlay(false);
		meta[NB_META_TILE_GLOW] = native_tile_overlay(true);
	}
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
	if (rebuild || meta_changed || strcmp(foe_name, shared.foe_name) != 0) {
		memcpy(shared.meta, meta, sizeof(meta));
		my_strcpy(shared.foe_name, foe_name, sizeof(shared.foe_name));
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
	for (const wchar_t *w = textblock_text(tb); w && *w; w++) {
		char mb[8];
		const int n = text_wctomb(mb, *w);
		if (n > 0 && info_len + (size_t)n < sizeof(info_buf) - 1) {
			memcpy(info_buf + info_len, mb, (size_t)n);
			info_len += (size_t)n;
			info_buf[info_len] = '\0';
		}
	}
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

/*
 * Serialised: "Look"; then per interesting grid: x \t y \t summary; monsters
 * first, then objects, then features, each nearest first.
 */
static void look_list(void)
{
	info_len = 0;
	info_buf[0] = '\0';
	info_add("Look\n");
	struct point_set *const pts = target_get_monsters(TARGET_LOOK, NULL, false);
	int listed = 0;
	for (int rank = 0; rank <= 2; rank++)
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
	if (r.kind == REQ_LOOK_LIST) { look_list(); publish_info(); return false; }
	if (r.kind == REQ_AUTO_MORE) {
		OPT(player, auto_more) = r.run;
		native_publish(at_prompt);
		return false;
	}
	if (r.kind == REQ_TEST_CHEST && at_prompt) {
		struct object *const chest = object_new();
		object_prep(chest, lookup_kind(TV_CHEST, lookup_sval(TV_CHEST, "Small wooden chest")), 5, RANDOMISE);
		chest->pval = r.dir ? 5 : 1; /* trapped, or merely locked: see obj-chest.c */
		drop_near(cave, &chest, 0, player->grid, false, false);
		square_know_pile(cave, player->grid);
		if (chest->known) chest->known->pval = chest->pval; /* a trap the player has noticed */
		native_publish(at_prompt);
		return false;
	}
	if (!at_prompt) return false;
	switch (r.kind) {
		case REQ_PREVIEW: preview_path(r.grid); return false;
		case REQ_CLEAR_PREVIEW: publish_path(r.grid, NULL, 0); return false;
		case REQ_TRAVEL:
		case REQ_ACTION:
		case REQ_MOVE:
		case REQ_REST:
		case REQ_TEST_DIE: game.stashed = r; return true;
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
		cmdq_push(CMD_PATHFIND);
		cmd_set_arg_point(cmdq_peek(), "point", target);
	}
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
		case NB_ACT_CLOSE: cmdq_push(CMD_CLOSE); break;
		case NB_ACT_DISARM: cmdq_push(CMD_DISARM); break;
		case NB_ACT_TUNNEL: cmdq_push(CMD_TUNNEL); break;
		case NB_ACT_BASH: cmdq_push(CMD_BASH); break;
		case NB_ACT_EXCHANGE: cmdq_push(CMD_EXCHANGE); break;
		case NB_ACT_INVENTORY: (void)native_panel(NB_TAB_PACK, NB_ITEM_INVENTORY); break;
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
	if (!map_visible()) return;
	if (r.kind == REQ_TRAVEL) {
		publish_path(r.grid, NULL, 0);
		push_travel(r.grid);
	} else if (r.kind == REQ_ACTION) {
		publish_path(r.grid, NULL, 0);
		run_action(r.action);
	} else if (r.kind == REQ_REST) {
		push_choice(CMD_REST, r.dir);
	} else if (r.kind == REQ_TEST_DIE) {
		take_hit(player, player->chp + 100, "the test harness");
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

void nb_test_chest(bool trapped)
{
	post((request){ .kind = REQ_TEST_CHEST, .dir = trapped });
}
#endif

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
