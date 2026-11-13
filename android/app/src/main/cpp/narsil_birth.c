/**
 * \file narsil_birth.c
 * \brief Native character creation (birth_hook) and death screen (death_hook).
 *
 * native_do_birth() is textui_do_birth() (ui-birth.c) stage for stage: the
 * same commands in the same order (CMD_BIRTH_INIT/RESET, CMD_CHOOSE_*, the
 * stat and skill point commands, CMD_NAME_CHOICE, CMD_HISTORY_CHOICE,
 * CMD_ACCEPT_CHARACTER), with each question published as an NB_PROMPT_SCREEN.
 *
 * Serialised screen (one record per line):
 *   Screen \t stage \t title
 *   #H \t text                         paragraphs above the options
 *   #O \t state \t label \t summary \t description    options, answered by index
 *   #S \t stat \t value \t spent \t next cost \t can sell \t can buy     (stage "stats")
 *   #P \t points left \t points in all                                 (stage "stats")
 *   #K \t skill \t total; #C \t combat line \t value                    (stage "stats")
 * Answers: an option index; -1 back; NB_SCREEN_* below; stats use NB_SKILL_*.
 */
#include "narsil_internal.h"

#include "angband.h"
#include "cmd-core.h"
#include "combat.h"
#include "game-event.h"
#include "game-input.h"
#include "init.h"
#include "message.h"
#include "obj-desc.h"
#include "obj-info.h"
#include "player.h"
#include "player-birth.h"
#include "player-calcs.h"
#include "player-skills.h"
#include "project.h"
#include "buildid.h"
#include "tutorial.h"
#include "ui-birth.h"
#include "ui-game.h"
#include "ui-history.h"
#include "ui-options.h"
#include "ui-player.h"
#include "ui-prefs.h"
#include "ui-output.h"
#include "ui-score.h"
#include "z-textblock.h"

#include <assert.h>
#include <string.h>

enum birth_stage {
	BIRTH_BACK = -1, BIRTH_RESET = 0, BIRTH_QUICKSTART, BIRTH_RACE_CHOICE, BIRTH_HOUSE_CHOICE,
	BIRTH_SEX_CHOICE, BIRTH_STAT_POINTS, BIRTH_SKILL_POINTS, BIRTH_NAME_CHOICE, BIRTH_AHW_CHOICE,
	BIRTH_HISTORY_CHOICE, BIRTH_FINAL_CONFIRM, BIRTH_COMPLETE
};

static const char *const skill_names_b[] = {
	#define SKILL(a, b) b,
	#include "list-skills.h"
	#undef SKILL
};
static const char *const stat_full[STAT_MAX] = { "Strength", "Dexterity", "Constitution", "Grace" };

/* Defined (without a header) in ui-birth.c. */
extern const char *list_player_flag_names[];

static bool quickstart_allowed;

static void on_enter_birth(game_event_type type, game_event_data *data, void *user)
{
	(void)type; (void)user;
	quickstart_allowed = data->flag;
}

/* ------------------------------------------------------------------------
 * Building screens
 * ------------------------------------------------------------------------ */

static void begin(const char *stage, const char *title)
{
	native_prompt_begin();
	native_prompt_add("Screen\t%s\t%s\n", stage, title);
}

static void para(const char *fmt, ...)
{
	char buf[1024];
	va_list vp;
	va_start(vp, fmt);
	vstrnfmt(buf, sizeof(buf), fmt, vp);
	va_end(vp);
	native_sanitise(buf);
	native_prompt_add("#H\t%s\n", buf);
}

static void option(int state, const char *label, const char *summary, const char *desc)
{
	char l[100], s[400], d[2000];
	my_strcpy(l, label, sizeof(l));
	my_strcpy(s, summary ? summary : "", sizeof(s));
	my_strcpy(d, desc ? desc : "", sizeof(d));
	native_sanitise(l); native_sanitise(s); native_sanitise(d);
	native_prompt_add("#O\t%d\t%s\t%s\t%s\n", state, l, s, d);
}

static int32_t ask_screen(void)
{
	native_prompt_end();
	return native_ask(NB_PROMPT_SCREEN);
}

/* race_help()/house_help(): stat adjustments, skill affinities and flags. */
static void traits(char *buf, size_t cap, const int stat_adj[STAT_MAX], const int skill_adj[SKILL_MAX],
		const bitflag *pflags, bool house)
{
	buf[0] = '\0';
	for (int j = 0; j < STAT_MAX; j++)
		my_strcat(buf, format("%s%s %+d", j ? " · " : "", stat_full[j], stat_adj[j]), cap);
	for (int j = 0; j < SKILL_MAX; j++) {
		const int adj = skill_adj[j];
		if (adj > 1 && house) my_strcat(buf, format(" · %s mastery", skill_names_b[j]), cap);
		else if (adj > 0) my_strcat(buf, format(" · %s affinity", skill_names_b[j]), cap);
		else if (adj < 0) my_strcat(buf, format(" · %s penalty", skill_names_b[j]), cap);
	}
	for (int j = 0; j < PF_MAX; j++)
		if (pf_has(pflags, j)) my_strcat(buf, format(" · %s", list_player_flag_names[j]), cap);
}

/* The character so far, as display_player(0) would show it. */
static void summary(void)
{
	para("%s, %s of the house of %s (%s)", player->full_name, player->race->name, player->house->short_name,
		player->sex->name);
	char line[400] = "";
	for (int i = 0; i < STAT_MAX; i++)
		my_strcat(line, format("%s%s %d", i ? " · " : "", stat_full[i], player->state.stat_use[i]), sizeof(line));
	para("%s", line);
	line[0] = '\0';
	for (int s = 0; s < SKILL_MAX; s++)
		my_strcat(line, format("%s%s %d", s ? " · " : "", skill_names_b[s], player->state.skill_use[s]), sizeof(line));
	para("%s", line);
	para("Health %d · Voice %d · Age %d · %d'%d\" · %d lb", player->mhp, player->msp, player->age,
		player->ht / 12, player->ht % 12, player->wt);
	if (player->history && player->history[0]) para("%s", player->history);
}

/* ------------------------------------------------------------------------
 * Stages
 * ------------------------------------------------------------------------ */

static enum birth_stage quickstart_stage(void)
{
	for (;;) {
		begin("quickstart", "New character");
		para("A new character based on your previous one:");
		summary();
		option(1, "Use this character", NULL, NULL);
		option(arg_force_name ? 0 : 1, "Change name and history", NULL, NULL);
		option(1, "Create a new character", NULL, NULL);
		const int32_t a = ask_screen();
		if (a == NB_SCREEN_OPTIONS) { native_options_birth(); continue; }
		if (a == 0) { cmdq_push(CMD_ACCEPT_CHARACTER); return BIRTH_COMPLETE; }
		if (a == 1 && !arg_force_name) return BIRTH_NAME_CHOICE;
		if (a == 2) {
			cmdq_push(CMD_BIRTH_RESET);
			cmdq_push(CMD_RESET_STATS);
			return BIRTH_RACE_CHOICE;
		}
	}
}

/* menu_question() for race, house and sex. */
static enum birth_stage choice_stage(enum birth_stage current)
{
	const char *stage, *title, *hint;
	cmd_code command;
	int n = 0;
	if (current == BIRTH_RACE_CHOICE) {
		stage = "race"; title = "Race"; hint = "Race affects stats, skills, and other character traits.";
		command = CMD_CHOOSE_RACE;
		for (struct player_race *r = races; r; r = r->next) n++;
	} else if (current == BIRTH_HOUSE_CHOICE) {
		stage = "house"; title = "House"; hint = "House affects stats, skills, and other character traits.";
		command = CMD_CHOOSE_HOUSE;
		while (player_house_from_count(n)) n++;
	} else {
		stage = "sex"; title = "Sex"; hint = "Sex has no gameplay effect.";
		command = CMD_CHOOSE_SEX;
		for (struct player_sex *s = sexes; s; s = s->next) n++;
	}
	for (;;) {
		begin(stage, title);
		para("%s", hint);
		for (int i = 0; i < n; i++) {
			char t[400];
			if (current == BIRTH_RACE_CHOICE) {
				const struct player_race *r = player_id2race(i);
				traits(t, sizeof(t), r->stat_adj, r->skill_adj, r->pflags, false);
				option(1, r->name, t, r->desc);
			} else if (current == BIRTH_HOUSE_CHOICE) {
				const struct player_race *r = player->race;
				const struct player_house *h = player_house_from_count(i);
				int stats[STAT_MAX], skills[SKILL_MAX];
				for (int j = 0; j < STAT_MAX; j++) stats[j] = r->stat_adj[j] + h->stat_adj[j];
				for (int j = 0; j < SKILL_MAX; j++) skills[j] = r->skill_adj[j] + h->skill_adj[j];
				traits(t, sizeof(t), stats, skills, r->pflags, true);
				option(1, h->name, t, h->desc);
			} else {
				option(1, player_id2sex(i)->name, NULL, NULL);
			}
		}
		const int32_t a = ask_screen();
		if (a == NB_SCREEN_OPTIONS) { native_options_birth(); continue; }
		if (a < 0) return BIRTH_BACK;
		const int pick = a == NB_SCREEN_RANDOM ? randint0(n) : a;
		if (pick < 0 || pick >= n) continue;
		cmdq_push(command);
		cmd_set_arg_choice(cmdq_peek(), "choice", pick);
		return current + 1;
	}
}

static struct { int spent[STAT_MAX], inc[STAT_MAX], left; } stat_pts;

static void on_stat_points(game_event_type type, game_event_data *data, void *user)
{
	(void)type; (void)user;
	memcpy(stat_pts.spent, data->points.points, sizeof(stat_pts.spent));
	memcpy(stat_pts.inc, data->points.inc_points, sizeof(stat_pts.inc));
	stat_pts.left = data->points.remaining;
}

/* What the stats feed: each skill, then the combat lines (only those that apply yet). */
static void stat_figures(void)
{
	const struct player_state *const st = &player->state;
	for (int s = 0; s < SKILL_MAX; s++)
		native_prompt_add("#K\t%s\t%d\n", skill_names_b[s], st->skill_use[s]);
	/* No kit yet at birth: show dice and protection only once there is some. */
	const int pmin = protection_roll(player, PROJ_HURT, true, MINIMISE), pmax = protection_roll(player, PROJ_HURT, true, MAXIMISE);
	native_prompt_add("#C\tAttack\t%s\n", st->mds > 1 ? format("(%+d, %dd%d)", st->skill_use[SKILL_MELEE], st->mdd, st->mds)
		: format("%+d", st->skill_use[SKILL_MELEE]));
	if (st->ads > 1) native_prompt_add("#C\tBows\t(%+d, %dd%d)\n", st->skill_use[SKILL_ARCHERY], st->add, st->ads);
	native_prompt_add("#C\tDefence\t%s\n", pmax > 0 ? format("[%+d, %d-%d]", st->skill_use[SKILL_EVASION], pmin, pmax)
		: format("%+d", st->skill_use[SKILL_EVASION]));
	native_prompt_add("#C\tHealth\t%d\n", player->mhp);
	native_prompt_add("#C\tVoice\t%d\n", player->msp);
}

/* stat_points_start/command/stop: one native screen per change. */
static enum birth_stage stats_stage(void)
{
	event_add_handler(EVENT_STATPOINTS, on_stat_points, NULL);
	enum birth_stage next = BIRTH_STAT_POINTS;
	while (next == BIRTH_STAT_POINTS) {
		cmdq_push(CMD_REFRESH_STATS);
		cmdq_execute(CTX_BIRTH);
		int total = stat_pts.left;
		for (int i = 0; i < STAT_MAX; i++) total += stat_pts.spent[i];
		begin("stats", "Stats");
		para("Spend points on your stats; the rest of your experience goes on skills next.");
		for (int i = 0; i < STAT_MAX; i++)
			native_prompt_add("#S\t%s\t%d\t%d\t%d\t%d\t%d\n", stat_full[i], player->state.stat_use[i], stat_pts.spent[i],
				stat_pts.inc[i], stat_pts.spent[i] > 0, stat_pts.inc[i] <= stat_pts.left);
		native_prompt_add("#P\t%d\t%d\n", stat_pts.left, total);
		stat_figures();
		const int32_t a = ask_screen();
		if (a >= NB_SKILL_BUY && a < NB_SKILL_BUY + STAT_MAX) {
			cmdq_push(CMD_BUY_STAT);
			cmd_set_arg_choice(cmdq_peek(), "choice", a - NB_SKILL_BUY);
		} else if (a >= NB_SKILL_SELL && a < NB_SKILL_SELL + STAT_MAX) {
			cmdq_push(CMD_SELL_STAT);
			cmd_set_arg_choice(cmdq_peek(), "choice", a - NB_SKILL_SELL);
		} else if (a == NB_SKILL_RESET) {
			cmdq_push(CMD_RESET_STATS);
			cmd_set_arg_choice(cmdq_peek(), "choice", false);
		} else if (a == NB_SKILL_ACCEPT) {
			next = BIRTH_SKILL_POINTS;
		} else if (a < 0) {
			next = BIRTH_BACK;
		}
		cmdq_execute(CTX_BIRTH);
	}
	event_remove_handler(EVENT_STATPOINTS, on_stat_points, NULL);
	return next;
}

/* NB_PROMPT_TEXT: true and buf set if answered. */
static bool ask_text(const char *title, char *buf, size_t cap, bool random_allowed)
{
	for (;;) {
		native_prompt_begin();
		native_prompt_add("%s\n%s\n%d\n%d\n", title, buf, (int)cap - 1, random_allowed);
		native_prompt_end();
		const int32_t a = native_ask(NB_PROMPT_TEXT);
		if (a == 1) { native_answer_text(buf, cap); return true; }
		if (a == NB_TEXT_RANDOM && random_allowed) { player_random_name(buf, cap); continue; }
		return false;
	}
}

/* get_name_command(). */
static enum birth_stage name_stage(void)
{
	if (arg_name[0]) my_strcpy(player->full_name, arg_name, sizeof(player->full_name));
	if (arg_force_name) return BIRTH_HISTORY_CHOICE;
	char name[PLAYER_NAME_LEN];
	my_strcpy(name, player->full_name, sizeof(name));
	if (!ask_text("Your character's name", name, sizeof(name), true)) return BIRTH_BACK;
	if (!name[0]) my_strcpy(name, player->full_name, sizeof(name));
	if (!savefile[0] && savefile_name_already_used(name, true, true)
			&& !get_check("A savefile for that name exists.  Overwrite it? "))
		return BIRTH_BACK;
	cmdq_push(CMD_NAME_CHOICE);
	cmd_set_arg_string(cmdq_peek(), "name", name);
	return BIRTH_HISTORY_CHOICE;
}

/* get_ahw_command(). */
static enum birth_stage ahw_stage(void)
{
	for (;;) {
		begin("ahw", "Age, height and weight");
		para("Age %d · height %d'%d\" · weight %d lb", player->age, player->ht / 12, player->ht % 12, player->wt);
		option(1, "Accept", NULL, NULL);
		option(1, "Roll again", NULL, NULL);
		const int32_t a = ask_screen();
		if (a < 0) return BIRTH_BACK;
		if (a == 0) return BIRTH_HISTORY_CHOICE;
		if (a == 1) get_ahw(player);
	}
}

/* get_history_command(). */
static enum birth_stage history_stage(void)
{
	begin("history", "History");
	para("%s", player->history ? player->history : "");
	option(1, "Accept", NULL, NULL);
	option(1, "Edit", NULL, NULL);
	const int32_t a = ask_screen();
	if (a < 0) return BIRTH_BACK;
	if (a == 0) return BIRTH_FINAL_CONFIRM;
	char history[240];
	my_strcpy(history, player->history ? player->history : "", sizeof(history));
	if (!ask_text("Your character's history", history, sizeof(history), false)) return BIRTH_BACK;
	cmdq_push(CMD_HISTORY_CHOICE);
	cmd_set_arg_string(cmdq_peek(), "history", history);
	return BIRTH_HISTORY_CHOICE;
}

/* get_confirm_command(). */
static enum birth_stage confirm_stage(void)
{
	begin("confirm", "Ready?");
	summary();
	option(1, "Begin the adventure", NULL, NULL);
	option(1, "Start over", NULL, NULL);
	const int32_t a = ask_screen();
	if (a < 0) return BIRTH_BACK;
	if (a == 1) return BIRTH_RESET;
	cmdq_push(CMD_ACCEPT_CHARACTER);
	return BIRTH_COMPLETE;
}

/* textui_do_birth(), stage for stage. */
void native_do_birth(void)
{
	enum birth_stage current = BIRTH_RESET, prev = BIRTH_BACK, next = current;
	bool done = false;
	cmdq_push(CMD_BIRTH_INIT);
	cmdq_execute(CTX_BIRTH);
	while (!done) {
		switch (current) {
			case BIRTH_RESET:
				cmdq_push(CMD_BIRTH_RESET);
				next = quickstart_allowed ? BIRTH_QUICKSTART : BIRTH_RACE_CHOICE;
				break;
			case BIRTH_QUICKSTART:
				next = quickstart_stage();
				done = next == BIRTH_COMPLETE;
				break;
			case BIRTH_RACE_CHOICE:
			case BIRTH_HOUSE_CHOICE:
			case BIRTH_SEX_CHOICE:
				next = choice_stage(current);
				if (next == BIRTH_BACK) next = current - 1;
				if (next == BIRTH_QUICKSTART) next = BIRTH_RESET;
				break;
			case BIRTH_STAT_POINTS:
				next = stats_stage();
				if (next == BIRTH_BACK) next = BIRTH_SEX_CHOICE;
				break;
			case BIRTH_SKILL_POINTS: {
				const int action = native_skill_points(CTX_BIRTH, prev == BIRTH_STAT_POINTS);
				next = action > 0 ? BIRTH_NAME_CHOICE : action < 0 ? BIRTH_STAT_POINTS : BIRTH_SKILL_POINTS;
				break;
			}
			case BIRTH_NAME_CHOICE:
				next = name_stage();
				if (next == BIRTH_BACK) next = BIRTH_SKILL_POINTS;
				break;
			case BIRTH_AHW_CHOICE:
				next = ahw_stage();
				if (next == BIRTH_BACK) next = BIRTH_NAME_CHOICE;
				break;
			case BIRTH_HISTORY_CHOICE:
				next = history_stage();
				if (next == BIRTH_BACK) next = BIRTH_AHW_CHOICE;
				break;
			case BIRTH_FINAL_CONFIRM:
				next = confirm_stage();
				if (next == BIRTH_BACK) next = BIRTH_HISTORY_CHOICE;
				done = next == BIRTH_COMPLETE;
				break;
			default:
				break;
		}
		prev = current;
		current = next;
		cmdq_execute(CTX_BIRTH);
	}
}

/* ------------------------------------------------------------------------
 * Welcome (the splash screen, news.txt, and its "press any key")
 * ------------------------------------------------------------------------ */

/* Drops {colour}...{/} markup; true if the line had any (the verse is the coloured part). */
static bool strip_markup(char *s)
{
	bool marked = false;
	char *out = s;
	for (const char *in = s; *in; ) {
		if (*in == '{') {
			const char *const close = strchr(in, '}');
			if (close) { marked = true; in = close + 1; continue; }
		}
		*out++ = *in++;
	}
	*out = '\0';
	return marked;
}

bool native_welcome(void)
{
	char path[1024], line[1024], prose[2048] = "";
	begin("welcome", "NarSil");
	path_build(path, sizeof(path), ANGBAND_DIR_SCREENS, "news.txt");
	ang_file *const fp = file_open(path, MODE_READ, FTYPE_TEXT);
	while (fp && file_getl(fp, line, sizeof(line))) {
		const bool verse = strip_markup(line);
		const char *text = line;
		while (*text == ' ') text++;
		if (verse) { if (*text) native_prompt_add("#V\t%s\n", text); continue; }
		if (!*text) { if (prose[0]) para("%s", prose); prose[0] = '\0'; continue; }
		my_strcat(prose, prose[0] ? " " : "", sizeof(prose));
		my_strcat(prose, text, sizeof(prose));
	}
	if (prose[0]) para("%s", prose);
	if (fp) file_close(fp);
	para("Version %s", buildver);
	option(1, "Begin", NULL, NULL);
	option(1, "Tutorial", "A short guided dungeon; your own character is untouched.", NULL);
	return ask_screen() == 1;
}

/* ------------------------------------------------------------------------
 * Tutorial notes (tutorial_textblock_show_hook): a card over the map
 * ------------------------------------------------------------------------ */

static void native_tutorial_note(textblock *tb, const char *header)
{
	char title[120];
	my_strcpy(title, header ? header : "Note", sizeof(title));
	begin("note", title);
	char line[1024];
	size_t n = 0;
	for (const wchar_t *w = textblock_text(tb); ; w++) {
		if (!*w || *w == L'\n') {
			line[n] = '\0';
			if (n) para("%s", line);
			n = 0;
			if (!*w) break;
			continue;
		}
		char mb[8];
		const int k = text_wctomb(mb, *w);
		if (k > 0 && n + (size_t)k < sizeof(line)) { memcpy(line + n, mb, (size_t)k); n += (size_t)k; }
	}
	option(1, "Continue", NULL, NULL);
	(void)ask_screen();
}

/* ------------------------------------------------------------------------
 * Death (death_screen() in ui-death.c)
 * ------------------------------------------------------------------------ */

enum { DEATH_SHEET, DEATH_GEAR, DEATH_MESSAGES, DEATH_HISTORY, DEATH_SCORES, DEATH_NEW_GAME, DEATH_QUIT };

static void death_messages_screen(void)
{
	begin("text", "Final messages");
	const int n = MIN((int)messages_num(), 60);
	for (int age = n - 1; age >= 0; age--) para("%s", message_str((uint16_t)age));
	(void)ask_screen();
}

static void death_sheet_screen(void)
{
	begin("text", "Character");
	summary();
	para("Experience %d · deepest %d ft · %d turns", player->exp, player->max_depth * 50, player->turn);
	(void)ask_screen();
}

/* death_examine(): pick items to read about until cancelled. */
static void death_gear_screen(void)
{
	struct object *obj;
	while (get_item(&obj, "Examine which item? ", "You have nothing to examine.", 0, NULL,
			USE_INVEN | USE_QUIVER | USE_EQUIP | IS_HARMLESS)) {
		char header[120];
		object_desc(header, sizeof(header), obj, ODESC_PREFIX | ODESC_FULL | ODESC_CAPITAL, player);
		textblock *tb = object_info(obj, OINFO_NONE);
		begin("text", header);
		char line[1024];
		size_t n = 0;
		for (const wchar_t *w = textblock_text(tb); ; w++) {
			if (!*w || *w == L'\n') {
				line[n] = '\0';
				if (n) para("%s", line);
				n = 0;
				if (!*w) break;
				continue;
			}
			char mb[8];
			const int k = text_wctomb(mb, *w);
			if (k > 0 && n + (size_t)k < sizeof(line)) { memcpy(line + n, mb, (size_t)k); n += (size_t)k; }
		}
		textblock_free(tb);
		(void)ask_screen();
	}
}

void native_death_screen(void)
{
	event_signal(EVENT_INPUT_FLUSH);
	event_signal(EVENT_MESSAGE_FLUSH);
	const char *fate = player->escaped ? "You have escaped" : streq(player->died_from, "Retiring") ? "You have retired"
		: "You have been slain";
	while (!play_again) {
		begin("death", fate);
		if (!player->escaped && !streq(player->died_from, "Retiring"))
			para("%s was killed by %s at %d ft.", player->full_name, player->died_from, player->depth * 50);
		para("Experience %d · deepest %d ft · %d turns", player->exp, player->max_depth * 50, player->turn);
		option(1, "Character", NULL, NULL);
		option(1, "Inventory and equipment", NULL, NULL);
		option(1, "Final messages", NULL, NULL);
		option(1, "Character history", NULL, NULL);
		option(1, "Scores", NULL, NULL);
		option(1, "New character", NULL, NULL);
		option(1, "Quit", NULL, NULL);
		switch (ask_screen()) {
			case DEATH_SHEET: death_sheet_screen(); break;
			case DEATH_GEAR: death_gear_screen(); break;
			case DEATH_MESSAGES: death_messages_screen(); break;
			case DEATH_HISTORY: native_knowledge_history(); break;
			case DEATH_SCORES: native_knowledge_scores(); break;
			case DEATH_NEW_GAME: play_again = get_check("Start a new character? "); break;
			case DEATH_QUIT: return;
			default: break;
		}
	}
}

void native_birth_init(void)
{
	event_add_handler(EVENT_ENTER_BIRTH, on_enter_birth, NULL);
	birth_hook = native_do_birth;
	death_hook = native_death_screen;
	tutorial_textblock_show_hook = native_tutorial_note;
}
