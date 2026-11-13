/**
 * \file narsil_bridge.c
 * \brief Game thread, term backend, key queue and safe-point saves.
 *
 * See narsil_bridge.h for the threading contract.  Only the game thread
 * touches NarSil state; the UI side only touches the mutex-protected fields
 * of `shared`.
 */
#include "narsil_bridge.h"
#include "narsil_internal.h"

#include "angband.h"
#include "cmd-core.h"
#include "game-world.h"
#include "init.h"
#include "player-calcs.h"
#include "savefile.h"
#include "tutorial.h"
#include "ui-display.h"
#include "ui-event.h"
#include "ui-game.h"
#include "ui-init.h"
#include "ui-input.h"
#include "ui-output.h"
#include "ui-prefs.h"
#include "ui-term.h"
#include "z-color.h"

#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <string.h>
#include <time.h>

enum { KEY_QUEUE_CAP = 256, PATH_CAP = 512 };

/* Autosave at the command prompt on every new level and after this many prompts. */
enum { AUTOSAVE_EVERY_PROMPTS = 100 };

typedef struct { int32_t cp, attr; } cell;
/* A key (code = keycode) or a mouse press (code = button, at x, y). */
typedef struct { bool mouse; uint32_t code; uint8_t mods; int16_t x, y; } queued_key;

/* Fixed at start; read-only afterwards. */
static struct {
	char lib_dir[PATH_CAP];
	char data_dir[PATH_CAP];
	char player_name[32];
	int cols, rows;
	void (*log)(const char *msg);
} cfg;

static _Thread_local bool on_game_thread;

/* Owned by the game thread. */
static struct {
	term t;
	cell *work;
	int cur_x, cur_y;
	bool cur_visible;
	bool at_command_prompt;
	bool between_commands;  /* running the native screens the command prompt opened: a save point too */
	int saved_depth;
	int prompts_since_save;
	uint64_t autosaves;
} game = { .saved_depth = -1 };

/* Shared between threads; guarded by `lock`. */
static struct {
	pthread_mutex_t lock;
	pthread_cond_t input_cv;  /* game thread waits: key or save request */
	pthread_cond_t idle_cv;   /* test harness waits: game blocked, queue empty */
	queued_key keys[KEY_QUEUE_CAP];
	size_t key_head, key_len;
	bool save_requested;
	uint64_t saves_done;
	bool waiting;
	bool in_prompt;  /* blocked in a native prompt: only an answer wakes it */
	cell *front;
	int cur_x, cur_y;
	bool cur_visible;
	uint64_t generation;
	enum nb_state state;
	char exit_msg[256];
} shared = {
	.lock = PTHREAD_MUTEX_INITIALIZER,
	.input_cv = PTHREAD_COND_INITIALIZER,
	.idle_cv = PTHREAD_COND_INITIALIZER,
};

static size_t n_cells(void)
{
	return (size_t)cfg.cols * (size_t)cfg.rows;
}

static void log_line(const char *msg)
{
	if (cfg.log) cfg.log(msg);
}

/* ------------------------------------------------------------------------
 * Term hooks (game thread)
 * ------------------------------------------------------------------------ */

static cell *work_at(int x, int y)
{
	assert(x >= 0 && x < cfg.cols && y >= 0 && y < cfg.rows);
	return &game.work[(size_t)y * (size_t)cfg.cols + (size_t)x];
}

static void wipe_cells(cell *c, size_t n)
{
	for (size_t i = 0; i < n; i++) c[i] = (cell){ .cp = ' ', .attr = COLOUR_WHITE };
}

static errr term_text(int x, int y, int n, int a, const wchar_t *s)
{
	const int end = MIN(x + n, cfg.cols);
	for (int i = x; i < end; i++)
		*work_at(i, y) = (cell){ .cp = (int32_t)s[i - x], .attr = a };
	return 0;
}

static errr term_wipe(int x, int y, int n)
{
	const int end = MIN(x + n, cfg.cols);
	if (end > x) wipe_cells(work_at(x, y), (size_t)(end - x));
	return 0;
}

static errr term_curs(int x, int y)
{
	game.cur_x = x;
	game.cur_y = y;
	return 0;
}

static void publish_frame(void)
{
	pthread_mutex_lock(&shared.lock);
	memcpy(shared.front, game.work, n_cells() * sizeof(cell));
	shared.cur_x = game.cur_x;
	shared.cur_y = game.cur_y;
	shared.cur_visible = game.cur_visible;
	shared.generation++;
	pthread_mutex_unlock(&shared.lock);
}

/* Never in the tutorial: its character would replace the player's own save. */
static bool safe_to_save(void)
{
	return (game.at_command_prompt || game.between_commands) && character_generated && player
		&& !player->is_dead && !in_tutorial();
}

/*
 * Mirrors save_game_checked() minus its screen output and message flush:
 * the flush can raise a -more- prompt, which would block a background save.
 */
static bool save_quietly(void)
{
	assert(safe_to_save());
	handle_stuff(player);
	my_strcpy(player->died_from, "(saved)", sizeof(player->died_from));
	return savefile_save(savefile);
}

/* At the top-level command prompt with the map showing (no overlay screen). */
static bool at_map_prompt(void)
{
	return game.at_command_prompt && screen_save_depth == 0;
}

/*
 * Returns 0 if any keys were delivered, 1 otherwise (Term_xtra convention).
 * Lock order: shared.lock may be held while taking the native layer's lock,
 * never the reverse.
 */
static errr term_event(bool wait)
{
	queued_key batch[KEY_QUEUE_CAP];
	size_t n = 0;

	pthread_mutex_lock(&shared.lock);
	for (;;) {
		if (native_has_request()) {
			pthread_mutex_unlock(&shared.lock);
			if (native_service(at_map_prompt())) {
				/* Null command: textui_get_cmd returns, get_cmd runs the stash. */
				Term_keypress(ESCAPE, 0);
				return 0;
			}
			pthread_mutex_lock(&shared.lock);
			continue;
		}
		if (shared.save_requested && safe_to_save()) {
			pthread_mutex_unlock(&shared.lock);
			bridge_service_save();
			pthread_mutex_lock(&shared.lock);
			continue;
		}
		if (shared.key_len > 0 || !wait) break;

		/* About to block: make sure the UI sees the current map/status. */
		pthread_mutex_unlock(&shared.lock);
		native_publish(at_map_prompt());
		pthread_mutex_lock(&shared.lock);
		if (shared.key_len > 0 || native_has_request()
				|| (shared.save_requested && safe_to_save()))
			continue;

		shared.waiting = true;
		pthread_cond_broadcast(&shared.idle_cv);
		pthread_cond_wait(&shared.input_cv, &shared.lock);
		shared.waiting = false;
	}
	for (; shared.key_len > 0; shared.key_len--, n++) {
		batch[n] = shared.keys[shared.key_head];
		shared.key_head = (shared.key_head + 1) % KEY_QUEUE_CAP;
	}
	pthread_mutex_unlock(&shared.lock);

	for (size_t i = 0; i < n; i++) {
		const queued_key *const k = &batch[i];
		/* Term_mousepress packs the modifiers into the button's high nibble. */
		if (k->mouse) Term_mousepress(k->x, k->y, (char)((k->code & 0x0F) | ((k->mods & 0x0F) << 4)));
		else Term_keypress(k->code, k->mods);
	}
	return n > 0 ? 0 : 1;
}

static void discard_keys(void)
{
	pthread_mutex_lock(&shared.lock);
	shared.key_len = 0;
	pthread_mutex_unlock(&shared.lock);
}

static void sleep_ms(int ms)
{
	if (ms <= 0) return;
	const struct timespec ts = { .tv_sec = ms / 1000, .tv_nsec = (long)(ms % 1000) * 1000000L };
	nanosleep(&ts, NULL);
}

static errr term_xtra(int n, int v)
{
	switch (n) {
		case TERM_XTRA_EVENT: return term_event(v != 0);
		case TERM_XTRA_FLUSH: discard_keys(); return 0;
		case TERM_XTRA_CLEAR: wipe_cells(game.work, n_cells()); return 0;
		case TERM_XTRA_SHAPE: game.cur_visible = v != 0; return 0;
		case TERM_XTRA_FRESH: publish_frame(); native_publish(at_map_prompt()); return 0;
		case TERM_XTRA_DELAY: sleep_ms(v); return 0;
		default: return 0;
	}
}

static void init_main_term(void)
{
	term *const t = &game.t;
	term_init(t, cfg.cols, cfg.rows, KEY_QUEUE_CAP);
	t->complex_input = true;
	t->text_hook = term_text;
	t->wipe_hook = term_wipe;
	t->curs_hook = term_curs;
	t->xtra_hook = term_xtra;
	Term_activate(t);
	angband_term[0] = Term;
}

/* ------------------------------------------------------------------------
 * Game thread
 * ------------------------------------------------------------------------ */

/*
 * Android may kill a backgrounded process without warning, and onStop saves
 * are only as good as the last time the app left the foreground.  Save
 * quietly whenever the level changes and every so many command prompts.
 */
static void autosave_if_due(void)
{
	if (!safe_to_save()) return;
	game.prompts_since_save++;
	if (player->depth == game.saved_depth && game.prompts_since_save < AUTOSAVE_EVERY_PROMPTS)
		return;
	const bool ok = save_quietly();
	game.saved_depth = player->depth;
	game.prompts_since_save = 0;
	game.autosaves++;
	log_line(ok ? "autosave: done" : "autosave: FAILED");
}

static errr get_cmd(cmd_context ctx)
{
	/* textui_init() (also re-run after death, after reinit_hook) resets the prompt hooks. */
	native_prompt_init();
	game.at_command_prompt = ctx == CTX_GAME;
	autosave_if_due();
	if (ctx == CTX_GAME) {
		game.between_commands = true;
		const bool queued = native_pack_resume();
		game.between_commands = false;
		if (queued) {
			game.at_command_prompt = false;
			return 0;
		}
	}
	const errr r = textui_get_cmd(ctx);
	game.at_command_prompt = false;
	if (ctx == CTX_GAME) {
		game.between_commands = true;
		native_run_stashed();
		game.between_commands = false;
	}
	return r;
}

/*
 * quit() would exit() the whole process.  On the game thread, end just the
 * thread so the host can show why and decide what to do.
 */
static void on_quit(const char *msg)
{
	if (!on_game_thread) return;
	if (msg) log_line(msg);
	pthread_mutex_lock(&shared.lock);
	my_strcpy(shared.exit_msg, msg ? msg : "", sizeof(shared.exit_msg));
	shared.state = NB_EXITED;
	pthread_cond_broadcast(&shared.idle_cv);
	pthread_mutex_unlock(&shared.lock);
	pthread_exit(NULL);
}

static void with_trailing_sep(char *dst, size_t cap, const char *src)
{
	my_strcpy(dst, src, cap);
	if (!suffix(dst, PATH_SEP)) my_strcat(dst, PATH_SEP, cap);
}

/* play_game() re-runs init after death/new game; cleanup_angband() dropped our handlers. */
static void reinit(void)
{
	native_init();
}

static bool tutorial_chosen;

/*
 * The native death screen has already asked; a new character replaces the dead
 * one.  After the tutorial, though, back to the player's own savefile.
 */
static enum game_mode_type play_again_new(void)
{
	if (!tutorial_chosen) return GAME_NEW;
	tutorial_chosen = false;
	return GAME_LOAD;
}

/*
 * config.h hardwires PRIVATE_USER_PATH ("~/.angband") on UNIX, which on Android
 * is unwritable and on a desktop is the player's real profile.  Keep the user
 * dir (prefs, lore, char dumps) and its archive under our data dir instead.
 */
static void use_private_user_dir(const char *data)
{
	char buf[PATH_CAP];
	path_build(buf, sizeof(buf), data, "user");
	string_free(ANGBAND_DIR_USER);
	ANGBAND_DIR_USER = string_make(buf);
	path_build(buf, sizeof(buf), ANGBAND_DIR_USER, "archive");
	string_free(ANGBAND_DIR_ARCHIVE);
	ANGBAND_DIR_ARCHIVE = string_make(buf);
}

static void *game_main(void *unused)
{
	(void)unused;
	on_game_thread = true;
	char lib[PATH_CAP], data[PATH_CAP];
	with_trailing_sep(lib, sizeof(lib), cfg.lib_dir);
	with_trailing_sep(data, sizeof(data), cfg.data_dir);

	quit_aux = on_quit;
	plog_aux = log_line;

	init_file_paths(lib, lib, data);
	use_private_user_dir(data);
	my_strcpy(arg_name, cfg.player_name, sizeof(arg_name));
	savefile_set_name(cfg.player_name, true, false);
	create_needed_dirs();

	ANGBAND_SYS = "android";
	init_main_term();
	cmd_get_hook = get_cmd;
	reinit_hook = reinit;
	play_again_hook = play_again_new;

	init_display();
	init_angband();
	textui_init();
	native_init();
	tutorial_chosen = native_welcome();
	play_game(tutorial_chosen ? GAME_TUTORIAL : GAME_LOAD);

	textui_cleanup();
	cleanup_angband();
	quit(NULL);
	return NULL;
}

/* ------------------------------------------------------------------------
 * Public API (any thread)
 * ------------------------------------------------------------------------ */

bool nb_start(const nb_config *c)
{
	assert(c && c->lib_dir && c->data_dir && c->player_name);
	assert(c->cols >= 80 && c->rows >= 24 && c->cols <= 255 && c->rows <= 255);

	pthread_mutex_lock(&shared.lock);
	const bool fresh = shared.state == NB_IDLE;
	if (fresh) shared.state = NB_RUNNING;
	pthread_mutex_unlock(&shared.lock);
	if (!fresh) return false;

	my_strcpy(cfg.lib_dir, c->lib_dir, sizeof(cfg.lib_dir));
	my_strcpy(cfg.data_dir, c->data_dir, sizeof(cfg.data_dir));
	my_strcpy(cfg.player_name, c->player_name, sizeof(cfg.player_name));
	cfg.cols = c->cols;
	cfg.rows = c->rows;
	cfg.log = c->log;

	game.work = calloc(n_cells(), sizeof(cell));
	shared.front = calloc(n_cells(), sizeof(cell));
	if (!game.work || !shared.front) return false;
	wipe_cells(game.work, n_cells());
	wipe_cells(shared.front, n_cells());

	/* The game recurses deeply (menus inside prompts inside commands). */
	pthread_attr_t attr;
	pthread_attr_init(&attr);
	pthread_attr_setstacksize(&attr, 8u << 20);
	pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
	pthread_t thread;
	const int rc = pthread_create(&thread, &attr, game_main, NULL);
	pthread_attr_destroy(&attr);
	return rc == 0;
}

static void enqueue(queued_key k)
{
	pthread_mutex_lock(&shared.lock);
	if (shared.key_len < KEY_QUEUE_CAP) {
		shared.keys[(shared.key_head + shared.key_len) % KEY_QUEUE_CAP] = k;
		shared.key_len++;
		pthread_cond_signal(&shared.input_cv);
	}
	pthread_mutex_unlock(&shared.lock);
}

void nb_push_key(uint32_t code, uint8_t mods)
{
	enqueue((queued_key){ .code = code, .mods = mods });
}

void nb_push_mouse(int x, int y, int button, uint8_t mods)
{
	assert(button >= 1 && button <= 15 && mods <= 0x0F);
	if (x < 0 || y < 0 || x >= cfg.cols || y >= cfg.rows) return;
	enqueue((queued_key){ .mouse = true, .code = (uint32_t)button, .mods = mods,
		.x = (int16_t)x, .y = (int16_t)y });
}

void bridge_set_idle(bool idle)
{
	pthread_mutex_lock(&shared.lock);
	shared.waiting = idle;
	shared.in_prompt = idle;
	if (idle) pthread_cond_broadcast(&shared.idle_cv);
	pthread_mutex_unlock(&shared.lock);
}

void bridge_wake_game(void)
{
	pthread_mutex_lock(&shared.lock);
	/* Not idle until the game thread has seen whatever woke it (a prompt waits on, still idle). */
	if (!shared.in_prompt) shared.waiting = false;
	pthread_cond_signal(&shared.input_cv);
	pthread_mutex_unlock(&shared.lock);
}

void nb_request_save(void)
{
	pthread_mutex_lock(&shared.lock);
	shared.save_requested = true;
	/* Not idle until the game thread has seen the request (saved or deferred); a prompt defers it. */
	if (!shared.in_prompt) shared.waiting = false;
	pthread_cond_signal(&shared.input_cv);
	pthread_mutex_unlock(&shared.lock);
	/* A native screen opened between commands saves too (bridge_service_save()). */
	native_prompt_wake();
}

void bridge_service_save(void)
{
	pthread_mutex_lock(&shared.lock);
	const bool due = shared.save_requested && safe_to_save();
	if (due) shared.save_requested = false;
	pthread_mutex_unlock(&shared.lock);
	if (!due) return;
	const bool ok = save_quietly();
	log_line(ok ? "save: done" : "save: FAILED");
	pthread_mutex_lock(&shared.lock);
	shared.saves_done++;
	pthread_cond_broadcast(&shared.idle_cv);
	pthread_mutex_unlock(&shared.lock);
}

uint64_t nb_save_count(void)
{
	pthread_mutex_lock(&shared.lock);
	const uint64_t n = shared.saves_done;
	pthread_mutex_unlock(&shared.lock);
	return n;
}

uint64_t nb_snapshot(int32_t *cells, size_t n_ints, int *cx, int *cy, bool *vis)
{
	assert(n_ints >= 2 * n_cells());
	pthread_mutex_lock(&shared.lock);
	const cell *const src = shared.front;
	for (size_t i = 0; i < n_cells(); i++) {
		cells[2 * i] = src[i].cp;
		cells[2 * i + 1] = src[i].attr;
	}
	*cx = shared.cur_x;
	*cy = shared.cur_y;
	*vis = shared.cur_visible;
	const uint64_t gen = shared.generation;
	pthread_mutex_unlock(&shared.lock);
	return gen;
}

uint64_t nb_generation(void)
{
	pthread_mutex_lock(&shared.lock);
	const uint64_t gen = shared.generation;
	pthread_mutex_unlock(&shared.lock);
	return gen;
}

int nb_cols(void) { return cfg.cols; }
int nb_rows(void) { return cfg.rows; }

size_t nb_palette(uint32_t *rgb, size_t n)
{
	const size_t m = MIN(n, (size_t)MAX_COLORS);
	for (size_t i = 0; i < m; i++) {
		const uint8_t *const c = angband_color_table[i];
		rgb[i] = ((uint32_t)c[1] << 16) | ((uint32_t)c[2] << 8) | c[3];
	}
	return m;
}

enum nb_state nb_state(void)
{
	pthread_mutex_lock(&shared.lock);
	const enum nb_state s = shared.state;
	pthread_mutex_unlock(&shared.lock);
	return s;
}

const char *nb_exit_message(void)
{
	return shared.exit_msg;
}

bool nb_wait_for_input(int timeout_ms)
{
	struct timespec deadline;
	clock_gettime(CLOCK_REALTIME, &deadline);
	deadline.tv_sec += timeout_ms / 1000;
	deadline.tv_nsec += (long)(timeout_ms % 1000) * 1000000L;
	if (deadline.tv_nsec >= 1000000000L) {
		deadline.tv_sec++;
		deadline.tv_nsec -= 1000000000L;
	}

	pthread_mutex_lock(&shared.lock);
	int rc = 0;
	while (rc != ETIMEDOUT && shared.state != NB_EXITED
			&& !(shared.waiting && shared.key_len == 0))
		rc = pthread_cond_timedwait(&shared.idle_cv, &shared.lock, &deadline);
	const bool ok = rc != ETIMEDOUT;
	pthread_mutex_unlock(&shared.lock);
	return ok;
}
