-- =============================================================================
-- 06 演示数据（可选）
--
-- 用途
--   建两个能直接登录的账号，用来试接口 / 跑演示。生产环境绝对不要执行本脚本。
--
--   alice  user_type=1 客户     —— 其主键与 OrderTool.DEMO_OWNER_USER_ID 一致，
--                                  用它登录才能看到"候选订单"列表（候选按归属过滤）
--   admin  user_type=3 管理员   —— 能访问 /api/admin/** 运营后台
--
-- 密码
--   两个账号的密码都是 123456，存的是 BCrypt 密文。
--   ⚠️ 仅用于本地演示：弱口令 + 公开密文，上线前必须改掉或直接删掉这两个账号。
--
-- 可重复执行
--   用 ON DUPLICATE KEY UPDATE，重复执行只会把密码重置回 123456。
--
-- 执行顺序
--   01 → 02 → 03 → 04 → 05 → （可选）06
-- =============================================================================

INSERT INTO `user` (`id`, `username`, `password`, `nickname`, `user_type`, `status`, `deleted`)
VALUES (1727138400000000001, 'alice',
        '$2a$10$lzo09L/d0SShiOJsIDv/rOfzqURgoH015W0lfSXhbUGwdAHLuT1XC',
        '演示客户', 1, 1, 0),
       (1727138400000000009, 'admin',
        '$2a$10$lzo09L/d0SShiOJsIDv/rOfzqURgoH015W0lfSXhbUGwdAHLuT1XC',
        '演示管理员', 3, 1, 0)
ON DUPLICATE KEY UPDATE `password` = VALUES(`password`),
                        `user_type` = VALUES(`user_type`),
                        `status`    = VALUES(`status`);

SELECT `id`, `username`, `user_type`, `status` FROM `user`;
