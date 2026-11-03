package com.vision.app;

import java.nio.charset.StandardCharsets;

/**
 * JNI wrapper for the embedded llama.cpp runtime library ("vision_llama").
 */
public class LlamaBridge implements LlamaBridgeInterface {
    private static volatile boolean libraryLoaded = false;
    private static Throwable loadError = null;

    static {
        try {
            System.loadLibrary("vision_llama");
            libraryLoaded = true;
        } catch (Throwable t) {
            loadError = t;
        }
    }

    public static boolean isLibraryLoaded() {
        return libraryLoaded;
    }

    public static Throwable getLoadError() {
        return loadError;
    }

    @Override
    public long load(String path, int ctxTokens, int threads) {
        if (!libraryLoaded || path == null) return 0;
        try {
            return nativeLoad(path, ctxTokens, threads);
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public String generate(long handle, String prompt, String gbnfGrammar, int maxTokens, long timeoutMs) {
        if (!libraryLoaded || handle == 0 || prompt == null) return null;
        try {
            byte[] bytes = nativeGenerate(handle, prompt, gbnfGrammar, maxTokens, timeoutMs);
            return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void unload(long handle) {
        if (!libraryLoaded || handle == 0) return;
        try {
            nativeUnload(handle);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public String formatChat(long handle, String templateHint, String systemPrompt, String userMessage) {
        if (!libraryLoaded || handle == 0) return null;
        try {
            byte[] bytes = nativeFormatChat(handle, templateHint, systemPrompt, userMessage);
            return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static native long nativeLoad(String path, int ctxTokens, int threads);
    public static native byte[] nativeGenerate(long handle, String prompt, String gbnfGrammar, int maxTokens, long timeoutMs);
    public static native void nativeUnload(long handle);
    public static native byte[] nativeFormatChat(long handle, String templateHint, String systemPrompt, String userMessage);
}
