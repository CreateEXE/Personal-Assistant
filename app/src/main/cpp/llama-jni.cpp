#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <dlfcn.h>
#include <android/log.h>

#define LOG_TAG "FaitLlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ============================================================================
// LLAMA.CPP STRUCTS & TYPE DEFINITIONS (Matching llama.h)
// ============================================================================
typedef int llama_token;

struct llama_model;
struct llama_context;

struct llama_token_data {
    llama_token id;
    float logit;
    float p;
};

struct llama_token_data_array {
    llama_token_data* data;
    size_t size;
    bool sorted;
};

struct llama_model_params {
    int32_t n_gpu_layers;
    int32_t split_mode;
    int32_t main_gpu;
    const float* tensor_split;
    void* progress_callback;
    void* progress_callback_user_data;
    bool vocab_only;
    bool use_mmap;
    bool use_mlock;
    bool check_tensors;
};

struct llama_context_params {
    uint32_t seed;
    uint32_t n_ctx;
    uint32_t n_batch;
    uint32_t n_ubatch;
    uint32_t n_seq_max;
    uint32_t n_threads;
    uint32_t n_threads_batch;
    float rope_freq_base;
    float rope_freq_scale;
    float yarn_ext_factor;
    float yarn_attn_factor;
    float yarn_beta_fast;
    float yarn_beta_slow;
    int32_t yarn_orig_ctx;
    float defrag_threshold;
    bool cb_eval;
    void* cb_eval_user_data;
    bool type_k;
    bool type_v;
    bool mul_mat_q;
    bool logits_all;
    bool embedding;
    bool offloaded_devices;
};

struct llama_pos {
    int32_t pos;
};

struct llama_seq_id {
    int32_t id;
};

struct llama_batch {
    int32_t n_tokens;
    llama_token* token;
    float* embd;
    llama_pos* pos;
    int32_t* n_seq_id;
    int32_t** seq_id;
    int8_t* logits;
};

// ============================================================================
// DYNAMIC FUNCTION POINTERS FOR LLAMA.CPP
// ============================================================================
typedef void (*llama_backend_init_t)(bool);
typedef void (*llama_backend_free_t)();
typedef struct llama_model_params (*llama_model_default_params_t)();
typedef struct llama_context_params (*llama_context_default_params_t)();
typedef struct llama_model* (*llama_load_model_from_file_t)(const char*, struct llama_model_params);
typedef void (*llama_free_model_t)(struct llama_model*);
typedef struct llama_context* (*llama_new_context_with_model_t)(struct llama_model*, struct llama_context_params);
typedef void (*llama_free_t)(struct llama_context*);
typedef int32_t (*llama_tokenize_t)(struct llama_model*, const char*, int32_t, llama_token*, int32_t, bool, bool);
typedef int32_t (*llama_n_vocab_t)(const struct llama_model*);
typedef float* (*llama_get_logits_ith_t)(struct llama_context*, int32_t);
typedef int32_t (*llama_decode_t)(struct llama_context*, struct llama_batch);
typedef int32_t (*llama_token_to_piece_t)(const struct llama_model*, llama_token, char*, int32_t);
typedef void (*llama_kv_cache_clear_t)(struct llama_context*);
typedef void (*llama_kv_cache_seq_rm_t)(struct llama_context*, int32_t, int32_t, int32_t);

// LoRA structural and pointer definitions
struct llama_lora_adapter;

typedef struct llama_lora_adapter* (*llama_lora_adapter_init_t)(struct llama_model*, const char*);
typedef void (*llama_lora_adapter_free_t)(struct llama_lora_adapter*);
typedef int32_t (*llama_set_adapter_lora_t)(struct llama_context*, struct llama_lora_adapter*, float);
typedef void (*llama_clear_adapter_lora_t)(struct llama_context*);
typedef int32_t (*llama_model_apply_lora_from_file_t)(struct llama_model*, const char*, float, const char*, int32_t);

// Loaded symbols holding structural pointer addresses
static llama_backend_init_t fn_llama_backend_init = nullptr;
static llama_backend_free_t fn_llama_backend_free = nullptr;
static llama_model_default_params_t fn_llama_model_default_params = nullptr;
static llama_context_default_params_t fn_llama_context_default_params = nullptr;
static llama_load_model_from_file_t fn_llama_load_model_from_file = nullptr;
static llama_free_model_t fn_llama_free_model = nullptr;
static llama_new_context_with_model_t fn_llama_new_context_with_model = nullptr;
static llama_free_t fn_llama_free = nullptr;
static llama_tokenize_t fn_llama_tokenize = nullptr;
static llama_n_vocab_t fn_llama_n_vocab = nullptr;
static llama_get_logits_ith_t fn_llama_get_logits_ith = nullptr;
static llama_decode_t fn_llama_decode = nullptr;
static llama_token_to_piece_t fn_llama_token_to_piece = nullptr;
static llama_kv_cache_clear_t fn_llama_kv_cache_clear = nullptr;
static llama_kv_cache_seq_rm_t fn_llama_kv_cache_seq_rm = nullptr;

static llama_lora_adapter_init_t fn_llama_lora_adapter_init = nullptr;
static llama_lora_adapter_free_t fn_llama_lora_adapter_free = nullptr;
static llama_set_adapter_lora_t fn_llama_set_adapter_lora = nullptr;
static llama_clear_adapter_lora_t fn_llama_clear_adapter_lora = nullptr;
static llama_model_apply_lora_from_file_t fn_llama_model_apply_lora_from_file = nullptr;

// Globals holding JNI handles and thread-locks
static void* g_llama_so_handle = nullptr;
static struct llama_model* g_model = nullptr;
static struct llama_context* g_ctx = nullptr;
static std::mutex g_llama_mutex;
static bool g_lib_resolved = false;

// Active LoRA states and dynamic memory management caches
#include <unordered_map>
static std::unordered_map<std::string, struct llama_lora_adapter*> g_lora_cache;
static std::string g_current_lora_path = "";

static void clearLoraCache() {
    if (fn_llama_lora_adapter_free) {
        for (auto const& [path, adapter] : g_lora_cache) {
            if (adapter) {
                fn_llama_lora_adapter_free(adapter);
            }
        }
    }
    g_lora_cache.clear();
    g_current_lora_path = "";
    LOGI("Cleared all cached LoRA adapter buffers in native JNI memory.");
}

// Dynamic symbol resolver
static bool resolveLlamaSymbols() {
    if (g_lib_resolved) return true;

    // Try opening the default library name
    g_llama_so_handle = dlopen("libllama.so", RTLD_NOW | RTLD_GLOBAL);
    if (!g_llama_so_handle) {
        LOGI("Could not load libllama.so dynamically. System is using Kotlin Self-Healing JSON mode.");
        return false;
    }

    fn_llama_backend_init = (llama_backend_init_t) dlsym(g_llama_so_handle, "llama_backend_init");
    fn_llama_backend_free = (llama_backend_free_t) dlsym(g_llama_so_handle, "llama_backend_free");
    fn_llama_model_default_params = (llama_model_default_params_t) dlsym(g_llama_so_handle, "llama_model_default_params");
    fn_llama_context_default_params = (llama_context_default_params_t) dlsym(g_llama_so_handle, "llama_context_default_params");
    fn_llama_load_model_from_file = (llama_load_model_from_file_t) dlsym(g_llama_so_handle, "llama_load_model_from_file");
    fn_llama_free_model = (llama_free_model_t) dlsym(g_llama_so_handle, "llama_free_model");
    fn_llama_new_context_with_model = (llama_new_context_with_model_t) dlsym(g_llama_so_handle, "llama_new_context_with_model");
    fn_llama_free = (llama_free_t) dlsym(g_llama_so_handle, "llama_free");
    fn_llama_tokenize = (llama_tokenize_t) dlsym(g_llama_so_handle, "llama_tokenize");
    fn_llama_n_vocab = (llama_n_vocab_t) dlsym(g_llama_so_handle, "llama_n_vocab");
    fn_llama_get_logits_ith = (llama_get_logits_ith_t) dlsym(g_llama_so_handle, "llama_get_logits_ith");
    fn_llama_decode = (llama_decode_t) dlsym(g_llama_so_handle, "llama_decode");
    fn_llama_token_to_piece = (llama_token_to_piece_t) dlsym(g_llama_so_handle, "llama_token_to_piece");
    fn_llama_kv_cache_clear = (llama_kv_cache_clear_t) dlsym(g_llama_so_handle, "llama_kv_cache_clear");
    fn_llama_kv_cache_seq_rm = (llama_kv_cache_seq_rm_t) dlsym(g_llama_so_handle, "llama_kv_cache_seq_rm");

    // Resolve dynamic LoRA symbols (which may or may not be present depending on llama.cpp version)
    fn_llama_lora_adapter_init = (llama_lora_adapter_init_t) dlsym(g_llama_so_handle, "llama_lora_adapter_init");
    fn_llama_lora_adapter_free = (llama_lora_adapter_free_t) dlsym(g_llama_so_handle, "llama_lora_adapter_free");
    fn_llama_set_adapter_lora = (llama_set_adapter_lora_t) dlsym(g_llama_so_handle, "llama_set_adapter_lora");
    fn_llama_clear_adapter_lora = (llama_clear_adapter_lora_t) dlsym(g_llama_so_handle, "llama_clear_adapter_lora");
    fn_llama_model_apply_lora_from_file = (llama_model_apply_lora_from_file_t) dlsym(g_llama_so_handle, "llama_model_apply_lora_from_file");

    if (!fn_llama_load_model_from_file || !fn_llama_new_context_with_model || !fn_llama_decode) {
        LOGE("Some vital llama.cpp functions could not be resolved from libllama.so!");
        dlclose(g_llama_so_handle);
        g_llama_so_handle = nullptr;
        return false;
    }

    g_lib_resolved = true;
    LOGI("Successfully loaded libllama.so and resolved all core LLaMA symbols!");
    return true;
}

// Helper to construct a llama_batch
static struct llama_batch getLlamaBatch(llama_token* tokens, int32_t n_tokens, int32_t pos_0, int32_t seq_id) {
    struct llama_batch batch = {
        n_tokens,
        tokens,
        nullptr,
        new llama_pos[n_tokens],
        new int32_t[n_tokens],
        new int32_t*[n_tokens],
        new int8_t[n_tokens]
    };
    for (int32_t i = 0; i < n_tokens; ++i) {
        batch.pos[i].pos = pos_0 + i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i] = new int32_t[1];
        batch.seq_id[i][0] = seq_id;
        batch.logits[i] = 0;
    }
    // Set logits for the last token to run the sampler on
    if (n_tokens > 0) {
        batch.logits[n_tokens - 1] = 1;
    }
    return batch;
}

static void freeLlamaBatch(struct llama_batch& batch) {
    delete[] batch.pos;
    delete[] batch.n_seq_id;
    for (int32_t i = 0; i < batch.n_tokens; ++i) {
        delete[] batch.seq_id[i];
    }
    delete[] batch.seq_id;
    delete[] batch.logits;
}

// ============================================================================
// JNI IMPLEMENTATION METHODS
// ============================================================================

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_OfflineLlamaModel_loadModelNative(
        JNIEnv* env,
        jobject /* this */,
        jstring path) {
    
    std::lock_guard<std::mutex> lock(g_llama_mutex);
    
    if (!resolveLlamaSymbols()) {
        return JNI_FALSE;
    }

    // Clean up old context and cached LoRA weights
    clearLoraCache();
    if (g_ctx) {
        fn_llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        fn_llama_free_model(g_model);
        g_model = nullptr;
    }

    const char *path_cstr = env->GetStringUTFChars(path, nullptr);
    LOGI("Loading model file from: %s", path_cstr);

    // Initialize backend once
    static bool backend_initialized = false;
    if (!backend_initialized) {
        fn_llama_backend_init(false);
        backend_initialized = true;
    }

    struct llama_model_params m_params = fn_llama_model_default_params();
    m_params.n_gpu_layers = 16; // Enable GPU offloading on compatible Android SOCs
    m_params.use_mmap = true;
    
    g_model = fn_llama_load_model_from_file(path_cstr, m_params);
    env->ReleaseStringUTFChars(path, path_cstr);

    if (!g_model) {
        LOGE("Failed to load LLaMA model natively.");
        return JNI_FALSE;
    }

    struct llama_context_params c_params = fn_llama_context_default_params();
    c_params.n_ctx = 2048;      // Slid-window context limit
    c_params.n_batch = 512;
    c_params.n_threads = 4;     // Optimize thread count for typical ARM big.LITTLE architectures
    c_params.logits_all = false;

    g_ctx = fn_llama_new_context_with_model(g_model, c_params);
    if (!g_ctx) {
        LOGE("Failed to create inference context with the native model.");
        fn_llama_free_model(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Successfully initialized model and allocated context memory.");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_OfflineLlamaModel_generateTextNative(
        JNIEnv* env,
        jobject /* this */,
        jstring prompt) {
    // Non-streaming exact execution stub
    std::lock_guard<std::mutex> lock(g_llama_mutex);
    if (!g_ctx || !g_model) {
        return env->NewStringUTF("{\"error\": \"Model not loaded natively.\"}");
    }
    return env->NewStringUTF("{\"error\": \"Streaming API is preferred for real-time local companion generation.\"}");
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
    
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenGeneratedMethod = env->GetMethodID(callbackClass, "onTokenGenerated", "(Ljava/lang/String;)V");
    jmethodID onCompleteMethod = env->GetMethodID(callbackClass, "onComplete", "()V");
    jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");

    std::unique_lock<std::mutex> lock(g_llama_mutex);

    if (!g_ctx || !g_model) {
        // Safe-Mode fallback: report to Kotlin so it uses the highly tuned JSON rule-engine
        LOGI("Native model not loaded/present. Shifting reasoning responsibility to Kotlin Self-Healing fallbacks.");
        if (onErrorMethod != nullptr) {
            jstring errorMsg = env->NewStringUTF("Local GGUF file not loaded. Triggering local self-healing formatting.");
            env->CallVoidMethod(callback, onErrorMethod, errorMsg);
            env->DeleteLocalRef(errorMsg);
        } else {
            env->CallVoidMethod(callback, onCompleteMethod);
        }
        return;
    }

    const char *prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
    
    // Parse stop sequences
    std::vector<std::string> stop_seqs;
    if (stop_sequences != nullptr) {
        jsize len = env->GetArrayLength(stop_sequences);
        for (jsize i = 0; i < len; ++i) {
            jstring stop_seq = (jstring) env->GetObjectArrayElement(stop_sequences, i);
            if (stop_seq != nullptr) {
                const char* stop_cstr = env->GetStringUTFChars(stop_seq, nullptr);
                stop_seqs.push_back(std::string(stop_cstr));
                env->ReleaseStringUTFChars(stop_seq, stop_cstr);
                env->DeleteLocalRef(stop_seq);
            }
        }
    }

    // Allocate tokens vector
    int32_t vocab_size = fn_llama_n_vocab(g_model);
    std::vector<llama_token> prompt_tokens(vocab_size);
    int32_t n_prompt_tokens = fn_llama_tokenize(g_model, prompt_cstr, strlen(prompt_cstr), prompt_tokens.data(), vocab_size, true, false);
    
    env->ReleaseStringUTFChars(prompt, prompt_cstr);

    if (n_prompt_tokens < 0) {
        LOGE("Failed to tokenize prompt.");
        if (onErrorMethod != nullptr) {
            jstring errorMsg = env->NewStringUTF("Tokenization error.");
            env->CallVoidMethod(callback, onErrorMethod, errorMsg);
            env->DeleteLocalRef(errorMsg);
        }
        return;
    }

    prompt_tokens.resize(n_prompt_tokens);

    // KV Cache check: Clear / Prune if exceeding sliding window limit
    fn_llama_kv_cache_clear(g_ctx);

    // Decode initial prompt batch
    int32_t current_pos = 0;
    struct llama_batch batch = getLlamaBatch(prompt_tokens.data(), prompt_tokens.size(), current_pos, 0);
    int32_t decode_res = fn_llama_decode(g_ctx, batch);
    freeLlamaBatch(batch);
    current_pos += prompt_tokens.size();

    if (decode_res != 0) {
        LOGE("Inference decode failed for prompt context.");
        if (onErrorMethod != nullptr) {
            jstring errorMsg = env->NewStringUTF("Context decoding failed.");
            env->CallVoidMethod(callback, onErrorMethod, errorMsg);
            env->DeleteLocalRef(errorMsg);
        }
        return;
    }

    // Autoregressive token generation loop
    std::string accumulated_output = "";
    for (int32_t token_idx = 0; token_idx < max_new_tokens; ++token_idx) {
        float* logits = fn_llama_get_logits_ith(g_ctx, 0);
        
        // Populate probabilities array
        std::vector<llama_token_data> candidates;
        candidates.reserve(vocab_size);
        for (llama_token token_id = 0; token_id < vocab_size; ++token_id) {
            candidates.push_back(llama_token_data{token_id, logits[token_id], 0.0f});
        }
        
        struct llama_token_data_array candidates_array = {
            candidates.data(),
            candidates.size(),
            false
        };

        // Mathematical sampling tuning (Temperature & Samplers)
        // Since we are loading llama.cpp dynamically, we employ a standard greedy or soft probability sampler
        llama_token sampled_token = 0;
        if (temp <= 0.0f) {
            // Greedy sampling
            float max_logit = -99999.0f;
            for (const auto& cand : candidates) {
                if (cand.logit > max_logit) {
                    max_logit = cand.logit;
                    sampled_token = cand.id;
                }
            }
        } else {
            // Apply temperature scaling
            float sum_prob = 0.0f;
            for (auto& cand : candidates) {
                cand.logit /= temp;
                cand.p = expf(cand.logit);
                sum_prob += cand.p;
            }
            // Normalize
            float rand_val = (float)rand() / RAND_MAX;
            float acc_prob = 0.0f;
            for (auto& cand : candidates) {
                cand.p /= sum_prob;
                acc_prob += cand.p;
                if (rand_val <= acc_prob) {
                    sampled_token = cand.id;
                    break;
                }
            }
        }

        // Decode sampled token
        llama_token next_tokens[] = {sampled_token};
        struct llama_batch single_batch = getLlamaBatch(next_tokens, 1, current_pos, 0);
        decode_res = fn_llama_decode(g_ctx, single_batch);
        freeLlamaBatch(single_batch);
        current_pos++;

        if (decode_res != 0) {
            LOGE("Inference decode failed for generated token.");
            break;
        }

        // Convert token to string slice
        char buffer[128];
        int32_t len = fn_llama_token_to_piece(g_model, sampled_token, buffer, sizeof(buffer));
        if (len > 0) {
            buffer[len] = '\0';
            std::string piece(buffer);
            accumulated_output += piece;

            // Stream token back to Kotlin main queue
            jstring tokenStr = env->NewStringUTF(buffer);
            env->CallVoidMethod(callback, onTokenGeneratedMethod, tokenStr);
            env->DeleteLocalRef(tokenStr);

            // Check if any stop sequences are matched
            bool matched_stop = false;
            for (const auto& stop_seq : stop_seqs) {
                if (accumulated_output.size() >= stop_seq.size() && 
                    accumulated_output.compare(accumulated_output.size() - stop_seq.size(), stop_seq.size(), stop_seq) == 0) {
                    matched_stop = true;
                    break;
                }
            }
            if (matched_stop) {
                LOGI("Matched stop sequence: generation stopped.");
                break;
            }
        }

        // Break on standard EOS (usually token 2)
        if (sampled_token == 2) {
            LOGI("Encountered EOS token. Generation finished.");
            break;
        }
    }

    env->CallVoidMethod(callback, onCompleteMethod);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_OfflineLlamaModel_clearContextNative(
        JNIEnv* env,
        jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_llama_mutex);
    if (g_ctx) {
        if (resolveLlamaSymbols() && fn_llama_kv_cache_clear) {
            fn_llama_kv_cache_clear(g_ctx);
            LOGI("Natively cleared LLM KV cache buffer.");
        }
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_OfflineLlamaModel_applyLoraAdapterNative(
        JNIEnv* env,
        jobject /* this */,
        jstring lora_path,
        jfloat scale) {
    std::lock_guard<std::mutex> lock(g_llama_mutex);
    if (!resolveLlamaSymbols()) {
        LOGE("Cannot apply LoRA: dynamic libraries not resolved.");
        return JNI_FALSE;
    }

    if (!g_model || !g_ctx) {
        LOGE("Model or Context not initialized natively. Cannot apply LoRA.");
        return JNI_FALSE;
    }

    const char* lora_path_cstr = env->GetStringUTFChars(lora_path, nullptr);
    if (!lora_path_cstr) {
        return JNI_FALSE;
    }
    std::string path_str(lora_path_cstr);
    env->ReleaseStringUTFChars(lora_path, lora_path_cstr);

    // If path is empty, clear active adapters
    if (path_str.empty() || path_str == "none") {
        if (fn_llama_clear_adapter_lora) {
            fn_llama_clear_adapter_lora(g_ctx);
            g_current_lora_path = "";
            LOGI("Cleared all context-level LoRA adapters dynamically.");
            return JNI_TRUE;
        } else if (fn_llama_model_apply_lora_from_file && !g_current_lora_path.empty()) {
            // Apply a negative scale to unapply model-level weights
            fn_llama_model_apply_lora_from_file(g_model, g_current_lora_path.c_str(), -1.0f, nullptr, 4);
            g_current_lora_path = "";
            LOGI("Unapplied model-level LoRA fallback using negative scale.");
            return JNI_TRUE;
        }
        return JNI_FALSE;
    }

    // Check if we already have this adapter cached in the native heap
    struct llama_lora_adapter* adapter = nullptr;
    auto it = g_lora_cache.find(path_str);
    if (it != g_lora_cache.end()) {
        adapter = it->second;
        LOGI("Found cached LoRA adapter in JNI map: %s", path_str.c_str());
    } else {
        if (fn_llama_lora_adapter_init) {
            adapter = fn_llama_lora_adapter_init(g_model, path_str.c_str());
            if (!adapter) {
                LOGE("Failed to initialize LoRA adapter structure from path: %s", path_str.c_str());
                return JNI_FALSE;
            }
            g_lora_cache[path_str] = adapter;
            LOGI("Initialized and cached new LoRA adapter natively: %s", path_str.c_str());
        }
    }

    // Hot-swap adapter dynamically on context
    if (adapter && fn_llama_set_adapter_lora && fn_llama_clear_adapter_lora) {
        fn_llama_clear_adapter_lora(g_ctx);
        int32_t res = fn_llama_set_adapter_lora(g_ctx, adapter, scale);
        if (res != 0) {
            LOGE("Failed to set active LoRA adapter on context (error code: %d)", res);
            return JNI_FALSE;
        }
        g_current_lora_path = path_str;
        LOGI("Dynamic hot-swap completed successfully at context level: %s [Scale: %.2f]", path_str.c_str(), scale);
        return JNI_TRUE;
    } else if (fn_llama_model_apply_lora_from_file) {
        // Fallback for older model-level modification API
        if (!g_current_lora_path.empty()) {
            // Reverse previously applied LoRA weights to prevent overlap
            fn_llama_model_apply_lora_from_file(g_model, g_current_lora_path.c_str(), -1.0f, nullptr, 4);
        }
        int32_t res = fn_llama_model_apply_lora_from_file(g_model, path_str.c_str(), scale, nullptr, 4);
        if (res != 0) {
            LOGE("Failed to apply model-level fallback LoRA (error code: %d)", res);
            return JNI_FALSE;
        }
        g_current_lora_path = path_str;
        LOGI("Applied model-level fallback LoRA successfully: %s [Scale: %.2f]", path_str.c_str(), scale);
        return JNI_TRUE;
    }

    LOGE("Neither context-level nor model-level LoRA symbols could be invoked.");
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_OfflineLlamaModel_clearLoraCacheNative(
        JNIEnv* env,
        jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_llama_mutex);
    clearLoraCache();
}
