-- Apply to existing AI databases before enabling ordered conversation task claims.
ALTER TABLE `tb_ai_task`
  ADD INDEX `idx_ai_task_conversation_order` (`conversation_id`, `message_id`, `status`);
