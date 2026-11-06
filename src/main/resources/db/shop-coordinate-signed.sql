-- Existing monolith and microservice content databases: allow valid western/southern coordinates.
ALTER TABLE tb_shop
    MODIFY COLUMN x DOUBLE NOT NULL COMMENT '经度',
    MODIFY COLUMN y DOUBLE NOT NULL COMMENT '维度';
