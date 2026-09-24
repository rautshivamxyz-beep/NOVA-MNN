// NOVA-MNN engine binding v2: chat-template path around MNN's Llm API.
// The system prompt lives as a real system message and every turn goes
// through response(ChatMessages), which applies the model's chat template
// (proper turn boundaries, EOS stopping) and reuses the KV cache - only
// the new suffix of the conversation is prefilled each turn.
// Decoding still steps one token at a time so STOP works after the
// current token.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <streambuf>
#include <ostream>
#include "llm/llm.hpp"

#define LOG_TAG "NOVA-MNN"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace MNN::Transformer;

struct NovaMnn {
    Llm* llm = nullptr;
    std::atomic<bool> stop{false};
    ChatMessages history;          // system + user/assistant turns
    std::string generated;         // current reply, accumulated from the stream
    jobject engineRef = nullptr;   // global ref to the MnnEngine instance
    jmethodID onTokenId = nullptr;
    bool haveSystem = false;
};

// std::streambuf that forwards generated chunks to Java onToken(String)
struct NovaStreamBuf : public std::streambuf {
    std::function<void(const char*, size_t)> fn;
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        if (n > 0) fn(s, (size_t)n);
        return n;
    }
    int_type overflow(int_type c) override {
        if (c != traits_type::eof()) {
            char ch = (char)c;
            fn(&ch, 1);
        }
        return c;
    }
};

static void emitToken(JNIEnv* env, NovaMnn* n, const char* s, size_t len) {
    if (n->engineRef == nullptr || n->onTokenId == nullptr) return;
    std::string chunk(s, len);
    jstring js = env->NewStringUTF(chunk.c_str());
    env->CallVoidMethod(n->engineRef, n->onTokenId, js);
    env->DeleteLocalRef(js);
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_org_nova_mnn_MnnEngine_initNative(JNIEnv* env, jobject thiz, jstring configPath, jint threads, jstring systemPrompt) {
    const char* cp = env->GetStringUTFChars(configPath, nullptr);
    std::string path(cp);
    env->ReleaseStringUTFChars(configPath, cp);
    Llm* llm = Llm::createLLM(path);
    if (llm == nullptr) {
        LOGE("createLLM failed for %s", path.c_str());
        return 0;
    }
    // lean CPU config, tuned for big.LITTLE phones: thread count = big
    // cores, low precision (fp16) compute, high power preference, and the
    // prompt cache so multi-turn chats only prefill the new suffix
    std::string cfg = "{\"backend_type\":\"cpu\",\"thread_num\":" +
        std::to_string((int)threads) +
        ",\"precision\":\"low\",\"power\":\"high\",\"prompt_cache\":true}";
    llm->set_config(cfg);
    if (!llm->load()) {
        LOGE("llm load failed for %s", path.c_str());
        Llm::destroy(llm);
        return 0;
    }
    NovaMnn* n = new NovaMnn();
    n->llm = llm;
    const char* sp = systemPrompt != nullptr ? env->GetStringUTFChars(systemPrompt, nullptr) : nullptr;
    if (sp != nullptr) {
        std::string sys(sp);
        env->ReleaseStringUTFChars(systemPrompt, sp);
        if (!sys.empty()) {
            n->history.push_back(ChatMessage("system", sys));
            n->haveSystem = true;
        }
    }
    n->engineRef = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    n->onTokenId = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
    LOGI("MNN engine ready (%d threads, chat template + prompt cache)", (int)threads);
    return (jlong)n;
}

// Blocking chat turn: response(ChatMessages, max_new_tokens=0) applies the
// chat template and prefills only the new suffix (prompt cache), then we
// step decode one token at a time so stop() lands after the current token.
JNIEXPORT void JNICALL
Java_org_nova_mnn_MnnEngine_submitNative(JNIEnv* env, jobject thiz, jlong handle, jstring prompt, jint maxTokens) {
    NovaMnn* n = (NovaMnn*)handle;
    if (n == nullptr || n->llm == nullptr) return;
    const char* p = env->GetStringUTFChars(prompt, nullptr);
    std::string input(p);
    env->ReleaseStringUTFChars(prompt, p);
    n->stop = false;
    n->generated.clear();
    NovaStreamBuf buf;
    buf.fn = [&](const char* s, size_t len) {
        n->generated.append(s, len);
        emitToken(env, n, s, len);
    };
    std::ostream os(&buf);
    n->history.push_back(ChatMessage("user", input));
    // prefill only (max_new_tokens 0) through the chat path
    n->llm->response(n->history, &os, "\n", 0);
    int count = 0;
    while (!n->stop.load() && count < (int)maxTokens && !n->llm->stoped()) {
        n->llm->generate(1);
        count++;
        auto st = n->llm->getContext()->status;
        if (st == LlmStatus::NORMAL_FINISHED) break;
    }
    // record the reply in the conversation so the next turn extends it
    n->history.push_back(ChatMessage("assistant", n->generated));
    n->llm->syncPromptCache(n->history);
    LOGI("turn done: %d tokens, history %zu messages", count, n->history.size());
}

JNIEXPORT void JNICALL
Java_org_nova_mnn_MnnEngine_stopNative(JNIEnv* env, jobject thiz, jlong handle) {
    NovaMnn* n = (NovaMnn*)handle;
    if (n != nullptr) n->stop = true;
}

// New conversation: clear turns + KV cache, keep the system message.
JNIEXPORT void JNICALL
Java_org_nova_mnn_MnnEngine_resetNative(JNIEnv* env, jobject thiz, jlong handle) {
    NovaMnn* n = (NovaMnn*)handle;
    if (n == nullptr || n->llm == nullptr) return;
    n->llm->reset();
    ChatMessages keep;
    if (n->haveSystem && !n->history.empty() && n->history[0].first == "system") {
        keep.push_back(n->history[0]);
    }
    n->history.swap(keep);
    n->generated.clear();
}

JNIEXPORT void JNICALL
Java_org_nova_mnn_MnnEngine_releaseNative(JNIEnv* env, jobject thiz, jlong handle) {
    NovaMnn* n = (NovaMnn*)handle;
    if (n == nullptr) return;
    if (n->engineRef != nullptr) env->DeleteGlobalRef(n->engineRef);
    if (n->llm != nullptr) Llm::destroy(n->llm);
    delete n;
}

// decode tokens per second, from the engine's own perf counters
JNIEXPORT jfloat JNICALL
Java_org_nova_mnn_MnnEngine_decodeSpeedNative(JNIEnv* env, jobject thiz, jlong handle) {
    NovaMnn* n = (NovaMnn*)handle;
    if (n == nullptr || n->llm == nullptr) return 0.0f;
    const LlmContext* c = n->llm->getContext();
    if (c == nullptr || c->decode_us <= 0 || c->gen_seq_len <= 0) return 0.0f;
    return (float)((double)c->gen_seq_len * 1000000.0 / (double)c->decode_us);
}

} // extern "C"
