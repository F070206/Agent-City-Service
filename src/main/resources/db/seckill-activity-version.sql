-- Run once on existing databases before deploying version-aware activity cache writes.
ALTER TABLE `tb_seckill_voucher`
  ADD COLUMN `activity_version` bigint(20) NOT NULL DEFAULT 0 COMMENT '秒杀活动元数据版本';
