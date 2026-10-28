-- =============================================================================
-- 10 刷新令牌表
--
-- 背景
--   访问令牌是无状态的（见 TokenCodec 注释的三条已知取舍：无法撤销、
--   改密钥全体失效、到期只能重新登录）。本表给系统补上"可吊销的长期凭证"：
--   刷新令牌（rotation + 登出），访问令牌本身保持无状态不变。
--
-- 安全约定
--   只存 SHA-256 哈希、绝不存原文：库被拖走也换不出可用的令牌。
--   旋转 = 旧行置 deleted=1（条件更新，天然防并发复用）+ 新发一条。
--   登出 = 旧行置 deleted=1（幂等，重复登出无害）。
--
-- 可重复执行（CREATE TABLE IF NOT EXISTS）
-- 执行顺序：… → 08 → 09 → 10（→ 06 可选）
-- =============================================================================

CREATE TABLE IF NOT EXISTS `refresh_token` (
    `id`          BIGINT    NOT NULL COMMENT '雪花ID',
    `user_id`     BIGINT    NOT NULL COMMENT '归属用户ID',
    `token_hash`  CHAR(64)  NOT NULL COMMENT '刷新令牌的 SHA-256 十六进制摘要（不存原文：库泄露不等于凭据泄露）',
    `expire_time` DATETIME  NOT NULL COMMENT '过期时间（每次旋转重新计）',
    `deleted`     TINYINT   NOT NULL DEFAULT 0 COMMENT '逻辑删除：1=已旋转或已登出（旋转后旧值复用会被拒）',
    `create_time` DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_token_hash` (`token_hash`),
    KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='刷新令牌（可吊销的长期凭证，访问令牌保持无状态）';
