-- ============================================================
-- 04 低置信度问题池的聚类结果
--
-- 阶段 9 引入。数据飞轮的后半段：问题池已按"完全相同的问法"做过哈希聚合，
-- 但运营要看的是"最近有 20 个人都在问赠品什么时候发货"这类**主题**，
-- 而它们的问法各不相同。每日定时任务对池子做一次聚类，把结果写回这两列，
-- 后台的"待优化清单"直接按簇读取（按规模排序）。
--
-- 执行顺序：在 01_schema.sql、02_knowledge_chunk.sql、03_pending_clarification.sql 之后执行。
-- 注意：MySQL 不支持 ADD COLUMN IF NOT EXISTS，本脚本不可重复执行。
-- ============================================================

ALTER TABLE `low_confidence_question`
    ADD COLUMN `cluster_key` VARCHAR(64) NULL
        COMMENT '聚类所属簇的键(取代表元的问题哈希)；NULL表示尚未聚类'
        AFTER `optimized`,
    ADD COLUMN `cluster_size` INT NOT NULL DEFAULT 0
        COMMENT '所在簇的规模(含代表元)；0表示尚未聚类'
        AFTER `cluster_key`;

-- 清单页的主查询：待优化的问题按簇规模、命中次数排序
-- （先按规模过滤掉"孤例"，再看被问得多的）
CREATE INDEX `idx_cluster_size` ON `low_confidence_question` (`optimized`, `cluster_size` DESC);

-- 说明：cluster_key 不建索引。它的用途是"把同一簇的行读出来后分组展示"，
-- 而不是"按键反查"，因此索引没有收益；真正需要走索引的是上面的清单查询。
