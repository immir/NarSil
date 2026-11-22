/* player/birth */

#include "unit-test.h"
#include "unit-test-data.h"
#include "player-birth.h"
#include "player-quest.h"
#include "player.h"
#include "player-timed.h"

int setup_tests(void **state) {
	struct player *p = mem_zalloc(sizeof *p);
	z_info = mem_zalloc(sizeof(struct angband_constants));
	z_info->pack_size = 23;
	z_info->quest_max = 1;
	z_info->start_exp = 5000;
	z_info->fixed_exp = 50000;
	player_init(p);
	*state = p;
	return 0;
}

int teardown_tests(void *state) {
	struct player *p = state;
	mem_free(z_info);
	mem_free(p->upkeep->inven);
	mem_free(p->upkeep);
	mem_free(p->timed);
	mem_free(p->obj_k->brands);
	mem_free(p->obj_k->slays);
	mem_free(p->obj_k);
	mem_free(state);
	return 0;
}

static int test_generate0(void *state) {
	struct player *p = state;
	player_generate(p, &test_race, &test_house, &test_sex, false);
	ptreq(p->race, &test_race);
	ptreq(p->house, &test_house);
	ptreq(p->sex, &test_sex);
	ok;
}

static int test_start_exp(void *state) {
	struct player *p = state;
	p->opts.opt[OPT_birth_fixed_exp] = false;
	p->timed[TMD_FOOD] = 0;
	player_generate(p, &test_race, &test_house, &test_sex, false);
	eq(p->exp, 5000);
	eq(p->new_exp, 5000);
	ok;
}

static int test_fixed_exp(void *state) {
	struct player *p = state;
	p->opts.opt[OPT_birth_fixed_exp] = true;
	p->timed[TMD_FOOD] = 0;
	player_generate(p, &test_race, &test_house, &test_sex, false);
	eq(p->exp, 50000);
	eq(p->new_exp, 50000);
	player_exp_gain(p, 1234);
	eq(p->exp, 50000);
	eq(p->new_exp, 50000);
	p->opts.opt[OPT_birth_fixed_exp] = false;
	ok;
}

const char *suite_name = "player/birth";
struct test tests[] = {
	{ "generate0", test_generate0 },
	{ "start_exp", test_start_exp },
	{ "fixed_exp", test_fixed_exp },
	{ NULL, NULL }
};
