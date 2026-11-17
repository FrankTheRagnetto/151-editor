/* Il ponte fra Kotlin (Mp3.kt) e LAME: apri, codifica a pezzi, chiudi. CBR, niente tag Xing. */
#include <jni.h>
#include <stdlib.h>
#include "lame.h"

JNIEXPORT jlong JNICALL
Java_com_the151universe_art_Mp3_apri(JNIEnv *env, jclass c, jint hz, jint canali, jint kbps) {
    lame_global_flags *g = lame_init();
    if (!g) return 0;
    lame_set_in_samplerate(g, hz);
    lame_set_out_samplerate(g, hz);
    lame_set_num_channels(g, canali);
    lame_set_mode(g, canali == 1 ? MONO : JOINT_STEREO);
    lame_set_brate(g, kbps);
    lame_set_quality(g, 2);
    lame_set_bWriteVbrTag(g, 0);
    if (lame_init_params(g) < 0) { lame_close(g); return 0; }
    return (jlong) (intptr_t) g;
}

/* pcm: campioni 16 bit intercalati, [frame] per canale; ritorna i byte scritti in out (o <0 se errore). */
JNIEXPORT jint JNICALL
Java_com_the151universe_art_Mp3_codifica(JNIEnv *env, jclass c, jlong h, jshortArray pcm, jint frame, jbyteArray out) {
    lame_global_flags *g = (lame_global_flags *) (intptr_t) h;
    jshort *p = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    jsize n = (*env)->GetArrayLength(env, out);
    int r = lame_get_num_channels(g) == 1
        ? lame_encode_buffer(g, p, p, frame, (unsigned char *) o, n)
        : lame_encode_buffer_interleaved(g, p, frame, (unsigned char *) o, n);
    (*env)->ReleaseShortArrayElements(env, pcm, p, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, o, 0);
    return r;
}

JNIEXPORT jint JNICALL
Java_com_the151universe_art_Mp3_chiudi(JNIEnv *env, jclass c, jlong h, jbyteArray out) {
    lame_global_flags *g = (lame_global_flags *) (intptr_t) h;
    int r = 0;
    if (out) {
        jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
        r = lame_encode_flush(g, (unsigned char *) o, (*env)->GetArrayLength(env, out));
        (*env)->ReleaseByteArrayElements(env, out, o, 0);
    }
    lame_close(g);
    return r;
}
