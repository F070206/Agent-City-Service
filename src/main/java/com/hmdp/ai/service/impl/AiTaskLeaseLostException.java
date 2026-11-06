package com.hmdp.ai.service.impl;

public class AiTaskLeaseLostException extends RuntimeException {
    public AiTaskLeaseLostException(Long messageId) {
        super("AI 任务租约已失效，消息ID=" + messageId);
    }
}
