#include <jni.h>
#include <android/log.h>
#include "llama.h"
#include <string>
#include <vector>
#include <chrono>
#include <cstring>
#include <algorithm>
#include <exception>

#define TAG "VisionLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct VisionLlamaHandle {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_vision_app_LlamaBridge_nativeLoad(
        JNIEnv* env,
        jclass /* clazz */,
        jstring pathStr,
        jint ctxTokens,
        jint threads) {
    if (pathStr == nullptr) {
        return 0;
    }
    const char* path = env->GetStringUTFChars(pathStr, nullptr);
    if (path == nullptr) {
        return 0;
    }

    try {
        llama_model_params mparams = llama_model_default_params();
        llama_model* model = llama_model_load_from_file(path, mparams);
        env->ReleaseStringUTFChars(pathStr, path);
        if (model == nullptr) {
            LOGE("Failed to load llama_model from file");
            return 0;
        }

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = ctxTokens > 0 ? (uint32_t)ctxTokens : 1024;
        cparams.n_threads = threads > 0 ? (int32_t)threads : 4;
        cparams.n_threads_batch = threads > 0 ? (int32_t)threads : 4;

        llama_context* ctx = llama_init_from_model(model, cparams);
        if (ctx == nullptr) {
            LOGE("Failed to create llama_context from model");
            llama_model_free(model);
            return 0;
        }

        auto* handle = new VisionLlamaHandle();
        handle->model = model;
        handle->ctx = ctx;
        return reinterpret_cast<jlong>(handle);
    } catch (const std::exception& e) {
        LOGE("Exception in nativeLoad: %s", e.what());
        env->ReleaseStringUTFChars(pathStr, path);
        return 0;
    } catch (...) {
        LOGE("Unknown exception in nativeLoad");
        env->ReleaseStringUTFChars(pathStr, path);
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_vision_app_LlamaBridge_nativeUnload(
        JNIEnv* /* env */,
        jclass /* clazz */,
        jlong handleVal) {
    if (handleVal == 0) {
        return;
    }
    auto* handle = reinterpret_cast<VisionLlamaHandle*>(handleVal);
    if (handle->ctx != nullptr) {
        llama_free(handle->ctx);
        handle->ctx = nullptr;
    }
    if (handle->model != nullptr) {
        llama_model_free(handle->model);
        handle->model = nullptr;
    }
    delete handle;
}

JNIEXPORT jstring JNICALL
Java_com_vision_app_LlamaBridge_nativeFormatChat(
        JNIEnv* env,
        jclass /* clazz */,
        jlong handleVal,
        jstring templateHintStr,
        jstring systemPromptStr,
        jstring userPromptStr) {
    if (handleVal == 0) {
        return nullptr;
    }
    auto* handle = reinterpret_cast<VisionLlamaHandle*>(handleVal);
    if (handle->model == nullptr) {
        return nullptr;
    }

    const char* templateHint = templateHintStr ? env->GetStringUTFChars(templateHintStr, nullptr) : nullptr;
    const char* sysPrompt = systemPromptStr ? env->GetStringUTFChars(systemPromptStr, nullptr) : "";
    const char* userPrompt = userPromptStr ? env->GetStringUTFChars(userPromptStr, nullptr) : "";

    std::vector<llama_chat_message> chat;
    if (sysPrompt && sysPrompt[0] != '\0') {
        chat.push_back({ "system", sysPrompt });
    }
    if (userPrompt && userPrompt[0] != '\0') {
        chat.push_back({ "user", userPrompt });
    }

    const char* embedded_tmpl = llama_model_chat_template(handle->model, nullptr);
    int32_t needed = -1;

    // First try the embedded template from model metadata
    if (embedded_tmpl != nullptr && embedded_tmpl[0] != '\0') {
        needed = llama_chat_apply_template(embedded_tmpl, chat.data(), chat.size(), true, nullptr, 0);
    }

    // Fall back to templateHint (e.g. "llama3" or "chatml")
    const char* effective_tmpl = embedded_tmpl;
    if (needed < 0 && templateHint != nullptr && templateHint[0] != '\0') {
        needed = llama_chat_apply_template(templateHint, chat.data(), chat.size(), true, nullptr, 0);
        effective_tmpl = templateHint;
    }

    jstring result = nullptr;
    if (needed > 0) {
        std::vector<char> buf(needed + 1, 0);
        int32_t written = llama_chat_apply_template(effective_tmpl, chat.data(), chat.size(), true, buf.data(), buf.size());
        if (written > 0) {
            result = env->NewStringUTF(buf.data());
        }
    }

    if (templateHintStr && templateHint) env->ReleaseStringUTFChars(templateHintStr, templateHint);
    if (systemPromptStr && sysPrompt) env->ReleaseStringUTFChars(systemPromptStr, sysPrompt);
    if (userPromptStr && userPrompt) env->ReleaseStringUTFChars(userPromptStr, userPrompt);

    return result;
}

JNIEXPORT jstring JNICALL
Java_com_vision_app_LlamaBridge_nativeGenerate(
        JNIEnv* env,
        jclass /* clazz */,
        jlong handleVal,
        jstring promptStr,
        jstring gbnfGrammarStr,
        jint maxTokens,
        jlong timeoutMs) {
    if (handleVal == 0 || promptStr == nullptr) {
        return nullptr;
    }
    auto* handle = reinterpret_cast<VisionLlamaHandle*>(handleVal);
    if (handle->model == nullptr || handle->ctx == nullptr) {
        return nullptr;
    }

    const char* prompt = env->GetStringUTFChars(promptStr, nullptr);
    if (prompt == nullptr) {
        return nullptr;
    }
    const char* grammar_str = gbnfGrammarStr ? env->GetStringUTFChars(gbnfGrammarStr, nullptr) : nullptr;

    const llama_vocab* vocab = llama_model_get_vocab(handle->model);
    if (vocab == nullptr) {
        env->ReleaseStringUTFChars(promptStr, prompt);
        if (gbnfGrammarStr && grammar_str) env->ReleaseStringUTFChars(gbnfGrammarStr, grammar_str);
        return nullptr;
    }

    auto start_time = std::chrono::steady_clock::now();

    // 1. Tokenize prompt
    int prompt_len = (int)strlen(prompt);
    int n_tokens = -llama_tokenize(vocab, prompt, prompt_len, nullptr, 0, true, true);
    if (n_tokens <= 0) {
        n_tokens = 512;
    }
    std::vector<llama_token> prompt_tokens(n_tokens);
    int token_count = llama_tokenize(vocab, prompt, prompt_len, prompt_tokens.data(), prompt_tokens.size(), true, true);
    if (token_count < 0) {
        prompt_tokens.resize(-token_count);
        token_count = llama_tokenize(vocab, prompt, prompt_len, prompt_tokens.data(), prompt_tokens.size(), true, true);
    }
    env->ReleaseStringUTFChars(promptStr, prompt);
    if (token_count <= 0) {
        if (gbnfGrammarStr && grammar_str) env->ReleaseStringUTFChars(gbnfGrammarStr, grammar_str);
        return nullptr;
    }
    prompt_tokens.resize(token_count);

    // 2. Initialize sampler chain with greedy/dist and optional GBNF grammar
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (grammar_str != nullptr && grammar_str[0] != '\0') {
        llama_sampler* g_smpl = llama_sampler_init_grammar(vocab, grammar_str, "root");
        if (g_smpl != nullptr) {
            llama_sampler_chain_add(smpl, g_smpl);
        } else {
            LOGW("llama_sampler_init_grammar returned NULL for grammar string");
        }
    }
    if (gbnfGrammarStr && grammar_str) {
        env->ReleaseStringUTFChars(gbnfGrammarStr, grammar_str);
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));

    // Clear KV memory for clean single-shot generation
    llama_memory_seq_rm(llama_get_memory(handle->ctx), -1, -1, -1);

    // 3. Process the prompt
    uint32_t n_batch = 512;
    for (size_t i = 0; i < prompt_tokens.size(); i += n_batch) {
        if (timeoutMs > 0) {
            auto now = std::chrono::steady_clock::now();
            auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - start_time).count();
            if (elapsed >= timeoutMs) {
                llama_sampler_free(smpl);
                return nullptr;
            }
        }
        size_t chunk_size = std::min((size_t)n_batch, prompt_tokens.size() - i);
        llama_batch batch = llama_batch_get_one(prompt_tokens.data() + i, (int32_t)chunk_size);
        if (llama_decode(handle->ctx, batch) != 0) {
            LOGE("llama_decode failed on prompt evaluation");
            llama_sampler_free(smpl);
            return nullptr;
        }
    }

    // 4. Generation token loop with timeout check on each token
    std::string result_text;
    int limit = maxTokens > 0 ? maxTokens : 256;

    for (int step = 0; step < limit; ++step) {
        if (timeoutMs > 0) {
            auto now = std::chrono::steady_clock::now();
            auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - start_time).count();
            if (elapsed >= timeoutMs) {
                LOGW("Generation timeout reached: %ld ms", (long)elapsed);
                llama_sampler_free(smpl);
                return nullptr;
            }
        }

        llama_token token = llama_sampler_sample(smpl, handle->ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }

        char piece_buf[128];
        int piece_len = llama_token_to_piece(vocab, token, piece_buf, sizeof(piece_buf), 0, false);
        if (piece_len < 0) {
            std::vector<char> dyn_buf(-piece_len);
            int piece_len_dyn = llama_token_to_piece(vocab, token, dyn_buf.data(), dyn_buf.size(), 0, false);
            if (piece_len_dyn > 0) {
                result_text.append(dyn_buf.data(), piece_len_dyn);
            }
        } else if (piece_len > 0) {
            result_text.append(piece_buf, piece_len);
        }

        llama_batch batch = llama_batch_get_one(&token, 1);
        if (llama_decode(handle->ctx, batch) != 0) {
            LOGE("llama_decode failed during token generation loop");
            break;
        }
    }

    llama_sampler_free(smpl);
    return env->NewStringUTF(result_text.c_str());
}

} // extern "C"
