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
