-- 新安装及已有数据库均需执行；仅创建持久化的超时关单失败重试表。
CREATE TABLE IF NOT EXISTS tb_order_timeout_retry (
    order_id BIGINT NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_retry_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claim_token VARCHAR(36) NULL,
    claim_until TIMESTAMP NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (order_id),
    KEY idx_timeout_retry_claim (next_retry_time, claim_until, order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
