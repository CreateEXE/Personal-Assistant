#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_OfflineLlamaModel_loadModel(
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
Java_com_example_OfflineLlamaModel_generateText(
        JNIEnv* env,
        jobject /* this */,
        jstring prompt) {
    const char *prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
    LOGI("Generating text for prompt: %s", prompt_cstr);
    env->ReleaseStringUTFChars(prompt, prompt_cstr);
    
    // Stub out the text generation function so it expects a strict JSON response
    std::string mock_json_response = R"({
        "response_text": "Sure, I have scheduled your appointment.",
        "actions": [
            {
                "type": "add_appointment",
                "title": "Meeting with Assistant",
                "start_time_offset_mins": 30,
                "duration_mins": 60
            }
        ]
    })";

    return env->NewStringUTF(mock_json_response.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_OfflineLlamaModel_generateTextStream(
        JNIEnv* env,
        jobject /* this */,
        jstring prompt,
        jobject callback) {
    
    const char *prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
    LOGI("Generating text stream for prompt: %s", prompt_cstr);
    env->ReleaseStringUTFChars(prompt, prompt_cstr);
    
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenGeneratedMethod = env->GetMethodID(callbackClass, "onTokenGenerated", "(Ljava/lang/String;)V");
    jmethodID onCompleteMethod = env->GetMethodID(callbackClass, "onComplete", "()V");

    // Stub mock response for streaming
    std::string mock_json_response = R"({
        "response_text": "Sure, I have scheduled your appointment.",
        "actions": [
            {
                "type": "add_appointment",
                "title": "Meeting with Assistant",
                "start_time_offset_mins": 30,
                "duration_mins": 60
            }
        ]
    })";

    // Simulate streaming by sending chunks
    for (size_t i = 0; i < mock_json_response.length(); i += 10) {
        std::string chunk = mock_json_response.substr(i, 10);
        jstring jChunk = env->NewStringUTF(chunk.c_str());
        env->CallVoidMethod(callback, onTokenGeneratedMethod, jChunk);
        env->DeleteLocalRef(jChunk);
    }
    
    env->CallVoidMethod(callback, onCompleteMethod);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_OfflineLlamaModel_clearContextNative(
        JNIEnv* env,
        jobject /* this */) {
    LOGI("Clearing JNI context buffer / KV Cache");
}

