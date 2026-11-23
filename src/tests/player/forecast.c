/* player/forecast */
/* The forecast's exact odds against attacks simulated with the game's dice rules. */

#include "unit-test.h"
#include "player-forecast.h"
#include "z-rand.h"

#include <math.h>

NOSETUP
NOTEARDOWN

#define TRIALS 400000
#define MAX_MARGIN 80

/* The crit_bonus() rule: a die for every (separation + weight) / 10 of margin */
static void crit_table(int separation, int weight, int crits[MAX_MARGIN + 1])
{
	for (int m = 0; m <= MAX_MARGIN; m++) crits[m] = MAX((m * 10 + 4) / (separation + weight), 0);
}

/* One attack as hit_roll() and the attack code roll it */
static int simulate(const struct forecast_roll *r, const int *crits, bool *hit, bool *crit)
{
	int margin = 0;

	for (int attempt = 0; attempt < (r->accurate ? 2 : 1); attempt++) {
		int a = randint1(20) + r->att;
		if (r->cursed) {
			const int second = randint1(20) + r->att;	/* not inside MIN(), which evaluates twice */
			a = MIN(a, second);
		}
		margin = a - (randint1(20) + r->evn);
		if (margin > 0) break;
	}
	*hit = margin > 0;
	*crit = false;
	if (!*hit) return 0;
	const int c = r->cumbersome ? 0 : crits[margin];
	*crit = c > 0;
	const int dam = damroll(r->dd + r->slay + c + r->extra_crit, r->ds);
	const int prt = (damroll(r->pd, r->ps) * r->prt_pct) / 100;
	const int net = MAX(dam - prt, 0);
	return net == 0 && r->puncture ? 5 : net;
}

/* The forecast's figures lie within five standard errors of the simulation's */
static bool agrees(const struct forecast_roll *r, const int *crits)
{
	struct attack_forecast f;
	double hits = 0, crit_hits = 0, sum = 0, sum2 = 0;

	forecast_odds(r, crits, &f);
	for (int i = 0; i < TRIALS; i++) {
		bool hit, crit;
		const int d = simulate(r, crits, &hit, &crit);
		hits += hit;
		crit_hits += crit;
		sum += d;
		sum2 += (double) d * d;
	}
	const double mean = sum / TRIALS;
	const double sd = sqrt(MAX(sum2 / TRIALS - mean * mean, 0.0) / TRIALS);
	const double p_err = 0.5 / sqrt((double) TRIALS);
	const bool ok_hit = fabs(hits / TRIALS - f.hit) < 5 * p_err;
	const bool ok_crit = fabs(crit_hits / TRIALS - f.crit) < 5 * p_err;
	const bool ok_dmg = fabs(mean - f.dmg) < 5 * sd + 1e-9;
	if (!(ok_hit && ok_crit && ok_dmg)) {
		printf("      forecast hit %.4f crit %.4f dmg %.3f; simulated %.4f %.4f %.3f\n",
			f.hit, f.crit, f.dmg, hits / TRIALS, crit_hits / TRIALS, mean);
	}
	return ok_hit && ok_crit && ok_dmg;
}

static int test_plain_melee(void *state)
{
	int crits[MAX_MARGIN + 1];
	const struct forecast_roll r = { .att = 8, .evn = 4, .dd = 2, .ds = 7, .pd = 1, .ps = 6, .prt_pct = 100 };

	Rand_state_seed(1);
	crit_table(70, 30, crits);
	require(agrees(&r, crits));
	ok;
}

static int test_modifiers(void *state)
{
	int crits[MAX_MARGIN + 1];
	const struct forecast_roll cursed = { .att = 12, .evn = 10, .cursed = true, .accurate = true,
		.dd = 1, .ds = 10, .slay = 1, .pd = 2, .ps = 4, .prt_pct = 50 };
	const struct forecast_roll heavy = { .att = 3, .evn = 15, .cumbersome = true,
		.dd = 3, .ds = 5, .pd = 4, .ps = 4, .prt_pct = 100 };
	const struct forecast_roll shot = { .att = 20, .evn = 2, .extra_crit = 1, .dd = 2, .ds = 9,
		.pd = 3, .ps = 6, .prt_pct = 0, .puncture = true };
	const struct forecast_roll blocked = { .att = 5, .evn = 5, .dd = 1, .ds = 3, .pd = 5, .ps = 8,
		.prt_pct = 100, .puncture = true };

	Rand_state_seed(2);
	crit_table(60, 20, crits);
	require(agrees(&cursed, crits));
	require(agrees(&heavy, crits));
	require(agrees(&shot, crits));
	require(agrees(&blocked, crits));
	ok;
}

static int test_edges(void *state)
{
	int crits[MAX_MARGIN + 1];
	struct attack_forecast f;
	const struct forecast_roll sure = { .att = 40, .evn = 0, .dd = 1, .ds = 1, .prt_pct = 100 };
	const struct forecast_roll hopeless = { .att = 0, .evn = 40, .dd = 1, .ds = 4, .prt_pct = 100 };

	crit_table(70, 0, crits);
	forecast_odds(&sure, crits, &f);
	require(fabs(f.hit - 1.0) < 1e-12);
	forecast_odds(&hopeless, crits, &f);
	require(f.hit == 0.0 && f.dmg == 0.0 && f.dmg_hit == 0.0);
	ok;
}

const char *suite_name = "player/forecast";
struct test tests[] = {
	{ "plain melee matches simulated attacks", test_plain_melee },
	{ "curses, accuracy, slays, sharpness, criticals and puncture", test_modifiers },
	{ "certain hits and certain misses", test_edges },
	{ NULL, NULL }
};
