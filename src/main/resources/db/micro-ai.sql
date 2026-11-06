-- Fresh database initialization only.
CREATE DATABASE IF NOT EXISTS hmdp_ai CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE hmdp_ai;
CREATE TABLE IF NOT EXISTS `tb_ai_conversation` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户ID',
  `title` varchar(128) NOT NULL COMMENT '会话标题',
  `scene` varchar(32) NOT NULL COMMENT '最近一次识别出的场景',
  `status` tinyint(1) NOT NULL DEFAULT 1 COMMENT '状态：1-正常，0-关闭',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_ai_conversation_user_status_updated` (`user_id`, `status`, `updated_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 会话表';

CREATE TABLE IF NOT EXISTS `tb_ai_message` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `conversation_id` bigint(20) NOT NULL COMMENT '会话ID',
  `role` varchar(16) NOT NULL COMMENT '消息角色：user/assistant/tool',
  `content` text COMMENT '消息内容',
  `tool_name` varchar(64) DEFAULT NULL COMMENT '工具名',
  `tool_payload` text COMMENT '工具摘要或 JSON',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_ai_message_conversation_created` (`conversation_id`, `created_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 消息表';

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
