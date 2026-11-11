/**
 * \file narsil_jni.c
 * \brief JNI glue: org.narsil.android.NarSilNative -> narsil_bridge.
 */
#include "narsil_bridge.h"

#include <android/log.h>
#include <jni.h>

#define JNI_FN(name) Java_org_narsil_android_NarSilNative_##name

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
	return (*env)->NewStringUTF(env, nb_state() == NB_EXITED ? nb_exit_message() : "");
}
