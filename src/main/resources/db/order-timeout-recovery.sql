-- 已有数据库只需执行一次；新库初始化脚本已包含该复合索引。
-- status 是首列，因此该索引也覆盖仅按订单状态筛选的查询。
ALTER TABLE tb_voucher_order
    DROP INDEX idx_voucher_order_status,
    ADD INDEX idx_voucher_order_timeout_recovery (status, create_time);
