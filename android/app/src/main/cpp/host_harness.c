/**
 * \file host_harness.c
 * \brief Drives the bridge from a key script, without Android.
 *
 * usage: narsil_host <lib_dir> <data_dir> [token...]
 *
 * Tokens run in order; after each key token the harness waits for the game
 * to block on input again:
 *   @esc @enter @tab @bs @up @down @left @right   special keys
 *   ^x                                            control-x
 *   @tap=X,Y @hold=X,Y                            left / right mouse press on a cell
 *   @tap@DX,DY                                    tap relative to the player's '@'
 *   @where                                        print the player's screen position
 *   @dump                                         print the current frame
 *   @save                                         request a save and wait for it
 *   @save-async                                   request a save; it runs at the next safe point
 *   @expect=TEXT                                  fail unless TEXT is on screen
 *   anything else                                 typed literally
 */
#include "narsil_bridge.h"

#include "ui-event.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

enum { WAIT_MS = 10000 };

static void log_stderr(const char *msg)
{
	fprintf(stderr, "[narsil] %s\n", msg);
}

static int32_t *frame_buf(void)
{
	static int32_t *buf;
	if (!buf) buf = malloc(sizeof(int32_t) * 2u * (size_t)nb_cols() * (size_t)nb_rows());
	return buf;
}

/* Renders the frame as UTF-8 lines into `out`. */
static void render(char *out, size_t cap)
{
	int cx, cy;
	bool vis;
	int32_t *const cells = frame_buf();
	nb_snapshot(cells, 2u * (size_t)nb_cols() * (size_t)nb_rows(), &cx, &cy, &vis);

	size_t len = 0;
	for (int y = 0; y < nb_rows(); y++) {
		for (int x = 0; x < nb_cols(); x++) {
			const int32_t cp = cells[2 * (y * nb_cols() + x)];
			char u[8];
			const int n = cp < 0x80 ? snprintf(u, sizeof(u), "%c", cp >= 32 ? (char)cp : '?')
				: snprintf(u, sizeof(u), "%lc", (wint_t)cp);
			if (len + (size_t)n + 2 < cap) { memcpy(out + len, u, (size_t)n); len += (size_t)n; }
		}
		if (len + 2 < cap) out[len++] = '\n';
	}
	out[len] = '\0';
}

static void dump(void)
{
	static char text[1 << 16];
	render(text, sizeof(text));
	printf("---- frame %llu ----\n%s", (unsigned long long)nb_generation(), text);
	fflush(stdout);
}

static bool expect(const char *needle)
{
	static char text[1 << 16];
	render(text, sizeof(text));
	return strstr(text, needle) != NULL;
}

/* Screen position of the player glyph, searching below the message line. */
static bool find_player(int *px, int *py)
{
	int cx, cy;
	bool vis;
	int32_t *const cells = frame_buf();
	nb_snapshot(cells, 2u * (size_t)nb_cols() * (size_t)nb_rows(), &cx, &cy, &vis);
	for (int y = 1; y < nb_rows() - 1; y++)
		for (int x = 13; x < nb_cols(); x++)
			if (cells[2 * (y * nb_cols() + x)] == '@') { *px = x; *py = y; return true; }
	return false;
}

static const struct { const char *name; uint32_t code; } specials[] = {
	{ "@esc", ESCAPE }, { "@enter", KC_ENTER }, { "@tab", KC_TAB },
	{ "@bs", KC_BACKSPACE }, { "@up", ARROW_UP }, { "@down", ARROW_DOWN },
	{ "@left", ARROW_LEFT }, { "@right", ARROW_RIGHT },
};

static bool settle(void)
{
	if (nb_wait_for_input(WAIT_MS)) return true;
	fprintf(stderr, "timeout waiting for input\n");
	return false;
}

static int run_token(const char *tok)
{
	for (size_t i = 0; i < sizeof(specials) / sizeof(specials[0]); i++)
		if (strcmp(tok, specials[i].name) == 0) {
			nb_push_key(specials[i].code, 0);
			return settle() ? 0 : 1;
		}
	if (strcmp(tok, "@dump") == 0) { dump(); return 0; }
	if (strcmp(tok, "@save") == 0) {
		const uint64_t before = nb_save_count();
		nb_request_save();
		if (!settle() || nb_save_count() != before + 1) {
			fprintf(stderr, "save did not complete\n");
			return 1;
		}
		return 0;
	}
	int mx, my;
	char tail;
	if (sscanf(tok, "@tap=%d,%d%c", &mx, &my, &tail) == 2) {
		nb_push_mouse(mx, my, 1, 0);
		return settle() ? 0 : 1;
	}
	if (sscanf(tok, "@tap@%d,%d%c", &mx, &my, &tail) == 2) {
		int px, py;
		if (!find_player(&px, &py)) { fprintf(stderr, "no @ on screen\n"); return 1; }
		printf("tap @(%d,%d)%+d%+d\n", px, py, mx, my);
		nb_push_mouse(px + mx, py + my, 1, 0);
		return settle() ? 0 : 1;
	}
	if (strncmp(tok, "@where", 6) == 0) {
		int px, py;
		if (find_player(&px, &py)) printf("player at %d,%d\n", px, py);
		return 0;
	}
	if (sscanf(tok, "@hold=%d,%d%c", &mx, &my, &tail) == 2) {
		nb_push_mouse(mx, my, 2, 0);
		return settle() ? 0 : 1;
	}
	if (strcmp(tok, "@save-async") == 0) {
		nb_request_save();
		return settle() ? 0 : 1;
	}
	if (strncmp(tok, "@expect=", 8) == 0) {
		if (expect(tok + 8)) return 0;
		fprintf(stderr, "expected text not on screen: %s\n", tok + 8);
		dump();
		return 1;
	}
	if (tok[0] == '^' && tok[1] && !tok[2]) {
		nb_push_key(KTRL(tok[1]), 0);
		return settle() ? 0 : 1;
	}
	for (const char *p = tok; *p; p++) {
		nb_push_key((unsigned char)*p, 0);
		if (!settle()) return 1;
	}
	return 0;
}

int main(int argc, char **argv)
{
	if (argc < 3) {
		fprintf(stderr, "usage: %s <lib_dir> <data_dir> [token...]\n", argv[0]);
		return 2;
	}
	const nb_config cfg = {
		.lib_dir = argv[1], .data_dir = argv[2], .player_name = "Harness",
		.cols = 80, .rows = 24, .log = log_stderr,
	};
	if (!nb_start(&cfg)) { fprintf(stderr, "nb_start failed\n"); return 1; }
	if (!settle()) { dump(); return 1; }

	for (int i = 3; i < argc; i++) {
		if (nb_state() == NB_EXITED) break;
		if (run_token(argv[i]) != 0) return 1;
	}
	printf("state=%d exit=\"%s\" saves=%llu\n", (int)nb_state(),
		nb_state() == NB_EXITED ? nb_exit_message() : "",
		(unsigned long long)nb_save_count());
	return 0;
}
