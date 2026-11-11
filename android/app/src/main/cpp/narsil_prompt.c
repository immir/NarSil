/**
 * \file narsil_prompt.c
 * \brief Native prompts: the game thread publishes a typed question and blocks
 *        until the UI answers.
 *
 *  - item:    get_item_hook.  Candidates mirror textui_get_item(); the choice
 *             goes through get_item_allow() (!* inscriptions) as in the textui.
 *  - actions: browsing (get_item with CMD_NULL, i.e. 'i'/'e'): after an item is
 *             picked, offer its actions as context_menu_object() would, with
 *             the object description inline (replacing Inspect).  A chosen
 *             command is queued with the item and get_item reports "nothing
 *             chosen", so the browse screen closes and the command runs.
 *  - check:   get_check_hook (yes/no).
 *  - aim:     get_aim_dir_hook.  Targetable monsters are offered for tapping;
 *             any other grid targets that location; swipes give a direction.
 */
#include "narsil_bridge.h"
#include "narsil_internal.h"

#include "angband.h"
#include "cmd-core.h"
#include "game-input.h"
#include "init.h"
#include "mon-desc.h"
#include "monster.h"
#include "obj-desc.h"
#include "obj-gear.h"
#include "obj-ignore.h"
#include "obj-info.h"
#include "obj-pile.h"
#include "obj-tval.h"
#include "obj-util.h"
#include "player-calcs.h"
#include "player-path.h"
#include "target.h"
#include "ui-game.h"
#include "ui-input.h"
#include "ui-keymap.h"
#include "ui-object.h"
#include "z-textblock.h"

#include <assert.h>
#include <pthread.h>
#include <stdarg.h>
#include <string.h>

enum { MAX_ITEMS = 96, MAX_ACTIONS = 16, MAX_TARGETS = 64, TEXT_CAP = 48 * 1024 };
#define ANSWER_PENDING INT32_MIN

/* Shared; guarded by `lock`. */
static struct {
	pthread_mutex_t lock;
	pthread_cond_t answered_cv;
	enum nb_prompt_kind kind;
	uint64_t gen;
	char text[TEXT_CAP];
	int32_t answer;
} shared = {
	.lock = PTHREAD_MUTEX_INITIALIZER,
	.answered_cv = PTHREAD_COND_INITIALIZER,
};

/* ------------------------------------------------------------------------
 * Ask and wait (game thread)
 * ------------------------------------------------------------------------ */

/* Publishes the prompt (text already built into shared.text) and blocks for the answer. */
static int32_t ask(enum nb_prompt_kind kind)
{
	assert(kind != NB_PROMPT_NONE);
	/* The UI decides what to draw under the prompt from fresh map/meta. */
	native_publish(false);

	pthread_mutex_lock(&shared.lock);
	shared.kind = kind;
	shared.answer = ANSWER_PENDING;
	shared.gen++;
	pthread_mutex_unlock(&shared.lock);

	bridge_set_idle(true);
	pthread_mutex_lock(&shared.lock);
	while (shared.answer == ANSWER_PENDING)
		pthread_cond_wait(&shared.answered_cv, &shared.lock);
	const int32_t answer = shared.answer;
	shared.kind = NB_PROMPT_NONE;
	shared.gen++;
	pthread_mutex_unlock(&shared.lock);
	return answer;
}

/*
 * Builder for shared.text: game thread only, called with shared.lock held,
 * before ask().  Truncates rather than overflowing.
 */
static size_t text_len;

static void text_reset(void)
{
	text_len = 0;
	shared.text[0] = '\0';
}

static void text_add(const char *fmt, ...)
{
	if (text_len >= sizeof(shared.text) - 1) return;
	va_list vp;
	va_start(vp, fmt);
	text_len += vstrnfmt(shared.text + text_len, sizeof(shared.text) - text_len, fmt, vp);
	va_end(vp);
}

static void text_add_utf8(uint32_t cp)
{
	char b[4];
	size_t n;
	if (cp < 0x80) { b[0] = (char)cp; n = 1; }
	else if (cp < 0x800) { b[0] = (char)(0xC0 | (cp >> 6)); b[1] = (char)(0x80 | (cp & 0x3F)); n = 2; }
	else if (cp < 0x10000) {
		b[0] = (char)(0xE0 | (cp >> 12)); b[1] = (char)(0x80 | ((cp >> 6) & 0x3F));
		b[2] = (char)(0x80 | (cp & 0x3F)); n = 3;
	} else {
		b[0] = (char)(0xF0 | (cp >> 18)); b[1] = (char)(0x80 | ((cp >> 12) & 0x3F));
		b[2] = (char)(0x80 | ((cp >> 6) & 0x3F)); b[3] = (char)(0x80 | (cp & 0x3F)); n = 4;
	}
	if (text_len + n < sizeof(shared.text)) {
		memcpy(shared.text + text_len, b, n);
		text_len += n;
		shared.text[text_len] = '\0';
	}
}

/* Fields are tab-separated and records newline-separated: flatten both. */
static void sanitise(char *s)
{
	for (; *s; s++) if (*s == '\t' || *s == '\n') *s = ' ';
}

static int keymode(void)
{
	return OPT(player, angband_keyset) ? KEYMAP_MODE_ANGBAND : KEYMAP_MODE_ORIG;
}

/* ------------------------------------------------------------------------
 * Items
 * ------------------------------------------------------------------------ */

typedef struct {
	struct object *obj;
	enum nb_item_section section;
} candidate;

static bool listed(const candidate *c, int n, const struct object *obj)
{
	for (int i = 0; i < n; i++) if (c[i].obj == obj) return true;
	return false;
}

static int add(candidate *c, int n, struct object *obj, enum nb_item_section s)
{
	if (!obj || n >= MAX_ITEMS || listed(c, n, obj)) return n;
	c[n] = (candidate){ .obj = obj, .section = s };
	return n + 1;
}

static int collect(candidate *c, cmd_code cmd, item_tester tester, int mode)
{
	int n = 0;

	if (mode & SHOW_THROWING) {
		struct object *throwing[MAX_ITEMS];
		const int k = scan_items(throwing, MAX_ITEMS, player, USE_INVEN | USE_FLOOR,
			obj_is_throwing);
		for (int i = 0; i < k; i++)
			if (object_test(tester, throwing[i])) n = add(c, n, throwing[i], NB_ITEM_THROWING);
	}
	if (mode & USE_INVEN)
		for (int i = 0; i < z_info->pack_size; i++) {
			struct object *const obj = player->upkeep->inven[i];
			if (obj && object_test(tester, obj)) n = add(c, n, obj, NB_ITEM_INVENTORY);
		}
	if (mode & USE_EQUIP) {
		const bool filter = cmd != CMD_NULL || tester != NULL;
		for (int i = 0; i < player->body.count; i++) {
			struct object *const obj = slot_object(player, i);
			if (obj && (!filter || object_test(tester, obj)))
				n = add(c, n, obj, NB_ITEM_EQUIPMENT);
		}
	}
	if (mode & USE_FLOOR) {
		struct object *floor[MAX_ITEMS];
		const int k = scan_floor(floor, MAX_ITEMS, player,
			OFLOOR_TEST | OFLOOR_SENSE | OFLOOR_VISIBLE, tester);
		for (int i = 0; i < k; i++) n = add(c, n, floor[i], NB_ITEM_FLOOR);
	}
	return n;
}

static void describe(char *buf, size_t cap, const struct object *obj)
{
	object_desc(buf, cap, obj, ODESC_PREFIX | ODESC_FULL, player);
	sanitise(buf);
}

static void publish_items(const char *pmt, const candidate *c, int n)
{
	pthread_mutex_lock(&shared.lock);
	text_reset();
	text_add("%s\n", pmt ? pmt : "Choose an item");
	for (int i = 0; i < n; i++) {
		const struct object *const obj = c[i].obj;
		char name[160];
		describe(name, sizeof(name), obj);
		const char label = c[i].section == NB_ITEM_FLOOR ? 0
			: gear_to_label(player, (struct object *)obj);
		text_add("%d\t%c\t%d\t%d\t%d\t%s\n", (int)c[i].section, label ? label : '-',
			(int)object_char(obj), (int)object_attr(obj), obj->weight * obj->number, name);
	}
	pthread_mutex_unlock(&shared.lock);
}

/* ------------------------------------------------------------------------
 * Item actions (browse mode)
 * ------------------------------------------------------------------------ */

typedef struct { const char *label; cmd_code cmd; bool valid; } action;

/* Mirrors context_menu_object(); Inspect is shown inline instead. */
static int item_actions(const struct object *obj, action *a)
{
	int n = 0;
#define ACT(l, c, v) do { assert(n < MAX_ACTIONS); a[n++] = (action){ (l), (c), (v) }; } while (0)
	if (obj_is_useable(obj)) {
		if (tval_is_horn(obj)) ACT("Blow", CMD_BLOW_HORN, player->csp > 0);
		else if (tval_is_staff(obj)) ACT("Use", CMD_USE_STAFF, obj_has_charges(obj));
		else if (tval_is_potion(obj)) ACT("Quaff", CMD_QUAFF, true);
		else if (tval_is_edible(obj)) ACT("Eat", CMD_EAT, true);
		else if (obj_can_fire(obj) && object_is_in_quiver(player, obj)) ACT("Fire", CMD_FIRE, true);
		else ACT("Use", CMD_USE, true);
	}
	if (obj_can_refuel(obj)) ACT("Refuel", CMD_REFUEL, true);
	if (object_is_equipped(player->body, obj) && obj_can_takeoff(obj)) ACT("Take off", CMD_TAKEOFF, true);
	else if (!object_is_equipped(player->body, obj) && obj_can_wear(obj)) ACT("Equip", CMD_WIELD, true);
	if (object_is_carried(player, obj)) ACT("Drop", CMD_DROP, true);
	else ACT("Pick up", CMD_PICKUP, inven_carry_okay(obj));
	if (obj_can_throw(obj)) ACT("Throw", CMD_THROW, true);
	ACT("Inscribe", CMD_INSCRIBE, true);
	if (obj_has_inscrip(obj)) ACT("Uninscribe", CMD_UNINSCRIBE, true);
	ACT(object_is_ignored(obj) ? "Unignore" : "Ignore", CMD_IGNORE, true);
#undef ACT
	return n;
}

/*
 * Serialised: line 0 = item name; line 1 = glyph \t attr; one line per action
 * (valid \t label); a line "--"; then the description (may span lines).
 */
static void publish_actions(const struct object *obj, const action *a, int n)
{
	char name[160];
	describe(name, sizeof(name), obj);
	textblock *const tb = object_info(obj, OINFO_NONE);
	const wchar_t *const w = textblock_text(tb);

	pthread_mutex_lock(&shared.lock);
	text_reset();
	text_add("%s\n%d\t%d\n", name, (int)object_char(obj), (int)object_attr(obj));
	for (int i = 0; i < n; i++) text_add("%d\t%s\n", a[i].valid ? 1 : 0, a[i].label);
	text_add("--\n");
	for (const wchar_t *p = w; p && *p; p++) text_add_utf8((uint32_t)*p);
	pthread_mutex_unlock(&shared.lock);
	textblock_free(tb);
}

/* Returns true if the action was taken (or refused); false to go back to the list. */
static bool run_action(struct object *obj)
{
	action a[MAX_ACTIONS];
	const int n = item_actions(obj, a);
	publish_actions(obj, a, n);
	const int32_t pick = ask(NB_PROMPT_ACTIONS);
	if (pick < 0 || pick >= n || !a[pick].valid) return false;

	const cmd_code cmd = a[pick].cmd;
	const unsigned char key = cmd_lookup_key(cmd, keymode());
	if (!key_confirm_command(key) || !get_item_allow(obj, key, cmd, false)) return true;
	if (cmd == CMD_IGNORE) {
		textui_cmd_ignore_menu(obj);
	} else {
		cmdq_push(cmd);
		cmd_set_arg_item(cmdq_peek(), "item", obj);
	}
	return true;
}

static bool native_get_item(struct object **choice, const char *pmt, const char *str,
		cmd_code cmd, item_tester tester, int mode)
{
	candidate c[MAX_ITEMS];

	event_signal(EVENT_MESSAGE_FLUSH);
	*choice = NULL;
	const int n = collect(c, cmd, tester, mode);
	if (n == 0) {
		if (str) msg("%s", str);
		return false;
	}

	for (;;) {
		publish_items(pmt, c, n);
		const int32_t pick = ask(NB_PROMPT_ITEM);
		if (pick < 0 || pick >= n) return false;
		struct object *const obj = c[pick].obj;

		if (cmd == CMD_NULL) {
			/* Browsing: act on the item here; the caller's menu sees no choice. */
			track_object(player->upkeep, obj);
			if (run_action(obj)) return false;
			continue;
		}
		if (!get_item_allow(obj, cmd_lookup_key(cmd, keymode()), cmd, (mode & IS_HARMLESS) != 0))
			return false;
		*choice = obj;
		return true;
	}
}

/* ------------------------------------------------------------------------
 * Yes / no
 * ------------------------------------------------------------------------ */

static bool native_get_check(const char *prompt)
{
	event_signal(EVENT_MESSAGE_FLUSH);
	pthread_mutex_lock(&shared.lock);
	text_reset();
	text_add("%s", prompt ? prompt : "Are you sure?");
	sanitise(shared.text);
	pthread_mutex_unlock(&shared.lock);
	return ask(NB_PROMPT_CHECK) == 1;
}

/* ------------------------------------------------------------------------
 * Aiming
 * ------------------------------------------------------------------------ */

/*
 * Serialised: line 0 = prompt; line 1 = range \t current target x \t y (-1
 * if none); then one line per targetable monster, nearest first:
 * x \t y \t in range (0/1) \t name.
 */
static void publish_aim(int range)
{
	struct point_set *const targets = target_get_monsters(TARGET_KILL, NULL, false);
	struct loc current = loc(-1, -1);
	if (target_okay(range)) target_get(&current);

	pthread_mutex_lock(&shared.lock);
	text_reset();
	text_add("Aim\n%d\t%d\t%d\n", range, current.x, current.y);
	for (int i = 0; i < point_set_size(targets) && i < MAX_TARGETS; i++) {
		const struct loc g = targets->pts[i];
		char name[80];
		monster_desc(name, sizeof(name), square_monster(cave, g), MDESC_STANDARD);
		sanitise(name);
		const bool in_range = !range || distance(player->grid, g) <= range;
		text_add("%d\t%d\t%d\t%s\n", g.x, g.y, in_range ? 1 : 0, name);
	}
	pthread_mutex_unlock(&shared.lock);
	point_set_dispose(targets);
}

/*
 * The target just set is unusable (beyond range or out of the line of fire):
 * the textui's targeting would not have allowed it, and DIR_TARGET would
 * degrade to "at your feet".  Aim the same way instead, as far as range allows.
 */
static bool aim_or_head_towards(int *dp, int range, struct loc g)
{
	if (target_okay(range)) { *dp = DIR_TARGET; return true; }
	const int dir = pathfind_direction_to(player->grid, g);
	if (dir == DIR_NONE || dir == DIR_UNKNOWN) return false;
	*dp = dir;
	return true;
}

static bool native_get_aim_dir(int *dp, int range)
{
	*dp = 0;
	event_signal(EVENT_MESSAGE_FLUSH);
	publish_aim(range);
	const int32_t a = ask(NB_PROMPT_AIM);

	if (a >= 1 && a <= 9 && a != 5) { *dp = a; return true; }
	if (a == NB_AIM_CURRENT && target_okay(range)) { *dp = DIR_TARGET; return true; }
	if (a == NB_AIM_NEAREST) {
		if (!target_set_closest(TARGET_KILL, NULL)) return false;
		struct loc g;
		target_get(&g);
		return aim_or_head_towards(dp, range, g);
	}
	if (a >= NB_AIM_GRID_BASE) {
		const struct loc g = loc((a - NB_AIM_GRID_BASE) % NB_AIM_GRID_STRIDE,
			(a - NB_AIM_GRID_BASE) / NB_AIM_GRID_STRIDE);
		if (!square_in_bounds_fully(cave, g)) return false;
		struct monster *const mon = square_monster(cave, g);
		if (mon && target_able(mon)) {
			health_track(player->upkeep, mon);
			target_set_monster(mon);
		} else {
			target_set_location(g);
		}
		return aim_or_head_towards(dp, range, g);
	}
	return false;
}

/* ------------------------------------------------------------------------
 * Install / public API
 * ------------------------------------------------------------------------ */

void native_prompt_init(void)
{
	get_item_hook = native_get_item;
	get_check_hook = native_get_check;
	get_aim_dir_hook = native_get_aim_dir;
}

uint64_t nb_prompt_generation(void)
{
	pthread_mutex_lock(&shared.lock);
	const uint64_t gen = shared.gen;
	pthread_mutex_unlock(&shared.lock);
	return gen;
}

enum nb_prompt_kind nb_prompt(char *text, size_t cap)
{
	assert(cap > 0);
	pthread_mutex_lock(&shared.lock);
	const enum nb_prompt_kind kind = shared.kind;
	my_strcpy(text, kind == NB_PROMPT_NONE ? "" : shared.text, cap);
	pthread_mutex_unlock(&shared.lock);
	return kind;
}

static bool prompt_open(void)
{
	pthread_mutex_lock(&shared.lock);
	const bool open = shared.kind != NB_PROMPT_NONE && shared.answer == ANSWER_PENDING;
	pthread_mutex_unlock(&shared.lock);
	return open;
}

void nb_answer(int32_t value)
{
	assert(value != ANSWER_PENDING);
	if (!prompt_open()) return;
	/* Busy before the game thread can run, so it is the one that next reports idle. */
	bridge_set_idle(false);
	pthread_mutex_lock(&shared.lock);
	if (shared.kind != NB_PROMPT_NONE && shared.answer == ANSWER_PENDING) {
		shared.answer = value;
		pthread_cond_signal(&shared.answered_cv);
	}
	pthread_mutex_unlock(&shared.lock);
}

void nb_answer_item(int index)
{
	nb_answer(index < 0 ? -1 : index);
}
