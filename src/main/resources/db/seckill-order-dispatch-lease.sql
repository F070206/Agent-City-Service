-- Upgrade only an existing table created before lease columns were added.
-- If the table does not exist, create it with seckill-order-dispatch.sql instead.
ALTER TABLE tb_seckill_order_dispatch
    ADD COLUMN claim_token VARCHAR(36) NULL AFTER next_retry_time,
    ADD COLUMN claim_until TIMESTAMP NULL AFTER claim_token,
    ADD KEY idx_dispatch_claim (claim_token, completed),
    ADD KEY idx_dispatch_lease (completed, claim_until, next_retry_time, order_id);
