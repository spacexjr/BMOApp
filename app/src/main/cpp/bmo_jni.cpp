#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "BmoLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::once_flag g_backend_once;
std::mutex g_engine_mutex;

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::atomic<bool> cancel{false};
    std::vector<llama_token> cache_tokens;
    int n_ctx = 1024;
    int n_threads = 3;
};

static bool abort_callback(void * data) {
    auto * engine = static_cast<Engine *>(data);
    return engine != nullptr && engine->cancel.load(std::memory_order_relaxed);
}

static void jni_throw(JNIEnv * env, const char * message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
    }
}

static std::string jstring_to_string(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

static jlong to_handle(Engine * engine) {
    return reinterpret_cast<jlong>(engine);
}

static Engine * from_handle(jlong handle) {
    return reinterpret_cast<Engine *>(handle);
}

static void free_engine(Engine * engine) {
    if (engine == nullptr) return;
    if (engine->ctx != nullptr) {
        llama_free(engine->ctx);
        engine->ctx = nullptr;
    }
    if (engine->model != nullptr) {
        llama_model_free(engine->model);
        engine->model = nullptr;
    }
    delete engine;
}

static bool decode_prompt(
        Engine * engine,
        const std::vector<llama_token> & tokens,
        int from,
        int n_batch) {
    const int n_total = static_cast<int>(tokens.size());
    for (int start = from; start < n_total; start += n_batch) {
        const int count = std::min(n_batch, n_total - start);
        llama_batch batch = llama_batch_init(count, 0, 1);
        if (batch.token == nullptr) return false;
        for (int i = 0; i < count; ++i) {
            const int index = start + i;
            batch.token[i] = tokens[index];
            batch.pos[i] = index;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (index == n_total - 1) ? 1 : 0;
        }
        batch.n_tokens = count;
        const int rc = llama_decode(engine->ctx, batch);
        llama_batch_free(batch);
        if (rc != 0) return false;
    }
    return true;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_spacexjr_bmo_llm_LlamaCppBridge_nativeBackendInit(JNIEnv *, jobject) {
    std::call_once(g_backend_once, []() {
        llama_backend_init();
        LOGI("llama.cpp backend initialized");
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_spacexjr_bmo_llm_LlamaCppBridge_nativeLoadModel(
        JNIEnv * env, jobject, jstring path, jint n_ctx, jint n_threads, jint n_batch) {
    std::string model_path = jstring_to_string(env, path);
    if (model_path.empty()) {
        jni_throw(env, "Caminho do modelo vazio.");
        return 0;
    }

    std::call_once(g_backend_once, []() { llama_backend_init(); });

    auto * engine = new Engine();
    engine->n_ctx = n_ctx > 0 ? n_ctx : 1024;
    engine->n_threads = n_threads > 0 ? n_threads : 3;

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.use_extra_bufts = false;

    LOGI("loading model %s", model_path.c_str());
    engine->model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (engine->model == nullptr) {
        delete engine;
        jni_throw(env, "Falha ao carregar o GGUF. Verifique se o arquivo é válido e se há memória suficiente.");
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(engine->n_ctx);
    ctx_params.n_batch = static_cast<uint32_t>(n_batch > 0 ? n_batch : 512);
    ctx_params.n_ubatch = static_cast<uint32_t>(n_batch > 0 ? n_batch : 512);
    ctx_params.n_threads = n_threads > 0 ? n_threads : 3;
    ctx_params.n_threads_batch = n_threads > 0 ? n_threads : 3;
    ctx_params.no_perf = true;
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    ctx_params.abort_callback = abort_callback;
    ctx_params.abort_callback_data = engine;

    engine->ctx = llama_init_from_model(engine->model, ctx_params);
    if (engine->ctx == nullptr) {
        llama_model_free(engine->model);
        delete engine;
        jni_throw(env, "Modelo carregado, mas não foi possível criar o contexto de inferência (memória insuficiente).");
        return 0;
    }

    engine->vocab = llama_model_get_vocab(engine->model);
    LOGI("model loaded, n_ctx=%u threads=%d", llama_n_ctx(engine->ctx), n_threads);
    return to_handle(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_spacexjr_bmo_llm_LlamaCppBridge_nativeFreeModel(JNIEnv *, jobject, jlong handle) {
    Engine * engine = from_handle(handle);
    if (engine == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(g_engine_mutex);
        free_engine(engine);
    }
    LOGI("model released");
}

extern "C" JNIEXPORT void JNICALL
Java_com_spacexjr_bmo_llm_LlamaCppBridge_nativeCancel(JNIEnv *, jobject, jlong handle) {
    Engine * engine = from_handle(handle);
    if (engine != nullptr) {
        engine->cancel.store(true, std::memory_order_relaxed);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_spacexjr_bmo_llm_LlamaCppBridge_nativeSupportsThinking(JNIEnv *, jobject, jlong handle) {
    Engine * engine = from_handle(handle);
    if (engine == nullptr || engine->model == nullptr) return JNI_FALSE;
    const char * tmpl = llama_model_chat_template(engine->model, nullptr);
    if (tmpl == nullptr) return JNI_FALSE;
    if (std::strstr(tmpl, "enable_thinking") != nullptr) return JNI_TRUE;
    if (std::strstr(tmpl, "think") != nullptr) return JNI_TRUE;
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_spacexjr_bmo_llm_LlamaCppBridge_nativeChatTemplate(JNIEnv * env, jobject, jlong handle) {
    Engine * engine = from_handle(handle);
    if (engine == nullptr || engine->model == nullptr) return env->NewStringUTF("");
    const char * tmpl = llama_model_chat_template(engine->model, nullptr);
    return env->NewStringUTF(tmpl != nullptr ? tmpl : "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_spacexjr_bmo_llm_LlamaCppBridge_nativeGenerate(
        JNIEnv * env, jobject, jlong handle, jstring prompt, jint max_tokens,
        jfloat temperature, jfloat top_p, jint top_k, jint seed) {
    Engine * engine = from_handle(handle);
    if (engine == nullptr || engine->ctx == nullptr || engine->vocab == nullptr) {
        jni_throw(env, "Modelo não carregado.");
        return nullptr;
    }

    std::string text = jstring_to_string(env, prompt);
    if (text.empty()) {
        jni_throw(env, "Prompt vazio.");
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(g_engine_mutex);
    engine->cancel.store(false, std::memory_order_relaxed);

    llama_memory_t memory = llama_get_memory(engine->ctx);

    const int n_ctx = static_cast<int>(llama_n_ctx(engine->ctx));
    std::vector<llama_token> tokens(text.size() + 32);
    int n_tokens = llama_tokenize(engine->vocab, text.c_str(), static_cast<int32_t>(text.size()),
                                  tokens.data(), static_cast<int32_t>(tokens.size()), false, true);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(engine->vocab, text.c_str(), static_cast<int32_t>(text.size()),
                                  tokens.data(), static_cast<int32_t>(tokens.size()), false, true);
    }
    if (n_tokens <= 0) {
        jni_throw(env, "Não foi possível tokenizar o prompt.");
        return nullptr;
    }
    if (n_tokens >= n_ctx - 4) {
        jni_throw(env, "O prompt excede o contexto configurado. Aumente o contexto ou reduza o histórico.");
        return nullptr;
    }
    tokens.resize(n_tokens);

    const int n_batch = static_cast<int>(llama_n_batch(engine->ctx));
    const int batch_size = n_batch > 0 ? n_batch : 512;

    int common = 0;
    const int cached_size = static_cast<int>(engine->cache_tokens.size());
    while (common < cached_size && common < n_tokens &&
           engine->cache_tokens[common] == tokens[common]) {
        ++common;
    }
    if (common == 0) {
        llama_memory_clear(memory, true);
    } else if (common < cached_size) {
        if (!llama_memory_seq_rm(memory, 0, common, -1)) {
            llama_memory_clear(memory, true);
            common = 0;
        }
    }
    LOGI("prompt tokens=%d (cache %d, reuse %d), n_batch=%d, n_ctx=%d",
         n_tokens, cached_size, common, batch_size, n_ctx);
    const auto promptStart = std::chrono::steady_clock::now();
    if (!decode_prompt(engine, tokens, common, batch_size)) {
        if (engine->cancel.load()) {
            jni_throw(env, "Geração cancelada.");
        } else {
            jni_throw(env, "Falha na inferência (llama_decode).");
        }
        return nullptr;
    }
    engine->cache_tokens = tokens;
    const auto promptMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - promptStart).count();

    auto * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k > 0 ? top_k : 40));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p > 0 ? top_p : 0.9f, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature > 0 ? temperature : 0.7f));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(static_cast<uint32_t>(seed)));

    std::string result;
    const auto generateStart = std::chrono::steady_clock::now();
    int generated = 0;
    const int limit = std::min(max_tokens > 0 ? max_tokens : 96, n_ctx - n_tokens - 1);
    for (int i = 0; i < limit; ++i) {
        if (engine->cancel.load(std::memory_order_relaxed)) break;
        const llama_token id = llama_sampler_sample(chain, engine->ctx, -1);
        if (llama_vocab_is_eog(engine->vocab, id)) break;
        llama_sampler_accept(chain, id);

        char piece[256];
        int n = llama_token_to_piece(engine->vocab, id, piece, sizeof(piece), 0, false);
        if (n > 0) {
            result.append(piece, n);
            if (result.find("<|im_end|>") != std::string::npos) {
                result.erase(result.find("<|im_end|>"));
                break;
            }
        }

        generated++;
        llama_batch next = llama_batch_init(1, 0, 1);
        if (next.token == nullptr) break;
        next.token[0] = id;
        next.pos[0] = n_tokens + i;
        next.n_seq_id[0] = 1;
        next.seq_id[0][0] = 0;
        next.logits[0] = 1;
        next.n_tokens = 1;
        const int rc = llama_decode(engine->ctx, next);
        llama_batch_free(next);
        if (rc != 0) break;
        engine->cache_tokens.push_back(id);
    }

    llama_sampler_free(chain);
    const auto generateMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - generateStart).count();
    LOGI("prompt eval %lld ms, geração %d tokens em %lld ms",
         static_cast<long long>(promptMs), generated, static_cast<long long>(generateMs));

    if (engine->cancel.load()) {
        engine->cancel.store(false, std::memory_order_relaxed);
        jni_throw(env, "Geração cancelada.");
        return nullptr;
    }

    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_spacexjr_bmo_llm_LlamaCppBridge_nativeInfo(JNIEnv * env, jobject, jlong handle) {
    Engine * engine = from_handle(handle);
    if (engine == nullptr || engine->ctx == nullptr) {
        return env->NewStringUTF("Modelo não carregado");
    }
    char desc[256] = {0};
    llama_model_desc(engine->model, desc, sizeof(desc));
    char buf[768];
    snprintf(buf, sizeof(buf),
             "%s · contexto %u · threads %d · vocabulário %d · pesos %.0f MiB",
             desc, llama_n_ctx(engine->ctx), engine->n_threads,
             llama_vocab_n_tokens(engine->vocab),
             static_cast<double>(llama_model_size(engine->model)) / (1024.0 * 1024.0));
    return env->NewStringUTF(buf);
}
