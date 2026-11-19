/**
 * \file gen-seed.c
 * \brief Seeded level generation: a game's seed fixes every level it generates
 *
 * Each visit to a depth has its own level seed, made from the game's seed, the
 * depth and how many levels the player has already seen there.  Generation runs
 * on streams derived from that seed and then puts the game's RNG back exactly as
 * it was, so combat and the rest of play are untouched and a level depends only
 * on the seed and the state of the game when it is reached.
 *
 * This work is free software; you can redistribute it and/or modify it
 * under the terms of either:
 *
 * a) the GNU General Public License as published by the Free Software
 *    Foundation, version 2, or
 *
 * b) the "Angband licence":
 *    This software may be copied and distributed for educational, research,
 *    and not for profit purposes provided that this copyright and statement
 *    are included in all such copies.  Other copyrights may also apply.
 */
#include "angband.h"
#include "gen-seed.h"
#include "init.h"
#include "monster.h"
#include "obj-util.h"
#include "player.h"

uint32_t seed_game;
uint32_t seed_game_requested;

/* Saved RNG states for nested seeded computations (objects within monsters, a
 * drop within a death); a push made while not generating saves nothing. */
#define RNG_STACK_MAX 16
static struct rand_state rng_stack[RNG_STACK_MAX];
static bool rng_stack_saved[RNG_STACK_MAX];
static int rng_stack_depth;

static struct {
	bool active;
	uint32_t seed;
	uint32_t count[GEN_MONSTER + 1];
	struct rand_state transient;
} gen;

/**
 * Settle the game's seed at birth: the one asked for, or a random nonzero one.
 */
void seed_game_choose(void)
{
	uint32_t seed = seed_game_requested;

	while (!seed) seed = (Rand_div(0x10000) << 16) | Rand_div(0x10000);
	seed_game = seed;
	seed_game_requested = 0;
}

/**
 * Read a seed typed by a player: decimal digits, 1 to 4294967295.  Blank is allowed and means
 * "choose one" (*seed is 0); anything else, including 0 and an overflow, is refused.
 */
bool seed_parse(const char *text, uint32_t *seed)
{
	uint64_t value = 0;

	while (*text == ' ') text++;
	*seed = 0;
	if (!*text) return true;
	for (; *text >= '0' && *text <= '9'; text++) {
		value = value * 10 + (uint64_t)(*text - '0');
		if (value > UINT32_MAX) return false;
	}
	while (*text == ' ') text++;
	if (*text || !value) return false;
	*seed = (uint32_t)value;
	return true;
}

uint32_t level_seed(uint32_t game_seed, int depth, int visit)
{
	assert(depth >= 0 && depth < SEED_DEPTHS && visit >= 0);
	return Rand_mix(Rand_mix(game_seed, (uint32_t)depth), (uint32_t)visit);
}

/**
 * How many levels the player has already been given at this depth.
 */
int level_visit(const struct player *p, int depth)
{
	assert(depth >= 0 && depth < SEED_DEPTHS);
	return p->level_visits[depth];
}

void level_visit_done(struct player *p, int depth)
{
	assert(depth >= 0 && depth < SEED_DEPTHS);
	if (p->level_visits[depth] < UINT8_MAX) p->level_visits[depth]++;
}

/**
 * Start generating a level from seed: the game's RNG is put aside until
 * gen_rng_end(), and the level's layout stream takes over.
 */
void gen_rng_begin(uint32_t seed)
{
	assert(!gen.active && rng_stack_depth == 0);
	Rand_state_save(&gen.transient);
	gen.active = true;
	gen.seed = seed;
	memset(gen.count, 0, sizeof(gen.count));
	Rand_state_seed(Rand_mix(seed, GEN_LAYOUT));
}

void gen_rng_end(void)
{
	assert(gen.active && rng_stack_depth == 0);
	Rand_state_load(&gen.transient);
	gen.active = false;
}

bool gen_rng_active(void)
{
	return gen.active;
}

static void rng_push(bool seeded, uint32_t seed)
{
	assert(rng_stack_depth < RNG_STACK_MAX);
	rng_stack_saved[rng_stack_depth] = seeded;
	if (seeded) {
		Rand_state_save(&rng_stack[rng_stack_depth]);
		Rand_state_seed(seed);
	}
	rng_stack_depth++;
}

static void rng_pop(void)
{
	assert(rng_stack_depth > 0);
	rng_stack_depth--;
	if (rng_stack_saved[rng_stack_depth]) Rand_state_load(&rng_stack[rng_stack_depth]);
}

/**
 * Give the next object or monster made during generation a stream of its own
 * (nothing happens outside generation).  Every push is matched by a pop.
 */
void gen_entity_push(enum gen_entity kind)
{
	assert(kind > GEN_LAYOUT && kind <= GEN_MONSTER);
	if (!gen.active) {
		rng_push(false, 0);
		return;
	}
	rng_push(true, Rand_mix(Rand_mix(gen.seed, kind), gen.count[kind]++));
}

void gen_entity_pop(void)
{
	rng_pop();
}

/**
 * A seed for a new monster's drop, from the stream in use (nonzero: 0 means "none").
 */
uint32_t gen_drop_seed(void)
{
	const uint32_t seed = (Rand_div(0x10000) << 16) | Rand_div(0x10000);

	return seed ? seed : 1;
}

/**
 * Run what follows on its own stream from seed (a monster's drop), until
 * rng_pop_seed() puts the RNG back.
 */
void rng_push_seed(uint32_t seed)
{
	rng_push(true, seed);
}

void rng_pop_seed(void)
{
	rng_pop();
}

struct gen_baseline {
	bool *artifact_created;
	bool *vault_made;
	uint8_t *unique_left;
	uint8_t num_artefacts;
	bool unique_forge_made;
	uint16_t forge_drought, forge_count;
	uint8_t fixed_forge_count;
};

struct gen_baseline *gen_baseline_save(const struct player *p)
{
	struct gen_baseline *b = mem_zalloc(sizeof(*b));

	b->artifact_created = mem_zalloc(z_info->a_max * sizeof(bool));
	b->vault_made = mem_zalloc(z_info->v_max * sizeof(bool));
	b->unique_left = mem_zalloc(z_info->r_max * sizeof(uint8_t));
	for (int i = 0; i < z_info->a_max; i++) b->artifact_created[i] = aup_info[i].created;
	memcpy(b->vault_made, p->vaults, z_info->v_max * sizeof(bool));
	for (int i = 0; i < z_info->r_max; i++) b->unique_left[i] = r_info[i].max_num;
	b->num_artefacts = p->num_artefacts;
	b->unique_forge_made = p->unique_forge_made;
	b->forge_drought = p->forge_drought;
	b->forge_count = p->forge_count;
	b->fixed_forge_count = p->fixed_forge_count;
	return b;
}

void gen_baseline_restore(struct player *p, const struct gen_baseline *b)
{
	for (int i = 0; i < z_info->a_max; i++) aup_info[i].created = b->artifact_created[i];
	memcpy(p->vaults, b->vault_made, z_info->v_max * sizeof(bool));
	for (int i = 0; i < z_info->r_max; i++) r_info[i].max_num = b->unique_left[i];
	p->num_artefacts = b->num_artefacts;
	p->unique_forge_made = b->unique_forge_made;
	p->forge_drought = b->forge_drought;
	p->forge_count = b->forge_count;
	p->fixed_forge_count = b->fixed_forge_count;
}

void gen_baseline_free(struct gen_baseline *b)
{
	mem_free(b->artifact_created);
	mem_free(b->vault_made);
	mem_free(b->unique_left);
	mem_free(b);
}
