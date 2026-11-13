/**
 * \file narsil_options.c
 * \brief Native game options: do_cmd_options() (ui-options.c) as NB_PROMPT_OPTIONS
 *        screens.  The option table, option_set() and the numeric settings are
 *        the game's; only the presentation differs.  The rarely used parts
 *        (item ignoring, keymaps, colours, pref files) stay textui menus.
 *
 * Serialised (one record per line):
 *   Options \t page \t title        page: main birth cheat
 *   #H \t heading
 *   #O \t answer \t on \t locked \t description \t name        a yes/no option
 *   #N \t answer base \t label \t value \t min \t max \t detail   answer = base + new value
 *   #L \t answer \t label \t detail                          another page or a textui menu
 * -1 goes back (or closes the main page).
 */
#include "narsil_internal.h"

#include "angband.h"
#include "option.h"
#include "player.h"
#include "player-calcs.h"
#include "ui-knowledge.h"
#include "ui-options.h"
#include "ui-output.h"

#include <assert.h>

enum {
	ANSWER_TOGGLE = 1000,            /* + option index */
	ANSWER_NUMBER = 10000,           /* + 1000 * setting + value */
	ANSWER_LINK = 100,               /* + link */
};

enum number_setting { NUM_HP_WARN, NUM_DELAY, NUM_LAZYMOVE, NUM_COUNT };
enum link { LINK_BIRTH, LINK_CHEAT, LINK_IGNORE, LINK_AUTOINSCRIBE, LINK_ADVANCED, LINK_COUNT };

static uint8_t *number_slot(enum number_setting n)
{
	switch (n) {
		case NUM_HP_WARN: return &player->opts.hitpoint_warn;
		case NUM_DELAY: return &player->opts.delay_factor;
		case NUM_LAZYMOVE: return &player->opts.lazymove_delay;
		default: assert(false); return NULL;
	}
}

static void add_toggles(int page, bool locked)
{
	for (int i = 0; option_page[page][i] != OPT_none; i++) {
		const int opt = option_page[page][i];
		native_prompt_add("#O\t%d\t%d\t%d\t%s\t%s\n", ANSWER_TOGGLE + opt, player->opts.opt[opt], locked,
			option_desc(opt), option_name(opt));
	}
}

static void add_number(enum number_setting n, const char *label, int max, const char *detail)
{
	assert(max < 1000);
	native_prompt_add("#N\t%d\t%s\t%d\t0\t%d\t%s\n", ANSWER_NUMBER + 1000 * (int)n, label, *number_slot(n), max, detail);
}

static void publish_main(void)
{
	native_prompt_begin();
	native_prompt_add("Options\tmain\tGame options\n");
	native_prompt_add("#H\tInterface\n");
	add_toggles(OP_INTERFACE, false);
	native_prompt_add("#H\tSettings\n");
	add_number(NUM_HP_WARN, "Hitpoint warning", 9, "Warn when health falls below this many tenths (0 = off).");
	add_number(NUM_DELAY, "Base delay", 255, "Milliseconds per step of missile and spell animations.");
	add_number(NUM_LAZYMOVE, "Movement delay", 255, "Centiseconds to wait for a second key for diagonal moves.");
	native_prompt_add("#H\tMore\n");
	native_prompt_add("#L\t%d\tBirth options\tSet when the character was created; shown for reference.\n", ANSWER_LINK + LINK_BIRTH);
	native_prompt_add("#L\t%d\tCheat options\tDebugging aids; using one marks the character as a cheater.\n", ANSWER_LINK + LINK_CHEAT);
	native_prompt_add("#L\t%d\tItem ignoring\tThe text menu.\n", ANSWER_LINK + LINK_IGNORE);
	native_prompt_add("#L\t%d\tAuto-inscriptions\tThe text menu.\n", ANSWER_LINK + LINK_AUTOINSCRIBE);
	native_prompt_add("#L\t%d\tAdvanced\tThe full text options menu: keymaps, colours, pref files.\n", ANSWER_LINK + LINK_ADVANCED);
	native_prompt_end();
}

static void publish_page(int page, const char *key, const char *title, bool locked)
{
	native_prompt_begin();
	native_prompt_add("Options\t%s\t%s\n", key, title);
	add_toggles(page, locked);
	native_prompt_end();
}

/* Applies a toggle or number answer; false if the answer is neither. */
static bool apply(int32_t a, bool birth_editable)
{
	if (a >= ANSWER_NUMBER && a < ANSWER_NUMBER + 1000 * NUM_COUNT) {
		const enum number_setting n = (enum number_setting)((a - ANSWER_NUMBER) / 1000);
		const int value = (a - ANSWER_NUMBER) % 1000;
		*number_slot(n) = (uint8_t)MIN(value, n == NUM_HP_WARN ? 9 : 255);
		player->upkeep->redraw |= PR_HP;
		return true;
	}
	if (a >= ANSWER_TOGGLE && a < ANSWER_TOGGLE + OPT_MAX) {
		const int opt = a - ANSWER_TOGGLE;
		if (option_type(opt) == OP_BIRTH && !birth_editable) return true;
		option_set(option_name(opt), !player->opts.opt[opt]);
		player->upkeep->redraw |= PR_MAP | PR_BASIC;
		return true;
	}
	return false;
}

/* One page of yes/no options until Back. */
static void toggle_page(int page, const char *key, const char *title, bool editable)
{
	for (;;) {
		publish_page(page, key, title, !editable);
		const int32_t a = native_ask(NB_PROMPT_OPTIONS);
		if (!apply(a, editable)) return;
	}
}

void native_options_birth(void)
{
	toggle_page(OP_BIRTH, "birth", "Birth options", true);
}

void native_options(void)
{
	for (;;) {
		publish_main();
		const int32_t a = native_ask(NB_PROMPT_OPTIONS);
		if (apply(a, false)) continue;
		switch (a - ANSWER_LINK) {
			case LINK_BIRTH: toggle_page(OP_BIRTH, "birth", "Birth options", false); break;
			case LINK_CHEAT: toggle_page(OP_CHEAT, "cheat", "Cheat options", true); break;
			case LINK_IGNORE: screen_save(); do_cmd_options_item("Item ignoring", 0); screen_load(); break;
			case LINK_AUTOINSCRIBE: screen_save(); textui_browse_object_knowledge("Auto-inscriptions", 0); screen_load(); break;
			case LINK_ADVANCED: do_cmd_options(); break;
			default: handle_stuff(player); return;
		}
	}
}
