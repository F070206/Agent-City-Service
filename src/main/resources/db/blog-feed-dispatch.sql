-- Run once on an existing content database before deploying durable blog feed fanout.
CREATE TABLE IF NOT EXISTS tb_blog_feed_dispatch (
    blog_id BIGINT UNSIGNED NOT NULL,
    follower_id BIGINT UNSIGNED NOT NULL,
    delivered TINYINT NOT NULL DEFAULT 0,
    attempts INT NOT NULL DEFAULT 0,
    next_retry_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claim_token VARCHAR(36) NULL,
    claim_until TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (blog_id, follower_id),
    KEY idx_blog_feed_dispatch_retry (delivered, next_retry_time, claim_until, blog_id, follower_id),
    KEY idx_blog_feed_dispatch_claim (claim_token, delivered),
    KEY idx_blog_feed_dispatch_cleanup (delivered, updated_at, blog_id, follower_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
