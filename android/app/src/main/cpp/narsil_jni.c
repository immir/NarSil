/**
 * \file narsil_jni.c
 * \brief JNI glue: org.narsil.android.NarSilNative -> narsil_bridge.
 */
#include "narsil_bridge.h"

#include <android/log.h>
#include <assert.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define JNI_FN(name) Java_org_narsil_android_NarSilNative_##name

/*
 * NewStringUTF() aborts the process on anything that is not valid *modified*
 * UTF-8 (e.g. a multi-byte character cut off by a buffer limit, or a 4-byte
 * sequence).  Decode ourselves, replacing what is invalid with U+FFFD.
 */
static jstring new_string(JNIEnv *env, const char *s)
{
	const size_t n = strlen(s);
	jchar *const out = malloc((n + 1) * sizeof(jchar) * 2);
	if (!out) return (*env)->NewStringUTF(env, "");
	size_t len = 0;
	for (size_t i = 0; i < n;) {
		const unsigned char c = (unsigned char)s[i];
		uint32_t cp;
		size_t k;
		if (c < 0x80) { cp = c; k = 1; }
		else if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; k = 2; }
		else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; k = 3; }
		else if ((c & 0xF8) == 0xF0) { cp = c & 0x07; k = 4; }
		else { out[len++] = 0xFFFD; i++; continue; }
		bool ok = i + k <= n;
		for (size_t j = 1; ok && j < k; j++) {
			const unsigned char cc = (unsigned char)s[i + j];
			ok = (cc & 0xC0) == 0x80;
			cp = (cp << 6) | (cc & 0x3F);
		}
		if (!ok || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) { out[len++] = 0xFFFD; i++; continue; }
		if (cp >= 0x10000) {
			cp -= 0x10000;
			out[len++] = (jchar)(0xD800 | (cp >> 10));
			out[len++] = (jchar)(0xDC00 | (cp & 0x3FF));
		} else {
			out[len++] = (jchar)cp;
		}
		i += k;
	}
	const jstring str = (*env)->NewString(env, out, (jsize)len);
	free(out);
	return str;
}

static void log_android(const char *msg)
{
	__android_log_write(ANDROID_LOG_INFO, "narsil", msg);
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeStart)(JNIEnv *env, jclass cls,
		jstring lib_dir, jstring data_dir, jstring player_name,
		jint cols, jint rows)
{
	(void)cls;
	const char *const lib = (*env)->GetStringUTFChars(env, lib_dir, NULL);
	const char *const data = (*env)->GetStringUTFChars(env, data_dir, NULL);
	const char *const name = (*env)->GetStringUTFChars(env, player_name, NULL);
	const nb_config cfg = {
		.lib_dir = lib, .data_dir = data, .player_name = name,
		.cols = cols, .rows = rows, .log = log_android,
	};
	const bool ok = nb_start(&cfg);
	(*env)->ReleaseStringUTFChars(env, player_name, name);
	(*env)->ReleaseStringUTFChars(env, data_dir, data);
	(*env)->ReleaseStringUTFChars(env, lib_dir, lib);
	return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativePushKey)(JNIEnv *env, jclass cls,
		jint code, jint mods)
{
	(void)env; (void)cls;
	nb_push_key((uint32_t)code, (uint8_t)mods);
}

JNIEXPORT void JNICALL JNI_FN(nativePushMouse)(JNIEnv *env, jclass cls,
		jint x, jint y, jint button, jint mods)
{
	(void)env; (void)cls;
	if (button < 1 || button > 15 || mods < 0 || mods > 0x0F) return;
	nb_push_mouse(x, y, button, (uint8_t)mods);
}

JNIEXPORT void JNICALL JNI_FN(nativeRequestSave)(JNIEnv *env, jclass cls)
{
	(void)env; (void)cls;
	nb_request_save();
}

JNIEXPORT jlong JNICALL JNI_FN(nativeSaveCount)(JNIEnv *env, jclass cls)
{
	(void)env; (void)cls;
	return (jlong)nb_save_count();
}

JNIEXPORT jlong JNICALL JNI_FN(nativeGeneration)(JNIEnv *env, jclass cls)
{
	(void)env; (void)cls;
	return (jlong)nb_generation();
}

/* cells: 2 * cols * rows ints; cursor: [x, y, visible]. */
JNIEXPORT jlong JNICALL JNI_FN(nativeSnapshot)(JNIEnv *env, jclass cls,
		jintArray cells, jintArray cursor)
{
	(void)cls;
	const jsize n = (*env)->GetArrayLength(env, cells);
	if ((size_t)n < 2u * (size_t)nb_cols() * (size_t)nb_rows()
			|| (*env)->GetArrayLength(env, cursor) < 3)
		return -1;

	jint *const buf = (*env)->GetIntArrayElements(env, cells, NULL);
	int cx, cy;
	bool vis;
	const uint64_t gen = nb_snapshot(buf, (size_t)n, &cx, &cy, &vis);
	(*env)->ReleaseIntArrayElements(env, cells, buf, 0);

	const jint c[3] = { cx, cy, vis ? 1 : 0 };
	(*env)->SetIntArrayRegion(env, cursor, 0, 3, c);
	return (jlong)gen;
}

JNIEXPORT jint JNICALL JNI_FN(nativeCols)(JNIEnv *env, jclass cls)
{
	(void)env; (void)cls;
	return nb_cols();
}

JNIEXPORT jint JNICALL JNI_FN(nativeRows)(JNIEnv *env, jclass cls)
{
	(void)env; (void)cls;
	return nb_rows();
}

JNIEXPORT jintArray JNICALL JNI_FN(nativePalette)(JNIEnv *env, jclass cls)
{
	(void)cls;
	uint32_t rgb[256];
	const size_t n = nb_palette(rgb, sizeof(rgb) / sizeof(rgb[0]));
	jint argb[256];
	for (size_t i = 0; i < n; i++) argb[i] = (jint)(0xFF000000u | rgb[i]);
	jintArray out = (*env)->NewIntArray(env, (jsize)n);
	if (out) (*env)->SetIntArrayRegion(env, out, 0, (jsize)n, argb);
	return out;
}

JNIEXPORT jint JNICALL JNI_FN(nativeState)(JNIEnv *env, jclass cls)
{
	(void)env; (void)cls;
	return (jint)nb_state();
}

JNIEXPORT jstring JNICALL JNI_FN(nativeExitMessage)(JNIEnv *env, jclass cls)
{
	(void)cls;
	return new_string(env, nb_state() == NB_EXITED ? nb_exit_message() : "");
}

/* ---- Native map / commands ---- */

JNIEXPORT jlong JNICALL JNI_FN(nativeMapGeneration)(JNIEnv *env, jclass cls)
{
	(void)env; (void)cls;
	return (jlong)nb_map_generation();
}

/* meta: NB_META_COUNT ints; cells: 2 * width * height ints, or null for meta only. */
JNIEXPORT jlong JNICALL JNI_FN(nativeMapSnapshot)(JNIEnv *env, jclass cls,
		jintArray meta, jintArray cells)
{
	(void)cls;
	if ((*env)->GetArrayLength(env, meta) < NB_META_COUNT) return -1;
	int32_t m[NB_META_COUNT];
	jint *const buf = cells ? (*env)->GetIntArrayElements(env, cells, NULL) : NULL;
	const size_t n = cells ? (size_t)(*env)->GetArrayLength(env, cells) : 0;
	const uint64_t gen = nb_map_snapshot(m, buf, n);
	if (buf) (*env)->ReleaseIntArrayElements(env, cells, buf, 0);
	(*env)->SetIntArrayRegion(env, meta, 0, NB_META_COUNT, m);
	return (jlong)gen;
}

JNIEXPORT void JNICALL JNI_FN(nativeRequestPreview)(JNIEnv *env, jclass cls, jint x, jint y)
{
	(void)env; (void)cls;
	nb_request_preview(x, y);
}

JNIEXPORT void JNICALL JNI_FN(nativeClearPreview)(JNIEnv *env, jclass cls)
{
	(void)env; (void)cls;
	nb_clear_preview();
}

JNIEXPORT void JNICALL JNI_FN(nativeRequestMove)(JNIEnv *env, jclass cls, jint dir, jboolean run)
{
	(void)env; (void)cls;
	nb_request_move(dir, run);
}

JNIEXPORT void JNICALL JNI_FN(nativeRequestTravel)(JNIEnv *env, jclass cls, jint x, jint y)
{
	(void)env; (void)cls;
	nb_request_travel(x, y);
}

JNIEXPORT jlong JNICALL JNI_FN(nativePathGeneration)(JNIEnv *env, jclass cls)
{
	(void)env; (void)cls;
	return (jlong)nb_path_generation();
}

/* xy: (x, y) pairs, target first; returns the number of pairs written. */
JNIEXPORT jint JNICALL JNI_FN(nativePath)(JNIEnv *env, jclass cls, jintArray xy)
{
	(void)cls;
	const jsize len = (*env)->GetArrayLength(env, xy);
	jint *const buf = (*env)->GetIntArrayElements(env, xy, NULL);
	const size_t n = nb_path(buf, (size_t)len / 2);
	(*env)->ReleaseIntArrayElements(env, xy, buf, 0);
	return (jint)n;
}

/* ---- Native prompts ---- */

JNIEXPORT jlong JNICALL JNI_FN(nativePromptGeneration)(JNIEnv *env, jclass cls)
{
	(void)env; (void)cls;
	return (jlong)nb_prompt_generation();
}

/* "<kind>\n<prompt text>"; kind 0 = none.  See nb_prompt(). */
JNIEXPORT jstring JNICALL JNI_FN(nativePrompt)(JNIEnv *env, jclass cls)
{
	(void)cls;
	enum { HEAD = 4 };  /* up to three digits and the newline */
	static char text[NB_PROMPT_CAP + HEAD];
	static char body[NB_PROMPT_CAP];
	const enum nb_prompt_kind kind = nb_prompt(body, sizeof(body));
	assert(kind >= 0 && kind < 1000);
	snprintf(text, sizeof(text), "%d\n%s", (int)kind, body);
	return new_string(env, text);
}

JNIEXPORT void JNICALL JNI_FN(nativeAnswerItem)(JNIEnv *env, jclass cls, jint index)
{
	(void)env; (void)cls;
	nb_answer_item(index);
}

JNIEXPORT void JNICALL JNI_FN(nativeAnswerText)(JNIEnv *env, jclass cls, jstring text)
{
	(void)cls;
	const char *const s = (*env)->GetStringUTFChars(env, text, NULL);
	if (!s) return;
	nb_answer_text(s);
	(*env)->ReleaseStringUTFChars(env, text, s);
}

JNIEXPORT void JNICALL JNI_FN(nativeAnswer)(JNIEnv *env, jclass cls, jint value)
{
	(void)env; (void)cls;
	nb_answer(value);
}

/* ---- Message log ---- */

JNIEXPORT jlong JNICALL JNI_FN(nativeMessagesGeneration)(JNIEnv *env, jclass cls)
{
	(void)env; (void)cls;
	return (jlong)nb_messages_generation();
}

/* attr \t count \t text per line, oldest first. */
JNIEXPORT jstring JNICALL JNI_FN(nativeMessages)(JNIEnv *env, jclass cls)
{
	(void)cls;
	static char text[NB_MESSAGES_CAP];
	nb_messages(text, sizeof(text));
	return new_string(env, text);
}

JNIEXPORT void JNICALL JNI_FN(nativeRequestAction)(JNIEnv *env, jclass cls, jint action)
{
	(void)env; (void)cls;
	if (action >= 0 && action < NB_ACT_COUNT) nb_request_action((enum nb_action)action);
}

/* ---- Inspect / look list ---- */

JNIEXPORT void JNICALL JNI_FN(nativeRequestInspect)(JNIEnv *env, jclass cls, jint x, jint y)
{
	(void)env; (void)cls;
	nb_request_inspect(x, y);
}

JNIEXPORT void JNICALL JNI_FN(nativeRequestLookList)(JNIEnv *env, jclass cls)
{
	(void)env; (void)cls;
	nb_request_look_list();
}

JNIEXPORT jlong JNICALL JNI_FN(nativeInfoGeneration)(JNIEnv *env, jclass cls)
{
	(void)env; (void)cls;
	return (jlong)nb_info_generation();
}

JNIEXPORT jstring JNICALL JNI_FN(nativeInfo)(JNIEnv *env, jclass cls)
{
	(void)cls;
	static char text[32 * 1024];
	nb_info(text, sizeof(text));
	return new_string(env, text);
}

JNIEXPORT jstring JNICALL JNI_FN(nativeFoeName)(JNIEnv *env, jclass cls)
{
	(void)cls;
	char name[80];
	nb_foe_name(name, sizeof(name));
	return new_string(env, name);
}
