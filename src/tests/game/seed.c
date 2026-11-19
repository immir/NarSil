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
	wipe_mon_list(cave, player);
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

const char *suite_name = "game/seed";
struct test tests[] = {
	{ "the same seed, depth and visit give the same level", test_same_seed_same_level },
	{ "each visit to a depth is a different level", test_visits_differ },
	{ "another seed gives another level", test_other_seed_differs },
	{ "generation leaves the game's RNG untouched", test_game_rng_untouched },
	{ "a change in the game's state stays local", test_state_changes_stay_local },
	{ NULL, NULL }
};
