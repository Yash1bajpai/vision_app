package com.vision.app;

/**
 * Interface for native LLM operations, allowing test decoupling and JVM fakes.
 */
public interface LlamaBridgeInterface {
    long load(String path, int ctxTokens, int threads);
    String generate(long handle, String prompt, String gbnfGrammar, int maxTokens, long timeoutMs);
    void unload(long handle);
    String formatChat(long handle, String templateHint, String systemPrompt, String userMessage);
}
