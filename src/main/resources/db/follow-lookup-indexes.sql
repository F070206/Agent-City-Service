-- Run once on an existing content database before deploying follow lookup/fanout traffic.
-- Fresh databases already create both directional indexes in micro-content.sql / hmdp.sql.
ALTER TABLE tb_follow
    ADD INDEX idx_follow_user_target (user_id, follow_user_id),
    ADD INDEX idx_follow_target_user (follow_user_id, user_id);
