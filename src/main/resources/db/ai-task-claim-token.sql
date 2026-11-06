-- For databases that already created tb_ai_task before claim_token was added.
ALTER TABLE `tb_ai_task`
  ADD COLUMN `claim_token` varchar(36) DEFAULT NULL COMMENT '本次领取标识' AFTER `status`;
