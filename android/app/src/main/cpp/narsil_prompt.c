/**
 * \file narsil_prompt.c
 * \brief Native prompts: the game thread publishes a typed question and blocks
 *        until the UI answers.
 *
 *  - item:    get_item_hook.  Candidates mirror textui_get_item(); the choice
 *             goes through get_item_allow() (!* inscriptions) as in the textui.
 *  - pack:    the panel's Pack tab (also what 'i' and 'e' open): every item, one expanded with its
 *             description and actions (context_menu_object() as buttons).
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
#include "mon-predicate.h"
#include "obj-desc.h"
#include "obj-gear.h"
#include "obj-ignore.h"
#include "obj-knowledge.h"
#include "obj-info.h"
#include "obj-pile.h"
#include "obj-tval.h"
#include "obj-util.h"
#include "player-abilities.h"
#include "player-birth.h"
#include "player-calcs.h"
#include "ui-options.h"
#include "ui-birth.h"
#include "ui-output.h"
#include "ui-player.h"
#include "player-skills.h"
#include "songs.h"
#include "player-path.h"
#include "player-util.h"
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

enum { MAX_ITEMS = 96, MAX_ACTIONS = 16, MAX_TARGETS = 64, TEXT_CAP = NB_PROMPT_CAP - 8 };
#define ANSWER_PENDING INT32_MIN

/* Shared; guarded by `lock`. */
static struct {
	pthread_mutex_t lock;
	pthread_cond_t answered_cv;
	enum nb_prompt_kind kind;
	int32_t panel_tab;      /* the character panel's open tab (enum nb_panel_tab), or -1 */
	uint64_t gen;
	char text[TEXT_CAP];
	int32_t answer;
	char answer_text[256];
} shared = {
	.lock = PTHREAD_MUTEX_INITIALIZER,
	.answered_cv = PTHREAD_COND_INITIALIZER,
	.panel_tab = -1,
};

/*
 * The character panel: a tab switch or close answered to any prompt inside it.
 * Until the panel loop takes it, every ask() returns -1 at once, so whatever
 * screens are open unwind back to the panel loop (game thread only).
 */
enum { PANEL_STAY = -1 };
static int panel_next = PANEL_STAY;

void native_prompt_wake(void)
{
	pthread_mutex_lock(&shared.lock);
	pthread_cond_broadcast(&shared.answered_cv);
	pthread_mutex_unlock(&shared.lock);
}

bool native_panel_leaving(void)
{
	return panel_next != PANEL_STAY;
}

/*
 * The textui's own hooks, captured when ours are installed.  While a keymap is
 * expanding (inkey_next), its remaining keys answer the prompt, exactly as the
 * textui would read them: e.g. the original keyset maps '6' to walk + '6'.
 */
static struct {
	bool (*get_item)(struct object **, const char *, const char *, cmd_code, item_tester, int);
	bool (*get_check)(const char *);
	bool (*get_aim_dir)(int *, int);
	bool (*get_rep_dir)(int *, bool);
	bool (*get_string)(const char *, char *, size_t);
	int (*get_quantity)(const char *, int);
} textui;

static bool keymap_pending(void)
{
	return inkey_next && inkey_next->code;
}

/* ------------------------------------------------------------------------
 * Ask and wait (game thread)
 * ------------------------------------------------------------------------ */

/* Publishes the prompt (text already built into shared.text) and blocks for the answer. */
static int32_t ask(enum nb_prompt_kind kind)
{
	assert(kind != NB_PROMPT_NONE);
	if (native_panel_leaving()) return -1;
	/* The UI decides what to draw under the prompt from fresh map/meta. */
	native_publish(false);

	pthread_mutex_lock(&shared.lock);
	shared.kind = kind;
	shared.answer = ANSWER_PENDING;
	shared.gen++;
	pthread_mutex_unlock(&shared.lock);

	bridge_set_idle(true);
	bridge_service_save();  /* one asked for before this prompt opened */
	pthread_mutex_lock(&shared.lock);
	while (shared.answer == ANSWER_PENDING) {
		pthread_cond_wait(&shared.answered_cv, &shared.lock);
		if (shared.answer != ANSWER_PENDING) break;
		/* Woken by nb_request_save() (native_prompt_wake). */
		pthread_mutex_unlock(&shared.lock);
		bridge_service_save();
		pthread_mutex_lock(&shared.lock);
	}
	const int32_t answer = shared.answer;
	shared.kind = NB_PROMPT_NONE;
	shared.gen++;
	const bool in_panel = shared.panel_tab >= 0;
	pthread_mutex_unlock(&shared.lock);
	if (in_panel && answer >= NB_PANEL_TAB && answer <= NB_PANEL_CLOSE) {
		panel_next = answer - NB_PANEL_TAB;
		return -1;
	}
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
 * Ignoring: textui_cmd_ignore_menu() (ui-object.c) as a native choice
 * ------------------------------------------------------------------------ */

enum ignore_choice { IGN_ITEM, UNIGN_ITEM, IGN_FLAVOR, UNIGN_FLAVOR, IGN_EGO, UNIGN_EGO, IGN_QUALITY };

static void native_ignore_menu(struct object *obj)
{
	if (!obj) return;
	enum ignore_choice code[8];
	int n = 0;
	pthread_mutex_lock(&shared.lock);
	text_reset();
	text_add("Ignore\n");
	const bool item_ignored = (obj->known->notice & OBJ_NOTICE_IGNORE) != 0;
	text_add("%s\t\n", item_ignored ? "Unignore this item" : "This item only");
	code[n++] = item_ignored ? UNIGN_ITEM : IGN_ITEM;
	if (ignore_tval(obj->tval) && (!obj->artifact || !object_flavor_is_aware(obj))) {
		const bool ignored = kind_is_ignored_aware(obj->kind) || kind_is_ignored_unaware(obj->kind);
		char kinds[70];
		object_desc(kinds, sizeof(kinds), obj, ODESC_NOEGO | ODESC_BASE | ODESC_PLURAL, player);
		sanitise(kinds);
		text_add("%s %s\t\n", ignored ? "Unignore all" : "All", kinds);
		code[n++] = ignored ? UNIGN_FLAVOR : IGN_FLAVOR;
	}
	const int type = ignore_type_of(obj);
	if (obj->known->ego && type != ITYPE_MAX) {
		struct ego_desc choice = { .e_idx = obj->ego->eidx, .itype = type, .short_name = "" };
		char name[80] = "";
		(void)ego_item_name(name, sizeof(name), &choice);
		sanitise(name);
		const bool ignored = ego_is_ignored(choice.e_idx, choice.itype);
		text_add("%s %s\t\n", ignored ? "Unignore all" : "All", strlen(name) > 4 ? name + 4 : name);
		code[n++] = ignored ? UNIGN_EGO : IGN_EGO;
	}
	uint8_t value = ignore_level_of(obj);
	if (tval_is_jewelry(obj) && value != IGNORE_BAD) value = IGNORE_MAX;
	if (value != IGNORE_MAX && type != ITYPE_MAX) {
		text_add("All %s %s\tThe quality and worse, for this kind of item.\n", quality_values[value].name, ignore_name_for_type(type));
		code[n++] = IGN_QUALITY;
	}
	pthread_mutex_unlock(&shared.lock);

	const int32_t pick = ask(NB_PROMPT_CHOICE);
	if (pick < 0 || pick >= n) return;
	switch (code[pick]) {
		case IGN_ITEM: obj->known->notice |= OBJ_NOTICE_IGNORE; break;
		case UNIGN_ITEM: obj->known->notice &= ~OBJ_NOTICE_IGNORE; break;
		case IGN_FLAVOR: object_ignore_flavor_of(obj); break;
		case UNIGN_FLAVOR: kind_ignore_clear(obj->kind); break;
		case IGN_EGO: ego_ignore(obj); break;
		case UNIGN_EGO: ego_ignore_clear(obj); break;
		case IGN_QUALITY: ignore_level[ignore_type_of(obj)] = ignore_level_of(obj); break;
	}
	player->upkeep->notice |= PN_IGNORE;
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

/* ------------------------------------------------------------------------
 * The pack: every item in one list; the UI expands one at a time to show its
 * description and actions.  After an action the pack reopens once the command
 * has run (see native_pack_resume()), except for aimed ones.
 * ------------------------------------------------------------------------ */

enum { PACK_COLLAPSE = NB_PACK_COLLAPSE, PACK_EXPAND = NB_PACK_EXPAND, PACK_ACT = NB_PACK_ACT, PACK_ACT_STRIDE = NB_PACK_ACT_STRIDE };

static struct { bool reopen; int focus; } pack_after;
static bool pack_keep_place;  /* reopened after an action, or reached by a tab switch: the UI keeps its scroll */

/*
 * Serialised: "Pack" \t expanded item (-1 none) \t section to show first \t
 * weight carried \t limit (tenths of lb) \t 1 to keep the list's place (see pack_keep_place); per item "#I \t section \t label \t
 * glyph \t attr \t weight \t name"; then for the expanded item "#A \t valid
 * \t label" per action and "#D \t line" per description line.
 */
static void publish_pack(const candidate *c, int n, int expanded, int focus)
{
	action a[MAX_ACTIONS];
	const int na = expanded >= 0 ? item_actions(c[expanded].obj, a) : 0;
	textblock *const tb = expanded >= 0 ? object_info(c[expanded].obj, OINFO_NONE) : NULL;

	pthread_mutex_lock(&shared.lock);
	text_reset();
	text_add("Pack\t%d\t%d\t%d\t%d\t%d\n", expanded, focus, player->upkeep->total_weight, weight_limit(player->state), pack_keep_place);
	for (int i = 0; i < n; i++) {
		const struct object *const obj = c[i].obj;
		char name[160];
		describe(name, sizeof(name), obj);
		const char label = c[i].section == NB_ITEM_FLOOR ? 0 : gear_to_label(player, (struct object *)obj);
		text_add("#I\t%d\t%c\t%d\t%d\t%d\t%s\n", (int)c[i].section, label ? label : '-',
			(int)object_char(obj), (int)object_attr(obj), obj->weight * obj->number, name);
	}
	for (int i = 0; i < na; i++) text_add("#A\t%d\t%s\n", a[i].valid ? 1 : 0, a[i].label);
	if (tb) {
		text_add("#D\t");
		for (const wchar_t *p = textblock_text(tb); p && *p; p++) {
			if (*p == L'\n') text_add("\n#D\t");
			else text_add_utf8(*p == L'\t' ? ' ' : (uint32_t)*p);
		}
		text_add("\n");
	}
	pthread_mutex_unlock(&shared.lock);
	if (tb) textblock_free(tb);
}

/* The chosen action (context_menu_object() as native buttons); true if a command was queued. */
static bool pack_action(struct object *obj, int k, int focus)
{
	action a[MAX_ACTIONS];
	const int n = item_actions(obj, a);
	if (k < 0 || k >= n || !a[k].valid) return false;
	const cmd_code cmd = a[k].cmd;
	const unsigned char key = cmd_lookup_key(cmd, keymode());
	if (!key_confirm_command(key) || !get_item_allow(obj, key, cmd, false)) return false;
	if (cmd == CMD_IGNORE) { native_ignore_menu(obj); return false; }
	cmdq_push(cmd);
	cmd_set_arg_item(cmdq_peek(), "item", obj);
	pack_after.reopen = cmd != CMD_FIRE && cmd != CMD_THROW;
	pack_after.focus = focus;
	return true;
}

/* True if a command was queued (and should now run). */
bool native_pack(int focus)
{
	event_signal(EVENT_MESSAGE_FLUSH);
	pack_after.reopen = false;
	int expanded = -1;
	for (;;) {
		candidate c[MAX_ITEMS];
		const int n = collect(c, CMD_NULL, NULL, USE_INVEN | USE_EQUIP | USE_FLOOR);
		if (expanded >= n) expanded = -1;
		publish_pack(c, n, expanded, focus);
		const int32_t a = ask(NB_PROMPT_PACK);
		if (a < 0) return false;
		if (a == PACK_COLLAPSE) { expanded = -1; continue; }
		if (a >= PACK_EXPAND && a < PACK_EXPAND + n) {
			expanded = a - PACK_EXPAND;
			track_object(player->upkeep, c[expanded].obj);
			continue;
		}
		const int i = (a - PACK_ACT) / PACK_ACT_STRIDE;
		if (a >= PACK_ACT && i < n && pack_action(c[i].obj, (a - PACK_ACT) % PACK_ACT_STRIDE, focus)) return true;
		handle_stuff(player);
	}
}

bool native_pack_resume(void)
{
	if (!pack_after.reopen) return false;
	pack_after.reopen = false;
	if (player->is_dead || !player->upkeep->playing || player->upkeep->generate_level || screen_save_depth > 0) return false;
	pack_keep_place = true;
	const bool queued = native_panel(NB_TAB_PACK, pack_after.focus);
	pack_keep_place = false;
	return queued;
}

static bool native_get_item(struct object **choice, const char *pmt, const char *str,
		cmd_code cmd, item_tester tester, int mode)
{
	if (keymap_pending()) return textui.get_item(choice, pmt, str, cmd, tester, mode);
	candidate c[MAX_ITEMS];

	event_signal(EVENT_MESSAGE_FLUSH);
	*choice = NULL;
	/* Browsing the pack or the gear ('i', 'e'): the panel's Pack tab.  At death, a plain pick (the examine screen). */
	if (cmd == CMD_NULL && !player->is_dead && (mode & (USE_INVEN | USE_EQUIP))) {
		(void)native_panel(NB_TAB_PACK, (mode & USE_INVEN) ? NB_ITEM_INVENTORY : NB_ITEM_EQUIPMENT);
		return false;
	}
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
	if (keymap_pending()) return textui.get_check(prompt);
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
	if (keymap_pending()) return textui.get_aim_dir(dp, range);
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
 * Adjacent direction (open, close, disarm, tunnel, bash, exchange, walk...)
 * ------------------------------------------------------------------------ */

static const char *neighbour_kind(struct loc n)
{
	if (!square_in_bounds(cave, n) || !square_isknown(cave, n)) return NULL;
	if (square_monster(cave, n) && monster_is_visible(square_monster(cave, n))) return "monster";
	if (square_iscloseddoor(cave, n)) return "door";
	if (square_isopendoor(cave, n)) return "open door";
	if (square_isrubble(cave, n)) return "rubble";
	if (square_isdisarmabletrap(cave, n) && square_isvisibletrap(cave, n)) return "trap";
	return NULL;
}

static int rep_dir_intent = -1;

void native_prompt_set_intent(int nb_action)
{
	rep_dir_intent = nb_action;
}

/* Does neighbour n suit the action that opened this prompt? */
static bool fits_intent(int intent, struct loc n)
{
	if (!square_in_bounds(cave, n) || !square_isknown(cave, n)) return false;
	switch (intent) {
		case NB_ACT_CLOSE: return square_isopendoor(cave, n);
		case NB_ACT_OPEN:
		case NB_ACT_BASH: return square_iscloseddoor(cave, n);
		case NB_ACT_TUNNEL: return square_isrubble(cave, n);
		case NB_ACT_DISARM: return square_isdisarmabletrap(cave, n) && square_isvisibletrap(cave, n);
		default: return false;
	}
}

static int sole_fitting_dir(int intent)
{
	int found = 0;
	for (int dir = 1; dir <= 9; dir++) {
		if (dir == 5 || !fits_intent(intent, loc_sum(player->grid, ddgrid[dir]))) continue;
		if (found) return 0;
		found = dir;
	}
	return found;
}

static bool native_get_rep_dir(int *dp, bool allow_5)
{
	if (keymap_pending()) return textui.get_rep_dir(dp, allow_5);
	*dp = 0;
	const int intent = rep_dir_intent;
	rep_dir_intent = -1;
	const int sole = sole_fitting_dir(intent);
	if (sole) { *dp = sole; return true; }

	event_signal(EVENT_MESSAGE_FLUSH);
	pthread_mutex_lock(&shared.lock);
	text_reset();
	text_add("Direction\n%d\n", allow_5 ? 1 : 0);
	for (int dir = 1; dir <= 9; dir++) {
		if (dir == 5) continue;
		const struct loc n = loc_sum(player->grid, ddgrid[dir]);
		const char *const what = neighbour_kind(n);
		if (what) text_add("%d\t%d\t%d\t%s\n", dir, n.x, n.y, what);
	}
	pthread_mutex_unlock(&shared.lock);

	const int32_t a = ask(NB_PROMPT_DIRECTION);
	if (a < 1 || a > 9 || (a == 5 && !allow_5)) return false;
	*dp = a;
	return true;
}

/* ------------------------------------------------------------------------
 * Songs (change_song_hook): the textui's song menu as a native choice
 * ------------------------------------------------------------------------ */

typedef struct { struct song *song; bool swap; } song_option;

/* Mirrors get_songs() in ui-songs.c. */
static int song_options(song_option *o, int max)
{
	int n = 0;
	for (struct ability *a = abilities; a && n < max; a = a->next)
		if (a->skill == SKILL_SONG && strstr(a->name, "Song of") && player_active_ability(player, a->name))
			o[n++] = (song_option){ lookup_song(a->name + strlen("Song of ")), false };
	if (player->song[SONG_MAIN] && n < max) o[n++] = (song_option){ NULL, false };
	if (player->song[SONG_MINOR] && n < max) o[n++] = (song_option){ NULL, true };
	return n;
}

/* Serialised: title; then per option: label \t description. */
static void native_change_song(void)
{
	song_option o[32];
	const int n = song_options(o, (int)N_ELEMENTS(o));
	if (n == 0) {
		msg("You do not know any songs of power.");
		return;
	}
	pthread_mutex_lock(&shared.lock);
	text_reset();
	text_add("Songs\n");
	for (int i = 0; i < n; i++) {
		char desc[400] = "";
		if (o[i].song) {
			my_strcpy(desc, o[i].song->desc ? o[i].song->desc : "", sizeof(desc));
			sanitise(desc);
			const bool current = player->song[SONG_MAIN] == o[i].song;
			text_add("Song of %s%s\t%s\n", o[i].song->name, current ? " (singing)" : "", desc);
		} else {
			text_add("%s\t\n", o[i].swap ? "Exchange themes" : "Stop singing");
		}
	}
	pthread_mutex_unlock(&shared.lock);

	const int32_t pick = ask(NB_PROMPT_CHOICE);
	if (pick < 0 || pick >= n) return;
	player_change_song(player, o[pick].song, o[pick].swap);
}

/* ------------------------------------------------------------------------
 * Spending experience on skills: gain_skills(CTX_GAME, true) with a native
 * prompt in place of the textui's skill screen.  The arithmetic, validation
 * and commands are the game's own (CMD_BUY/SELL/RESET/REFRESH_SKILLS).
 * ------------------------------------------------------------------------ */

static const char *skill_names[] = {
	#define SKILL(a, b) b,
	#include "list-skills.h"
	#undef SKILL
};

static struct { int spent[SKILL_MAX], inc[SKILL_MAX], left; } skill_points;

static void on_skill_points(game_event_type type, game_event_data *data, void *user)
{
	(void)type; (void)user;
	memcpy(skill_points.spent, data->exp.exp, sizeof(skill_points.spent));
	memcpy(skill_points.inc, data->exp.inc_exp, sizeof(skill_points.inc));
	skill_points.left = data->exp.remaining;
}

/*
 * Serialised: "Skills" \t experience left; then per skill:
 * name \t base \t total (with modifiers) \t experience spent this session \t cost of the next point.
 */
static void publish_skills(void)
{
	pthread_mutex_lock(&shared.lock);
	text_reset();
	text_add("Skills\t%d\n", skill_points.left);
	for (int i = 0; i < SKILL_MAX; i++)
		text_add("%s\t%d\t%d\t%d\t%d\n", skill_names[i], player->skill_base[i],
			player->state.skill_use[i], skill_points.spent[i], skill_points.inc[i]);
	pthread_mutex_unlock(&shared.lock);
}

/* gain_skills(): 1 accepted, -1 cancelled (this session's changes undone). */
int native_skill_points(int context, bool reset)
{
	event_add_handler(EVENT_SKILLPOINTS, on_skill_points, NULL);
	init_skills(false, reset);
	int next = 0;
	while (!next) {
		publish_skills();
		const int32_t a = ask(NB_PROMPT_SKILLS);
		if (a >= NB_SKILL_BUY && a < NB_SKILL_BUY + SKILL_MAX) {
			cmdq_push(CMD_BUY_SKILL);
			cmd_set_arg_choice(cmdq_peek(), "choice", a - NB_SKILL_BUY);
		} else if (a >= NB_SKILL_SELL && a < NB_SKILL_SELL + SKILL_MAX) {
			cmdq_push(CMD_SELL_SKILL);
			cmd_set_arg_choice(cmdq_peek(), "choice", a - NB_SKILL_SELL);
		} else if (a == NB_SKILL_ACCEPT) {
			next = 1;
		} else if (native_panel_leaving()) {
			/* Switching panel tabs keeps what was bought. */
			next = 1;
		} else {
			/* Reset, or cancel (which undoes this session's changes). */
			cmdq_push(CMD_RESET_SKILLS);
			cmd_set_arg_choice(cmdq_peek(), "choice", false);
			if (a != NB_SKILL_RESET) next = -1;
		}
		cmdq_push(CMD_REFRESH_SKILLS);
		cmdq_execute(context);
	}
	event_remove_handler(EVENT_SKILLPOINTS, on_skill_points, NULL);
	if (context == CTX_GAME) {
		finalise_skills();
		player->upkeep->redraw |= PR_EXP;
	}
	return next;
}

void native_gain_skills(void)
{
	(void)native_skill_points(CTX_GAME, true);
}

/* ------------------------------------------------------------------------
 * Abilities: do_cmd_abilities() (ui-abilities.c) as one native screen.  Learning
 * and switching use the same calls as the textui (player_gain_ability() asks
 * its confirmation through the native check prompt).
 * ------------------------------------------------------------------------ */

static const char *bane_names[] = {
	#define BANE(a, b) b,
	#include "list-bane-types.h"
	#undef BANE
};

static enum nb_ability_state ability_state(struct ability *a)
{
	const struct ability *innate = locate_ability(player->abilities, a);
	const struct ability *item = locate_ability(player->item_abilities, a);
	if (innate) return innate->active ? NB_ABILITY_ON : NB_ABILITY_OFF;
	if (item) return item->active ? NB_ABILITY_ITEM_ON : NB_ABILITY_ITEM_OFF;
	return player_has_prereq_abilities(player, a) && player->skill_base[a->skill] >= a->level
		? NB_ABILITY_AVAILABLE : NB_ABILITY_LOCKED;
}

static bool is_bane(const struct ability *a)
{
	return a->skill == SKILL_PERCEPTION && streq(a->name, "Bane");
}

/* The global ability list in skill order (as the textui menus list them). */
static int abilities_by_skill(struct ability **out, int max)
{
	int n = 0;
	for (int s = 0; s < SKILL_MAX; s++)
		for (struct ability *a = abilities; a && n < max; a = a->next)
			if (a->skill == s) out[n++] = a;
	return n;
}

/*
 * Serialised: "Abilities" \t experience to spend; per skill, \t-separated,
 * name/base points/total with modifiers (abilities need base points);
 * then per ability: skill \t state \t skill points needed \t cost (-1 when
 * prerequisites are missing) \t prerequisites (" or "-joined) \t name \t description.
 */
static void publish_abilities(struct ability **list, int n)
{
	pthread_mutex_lock(&shared.lock);
	text_reset();
	text_add("Abilities\t%d\n", player->new_exp);
	for (int s = 0; s < SKILL_MAX; s++)
		text_add("%s%s/%d/%d", s ? "\t" : "", skill_names[s], player->skill_base[s], player->state.skill_use[s]);
	text_add("\n");
	for (int i = 0; i < n; i++) {
		struct ability *a = list[i];
		const enum nb_ability_state st = ability_state(a);
		char prereqs[300] = "";
		for (const struct ability *p = a->prerequisites; p; p = p->next)
			my_strcat(prereqs, format("%s%s", prereqs[0] ? " or " : "", p->name), sizeof(prereqs));
		char name[120], desc[2000];
		my_strcpy(name, a->name, sizeof(name));
		my_strcpy(desc, a->desc ? a->desc : "", sizeof(desc));
		if (is_bane(a) && player->bane_type > 0 && (st == NB_ABILITY_ON || st == NB_ABILITY_OFF)) {
			strnfmt(name, sizeof(name), "%s-Bane", bane_names[player->bane_type]);
			my_strcat(desc, format("  %s slain: %d, giving a %+d bonus.", bane_names[player->bane_type],
				player_bane_type_killed(player->bane_type), calc_bane_bonus(player)), sizeof(desc));
		}
		sanitise(prereqs); sanitise(name); sanitise(desc);
		const int cost = player_has_prereq_abilities(player, a) ? player_ability_cost(player, a) : -1;
		text_add("%d\t%d\t%d\t%d\t%s\t%s\t%s\n", a->skill, (int)st, a->level, cost, prereqs, name, desc);
	}
	pthread_mutex_unlock(&shared.lock);
}

/* bane_menu(): only enemy types with four kills can be chosen.  False if none was. */
static bool choose_bane(void)
{
	int type[N_ELEMENTS(bane_names)], n = 0;
	pthread_mutex_lock(&shared.lock);
	text_reset();
	text_add("Choose the enemy for your Bane\n");
	for (int t = 1; t < (int)N_ELEMENTS(bane_names); t++) {
		const int kills = player_bane_type_killed(t);
		if (kills < 4) continue;
		type[n++] = t;
		text_add("%s\tYou have slain %d of these foes.\n", bane_names[t], kills);
	}
	pthread_mutex_unlock(&shared.lock);
	if (n == 0) {
		msg("You must slay at least four of a kind of foe before you can choose it as your Bane.");
		return false;
	}
	const int32_t pick = ask(NB_PROMPT_CHOICE);
	if (pick < 0 || pick >= n) return false;
	player->bane_type = type[pick];
	return true;
}

/* ability_action(): switch a held ability, or learn an available one. */
static void use_ability(struct ability *a)
{
	struct ability *held = locate_ability(player->abilities, a);
	if (!held) held = locate_ability(player->item_abilities, a);
	if (held) {
		held->active = !held->active;
		player->upkeep->update |= PU_BONUS;
		return;
	}
	if (ability_state(a) != NB_ABILITY_AVAILABLE) {
		msg("Insufficient prerequisites for ability!");
		return;
	}
	if (!player_can_gain_ability(player, a)) {
		msg("You do not have enough experience to acquire this ability.");
		return;
	}
	if (is_bane(a) && !choose_bane()) return;
	if (!player_gain_ability(player, a) && is_bane(a)) player->bane_type = 0;
}

void native_abilities(void)
{
	struct ability *list[400];
	const int n = abilities_by_skill(list, (int)N_ELEMENTS(list));
	for (;;) {
		handle_stuff(player);
		publish_abilities(list, n);
		const int32_t pick = ask(NB_PROMPT_ABILITIES);
		if (pick < 0 || pick >= n) break;
		use_ability(list[pick]);
	}
	handle_stuff(player);
}

/* ------------------------------------------------------------------------
 * The character panel: tabs over the pack, character, skills, abilities,
 * knowledge, smithing and options.  Each tab is its screen's own loop; a tab
 * answer unwinds it (see ask()) and the loop here opens the next.
 * ------------------------------------------------------------------------ */

static int last_tab = NB_TAB_PACK;

static void set_panel_tab(int tab)
{
	pthread_mutex_lock(&shared.lock);
	shared.panel_tab = tab;
	pthread_mutex_unlock(&shared.lock);
}

/* The 'c' of do_cmd_change_name(), as a native text prompt (with Random). */
static void rename_character(void)
{
	if (arg_force_name) { msg("You are not allowed to change your name!"); return; }
	char name[PLAYER_NAME_LEN];
	my_strcpy(name, player->full_name, sizeof(name));
	for (;;) {
		pthread_mutex_lock(&shared.lock);
		text_reset();
		text_add("Your name\n%s\n%d\n1\n", name, (int)sizeof(name) - 1);
		pthread_mutex_unlock(&shared.lock);
		const int32_t a = ask(NB_PROMPT_TEXT);
		if (a == NB_TEXT_RANDOM) { player_random_name(name, sizeof(name)); continue; }
		if (a != 1) return;
		native_answer_text(name, sizeof(name));
		if (name[0]) my_strcpy(player->full_name, name, sizeof(player->full_name));
		return;
	}
}

/* The 's' of do_cmd_change_name(), to a fixed file the UI can share. */
static void share_character_dump(void)
{
	char path[1024];
	path_build(path, sizeof(path), ANGBAND_DIR_USER, "character-dump.txt");
	/* No flush: a -more- now would sit under the panel. */
	msg(dump_save(path) ? "Character dump written." : "Character dump failed!");
}

static void character_tab(void)
{
	for (;;) {
		native_prompt_begin();
		native_prompt_add("%s", native_character_text());
		native_prompt_end();
		const int32_t a = ask(NB_PROMPT_CHARACTER);
		if (a == NB_CHARACTER_SCREEN) do_cmd_change_name();
		else if (a == NB_CHARACTER_RENAME) rename_character();
		else if (a == NB_CHARACTER_DUMP) share_character_dump();
		else return;
	}
}

/* Accepting keeps the tab open with a fresh session; Cancel (undo) closes the panel. */
static void skills_tab(void)
{
	while (native_skill_points(CTX_GAME, true) == 1 && !native_panel_leaving()) {}
}

/* Runs tabs until one closes the panel; true if a command was queued (e.g. a pack action). */
bool native_panel(int tab, int focus)
{
	assert(tab >= -1 && tab < NB_TAB_COUNT);
	if (tab < 0) tab = last_tab;
	/* Any -more- first, while the message strip is still in view. */
	event_signal(EVENT_MESSAGE_FLUSH);
	bool queued = false;
	for (;;) {
		last_tab = tab;
		panel_next = PANEL_STAY;
		set_panel_tab(tab);
		switch ((enum nb_panel_tab)tab) {
			case NB_TAB_PACK: queued = native_pack(focus); break;
			case NB_TAB_CHARACTER: character_tab(); break;
			case NB_TAB_SKILLS: skills_tab(); break;
			case NB_TAB_ABILITIES: native_abilities(); break;
			case NB_TAB_KNOWLEDGE: native_knowledge(); break;
			case NB_TAB_SMITH: queued = native_smith_tab(); break;
			case NB_TAB_OPTIONS: native_options(); break;
			case NB_TAB_COUNT: break;
		}
		if (queued || panel_next == PANEL_STAY || panel_next >= NB_TAB_COUNT) break;
		tab = panel_next;
		focus = NB_ITEM_INVENTORY;
		pack_keep_place = true;
	}
	pack_keep_place = false;
	panel_next = PANEL_STAY;
	set_panel_tab(-1);
	handle_stuff(player);
	return queued;
}

int nb_panel_tab(void)
{
	pthread_mutex_lock(&shared.lock);
	const int tab = shared.panel_tab;
	pthread_mutex_unlock(&shared.lock);
	return tab;
}

/* ------------------------------------------------------------------------
 * Text and quantities: get_string() and get_quantity()
 * ------------------------------------------------------------------------ */

/* Prompts read "Inscription: "; the native title drops the trailing colon. */
static void prompt_title(char *dst, size_t cap, const char *prompt, const char *fallback)
{
	my_strcpy(dst, prompt && prompt[0] ? prompt : fallback, cap);
	size_t n = strlen(dst);
	while (n > 0 && (dst[n - 1] == ' ' || dst[n - 1] == ':' || dst[n - 1] == '?')) dst[--n] = '\0';
	sanitise(dst);
}

/* NB_PROMPT_TEXT: title; default text; maximum length in bytes; no random. */
static bool native_get_string(const char *prompt, char *buf, size_t len)
{
	if (keymap_pending()) return textui.get_string(prompt, buf, len);
	assert(len > 0);
	event_signal(EVENT_MESSAGE_FLUSH);
	char title[160], initial[256];
	prompt_title(title, sizeof(title), prompt, "Enter text");
	my_strcpy(initial, buf, sizeof(initial));
	sanitise(initial);
	pthread_mutex_lock(&shared.lock);
	text_reset();
	text_add("%s\n%s\n%d\n0\n", title, initial, (int)MIN(len - 1, sizeof(shared.answer_text) - 1));
	pthread_mutex_unlock(&shared.lock);
	if (ask(NB_PROMPT_TEXT) != 1) return false;
	native_answer_text(buf, len);
	return true;
}

/* NB_PROMPT_QUANTITY: "how many?" with a default of 1, as textui_get_quantity(). */
static int native_get_quantity(const char *prompt, int max)
{
	if (keymap_pending()) return textui.get_quantity(prompt, max);
	if (max <= 1) return MAX(max, 0);
	event_signal(EVENT_MESSAGE_FLUSH);
	char title[160];
	prompt_title(title, sizeof(title), prompt, "How many");
	pthread_mutex_lock(&shared.lock);
	text_reset();
	text_add("%s\n%d\n%d\n", title, max, 1);
	pthread_mutex_unlock(&shared.lock);
	const int32_t a = ask(NB_PROMPT_QUANTITY);
	return a < 0 ? 0 : MIN(a, max);
}

/* ------------------------------------------------------------------------
 * Install / public API
 * ------------------------------------------------------------------------ */

void native_prompt_init(void)
{
	/* Called often (textui_init() resets the hooks); capture the textui's only once they are its. */
	if (get_item_hook != native_get_item) textui.get_item = get_item_hook;
	if (get_check_hook != native_get_check) textui.get_check = get_check_hook;
	if (get_aim_dir_hook != native_get_aim_dir) textui.get_aim_dir = get_aim_dir_hook;
	if (get_rep_dir_hook != native_get_rep_dir) textui.get_rep_dir = get_rep_dir_hook;
	if (get_string_hook != native_get_string) textui.get_string = get_string_hook;
	if (get_quantity_hook != native_get_quantity) textui.get_quantity = get_quantity_hook;
	change_song_hook = native_change_song;
	smith_object_hook = native_smith_object;
	assert(textui.get_item && textui.get_check && textui.get_aim_dir && textui.get_rep_dir && textui.get_string
		&& textui.get_quantity);
	get_item_hook = native_get_item;
	get_check_hook = native_get_check;
	get_aim_dir_hook = native_get_aim_dir;
	get_rep_dir_hook = native_get_rep_dir;
	get_string_hook = native_get_string;
	get_quantity_hook = native_get_quantity;
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

void nb_answer_text(const char *text)
{
	if (!prompt_open()) return;
	bridge_set_idle(false);
	pthread_mutex_lock(&shared.lock);
	if (shared.kind == NB_PROMPT_TEXT && shared.answer == ANSWER_PENDING) {
		my_strcpy(shared.answer_text, text, sizeof(shared.answer_text));
		shared.answer = 1;
		pthread_cond_signal(&shared.answered_cv);
	}
	pthread_mutex_unlock(&shared.lock);
}

void native_answer_text(char *buf, size_t cap)
{
	pthread_mutex_lock(&shared.lock);
	my_strcpy(buf, shared.answer_text, cap);
	pthread_mutex_unlock(&shared.lock);
}

void native_prompt_begin(void) { pthread_mutex_lock(&shared.lock); text_reset(); }
void native_prompt_end(void) { pthread_mutex_unlock(&shared.lock); }
int32_t native_ask(enum nb_prompt_kind kind) { return ask(kind); }
void native_sanitise(char *s) { sanitise(s); }

void native_prompt_add(const char *fmt, ...)
{
	if (text_len >= sizeof(shared.text) - 1) return;
	va_list vp;
	va_start(vp, fmt);
	text_len += vstrnfmt(shared.text + text_len, sizeof(shared.text) - text_len, fmt, vp);
	va_end(vp);
}

void nb_answer_item(int index)
{
	nb_answer(index < 0 ? -1 : index);
}
