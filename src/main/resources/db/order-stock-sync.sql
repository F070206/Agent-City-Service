-- New database / missing table only. Existing tables without lease columns use order-stock-sync-lease.sql.
CREATE TABLE IF NOT EXISTS tb_order_stock_sync (
    order_id BIGINT NOT NULL COMMENT '关单订单ID，同时作为幂等事件ID',
    voucher_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    completed TINYINT NOT NULL DEFAULT 0,
    attempts INT NOT NULL DEFAULT 0,
    next_retry_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claim_token VARCHAR(36) NULL,
    claim_until TIMESTAMP NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (order_id),
    KEY idx_pending_retry (completed, next_retry_time, claim_until, order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
