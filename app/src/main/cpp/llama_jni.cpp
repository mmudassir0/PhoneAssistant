// JNI bridge between the app and llama.cpp (plain C API only, pinned version in CMakeLists.txt).
//
// Text crosses the boundary as UTF-8 byte arrays, not Java strings: JNI's "modified UTF-8"
// mangles emoji and other 4-byte characters. Output is streamed back only at complete
// UTF-8 characters, since a single token can end in the middle of one.

#include <jni.h>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "llama-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#define LOGE(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#endif

namespace {

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::string chat_template;
    // Tokens currently held in the KV cache, so the next request only processes what changed.
    std::vector<llama_token> cached;
    int n_batch = 512;
    std::atomic<bool> stop{false};
    std::mutex busy;
};

// Error codes returned to Kotlin (see LlamaNative.kt).
constexpr int ERR_TEMPLATE = -1;
constexpr int ERR_TOO_LONG = -2;
constexpr int ERR_DECODE = -3;
constexpr int ERR_TOKENIZE = -4;

void log_callback(ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) LOGE("%s", text);
}

std::string bytes_to_string(JNIEnv * env, jbyteArray arr) {
    const jsize n = env->GetArrayLength(arr);
    std::string s(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

std::string jstring_to_string(JNIEnv * env, jstring js) {
    const char * c = env->GetStringUTFChars(js, nullptr);
    std::string s(c ? c : "");
    if (c) env->ReleaseStringUTFChars(js, c);
    return s;
}

/**
 * Moves the complete UTF-8 text at the start of [pending] into the result, replacing invalid
 * bytes with U+FFFD. An incomplete character at the end stays in [pending] for the next token
 * (unless [all] is true, at the very end of generation).
 */
std::string take_utf8(std::string & pending, bool all) {
    static const char REPLACEMENT[] = "\xEF\xBF\xBD";
    std::string out;
    size_t i = 0;
    while (i < pending.size()) {
        const auto c = static_cast<unsigned char>(pending[i]);
        size_t len = c < 0x80 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 0;
        if (len == 0 || (len == 2 && c < 0xC2) || (len == 4 && c > 0xF4)) { // stray or invalid lead byte
            out += REPLACEMENT;
            i++;
            continue;
        }
        size_t j = 1;
        while (j < len && i + j < pending.size() && (static_cast<unsigned char>(pending[i + j]) & 0xC0) == 0x80) j++;
        if (j == len) {                 // complete character
            out.append(pending, i, len);
            i += len;
        } else if (i + j == pending.size() && !all) {
            break;                      // truncated at the end: wait for the next token
        } else {                        // broken sequence
            out += REPLACEMENT;
            i += j;
        }
    }
    pending.erase(0, i);
    return out;
}

std::string token_piece(const llama_vocab * vocab, llama_token tok) {
    char buf[256];
    int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, false);
    if (n < 0) {
        std::string big(static_cast<size_t>(-n), '\0');
        n = llama_token_to_piece(vocab, tok, &big[0], -n, 0, false);
        return n > 0 ? big.substr(0, static_cast<size_t>(n)) : std::string();
    }
    return std::string(buf, static_cast<size_t>(n));
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text) {
    int n = -llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), nullptr, 0, true, true);
    if (n <= 0) return {};
    std::vector<llama_token> out(static_cast<size_t>(n));
    n = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), out.data(), n, true, true);
    if (n < 0) return {};
    out.resize(static_cast<size_t>(n));
    return out;
}

bool decode_tokens(Engine * e, llama_token * tokens, size_t count) {
    for (size_t i = 0; i < count; i += static_cast<size_t>(e->n_batch)) {
        const int n = static_cast<int>(std::min(count - i, static_cast<size_t>(e->n_batch)));
        if (llama_decode(e->ctx, llama_batch_get_one(tokens + i, n)) != 0) return false;
    }
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_mudassir_assistant_ai_LlamaNative_nativeLoad(JNIEnv * env, jclass, jstring jpath, jint n_ctx, jint n_threads) {
    static std::once_flag init;
    std::call_once(init, [] {
        llama_log_set(log_callback, nullptr);
        llama_backend_init();
    });

    const std::string path = jstring_to_string(env, jpath);
    // Defaults: auto load mode (memory-mapped) and weight repacking for fast ARM kernels.
    llama_model_params mp = llama_model_default_params();
    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) {
        LOGE("failed to load model %s", path.c_str());
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(n_ctx);
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        LOGE("failed to create context");
        llama_model_free(model);
        return 0;
    }

    auto * e = new Engine();
    e->model = model;
    e->ctx = ctx;
    e->vocab = llama_model_get_vocab(model);
    const char * tmpl = llama_model_chat_template(model, nullptr);
    e->chat_template = tmpl ? tmpl : "chatml";
    LOGI("model loaded: ctx=%d threads=%d", n_ctx, n_threads);
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_com_mudassir_assistant_ai_LlamaNative_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (!e) return;
    e->stop = true;
    std::lock_guard<std::mutex> lock(e->busy);
    llama_free(e->ctx);
    llama_model_free(e->model);
    delete e;
}

JNIEXPORT void JNICALL
Java_com_mudassir_assistant_ai_LlamaNative_nativeStop(JNIEnv *, jclass, jlong handle) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (e) e->stop = true;
}

/**
 * Runs one chat turn. [roles] are "system"/"user"/"assistant"; [contents] are UTF-8 byte arrays.
 * Streams text to callback.onText(byte[]): returning false stops early.
 * Returns the number of generated tokens, or a negative error code.
 */
JNIEXPORT jint JNICALL
Java_com_mudassir_assistant_ai_LlamaNative_nativeGenerate(
        JNIEnv * env, jclass, jlong handle, jobjectArray roles, jobjectArray contents,
        jint max_tokens, jfloat temperature, jobject callback) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (!e) return ERR_DECODE;
    std::lock_guard<std::mutex> lock(e->busy);
    e->stop = false;

    // ---- build the prompt with the model's own chat template ----
    const jsize n_msg = env->GetArrayLength(roles);
    std::vector<std::string> role_strs, content_strs;
    role_strs.reserve(n_msg);
    content_strs.reserve(n_msg);
    size_t total = 0;
    for (jsize i = 0; i < n_msg; i++) {
        auto jr = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto jc = static_cast<jbyteArray>(env->GetObjectArrayElement(contents, i));
        role_strs.push_back(jstring_to_string(env, jr));
        content_strs.push_back(bytes_to_string(env, jc));
        total += content_strs.back().size();
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
    }
    std::vector<llama_chat_message> msgs;
    for (jsize i = 0; i < n_msg; i++) msgs.push_back({role_strs[i].c_str(), content_strs[i].c_str()});

    std::vector<char> buf(total * 2 + 512);
    int len = llama_chat_apply_template(e->chat_template.c_str(), msgs.data(), msgs.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
    if (len > static_cast<int>(buf.size())) {
        buf.resize(static_cast<size_t>(len));
        len = llama_chat_apply_template(e->chat_template.c_str(), msgs.data(), msgs.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
    }
    if (len < 0) return ERR_TEMPLATE;
    const std::string prompt(buf.data(), static_cast<size_t>(len));

    std::vector<llama_token> tokens = tokenize(e->vocab, prompt);
    if (tokens.empty()) return ERR_TOKENIZE;
    const int n_ctx = static_cast<int>(llama_n_ctx(e->ctx));
    if (static_cast<int>(tokens.size()) + max_tokens >= n_ctx) return ERR_TOO_LONG;

    // ---- reuse the part of the KV cache that matches (system prompt + earlier turns) ----
    size_t keep = 0;
    while (keep < e->cached.size() && keep < tokens.size() && e->cached[keep] == tokens[keep]) keep++;
    if (keep == tokens.size()) keep--; // must decode at least one token to get fresh logits
    llama_memory_t mem = llama_get_memory(e->ctx);
    if (!llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(keep), -1)) {
        llama_memory_clear(mem, true);
        keep = 0;
    }
    e->cached.assign(tokens.begin(), tokens.begin() + static_cast<long>(keep));

    if (!decode_tokens(e, tokens.data() + keep, tokens.size() - keep)) {
        llama_memory_clear(mem, true);
        e->cached.clear();
        return ERR_DECODE;
    }
    e->cached = tokens;

    // ---- sample ----
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(e->vocab), 64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    jclass cb_class = env->GetObjectClass(callback);
    jmethodID on_text = env->GetMethodID(cb_class, "onText", "([B)Z");

    std::string pending;
    int generated = 0;
    int result = 0;
    auto flush = [&](bool all) -> bool {
        const std::string text = take_utf8(pending, all);
        if (text.empty()) return true;
        const auto n = static_cast<jsize>(text.size());
        jbyteArray arr = env->NewByteArray(n);
        env->SetByteArrayRegion(arr, 0, n, reinterpret_cast<const jbyte *>(text.data()));
        const jboolean keep_going = env->CallBooleanMethod(callback, on_text, arr);
        env->DeleteLocalRef(arr);
        return keep_going == JNI_TRUE && !env->ExceptionCheck();
    };

    while (generated < max_tokens && !e->stop) {
        llama_token tok = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, tok)) break;
        generated++;
        pending += token_piece(e->vocab, tok);
        if (!flush(false)) break;
        if (static_cast<int>(e->cached.size()) + 1 >= n_ctx) break;
        if (llama_decode(e->ctx, llama_batch_get_one(&tok, 1)) != 0) {
            result = ERR_DECODE;
            break;
        }
        e->cached.push_back(tok);
    }
    flush(true);
    llama_sampler_free(smpl);
    env->DeleteLocalRef(cb_class);
    return result < 0 ? result : generated;
}

/** Forget the conversation held in the KV cache (e.g. "new chat"). */
JNIEXPORT void JNICALL
Java_com_mudassir_assistant_ai_LlamaNative_nativeReset(JNIEnv *, jclass, jlong handle) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (!e) return;
    std::lock_guard<std::mutex> lock(e->busy);
    llama_memory_clear(llama_get_memory(e->ctx), true);
    e->cached.clear();
}

}  // extern "C"
