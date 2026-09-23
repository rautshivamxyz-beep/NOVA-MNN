package org.nova.mnn;

/**
 * Minimal Java surface over the MNN LLM runtime (libnova-mnn.so +
 * libMNN.so). Streaming comes back through TokenListener.onToken,
 * called on the thread that called submit().
 */
public class MnnEngine {

    public interface TokenListener {
        void onToken(String token);
    }

    private volatile long handle = 0;
    public volatile TokenListener listener;

    public boolean isLoaded() {
        return handle != 0;
    }

    public boolean init(String configPath, int threads) {
        if (handle != 0) {
            release();
        }
        handle = initNative(configPath, threads);
        return handle != 0;
    }

    /** Blocking: streams tokens to listener until done, stopped or maxTokens. */
    public void submit(String prompt, int maxTokens) {
        if (handle != 0) {
            submitNative(handle, prompt, maxTokens);
        }
    }

    public void stop() {
        if (handle != 0) {
            stopNative(handle);
        }
    }

    public void reset() {
        if (handle != 0) {
            resetNative(handle);
        }
    }

    public void release() {
        if (handle != 0) {
            releaseNative(handle);
            handle = 0;
        }
    }

    /** Decode tokens per second of the last generation, 0 if unknown. */
    public float decodeSpeed() {
        return handle != 0 ? decodeSpeedNative(handle) : 0.0f;
    }

    // called from JNI - do not rename or change the signature
    private void onToken(String t) {
        TokenListener l = listener;
        if (l != null && t != null && !t.isEmpty()) {
            l.onToken(t);
        }
    }

    private native long initNative(String configPath, int threads);
    private native void submitNative(long handle, String prompt, int maxTokens);
    private native void stopNative(long handle);
    private native void resetNative(long handle);
    private native void releaseNative(long handle);
    private native float decodeSpeedNative(long handle);

    static {
        System.loadLibrary("nova-mnn");
    }
}
