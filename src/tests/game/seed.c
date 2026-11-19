/* game/seed.c */
/* Seeded levels: the game's seed, the depth and the visit fix the level. */

#include "unit-test.h"
#include "test-utils.h"

#include "cave.h"
#include "game-world.h"
#include "gen-seed.h"
#include "generate.h"
#include "init.h"
#include "mon-make.h"
#include "mon-util.h"
#include "obj-pile.h"
#include "savefile.h"
#include "wizard.h"
#include "z-file.h"
#include "monster.h"
#include "obj-util.h"
#include "player.h"
#include "player-birth.h"

static struct gen_baseline *baseline;

int setup_tests(void **state) {
	set_file_paths();
	init_angband();
#ifdef UNIX
	create_needed_dirs();
#endif
	if (!player_make_simple(NULL, NULL, NULL, "Tester")) {
		cleanup_angband();
		return 1;
	}
	seed_game = 20261119;
	baseline = gen_baseline_save(player);
	return 0;
}

int teardown_tests(void *state) {
	gen_baseline_free(baseline);
	if (cave) wipe_mon_list(cave, player);
	cleanup_angband();
	return 0;
}

static uint32_t fnv(uint32_t h, uint32_t v) {
	for (int i = 0; i < 4; i++) h = (h ^ ((v >> (8 * i)) & 0xff)) * 16777619U;
	return h;
}

static uint32_t object_sig(const struct object *obj) {
	uint32_t h = 2166136261U;
	h = fnv(h, obj->kind->kidx);
	h = fnv(h, obj->ego ? obj->ego->eidx + 1 : 0);
	h = fnv(h, obj->artifact ? obj->artifact->aidx + 1 : 0);
	h = fnv(h, obj->number);
	h = fnv(h, (uint32_t)obj->grid.x << 16 | (uint32_t)obj->grid.y);
	return h;
}

static uint32_t terrain_hash(struct chunk *c) {
	uint32_t h = 2166136261U;
	for (int y = 0; y < c->height; y++)
		for (int x = 0; x < c->width; x++)
			h = fnv(h, square(c, loc(x, y))->feat);
	return h;
}

static uint32_t monster_hash(struct chunk *c) {
	uint32_t h = 2166136261U;
	for (int i = 1; i < cave_monster_max(c); i++) {
		const struct monster *mon = cave_monster(c, i);
		if (!mon->race) continue;
		h = fnv(h, mon->race->ridx);
		h = fnv(h, (uint32_t)mon->grid.x << 16 | (uint32_t)mon->grid.y);
	}
	return h;
}

/* The level's objects, in grid order: their signatures, and how many. */
static int object_sigs(struct chunk *c, uint32_t *sigs, int max) {
	int n = 0;
	for (int y = 0; y < c->height; y++)
		for (int x = 0; x < c->width; x++)
			for (const struct object *obj = square_object(c, loc(x, y)); obj; obj = obj->next)
				if (n < max) sigs[n++] = object_sig(obj);
	return n;
}

static uint32_t level_hash(struct chunk *c) {
	uint32_t sigs[512];
	const int n = object_sigs(c, sigs, 512);
	uint32_t h = fnv(terrain_hash(c), monster_hash(c));
	for (int i = 0; i < n; i++) h = fnv(h, sigs[i]);
	return fnv(h, n);
}

/* Generate a visit to depth from the baseline, with the game's RNG first set to scramble. */
static void generate(int depth, int visit, uint32_t scramble) {
	level_discard(player);
	gen_baseline_restore(player, baseline);
	player->depth = depth;
	player->level_visits[depth] = visit;
	Rand_state_seed(scramble);
	prepare_next_level(player);
}

static int test_same_seed_same_level(void *state) {
	for (int depth = 1; depth <= 12; depth += 4) {
		generate(depth, 0, 1);
		const uint32_t first = level_hash(cave);
		generate(depth, 0, 99);
		eq(level_hash(cave), first);
	}
	ok;
}

static int test_visits_differ(void *state) {
	generate(5, 0, 1);
	const uint32_t first = level_hash(cave);
	eq(player->level_visits[5], 1);
	generate(5, 1, 1);
	const uint32_t second = level_hash(cave);
	generate(5, 2, 1);
	require(first != second && second != level_hash(cave) && first != level_hash(cave));
	ok;
}

static int test_other_seed_differs(void *state) {
	generate(5, 0, 1);
	const uint32_t first = level_hash(cave);
	seed_game++;
	generate(5, 0, 1);
	seed_game--;
	require(level_hash(cave) != first);
	ok;
}

/* Generating a level leaves the game's own RNG exactly where it was. */
static int test_game_rng_untouched(void *state) {
	uint32_t before[16], after[16];

	Rand_state_seed(4242);
	for (int i = 0; i < 16; i++) before[i] = Rand_div(1000000);
	generate(5, 0, 4242);
	for (int i = 0; i < 16; i++) after[i] = Rand_div(1000000);
	require(memcmp(before, after, sizeof(before)) == 0);
	ok;
}

/* More artefacts already made changes some objects' rolls, but not the layout,
 * the monsters or (nearly all) the other objects. */
static int test_state_changes_stay_local(void *state) {
	uint32_t first[512], second[512];

	generate(8, 0, 1);
	const uint32_t terrain = terrain_hash(cave), monsters = monster_hash(cave);
	const int n = object_sigs(cave, first, 512);
	level_discard(player);
	gen_baseline_restore(player, baseline);
	player->depth = 8;
	player->level_visits[8] = 0;
	player->num_artefacts = 40;
	prepare_next_level(player);
	eq(terrain_hash(cave), terrain);
	eq(monster_hash(cave), monsters);
	const int m = object_sigs(cave, second, 512);
	eq(m, n);
	int same = 0;
	for (int i = 0; i < n; i++) same += first[i] == second[i];
	require(n > 0 && same * 10 >= n * 9);
	ok;
}

/* Drop whatever a monster of this race would, from the given seed, at a quiet grid; return a signature. */
static uint32_t drop_sig(struct monster_race *race, uint32_t drop_seed, uint32_t scramble) {
	struct monster mon;
	struct loc grid = player->grid;
	uint32_t sigs[64];

	memset(&mon, 0, sizeof(mon));
	mon.race = race;
	mon.drop_seed = drop_seed;
	mon.grid = grid;
	for (int y = grid.y - 3; y <= grid.y + 3; y++)
		for (int x = grid.x - 3; x <= grid.x + 3; x++)
			square_excise_pile(cave, loc(x, y));
	Rand_state_seed(scramble);
	drop_loot(cave, &mon, grid, false);
	uint32_t h = 2166136261U;
	int n = 0;
	for (int y = grid.y - 3; y <= grid.y + 3; y++)
		for (int x = grid.x - 3; x <= grid.x + 3; x++)
			for (const struct object *obj = square_object(cave, loc(x, y)); obj; obj = obj->next)
				if (n < 64) sigs[n++] = object_sig(obj);
	for (int i = 0; i < n; i++) h = fnv(h, sigs[i]);
	return fnv(h, n);
}

/* A monster's drop is fixed by its seed, not by what the game's RNG is doing when it dies. */
static int test_drop_follows_seed(void *state) {
	struct monster_race *race = NULL;
	for (int i = 1; i < z_info->r_max && !race; i++)
		if (r_info[i].name && r_info[i].drops && r_info[i].level < 20) race = &r_info[i];
	require(race != NULL);
	generate(10, 0, 1);
	int differs = 0;
	for (uint32_t seed = 1; seed <= 24; seed++) {
		const uint32_t first = drop_sig(race, seed, 1);
		eq(drop_sig(race, seed, 777), first);
		differs += first != drop_sig(race, seed + 100, 1);
	}
	require(differs > 0);
	ok;
}

static int test_new_monsters_get_seeds(void *state) {
	generate(6, 0, 1);
	int n = 0, distinct = 0;
	uint32_t prev = 0;
	for (int i = 1; i < cave_monster_max(cave); i++) {
		const struct monster *mon = cave_monster(cave, i);
		if (!mon->race) continue;
		require(mon->drop_seed != 0);
		distinct += mon->drop_seed != prev;
		prev = mon->drop_seed;
		n++;
	}
	require(n > 3 && distinct > n / 2);
	/* and the same level has the same seeds */
	const uint32_t first = cave_monster(cave, 1)->drop_seed;
	generate(6, 0, 5);
	eq(cave_monster(cave, 1)->drop_seed, first);
	ok;
}

/* The seed, the levels seen and the monsters' drop seeds all survive a save and a load. */
static int test_save_load_keeps_seeds(void *state) {
	generate(7, 0, 1);
	generate(7, 1, 1);
	on_new_level();
	const uint32_t game = seed_game;
	const int visits = player->level_visits[7];
	const int n = cave_monster_max(cave);
	uint32_t *drop_seeds = mem_zalloc(n * sizeof(uint32_t));
	for (int i = 1; i < n; i++) drop_seeds[i] = cave_monster(cave, i)->drop_seed;
	eq(visits, 2);

	eq(savefile_save("SeedTest"), true);
	seed_game = 0;
	player->level_visits[7] = 0;
	for (int i = 1; i < n; i++) cave_monster(cave, i)->drop_seed = 0;
	eq(savefile_load("SeedTest", false), true);

	eq(seed_game, game);
	eq(player->level_visits[7], visits);
	eq(cave_monster_max(cave), n);
	for (int i = 1; i < n; i++) eq(cave_monster(cave, i)->drop_seed, drop_seeds[i]);
	mem_free(drop_seeds);
	file_delete("SeedTest");
	ok;
}

/* What a player may type as a seed. */
static int test_seed_parse(void *state) {
	uint32_t seed = 99;

	require(seed_parse("12345", &seed)); eq(seed, 12345);
	require(seed_parse("  42  ", &seed)); eq(seed, 42);
	require(seed_parse("4294967295", &seed)); eq(seed, 4294967295U);
	require(seed_parse("", &seed)); eq(seed, 0);
	require(seed_parse("   ", &seed)); eq(seed, 0);
	require(!seed_parse("0", &seed));
	require(!seed_parse("4294967296", &seed));
	require(!seed_parse("99999999999999999999", &seed));
	require(!seed_parse("12ab", &seed));
	require(!seed_parse("-5", &seed));
	require(!seed_parse("1 2", &seed));
	ok;
}

/* Read a whole text file (for comparing two catalogs). */
static char *slurp(const char *path) {
	ang_file *f = file_open(path, MODE_READ, FTYPE_TEXT);
	char line[2048];
	size_t size = 1;
	char *text = mem_zalloc(size);

	if (!f) return text;
	while (file_getl(f, line, sizeof(line))) {
		const size_t add = strlen(line) + 1;
		text = mem_realloc(text, size + add);
		memcpy(text + size - 1, line, strlen(line));
		text[size - 1 + strlen(line)] = '\n';
		text[size + add - 1] = '\0';
		size += add;
	}
	file_close(f);
	return text;
}

/* The seed catalog is the same every time, names what the levels hold, and changes with the seed. */
static int test_catalog_is_reproducible(void *state) {
	spoil_seeds("SeedCat1", 5150, 5150, 8, 2);
	Rand_state_seed(1);
	spoil_seeds("SeedCat2", 5150, 5150, 8, 2);
	spoil_seeds("SeedCat3", 5151, 5151, 8, 2);
	char *one = slurp("SeedCat1"), *two = slurp("SeedCat2"), *three = slurp("SeedCat3");

	require(strlen(one) > 500);
	require(strstr(one, "seed catalog") != NULL);
	require(strstr(one, "== 400 ft (level 8), visit 2 ==") != NULL);
	require(strstr(one, "(nothing)") == NULL);
	require(streq(one, two));
	require(!streq(one, three));

	/* In each level, the items come before the features, so no item line follows "Features" */
	bool in_features = false;
	for (const char *line = one; *line; line = strchr(line, '\n') ? strchr(line, '\n') + 1 : line + strlen(line)) {
		if (strncmp(line, "== ", 3) == 0) in_features = false;
		else if (strncmp(line, "  Features", 10) == 0) in_features = true;
		else if (in_features) require(strncmp(line, "    (", 5) != 0);
	}
	mem_free(one);
	mem_free(two);
	mem_free(three);
	file_delete("SeedCat1");
	file_delete("SeedCat2");
	file_delete("SeedCat3");
	ok;
}

/* A range of seeds is each seed's catalog in turn, under a heading of its own. */
static int test_catalog_range(void *state) {
	spoil_seeds("SeedRange", 5150, 5152, 6, 2);
	char *range = slurp("SeedRange");

	require(strstr(range, "Seeds 5150 to 5152") != NULL);
	for (uint32_t seed = 5150; seed <= 5152; seed++) {
		spoil_seeds("SeedOne", seed, seed, 6, 2);
		char *one = slurp("SeedOne");
		const char *levels = strstr(one, "== 50 ft");

		require(levels != NULL);
		require(strstr(range, format("######## Seed %u ########", seed)) != NULL);
		require(strstr(range, levels) != NULL);
		mem_free(one);
	}
	mem_free(range);
	file_delete("SeedRange");
	file_delete("SeedOne");
	ok;
}

const char *suite_name = "game/seed";
struct test tests[] = {
	{ "the same seed, depth and visit give the same level", test_same_seed_same_level },
	{ "each visit to a depth is a different level", test_visits_differ },
	{ "another seed gives another level", test_other_seed_differs },
	{ "generation leaves the game's RNG untouched", test_game_rng_untouched },
	{ "a change in the game's state stays local", test_state_changes_stay_local },
	{ "a monster drop follows its seed", test_drop_follows_seed },
	{ "new monsters get drop seeds, fixed by the level seed", test_new_monsters_get_seeds },
	{ "the seed, visits and drop seeds survive a save and load", test_save_load_keeps_seeds },
	{ "reading a typed seed", test_seed_parse },
	{ "the seed catalog is reproducible", test_catalog_is_reproducible },
	{ "a range of seeds is each seed in turn", test_catalog_range },
	{ NULL, NULL }
};
