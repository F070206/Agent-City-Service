-- Run once on an existing tb_order_stock_sync table before deploying leased workers.
ALTER TABLE tb_order_stock_sync
    ADD COLUMN claim_token VARCHAR(36) NULL AFTER next_retry_time,
    ADD COLUMN claim_until TIMESTAMP NULL AFTER claim_token,
    DROP INDEX idx_pending_retry,
    ADD INDEX idx_pending_retry (completed, next_retry_time, claim_until, order_id);
