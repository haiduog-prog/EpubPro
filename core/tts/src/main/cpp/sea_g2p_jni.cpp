#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include "sea_g2p.h"

namespace {

constexpr const char* kTag = "SeaG2pJni";

std::string toStdUtf8(JNIEnv* env, jbyteArray bytes) {
    if (bytes == nullptr) {
        return {};
    }
    const jsize len = env->GetArrayLength(bytes);
    if (len <= 0) {
        return {};
    }
    std::string out(static_cast<size_t>(len), '\0');
    env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte*>(out.data()));
    return out;
}

jbyteArray toJavaBytes(JNIEnv* env, const char* text) {
    if (text == nullptr) {
        return nullptr;
    }
    const size_t len = std::strlen(text);
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(len));
    if (arr != nullptr && len > 0) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte*>(text));
    }
    return arr;
}

void throwRuntime(JNIEnv* env, const char* fallback) {
    const char* err = sea_g2p_last_error();
    const char* msg = (err != nullptr && err[0] != '\0') ? err : fallback;
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", msg);
    jclass exClass = env->FindClass("java/lang/IllegalStateException");
    if (exClass != nullptr) {
        env->ThrowNew(exClass, msg);
    }
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_epubpro_core_tts_SeaG2pNative_abiVersion(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jint>(sea_g2p_abi_version());
}

JNIEXPORT jlong JNICALL
Java_com_epubpro_core_tts_SeaG2pNative_open(JNIEnv* env, jobject /*thiz*/, jbyteArray dictPathUtf8) {
    const std::string path = toStdUtf8(env, dictPathUtf8);
    if (path.empty()) {
        throwRuntime(env, "dict_path is empty");
        return 0;
    }
    sea_g2p* handle = sea_g2p_open(path.c_str());
    if (handle == nullptr) {
        throwRuntime(env, "sea_g2p_open failed");
        return 0;
    }
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL
Java_com_epubpro_core_tts_SeaG2pNative_close(JNIEnv* /*env*/, jobject /*thiz*/, jlong handlePtr) {
    if (handlePtr != 0) {
        sea_g2p_close(reinterpret_cast<sea_g2p*>(handlePtr));
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_epubpro_core_tts_SeaG2pNative_phonemize(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handlePtr,
    jbyteArray textUtf8,
    jboolean puncNorm
) {
    if (handlePtr == 0) {
        throwRuntime(env, "sea_g2p handle is closed");
        return nullptr;
    }
    const std::string input = toStdUtf8(env, textUtf8);
    char* result = sea_g2p_phonemize(
        reinterpret_cast<const sea_g2p*>(handlePtr),
        input.c_str(),
        puncNorm ? 1 : 0
    );
    if (result == nullptr) {
        throwRuntime(env, "sea_g2p_phonemize returned null");
        return nullptr;
    }
    jbyteArray out = toJavaBytes(env, result);
    sea_g2p_string_free(result);
    return out;
}

JNIEXPORT jbyteArray JNICALL
Java_com_epubpro_core_tts_SeaG2pNative_normalize(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handlePtr,
    jbyteArray textUtf8,
    jboolean puncNorm
) {
    if (handlePtr == 0) {
        throwRuntime(env, "sea_g2p handle is closed");
        return nullptr;
    }
    const std::string input = toStdUtf8(env, textUtf8);
    char* result = sea_g2p_normalize(
        reinterpret_cast<const sea_g2p*>(handlePtr),
        input.c_str(),
        puncNorm ? 1 : 0
    );
    if (result == nullptr) {
        throwRuntime(env, "sea_g2p_normalize returned null");
        return nullptr;
    }
    jbyteArray out = toJavaBytes(env, result);
    sea_g2p_string_free(result);
    return out;
}

JNIEXPORT jbyteArray JNICALL
Java_com_epubpro_core_tts_SeaG2pNative_puncNorm(
    JNIEnv* env,
    jobject /*thiz*/,
    jbyteArray textUtf8
) {
    const std::string input = toStdUtf8(env, textUtf8);
    char* result = sea_g2p_punc_norm(input.c_str());
    if (result == nullptr) {
        throwRuntime(env, "sea_g2p_punc_norm returned null");
        return nullptr;
    }
    jbyteArray out = toJavaBytes(env, result);
    sea_g2p_string_free(result);
    return out;
}

}  // extern "C"
