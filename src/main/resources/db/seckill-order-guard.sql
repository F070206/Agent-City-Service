-- Execute once with order writes paused. First check for conflicting active history:
-- SELECT o.user_id, o.voucher_id, COUNT(*) AS active_orders
-- FROM tb_voucher_order o
-- JOIN tb_seckill_voucher s ON s.voucher_id = o.voucher_id
-- WHERE o.status <> 4
-- GROUP BY o.user_id, o.voucher_id
-- HAVING COUNT(*) > 1;
-- Resolve every returned pair before continuing. The insert intentionally fails
-- on duplicate active pairs instead of silently choosing one order.
CREATE TABLE IF NOT EXISTS tb_seckill_order_guard (
    user_id BIGINT UNSIGNED NOT NULL,
    voucher_id BIGINT UNSIGNED NOT NULL,
    active_order_id BIGINT NULL,
    PRIMARY KEY (user_id, voucher_id),
    UNIQUE KEY uk_seckill_order_guard_order (active_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Re-runnable for already migrated pairs; concurrent order writes must remain paused.
INSERT INTO tb_seckill_order_guard (user_id, voucher_id, active_order_id)
SELECT o.user_id, o.voucher_id, o.id
FROM tb_voucher_order o
JOIN tb_seckill_voucher s ON s.voucher_id = o.voucher_id
WHERE o.status <> 4
  AND NOT EXISTS (
      SELECT 1 FROM tb_seckill_order_guard g
      WHERE g.user_id = o.user_id AND g.voucher_id = o.voucher_id
  );
