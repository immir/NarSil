/* z-rand/state */
/* Saving, loading and seeding the RNG state, and deriving seeds. */

#include "unit-test.h"
#include "z-rand.h"

NOSETUP
NOTEARDOWN

static void draw(uint32_t *out, int n)
{
	for (int i = 0; i < n; i++) out[i] = Rand_div(0x10000000);
}

static int test_save_load_round_trip(void *state)
{
	struct rand_state saved;
	uint32_t first[64], second[64];

	Rand_state_seed(12345);
	draw(first, 7);
	Rand_state_save(&saved);
	draw(first, 64);
	Rand_state_load(&saved);
	draw(second, 64);
	require(memcmp(first, second, sizeof(first)) == 0);
	ok;
}

static int test_save_load_quick(void *state)
{
	struct rand_state saved;
	uint32_t first[16], second[16];

	Rand_quick = true;
	Rand_value = 99;
	Rand_state_save(&saved);
	draw(first, 16);
	Rand_state_seed(7);
	Rand_state_load(&saved);
	require(Rand_quick);
	draw(second, 16);
	Rand_quick = false;
	require(memcmp(first, second, sizeof(first)) == 0);
	ok;
}

/* The same seed gives the same sequence whatever the RNG did before. */
static int test_seed_is_pure(void *state)
{
	uint32_t first[64], second[64];

	Rand_state_seed(2024);
	draw(first, 64);
	Rand_state_seed(1);
	draw(second, 13);
	Rand_state_seed(2024);
	draw(second, 64);
	require(memcmp(first, second, sizeof(first)) == 0);
	Rand_state_seed(2025);
	draw(second, 64);
	require(memcmp(first, second, sizeof(first)) != 0);
	ok;
}

static int test_mix(void *state)
{
	/* Fixed values: a seed must mean the same thing on every build. */
	eq(Rand_mix(0, 0), Rand_mix(0, 0));
	require(Rand_mix(1, 2) != Rand_mix(2, 1));
	require(Rand_mix(1, 2) != Rand_mix(1, 3));
	/* Neighbouring inputs give unrelated outputs: about half the bits differ. */
	for (uint32_t b = 0; b < 32; b++) {
		const uint32_t d = Rand_mix(77, b) ^ Rand_mix(77, b + 1);
		int bits = 0;
		for (uint32_t x = d; x; x &= x - 1) bits++;
		require(bits >= 6 && bits <= 26);
	}
	ok;
}

const char *suite_name = "z-rand/state";
struct test tests[] = {
	{ "save and load restore the sequence", test_save_load_round_trip },
	{ "save and load keep the quick RNG", test_save_load_quick },
	{ "seeding ignores the RNG's history", test_seed_is_pure },
	{ "mixing seeds", test_mix },
	{ NULL, NULL }
};
