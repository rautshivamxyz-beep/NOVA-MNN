// NOVA-MNN engine binding: a minimal JNI wrapper around MNN's Llm API.
// Same design as llama.cpp's ai_chat binding: init / submit(streaming) /
// stop / reset / release, plus speed stats for the UI.
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
    jobject engineRef = nullptr;   // global ref to the MnnEngine instance
    jmethodID onTokenId = nullptr;
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
Java_org_nova_mnn_MnnEngine_initNative(JNIEnv* env, jobject thiz, jstring configPath, jint threads) {
    const char* cp = env->GetStringUTFChars(configPath, nullptr);
    std::string path(cp);
    env->ReleaseStringUTFChars(configPath, cp);
    Llm* llm = Llm::createLLM(path);
    if (llm == nullptr) {
        LOGE("createLLM failed for %s", path.c_str());
        return 0;
    }
    // lean CPU config: thread count and low precision = the fast path
    std::string cfg = "{\"backend_type\":\"cpu\",\"thread_num\":" +
        std::to_string((int)threads) + ",\"precision\":\"low\"}";
    llm->set_config(cfg);
    if (!llm->load()) {
        LOGE("llm load failed for %s", path.c_str());
        Llm::destroy(llm);
        return 0;
    }
    NovaMnn* n = new NovaMnn();
    n->llm = llm;
    n->engineRef = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    n->onTokenId = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
    LOGI("MNN engine ready (%d threads)", (int)threads);
    return (jlong)n;
}

// Blocking generation on the calling thread; tokens stream back through
// onToken. Prefill first, then one-token stepping so stop works cleanly.
JNIEXPORT void JNICALL
Java_org_nova_mnn_MnnEngine_submitNative(JNIEnv* env, jobject thiz, jlong handle, jstring prompt, jint maxTokens) {
    NovaMnn* n = (NovaMnn*)handle;
    if (n == nullptr || n->llm == nullptr) return;
    const char* p = env->GetStringUTFChars(prompt, nullptr);
    std::string input(p);
    env->ReleaseStringUTFChars(prompt, p);
    n->stop = false;
    NovaStreamBuf buf;
    buf.fn = [&](const char* s, size_t len) {
        emitToken(env, n, s, len);
    };
    std::ostream os(&buf);
    std::vector<int> ids = n->llm->tokenizer_encode(input);
    // prefill only (max_new_tokens 0), then step decode one token at a time
    n->llm->response(ids, &os, "\n", 0);
    int count = 0;
    while (!n->stop.load() && count < (int)maxTokens && !n->llm->stoped()) {
        n->llm->generate(1);
        count++;
        auto st = n->llm->getContext()->status;
        if (st == LlmStatus::NORMAL_FINISHED) break;
    }
}

JNIEXPORT void JNICALL
Java_org_nova_mnn_MnnEngine_stopNative(JNIEnv* env, jobject thiz, jlong handle) {
    NovaMnn* n = (NovaMnn*)handle;
    if (n != nullptr) n->stop = true;
}

JNIEXPORT void JNICALL
Java_org_nova_mnn_MnnEngine_resetNative(JNIEnv* env, jobject thiz, jlong handle) {
    NovaMnn* n = (NovaMnn*)handle;
    if (n != nullptr && n->llm != nullptr) n->llm->reset();
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
