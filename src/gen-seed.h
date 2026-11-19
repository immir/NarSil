/**
 * \file gen-seed.h
 * \brief Seeded level generation: a game's seed fixes every level it generates
 */
#ifndef GEN_SEED_H
#define GEN_SEED_H

#include "h-basic.h"

struct player;

/**
 * Depths whose visits are counted (world:max-depth may not exceed it).
 */
#define SEED_DEPTHS 128

/**
 * The independent streams generation draws from.  Layout is the level's own
 * stream; each object and each monster (or group) gets a seed of its own, so
 * a draw that depends on the game's state (artefacts already made, uniques
 * dead) changes only that object or monster, never the rest of the level.
 */
enum gen_entity {
	GEN_LAYOUT = 1,
	GEN_OBJECT,
	GEN_MONSTER
};

/**
 * The game's seed: chosen at birth, saved, and shown so a game can be shared.
 */
extern uint32_t seed_game;

/**
 * A seed asked for before birth (0: choose one at random).
 */
extern uint32_t seed_game_requested;

void seed_game_choose(void);
uint32_t level_seed(uint32_t game_seed, int depth, int visit);
int level_visit(const struct player *p, int depth);
void level_visit_done(struct player *p, int depth);

void gen_rng_begin(uint32_t seed);
void gen_rng_end(void);
bool gen_rng_active(void);
void gen_entity_push(enum gen_entity kind);
void gen_entity_pop(void);

/**
 * What generation reads from the game besides the seed: artefacts made, greater
 * vaults used, uniques left, the forge counters.  Saved and restored so levels
 * can be generated as if from the same point (the seed catalog, tests).
 */
struct gen_baseline;
struct gen_baseline *gen_baseline_save(const struct player *p);
void gen_baseline_restore(struct player *p, const struct gen_baseline *b);
void gen_baseline_free(struct gen_baseline *b);

void rng_push_seed(uint32_t seed);
void rng_pop_seed(void);

#endif /* !GEN_SEED_H */
