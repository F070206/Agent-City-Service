-- Add the creation-time range index used to find aged pending AI tasks efficiently.
ALTER TABLE `tb_ai_task`
  ADD INDEX `idx_ai_task_pending_recovery` (`status`, `created_time`, `message_id`);
