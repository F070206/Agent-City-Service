-- Run once on an existing order database before serving shop voucher listings.
-- Fresh databases already create this index in micro-order.sql / hmdp.sql.
ALTER TABLE tb_voucher
    ADD INDEX idx_voucher_shop_status (shop_id, status);
