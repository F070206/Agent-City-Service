-- Run once on an existing content database before deploying the ranked blog query.
-- Fresh databases already create this index in micro-content.sql / hmdp.sql.
ALTER TABLE tb_blog
    ADD INDEX idx_blog_liked_id (liked, id);
