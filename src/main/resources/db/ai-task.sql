-- Apply to an existing AI database before deploying durable chat task processing.
CREATE TABLE IF NOT EXISTS `tb_ai_task` (
  `message_id` bigint(20) NOT NULL COMMENT '用户消息ID',
  `conversation_id` bigint(20) NOT NULL COMMENT '会话ID',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户ID',
  `content` text NOT NULL COMMENT '原始请求内容',
  `x` double DEFAULT NULL COMMENT '请求经度',
  `y` double DEFAULT NULL COMMENT '请求纬度',
  `status` tinyint(1) NOT NULL DEFAULT 0 COMMENT '0-待处理，1-处理中，2-已完成',
  `claim_token` varchar(36) DEFAULT NULL COMMENT '本次领取标识',
  `lease_until` datetime DEFAULT NULL COMMENT '处理租约截止时间',
  `attempts` int NOT NULL DEFAULT 0 COMMENT '领取次数',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`message_id`),
  KEY `idx_ai_task_recovery` (`status`, `lease_until`, `message_id`),
  KEY `idx_ai_task_pending_recovery` (`status`, `created_time`, `message_id`),
  KEY `idx_ai_task_conversation_order` (`conversation_id`, `message_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 异步消息任务';
