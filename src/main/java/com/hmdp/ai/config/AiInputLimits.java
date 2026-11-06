package com.hmdp.ai.config;

/** Shared input budget for user prompts and model-generated retrieval queries. */
public final class AiInputLimits {
    public static final int MAX_QUERY_CHARS = 8_000;
    public static final int MAX_AGENT_TOOL_OUTPUT_CHARS = 8_000;
    public static final int MAX_AGENT_TOOL_CONTEXT_CHARS = 24_000;

    private AiInputLimits() {
    }
}
