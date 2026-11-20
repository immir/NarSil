/* player/randname */

#include "unit-test.h"
#include "player.h"
#include "randname.h"
#include "z-util.h"

NOTEARDOWN

/* Pools with disjoint letters, so a name shows which list it came from. */
static const char *no_names[] = { NULL };
static const char *noldor_names[] = {
	"feanor", "fingolfin", "finarfin", "fingon", "turgon", "aredhel", "maedhros", "maglor", "celegorm", "caranthir",
	"curufin", "amrod", "amras", "galadriel", "finrod", "angrod", "aegnor", "orodreth", "idril", "elenwe", NULL };
static const char *naugrim_names[] = {
	"zzukkuz", "zzakhuk", "khazzuk", "kuzkhaz", "khazukk", "zukzuk", "kkhuzaz", "zazkhuk", "khukzaz", "zazukh",
	"ukkhaz", "khuzakk", "zzukhaz", "kuzzak", "zukhaz", NULL };
static const char *general_names[] = { "qqexqe", "qexqqxe", "qxexqqexq", NULL };

static const char **sections[RANDNAME_NUM_TYPES];
static struct player_race noldor = { .name = "Noldor" };
static struct player_race naugrim = { .name = "Naugrim" };
static struct player_race edain = { .name = "Edain" };
static struct player fake;

int setup_tests(void **state) {
	for (int i = 0; i < RANDNAME_NUM_TYPES; i++) sections[i] = no_names;
	sections[RANDNAME_TOLKIEN] = general_names;
	sections[RANDNAME_NOLDOR] = noldor_names;
	sections[RANDNAME_NAUGRIM] = naugrim_names;
	name_sections = (const char ***)sections;
	player = &fake;
	return 0;
}

static bool listed(const char **words, const char *name)
{
	for (; *words; words++) if (my_stricmp(*words, name) == 0) return true;
	return false;
}

static int test_naugrim_names_use_the_naugrim_list(void *state) {
	char buf[32];

	fake.race = &naugrim;
	for (int i = 0; i < 60; i++) {
		player_random_name(buf, sizeof(buf));
		require(strlen(buf) >= 4);
		require(strspn(buf, "ZzKkHhAaUu") == strlen(buf));
		require(!listed(naugrim_names, buf));
	}
	ok;
}

static int test_noldor_names_use_the_noldor_list(void *state) {
	char buf[32];

	fake.race = &noldor;
	for (int i = 0; i < 60; i++) {
		player_random_name(buf, sizeof(buf));
		require(strspn(buf, "ZzKkHh") == 0);
		require(isupper((unsigned char)buf[0]));
		for (const char *c = buf + 1; *c; c++) require(islower((unsigned char)*c));
		require(!listed(noldor_names, buf));
	}
	ok;
}

static int test_a_race_without_names_uses_the_general_list(void *state) {
	char buf[32];

	fake.race = &edain;
	player_random_name(buf, sizeof(buf));
	require(strspn(buf, "QqXxEe") == strlen(buf));
	fake.race = NULL;
	player_random_name(buf, sizeof(buf));
	require(strspn(buf, "QqXxEe") == strlen(buf));
	ok;
}

const char *suite_name = "player/randname";
struct test tests[] = {
	{ "naugrim", test_naugrim_names_use_the_naugrim_list },
	{ "noldor", test_noldor_names_use_the_noldor_list },
	{ "general", test_a_race_without_names_uses_the_general_list },
	{ NULL, NULL }
};
