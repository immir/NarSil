/**
 * \file narsil_combat.c
 * \brief Extra lines for the pack's item description, behind an option (nb_set_item_details()): what an
 *        ego item gives in general, and what each ability the item grants does.
 */
#include "narsil_internal.h"

#include "angband.h"
#include "obj-info.h"
#include "obj-knowledge.h"
#include "player-abilities.h"
#include "project.h"

#include <assert.h>
#include <string.h>

static void add_line(char *const buf, const size_t cap, const char *const fmt, ...) __attribute__((format(printf, 3, 4)));
static void add_line(char *const buf, const size_t cap, const char *const fmt, ...)
{
	va_list args;
	va_start(args, fmt);
	char line[256];
	vstrnfmt(line, sizeof(line), fmt, args);
	va_end(args);
	my_strcat(buf, line, cap);
	my_strcat(buf, "\n", cap);
}

/* What each ability the item is known to grant does: its name, then the game's description. */
static void abilities_text(const struct object *const obj, char *const buf, const size_t cap)
{
	const bool known_kind = obj->kind && obj->kind->aware;
	const bool known_ego = obj->ego && (obj->ego->aware || (obj->known && obj->known->ego == obj->ego));
	for (struct ability *a = obj->abilities; a; a = a->next) {
		const bool known = (known_kind && locate_ability(obj->kind->abilities, a))
			|| (known_ego && locate_ability(obj->ego->abilities, a))
			|| (obj->known && locate_ability(obj->known->abilities, a));
		if (known && a->desc) add_line(buf, cap, "%s: %s", a->name, a->desc);
	}
}

/* What the game says an ego type can give, for an ego the player knows (the item's own text covers only
 * the properties already learned). */
static void bonus_line(char *const buf, const size_t cap, const int max, const char *const what)
{
	if (max > 0) add_line(buf, cap, "A random bonus of 1%s to %s.", max > 1 ? format(" to %d", max) : "", what);
}

static void ego_text(const struct object *const obj, char *const buf, const size_t cap)
{
	if (!obj->ego || !obj->known || !obj->known->ego) return;
	const struct ego_item *const e = obj->ego;
	add_line(buf, cap, "In general, items %s have:", e->name);
	bonus_line(buf, cap, e->att, "attack");
	bonus_line(buf, cap, e->dd, "the number of damage dice");
	bonus_line(buf, cap, e->ds, "the sides of each damage die");
	bonus_line(buf, cap, e->evn, "evasion");
	bonus_line(buf, cap, e->pd, "the number of protection dice");
	bonus_line(buf, cap, e->ps, "the sides of each protection die");
	for (int i = 0; i < ELEM_MAX; i++)
		if (e->el_info[i].flags & EL_INFO_IGNORE) add_line(buf, cap, "Cannot be damaged by %s.", projections[i].name);
	textblock *const tb = object_info_ego(obj->ego);
	char line[256];
	size_t n = 0;
	for (const wchar_t *w = textblock_text(tb); w && *w; w++) {
		if (*w == L'\n' || n + 8 >= sizeof(line)) {
			line[n] = '\0';
			if (n && !strstr(line, "does not seem to possess") && !strstr(line, "grants you the ability")) add_line(buf, cap, "%s", line);
			n = 0;
			continue;
		}
		char mb[8];
		const int k = text_wctomb(mb, *w == L'\t' ? L' ' : *w);
		if (k > 0) { memcpy(line + n, mb, (size_t)k); n += (size_t)k; }
	}
	line[n] = '\0';
	if (n && !strstr(line, "does not seem to possess") && !strstr(line, "grants you the ability")) add_line(buf, cap, "%s", line);
	textblock_free(tb);
}

size_t native_item_details(struct object *const obj, char *const buf, const size_t cap)
{
	assert(cap > 0);
	buf[0] = '\0';
	if (!native_item_details_on()) return 0;
	ego_text(obj, buf, cap);
	abilities_text(obj, buf, cap);
	return strlen(buf);
}
