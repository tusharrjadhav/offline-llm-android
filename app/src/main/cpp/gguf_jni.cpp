#include <jni.h>
#include <string>
#include <vector>
#include <unordered_map>
#include <mutex>
#include <cstdint>
#include <thread>
#include <algorithm>
#include <chrono>
#include <android/log.h>
#include "llama.h"

#define NATIVE_TAG "GGUFNative"
#define NLOGI(...) __android_log_print(ANDROID_LOG_INFO, NATIVE_TAG, __VA_ARGS__)
#define NLOGE(...) __android_log_print(ANDROID_LOG_ERROR, NATIVE_TAG, __VA_ARGS__)

struct RequestState {
    std::vector<std::string> tokens;
    size_t index = 0;
    bool done = false;
    bool stopped = false;
    std::string error;
    std::string utf8_carry;
};

struct ModelState {
    llama_model * model = nullptr;
    int32_t context_length = 4096;
    int32_t n_threads = 4;
    int32_t n_threads_batch = 4;
    int32_t gpu_layers = 0;
};

struct SessionState {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::string system_prompt;
};

static std::mutex g_mutex;
static jlong g_next_handle = 1;
static bool g_backend_inited = false;

static std::unordered_map<jlong, ModelState> g_models;
static std::unordered_map<jlong, SessionState> g_sessions;
static std::unordered_map<jlong, RequestState> g_requests;

static size_t utf8_sequence_len(uint8_t lead) {
    if ((lead & 0x80) == 0x00) return 1;
    if ((lead & 0xE0) == 0xC0) return 2;
    if ((lead & 0xF0) == 0xE0) return 3;
    if ((lead & 0xF8) == 0xF0) return 4;
    return 0;
}

static bool utf8_is_continuation(uint8_t b) {
    return (b & 0xC0) == 0x80;
}

static uint32_t utf8_decode_codepoint(const std::string & s, size_t i, size_t len) {
    const uint8_t b0 = static_cast<uint8_t>(s[i]);
    if (len == 1) return b0;
    if (len == 2) {
        return ((b0 & 0x1F) << 6) |
               (static_cast<uint8_t>(s[i + 1]) & 0x3F);
    }
    if (len == 3) {
        return ((b0 & 0x0F) << 12) |
               ((static_cast<uint8_t>(s[i + 1]) & 0x3F) << 6) |
               (static_cast<uint8_t>(s[i + 2]) & 0x3F);
    }
    return ((b0 & 0x07) << 18) |
           ((static_cast<uint8_t>(s[i + 1]) & 0x3F) << 12) |
           ((static_cast<uint8_t>(s[i + 2]) & 0x3F) << 6) |
           (static_cast<uint8_t>(s[i + 3]) & 0x3F);
}

static void utf8_split_stream(
    const std::string & input,
    std::string & out_valid,
    std::string & out_carry,
    bool flush_incomplete) {

    out_valid.clear();
    out_carry.clear();

    size_t i = 0;
    while (i < input.size()) {
        const uint8_t lead = static_cast<uint8_t>(input[i]);
        const size_t len = utf8_sequence_len(lead);
        if (len == 0) {
            out_valid.push_back('?');
            ++i;
            continue;
        }
        if (len == 1) {
            out_valid.push_back(static_cast<char>(lead));
            ++i;
            continue;
        }

        if (i + len > input.size()) {
            if (flush_incomplete) {
                out_valid.push_back('?');
            } else {
                out_carry.assign(input.data() + i, input.size() - i);
            }
            break;
        }

        bool cont_ok = true;
        for (size_t j = 1; j < len; ++j) {
            if (!utf8_is_continuation(static_cast<uint8_t>(input[i + j]))) {
                cont_ok = false;
                break;
            }
        }
        if (!cont_ok) {
            out_valid.push_back('?');
            ++i; // re-sync by advancing one byte
            continue;
        }

        const uint32_t cp = utf8_decode_codepoint(input, i, len);
        const bool overlong = (len == 2 && cp < 0x80) ||
                              (len == 3 && cp < 0x800) ||
                              (len == 4 && cp < 0x10000);
        const bool invalid_cp = cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF);
        if (overlong || invalid_cp) {
            out_valid.push_back('?');
            ++i;
            continue;
        }

        out_valid.append(input, i, len);
        i += len;
    }
}

static bool request_is_stopped(jlong requestId) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_requests.find(requestId);
    return it == g_requests.end() || it->second.stopped;
}

static void request_push_token(jlong requestId, const std::string & token) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_requests.find(requestId);
    if (it == g_requests.end() || it->second.stopped) return;
    it->second.utf8_carry.append(token);

    std::string emit;
    std::string carry;
    utf8_split_stream(it->second.utf8_carry, emit, carry, false);
    it->second.utf8_carry.swap(carry);
    if (!emit.empty()) {
        it->second.tokens.push_back(emit);
    }
}

static void request_finish(jlong requestId, const std::string & error = "") {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_requests.find(requestId);
    if (it == g_requests.end()) return;

    if (!it->second.utf8_carry.empty()) {
        std::string emit;
        std::string carry;
        utf8_split_stream(it->second.utf8_carry, emit, carry, true);
        if (!emit.empty()) {
            it->second.tokens.push_back(emit);
        }
        it->second.utf8_carry.clear();
    }

    if (!error.empty()) {
        it->second.error = error;
    }
    it->second.done = true;
}

static jstring make_jstring_from_utf8_bytes(JNIEnv * env, const std::string & value) {
    jclass stringCls = env->FindClass("java/lang/String");
    jclass stdCharsetsCls = env->FindClass("java/nio/charset/StandardCharsets");
    if (stringCls == nullptr || stdCharsetsCls == nullptr) {
        return env->NewStringUTF("");
    }

    jmethodID ctor = env->GetMethodID(stringCls, "<init>", "([BLjava/nio/charset/Charset;)V");
    jfieldID utf8Field = env->GetStaticFieldID(stdCharsetsCls, "UTF_8", "Ljava/nio/charset/Charset;");
    if (ctor == nullptr || utf8Field == nullptr) {
        env->DeleteLocalRef(stringCls);
        env->DeleteLocalRef(stdCharsetsCls);
        return env->NewStringUTF("");
    }

    jobject utf8Charset = env->GetStaticObjectField(stdCharsetsCls, utf8Field);
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(value.size()));
    if (bytes != nullptr && !value.empty()) {
        env->SetByteArrayRegion(
            bytes,
            0,
            static_cast<jsize>(value.size()),
            reinterpret_cast<const jbyte *>(value.data()));
    }

    jstring out = static_cast<jstring>(env->NewObject(stringCls, ctor, bytes, utf8Charset));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(utf8Charset);
    env->DeleteLocalRef(stringCls);
    env->DeleteLocalRef(stdCharsetsCls);
    return out;
}

static jobject make_poll_result(JNIEnv *env, const std::string &token, bool done, const std::string &error) {
    jclass cls = env->FindClass("com/android/gguf_llama_jin/inference/NativeTokenPollResult");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;ZLjava/lang/String;)V");

    jstring tokenStr = make_jstring_from_utf8_bytes(env, token);
    jstring errorStr = make_jstring_from_utf8_bytes(env, error);
    jobject obj = env->NewObject(cls, ctor, tokenStr, done ? JNI_TRUE : JNI_FALSE, errorStr);

    env->DeleteLocalRef(tokenStr);
    env->DeleteLocalRef(errorStr);
    env->DeleteLocalRef(cls);
    return obj;
}

static std::string token_to_piece_safe(const llama_vocab * vocab, llama_token tok) {
    char buf[256];
    int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
    if (n <= 0) {
        return "";
    }
    return std::string(buf, n);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_android_gguf_1llama_1jin_inference_LlmNativeBridgeImpl_nativeLoadModel(
    JNIEnv * env,
    jobject,
    jstring modelPath,
    jint contextLength,
    jint threads,
    jint gpuLayers) {

    const char * pathChars = env->GetStringUTFChars(modelPath, nullptr);
    std::string path = pathChars ? pathChars : "";
    env->ReleaseStringUTFChars(modelPath, pathChars);

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_backend_inited) {
        llama_backend_init();
        g_backend_inited = true;
    }

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = gpuLayers;

    llama_model * model = llama_model_load_from_file(path.c_str(), model_params);
    if (model == nullptr) {
        return 0;
    }

    jlong handle = g_next_handle++;
    const int32_t t = threads > 0 ? threads : 4;
    g_models.emplace(handle, ModelState{model, contextLength, t, t, gpuLayers});
    return handle;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_android_gguf_1llama_1jin_inference_LlmNativeBridgeImpl_nativeStartSession(
    JNIEnv * env,
    jobject,
    jlong modelHandle,
    jstring systemPrompt) {

    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_models.find(modelHandle);
    if (it == g_models.end() || it->second.model == nullptr) {
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = it->second.context_length > 0 ? it->second.context_length : 4096;
    ctx_params.n_batch = std::min<uint32_t>(512, ctx_params.n_ctx);
    ctx_params.n_threads = it->second.n_threads;
    ctx_params.n_threads_batch = it->second.n_threads_batch;

    llama_context * ctx = llama_init_from_model(it->second.model, ctx_params);
    if (ctx == nullptr) {
        return 0;
    }

    std::string systemText;
    if (systemPrompt != nullptr) {
        const char * promptChars = env->GetStringUTFChars(systemPrompt, nullptr);
        systemText = promptChars ? promptChars : "";
        env->ReleaseStringUTFChars(systemPrompt, promptChars);
    }

    jlong sessionHandle = g_next_handle++;
    g_sessions.emplace(sessionHandle, SessionState{it->second.model, ctx, llama_model_get_vocab(it->second.model), systemText});
    return sessionHandle;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_android_gguf_1llama_1jin_inference_LlmNativeBridgeImpl_nativeGenerateStart(
    JNIEnv * env,
    jobject,
    jlong sessionHandle,
    jstring prompt,
    jfloat temperature,
    jfloat topP,
    jint maxTokens) {

    const char * promptChars = env->GetStringUTFChars(prompt, nullptr);
    std::string promptText(promptChars ? promptChars : "");
    env->ReleaseStringUTFChars(prompt, promptChars);

    jlong reqId = 0;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto sit = g_sessions.find(sessionHandle);
        if (sit == g_sessions.end() || sit->second.ctx == nullptr || sit->second.vocab == nullptr) {
            return 0;
        }

        reqId = g_next_handle++;
        g_requests.emplace(reqId, RequestState{});
    }

    std::thread([sessionHandle, reqId, promptText, temperature, topP, maxTokens]() {
        auto t0 = std::chrono::steady_clock::now();
        const llama_vocab * vocab = nullptr;
        llama_context * ctx = nullptr;
        llama_model * model = nullptr;
        std::string systemText;
        bool missingSession = false;
        {
            std::lock_guard<std::mutex> lock(g_mutex);
            auto sit = g_sessions.find(sessionHandle);
            if (sit == g_sessions.end() || sit->second.ctx == nullptr || sit->second.vocab == nullptr) {
                missingSession = true;
            } else {
                vocab = sit->second.vocab;
                ctx = sit->second.ctx;
                model = sit->second.model;
                systemText = sit->second.system_prompt;
            }
        }
        if (missingSession) {
            request_finish(reqId, "Session not found");
            return;
        }
        NLOGI("req=%lld generation start promptLen=%zu", (long long) reqId, promptText.size());

        // Reset KV/memory between prompts to avoid stale context accumulation.
        llama_memory_clear(llama_get_memory(ctx), false);

        std::string finalPrompt = promptText;
        const char * tmpl = llama_model_chat_template(model, nullptr);
        if (tmpl != nullptr) {
            std::vector<llama_chat_message> chat;
            if (!systemText.empty()) {
                chat.push_back(llama_chat_message{"system", systemText.c_str()});
            }
            chat.push_back(llama_chat_message{"user", promptText.c_str()});

            int32_t needed = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, nullptr, 0);
            if (needed > 0) {
                std::vector<char> buf(static_cast<size_t>(needed) + 1);
                int32_t written = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
                if (written > 0) {
                    finalPrompt.assign(buf.data(), static_cast<size_t>(written));
                }
            }
        }
        auto tTemplate = std::chrono::steady_clock::now();
        NLOGI("req=%lld template applied finalPromptLen=%zu in %lld ms", (long long) reqId, finalPrompt.size(),
              (long long) std::chrono::duration_cast<std::chrono::milliseconds>(tTemplate - t0).count());

        int n_prompt = -llama_tokenize(vocab, finalPrompt.c_str(), static_cast<int32_t>(finalPrompt.size()), nullptr, 0, true, true);
        if (n_prompt <= 0) {
            request_finish(reqId, "Tokenization failed");
            return;
        }

        std::vector<llama_token> prompt_tokens(static_cast<size_t>(n_prompt));
        if (llama_tokenize(vocab, finalPrompt.c_str(), static_cast<int32_t>(finalPrompt.size()), prompt_tokens.data(), n_prompt, true, true) < 0) {
            request_finish(reqId, "Prompt tokenize pass failed");
            return;
        }
        auto tTokenize = std::chrono::steady_clock::now();
        NLOGI("req=%lld tokenized n_prompt=%d in %lld ms", (long long) reqId, n_prompt,
              (long long) std::chrono::duration_cast<std::chrono::milliseconds>(tTokenize - tTemplate).count());

        llama_batch batch = llama_batch_get_one(prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()));

        if (llama_model_has_encoder(model)) {
            if (llama_encode(ctx, batch) != 0) {
                request_finish(reqId, "Encoder eval failed");
                return;
            }

            llama_token decoder_start = llama_model_decoder_start_token(model);
            if (decoder_start == LLAMA_TOKEN_NULL) {
                decoder_start = llama_vocab_bos(vocab);
            }
            batch = llama_batch_get_one(&decoder_start, 1);
        }

        llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
        llama_sampler * sampler = llama_sampler_chain_init(sparams);

        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
        if (topP > 0.0f && topP <= 1.0f) {
            llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
        }
        if (temperature > 0.0f) {
            llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        } else {
            llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
        }

        bool anyToken = false;
        auto tFirstDecodeStart = std::chrono::steady_clock::now();
        for (int i = 0; i < maxTokens; i++) {
            if (request_is_stopped(reqId)) {
                break;
            }

            if (llama_decode(ctx, batch) != 0) {
                request_finish(reqId, "Decode failed");
                llama_sampler_free(sampler);
                return;
            }
            if (i == 0) {
                auto tFirstDecodeEnd = std::chrono::steady_clock::now();
                NLOGI("req=%lld prefill+first decode took %lld ms", (long long) reqId,
                      (long long) std::chrono::duration_cast<std::chrono::milliseconds>(tFirstDecodeEnd - tFirstDecodeStart).count());
            }

            llama_token next = llama_sampler_sample(sampler, ctx, -1);
            if (llama_vocab_is_eog(vocab, next)) {
                break;
            }

            std::string piece = token_to_piece_safe(vocab, next);
            if (!piece.empty()) {
                anyToken = true;
                request_push_token(reqId, piece);
                if (i < 5 || i % 25 == 0) {
                    auto tNow = std::chrono::steady_clock::now();
                    NLOGI("req=%lld emitted token#%d in %lld ms piece='%s'",
                          (long long) reqId,
                          i + 1,
                          (long long) std::chrono::duration_cast<std::chrono::milliseconds>(tNow - t0).count(),
                          piece.c_str());
                }
            }

            batch = llama_batch_get_one(&next, 1);
        }

        llama_sampler_free(sampler);
        if (!anyToken && !request_is_stopped(reqId)) {
            request_finish(reqId, "No tokens generated");
            return;
        }
        auto tDone = std::chrono::steady_clock::now();
        NLOGI("req=%lld done in %lld ms", (long long) reqId,
              (long long) std::chrono::duration_cast<std::chrono::milliseconds>(tDone - t0).count());
        request_finish(reqId);
    }).detach();

    return reqId;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_android_gguf_1llama_1jin_inference_LlmNativeBridgeImpl_nativePollToken(
    JNIEnv * env,
    jobject,
    jlong requestId) {

    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_requests.find(requestId);
    if (it == g_requests.end()) {
        return make_poll_result(env, "", true, "Invalid request");
    }

    RequestState & state = it->second;
    if (!state.error.empty()) {
        std::string err = state.error;
        g_requests.erase(it);
        return make_poll_result(env, "", true, err);
    }

    if (state.index < state.tokens.size()) {
        std::string token = state.tokens[state.index++];
        return make_poll_result(env, token, false, "");
    }

    if (state.stopped || state.done) {
        g_requests.erase(it);
        return make_poll_result(env, "", true, "");
    }
    return make_poll_result(env, "", false, "");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_android_gguf_1llama_1jin_inference_LlmNativeBridgeImpl_nativeStop(
    JNIEnv *,
    jobject,
    jlong) {

    std::lock_guard<std::mutex> lock(g_mutex);
    for (auto & entry : g_requests) {
        entry.second.stopped = true;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_android_gguf_1llama_1jin_inference_LlmNativeBridgeImpl_nativeUnloadModel(
    JNIEnv *,
    jobject,
    jlong modelHandle) {

    std::lock_guard<std::mutex> lock(g_mutex);
    auto mit = g_models.find(modelHandle);
    if (mit == g_models.end()) {
        return;
    }
    llama_model * modelPtr = mit->second.model;

    // free sessions using this model
    for (auto it = g_sessions.begin(); it != g_sessions.end();) {
        if (it->second.model == modelPtr && it->second.ctx != nullptr) {
            llama_free(it->second.ctx);
            it = g_sessions.erase(it);
        } else {
            ++it;
        }
    }

    if (mit != g_models.end() && mit->second.model != nullptr) {
        llama_model_free(mit->second.model);
        g_models.erase(mit);
    }
}
