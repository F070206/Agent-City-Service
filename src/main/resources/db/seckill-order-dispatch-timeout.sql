-- Run once on an existing dispatch table to move delayed timeout sends off the HTTP request path.
-- Existing rows are marked sent: the DB timeout recovery worker still closes overdue orders,
-- and rescheduling old messages could create duplicates during deployment.
ALTER TABLE tb_seckill_order_dispatch
    ADD COLUMN timeout_sent TINYINT NOT NULL DEFAULT 1,
    ADD COLUMN timeout_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN timeout_retry_time TIMESTAMP NULL,
    ADD COLUMN timeout_claim_token VARCHAR(36) NULL,
    ADD COLUMN timeout_claim_until TIMESTAMP NULL,
    ADD KEY idx_dispatch_timeout_claim (timeout_sent, timeout_retry_time, timeout_claim_until, order_id);
