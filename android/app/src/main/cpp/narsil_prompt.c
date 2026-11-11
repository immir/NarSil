/**
 * \file narsil_prompt.c
 * \brief Native prompts: the game thread publishes a typed question and blocks
 *        until the UI answers.  Currently get_item (item selection).
 *
 * Candidate lists mirror textui_get_item(): inventory and floor filtered by
 * the tester, equipment filtered unless browsing with no command or tester,
 * and throwables first for SHOW_THROWING.  The chosen object then goes
 * through get_item_allow() exactly as the textui does (!* inscriptions).
 */
#include "narsil_bridge.h"
#include "narsil_internal.h"

#include "angband.h"
#include "cmd-core.h"
#include "game-input.h"
#include "init.h"
#include "obj-desc.h"
#include "obj-gear.h"
#include "obj-pile.h"
#include "obj-util.h"
#include "player-calcs.h"
#include "ui-game.h"
#include "ui-keymap.h"
#include "ui-object.h"

#include <assert.h>
#include <pthread.h>
#include <string.h>

enum { MAX_ITEMS = 96, TEXT_CAP = 32 * 1024, ANSWER_PENDING = -2, ANSWER_CANCEL = -1 };

/* Shared; guarded by `lock`. */
static struct {
	pthread_mutex_t lock;
	pthread_cond_t answered_cv;
	enum nb_prompt_kind kind;
	uint64_t gen;
	char text[TEXT_CAP];
	int answer;
} shared = {
	.lock = PTHREAD_MUTEX_INITIALIZER,
	.answered_cv = PTHREAD_COND_INITIALIZER,
};

typedef struct {
	struct object *obj;
	enum nb_item_section section;
} candidate;

/* ------------------------------------------------------------------------
 * Candidate lists (game thread)
 * ------------------------------------------------------------------------ */

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

/*
 * Serialised for the UI, one record per line, fields tab-separated:
 *   line 0: prompt
 *   item:   section, label, glyph codepoint, attr, weight (1/10 lb), name
 */
static void serialise(char *out, size_t cap, const char *pmt, const candidate *c, int n)
{
	size_t len = (size_t)strnfmt(out, cap, "%s\n", pmt ? pmt : "Choose an item");
	for (int i = 0; i < n && len < cap; i++) {
		const struct object *const obj = c[i].obj;
		char name[160];
		object_desc(name, sizeof(name), obj, ODESC_PREFIX | ODESC_FULL, player);
		for (char *p = name; *p; p++) if (*p == '\t' || *p == '\n') *p = ' ';
		const char label = c[i].section == NB_ITEM_FLOOR ? '-' : gear_to_label(player, (struct object *)obj);
		len += (size_t)strnfmt(out + len, cap - len, "%d\t%c\t%d\t%d\t%d\t%s\n",
			(int)c[i].section, label ? label : '-', (int)object_char(obj),
			(int)object_attr(obj), obj->weight * obj->number, name);
	}
}

/* ------------------------------------------------------------------------
 * Ask and wait (game thread)
 * ------------------------------------------------------------------------ */

static int ask(enum nb_prompt_kind kind)
{
	pthread_mutex_lock(&shared.lock);
	shared.kind = kind;
	shared.answer = ANSWER_PENDING;
	shared.gen++;
	pthread_mutex_unlock(&shared.lock);

	bridge_set_idle(true);
	pthread_mutex_lock(&shared.lock);
	while (shared.answer == ANSWER_PENDING)
		pthread_cond_wait(&shared.answered_cv, &shared.lock);
	const int answer = shared.answer;
	shared.kind = NB_PROMPT_NONE;
	shared.gen++;
	pthread_mutex_unlock(&shared.lock);
	return answer;
}

static bool native_get_item(struct object **choice, const char *pmt, const char *str,
		cmd_code cmd, item_tester tester, int mode)
{
	candidate c[MAX_ITEMS];

	event_signal(EVENT_MESSAGE_FLUSH);
	const int n = collect(c, cmd, tester, mode);
	*choice = NULL;
	if (n == 0) {
		if (str) msg("%s", str);
		return false;
	}

	pthread_mutex_lock(&shared.lock);
	serialise(shared.text, sizeof(shared.text), pmt, c, n);
	pthread_mutex_unlock(&shared.lock);

	const int answer = ask(NB_PROMPT_ITEM);
	if (answer < 0 || answer >= n) return false;

	struct object *const obj = c[answer].obj;
	const bool harmless = (mode & IS_HARMLESS) != 0;
	const int keymode = OPT(player, angband_keyset) ? KEYMAP_MODE_ANGBAND : KEYMAP_MODE_ORIG;
	if (cmd != CMD_NULL && !get_item_allow(obj, cmd_lookup_key(cmd, keymode), cmd, harmless))
		return false;
	*choice = obj;
	return true;
}

void native_prompt_init(void)
{
	get_item_hook = native_get_item;
}

/* ------------------------------------------------------------------------
 * Public API (any thread)
 * ------------------------------------------------------------------------ */

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

static bool item_prompt_open(void)
{
	pthread_mutex_lock(&shared.lock);
	const bool open = shared.kind == NB_PROMPT_ITEM && shared.answer == ANSWER_PENDING;
	pthread_mutex_unlock(&shared.lock);
	return open;
}

void nb_answer_item(int index)
{
	if (!item_prompt_open()) return;
	/* Busy before the game thread can run, so it is the one that next reports idle. */
	bridge_set_idle(false);
	pthread_mutex_lock(&shared.lock);
	if (shared.kind == NB_PROMPT_ITEM && shared.answer == ANSWER_PENDING) {
		shared.answer = index < 0 ? ANSWER_CANCEL : index;
		pthread_cond_signal(&shared.answered_cv);
	}
	pthread_mutex_unlock(&shared.lock);
}
