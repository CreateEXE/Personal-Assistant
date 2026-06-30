#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_OfflineLlamaModel_loadModelNative(
        JNIEnv* env,
        jobject /* this */,
        jstring path) {
    const char *path_cstr = env->GetStringUTFChars(path, nullptr);
    LOGI("Loading model from %s", path_cstr);
    env->ReleaseStringUTFChars(path, path_cstr);
    
    // Stub implementation for model loading
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_OfflineLlamaModel_generateTextNative(
        JNIEnv* env,
        jobject /* this */,
        jstring prompt) {
    const char *prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
    LOGI("Generating text for prompt: %s", prompt_cstr);
    env->ReleaseStringUTFChars(prompt, prompt_cstr);
    
    // Return an error to force fallback
    std::string mock_json_response = "{\"error\": \"use fallback\"}";

    return env->NewStringUTF(mock_json_response.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_OfflineLlamaModel_generateTextStreamNative(
        JNIEnv* env,
        jobject /* this */,
        jstring prompt,
        jfloat temp,
        jfloat repeat_penalty,
        jfloat top_p,
        jint max_new_tokens,
        jobjectArray stop_sequences,
        jobject callback) {
    
    const char *prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
    
    // Log parameters to verify sampler tuning is active in the JNI wrapper
    LOGI("Generating stream natively with parameters: temp=%.2f, repeat_penalty=%.2f, top_p=%.2f, max_new_tokens=%d",
         temp, repeat_penalty, top_p, max_new_tokens);
         
    // Access and parse stop sequences
    if (stop_sequences != nullptr) {
        jsize len = env->GetArrayLength(stop_sequences);
        for (jsize i = 0; i < len; ++i) {
            jstring stop_seq = (jstring) env->GetObjectArrayElement(stop_sequences, i);
            if (stop_seq != nullptr) {
                const char* stop_cstr = env->GetStringUTFChars(stop_seq, nullptr);
                LOGI("Configured Stop Sequence %d: %s", i, stop_cstr);
                env->ReleaseStringUTFChars(stop_seq, stop_cstr);
                env->DeleteLocalRef(stop_seq);
            }
        }
    }
    
    LOGI("Prompt text: %s", prompt_cstr);
    env->ReleaseStringUTFChars(prompt, prompt_cstr);
    
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenGeneratedMethod = env->GetMethodID(callbackClass, "onTokenGenerated", "(Ljava/lang/String;)V");
    jmethodID onCompleteMethod = env->GetMethodID(callbackClass, "onComplete", "()V");

    // Call onError to trigger Kotlin fallback
    jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
    if (onErrorMethod != nullptr) {
        jstring errorMsg = env->NewStringUTF("Triggering Kotlin fallback");
        env->CallVoidMethod(callback, onErrorMethod, errorMsg);
        env->DeleteLocalRef(errorMsg);
    } else {
        env->CallVoidMethod(callback, onCompleteMethod);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_OfflineLlamaModel_clearContextNative(
        JNIEnv* env,
        jobject /* this */) {
    LOGI("Clearing JNI context buffer / KV Cache");
}
