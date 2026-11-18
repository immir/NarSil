/**
 * \file narsil_crash.c
 * \brief Leaves a note behind when the native game dies by a signal.
 *
 * The handler writes <data_dir>/crash.txt (the signal, the fault address and the call stack as module+offset
 * pairs) using only open/write/close, then lets the signal take its default course.  The apps look for the
 * file at the next start, offer to send it with the log, and delete it (nb_crash_report).
 */
#define _GNU_SOURCE
#include "narsil_bridge.h"
#include "narsil_internal.h"

#include <assert.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <unwind.h>

#define CRASH_PATH_CAP 1100
#define CRASH_FRAMES 48

static char crash_path[CRASH_PATH_CAP];

static const int crash_signals[] = { SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL };

/* Async-signal-safe output: a small buffer-free writer. */
static void put(const int fd, const char *s)
{
	size_t n = strlen(s);
	while (n > 0) {
		const ssize_t w = write(fd, s, n);
		if (w <= 0) return;
		s += w;
		n -= (size_t)w;
	}
}

static void put_hex(const int fd, const uintptr_t v)
{
	char buf[2 + 2 * sizeof(uintptr_t) + 1];
	char *p = buf + sizeof(buf) - 1;
	*p = '\0';
	uintptr_t x = v;
	do { *--p = "0123456789abcdef"[x & 15]; x >>= 4; } while (x);
	*--p = 'x';
	*--p = '0';
	put(fd, p);
}

static void put_dec(const int fd, long v)
{
	char buf[24];
	char *p = buf + sizeof(buf) - 1;
	*p = '\0';
	const bool neg = v < 0;
	unsigned long x = neg ? 0ul - (unsigned long)v : (unsigned long)v;
	do { *--p = (char)('0' + x % 10); x /= 10; } while (x);
	if (neg) *--p = '-';
	put(fd, p);
}

struct trace { int fd; int n; };

static _Unwind_Reason_Code frame_cb(struct _Unwind_Context *ctx, void *arg)
{
	struct trace *const t = arg;
	const uintptr_t pc = _Unwind_GetIP(ctx);
	if (pc == 0 || t->n >= CRASH_FRAMES) return _URC_END_OF_STACK;
	Dl_info info;
	put(t->fd, "  #");
	put_dec(t->fd, t->n++);
	put(t->fd, " ");
	if (dladdr((void *)pc, &info) && info.dli_fname) {
		put(t->fd, info.dli_fname);
		put(t->fd, " +");
		put_hex(t->fd, pc - (uintptr_t)info.dli_fbase);
		if (info.dli_sname) { put(t->fd, " "); put(t->fd, info.dli_sname); }
	} else {
		put(t->fd, "pc ");
		put_hex(t->fd, pc);
	}
	put(t->fd, "\n");
	return _URC_NO_REASON;
}

static void on_crash(const int sig, siginfo_t *const info, void *const unused)
{
	(void)unused;
	const int fd = open(crash_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
	if (fd >= 0) {
		put(fd, "NarSil native crash: signal ");
		put_dec(fd, sig);
		put(fd, " (");
		put(fd, sig == SIGSEGV ? "SEGV" : sig == SIGABRT ? "ABRT" : sig == SIGBUS ? "BUS" : sig == SIGFPE ? "FPE" : "ILL");
		put(fd, ") at ");
		put_hex(fd, info ? (uintptr_t)info->si_addr : 0);
		put(fd, ", time ");
		put_dec(fd, (long)time(NULL));
		put(fd, "\n");
		struct trace t = { fd, 0 };
		_Unwind_Backtrace(frame_cb, &t);
		close(fd);
	}
	signal(sig, SIG_DFL);
	raise(sig);
}

void bridge_install_crash_handler(const char *const data_dir)
{
	assert(data_dir);
	if (strlen(data_dir) + sizeof("/" NB_CRASH_FILE) >= sizeof(crash_path)) return;
	strcpy(crash_path, data_dir);
	strcat(crash_path, "/" NB_CRASH_FILE);

	struct sigaction sa;
	memset(&sa, 0, sizeof(sa));
	sa.sa_sigaction = on_crash;
	sa.sa_flags = SA_SIGINFO | SA_NODEFER;
	sigemptyset(&sa.sa_mask);
	for (size_t i = 0; i < sizeof(crash_signals) / sizeof(crash_signals[0]); i++)
		sigaction(crash_signals[i], &sa, NULL);
}

bool nb_crash_report(const char *const data_dir, char *const out, const size_t cap)
{
	assert(data_dir && out && cap > 0);
	out[0] = '\0';
	char path[CRASH_PATH_CAP];
	if (strlen(data_dir) + sizeof("/" NB_CRASH_FILE) >= sizeof(path)) return false;
	strcpy(path, data_dir);
	strcat(path, "/" NB_CRASH_FILE);
	const int fd = open(path, O_RDONLY);
	if (fd < 0) return false;
	const ssize_t n = read(fd, out, cap - 1);
	close(fd);
	unlink(path);
	out[n > 0 ? n : 0] = '\0';
	return n > 0;
}

void nb_crash_for_test(void)
{
	raise(SIGSEGV);
}
