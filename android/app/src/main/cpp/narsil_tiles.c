/**
 * \file narsil_tiles.c
 * \brief Tiles for the native map: a second set of the visual tables
 *        (monster_x_attr, feat_x_char, …) loaded from the MicroChasm pref files,
 *        swapped in only while the map is built.  The game itself, and every
 *        other screen, keeps its ASCII tables.
 *
 * In graphics mode an attr/char pair is a tile position: attr = 0x80 | row,
 * char = 0x80 | column, in lib/tiles/microchasm/16x16.png (16 x 16 pixel tiles).
 */
#include "narsil_internal.h"

#include "angband.h"
#include "grafmode.h"
#include "init.h"
#include "obj-tval.h"
#include "object.h"
#include "ui-map.h"
#include "ui-prefs.h"

#include <assert.h>
#include <string.h>

/* The visual tables, by pointer: the game's live ones, or our tile set. */
typedef struct {
	uint8_t *mon_a; wchar_t *mon_c;
	uint8_t *kind_a; wchar_t *kind_c;
	uint8_t *feat_a[LIGHTING_MAX]; wchar_t *feat_c[LIGHTING_MAX];
	uint8_t *trap_a[LIGHTING_MAX]; wchar_t *trap_c[LIGHTING_MAX];
	uint8_t *flav_a; wchar_t *flav_c;
	uint8_t alert_a, glow_a, damage_a[10];
	wchar_t alert_c, glow_c, damage_c[10];
} visuals;

static visuals tile_set;
static int tile_mode;    /* the MicroChasm graphics mode id, 0 until loaded */
static bool load_failed;

static size_t flavour_count(void)
{
	size_t n = 0;
	for (const struct flavor *f = flavors; f; f = f->next) n = MAX(n, (size_t)f->fidx + 1);
	return n;
}

static visuals live(void)
{
	visuals v = { monster_x_attr, monster_x_char, kind_x_attr, kind_x_char, { 0 }, { 0 }, { 0 }, { 0 },
		flavor_x_attr, flavor_x_char, alert_x_attr, glow_x_attr, { 0 }, alert_x_char, glow_x_char, { 0 } };
	for (int l = 0; l < LIGHTING_MAX; l++) {
		v.feat_a[l] = feat_x_attr[l]; v.feat_c[l] = feat_x_char[l];
		v.trap_a[l] = trap_x_attr[l]; v.trap_c[l] = trap_x_char[l];
	}
	memcpy(v.damage_a, damage_x_attr, sizeof(v.damage_a));
	memcpy(v.damage_c, damage_x_char, sizeof(v.damage_c));
	return v;
}

static void install(const visuals *v)
{
	monster_x_attr = v->mon_a; monster_x_char = v->mon_c;
	kind_x_attr = v->kind_a; kind_x_char = v->kind_c;
	for (int l = 0; l < LIGHTING_MAX; l++) {
		feat_x_attr[l] = v->feat_a[l]; feat_x_char[l] = v->feat_c[l];
		trap_x_attr[l] = v->trap_a[l]; trap_x_char[l] = v->trap_c[l];
	}
	flavor_x_attr = v->flav_a; flavor_x_char = v->flav_c;
	alert_x_attr = v->alert_a; alert_x_char = v->alert_c;
	glow_x_attr = v->glow_a; glow_x_char = v->glow_c;
	memcpy(damage_x_attr, v->damage_a, sizeof(v->damage_a));
	memcpy(damage_x_char, v->damage_c, sizeof(v->damage_c));
}

#define DUP(p, n) memcpy(mem_alloc((n) * sizeof(*(p))), (p), (n) * sizeof(*(p)))

/* A copy of every table's contents (the scalars come along by value). */
static visuals duplicate(const visuals *v)
{
	const size_t nf = flavour_count();
	visuals d = *v;
	d.mon_a = DUP(v->mon_a, z_info->r_max); d.mon_c = DUP(v->mon_c, z_info->r_max);
	d.kind_a = DUP(v->kind_a, z_info->k_max); d.kind_c = DUP(v->kind_c, z_info->k_max);
	for (int l = 0; l < LIGHTING_MAX; l++) {
		d.feat_a[l] = DUP(v->feat_a[l], FEAT_MAX); d.feat_c[l] = DUP(v->feat_c[l], FEAT_MAX);
		d.trap_a[l] = DUP(v->trap_a[l], z_info->trap_max); d.trap_c[l] = DUP(v->trap_c[l], z_info->trap_max);
	}
	d.flav_a = DUP(v->flav_a, nf); d.flav_c = DUP(v->flav_c, nf);
	return d;
}

/* Copies the contents of src's tables into dst's (same shapes). */
static void copy_into(const visuals *dst, const visuals *src)
{
	const size_t nf = flavour_count();
	memcpy(dst->mon_a, src->mon_a, z_info->r_max * sizeof(uint8_t));
	memcpy(dst->mon_c, src->mon_c, z_info->r_max * sizeof(wchar_t));
	memcpy(dst->kind_a, src->kind_a, z_info->k_max * sizeof(uint8_t));
	memcpy(dst->kind_c, src->kind_c, z_info->k_max * sizeof(wchar_t));
	for (int l = 0; l < LIGHTING_MAX; l++) {
		memcpy(dst->feat_a[l], src->feat_a[l], FEAT_MAX * sizeof(uint8_t));
		memcpy(dst->feat_c[l], src->feat_c[l], FEAT_MAX * sizeof(wchar_t));
		memcpy(dst->trap_a[l], src->trap_a[l], z_info->trap_max * sizeof(uint8_t));
		memcpy(dst->trap_c[l], src->trap_c[l], z_info->trap_max * sizeof(wchar_t));
	}
	memcpy(dst->flav_a, src->flav_a, nf * sizeof(uint8_t));
	memcpy(dst->flav_c, src->flav_c, nf * sizeof(wchar_t));
}

static void release(visuals *v)
{
	mem_free(v->mon_a); mem_free(v->mon_c); mem_free(v->kind_a); mem_free(v->kind_c);
	for (int l = 0; l < LIGHTING_MAX; l++) {
		mem_free(v->feat_a[l]); mem_free(v->feat_c[l]); mem_free(v->trap_a[l]); mem_free(v->trap_c[l]);
	}
	mem_free(v->flav_a); mem_free(v->flav_c);
}

/* Loads the tile set once (game thread); false if the tiles are not installed. */
static bool load_tiles(void)
{
	if (tile_mode) return true;
	if (load_failed) return false;
	if (!graphics_modes && !init_graphics_modes()) { load_failed = true; return false; }
	const graphics_mode *mode = graphics_modes;
	while (mode && !strstr(mode->path, "microchasm")) mode = mode->pNext;
	if (!mode) { load_failed = true; return false; }

	/* reset_visuals() rewrites the live tables: keep their contents, read the tiles, put them back. */
	const visuals ascii = live();
	visuals saved = duplicate(&ascii);
	use_graphics = mode->grafID;
	reset_visuals(true);
	tile_set = duplicate(&ascii);
	tile_set.alert_a = alert_x_attr; tile_set.alert_c = alert_x_char;
	tile_set.glow_a = glow_x_attr; tile_set.glow_c = glow_x_char;
	memcpy(tile_set.damage_a, damage_x_attr, sizeof(tile_set.damage_a));
	memcpy(tile_set.damage_c, damage_x_char, sizeof(tile_set.damage_c));
	use_graphics = GRAPHICS_NONE;
	copy_into(&ascii, &saved);
	install(&ascii);           /* the live pointers, unchanged, and the scalars as they were */
	release(&saved);
	tile_mode = mode->grafID;
	return true;
}

int32_t native_tile(const struct grid_data *grid)
{
	if (!load_tiles()) return -1;
	const visuals ascii = live();
	install(&tile_set);
	use_graphics = tile_mode;
	int a, ta;
	wchar_t c, tc;
	struct grid_data g = *grid;  /* grid_data_as_text() may adjust it */
	grid_data_as_text(&g, &a, &c, &ta, &tc);
	use_graphics = GRAPHICS_NONE;
	install(&ascii);
	return (a & 0x80) && (c & 0x80) ? ((a & 0x7F) << 8) | (c & 0x7F) : -1;
}
