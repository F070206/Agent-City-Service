-- 已有订单库部署新代码前执行；新库初始化脚本也包含该表。
CREATE TABLE IF NOT EXISTS tb_seckill_order_dispatch (
    order_id BIGINT NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    payload TEXT NOT NULL,
    completed TINYINT NOT NULL DEFAULT 0,
    attempts INT NOT NULL DEFAULT 0,
    next_retry_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    timeout_sent TINYINT NOT NULL DEFAULT 0,
    timeout_attempts INT NOT NULL DEFAULT 0,
    timeout_retry_time TIMESTAMP NULL,
    timeout_claim_token VARCHAR(36) NULL,
    timeout_claim_until TIMESTAMP NULL,
    claim_token VARCHAR(36) NULL,
    claim_until TIMESTAMP NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (order_id),
    KEY idx_dispatch_retry (completed, next_retry_time, order_id),
    KEY idx_dispatch_claim (claim_token, completed),
    KEY idx_dispatch_lease (completed, claim_until, next_retry_time, order_id),
    KEY idx_dispatch_timeout_claim (timeout_sent, timeout_retry_time, timeout_claim_until, order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
