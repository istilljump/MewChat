-- =============================================================================
-- MewChat —— 知识切片表
-- -----------------------------------------------------------------------------
-- 为什么需要这张表：
--   阶段 2 的表结构里，切片只存在于 Milvus（text 字段），MySQL 只有文档全文
--   knowledge_document.content。但 BM25 关键词召回必须在"段落"粒度上做，
--   否则既无法给出段落号（溯源要求），也无法与向量结果按同一粒度融合。
--   因此把切片同时落一份到 MySQL：向量库负责语义召回，MySQL 负责关键词召回。
--
-- 数据流向（由 rag/document 模块负责）：
--   文档全文 → 切片 → 本表(chunk) → 向量化 → Milvus(metadata.doc_id + chunk_no)
--   两侧用 (doc_id, chunk_no) 对齐，删除/重建时先删本表再删 Milvus。
--
-- 与其它表的差异：
--   1. 本表不做逻辑删除。切片是"可由文档全文重新推导"的派生数据，
--      重新入库时整体物理删除再插入；若用逻辑删除，(doc_id, chunk_no)
--      的唯一键会被软删的行占住，同一文档再也无法重新入库。
--   2. content 是 BM25 的检索对象，因此建 ngram 全文索引以支持中文分词。
--
-- 执行前请确认已执行 01_schema.sql。本脚本不含 DROP TABLE，可重复执行。
-- =============================================================================

USE `mewchat`;


CREATE TABLE IF NOT EXISTS `knowledge_chunk` (
    `id`             BIGINT      NOT NULL              COMMENT '主键(雪花ID)',
    `doc_id`         BIGINT      NOT NULL              COMMENT '所属文档ID，关联 knowledge_document.id',
    `chunk_no`       INT         NOT NULL              COMMENT '段落号，从1开始，与 Milvus 元数据里的 chunk_no 一致',
    `content`        TEXT        NOT NULL              COMMENT '切片原文，BM25 关键词检索的对象',
    `char_start`     INT                  DEFAULT NULL COMMENT '切片在文档全文中的起始偏移，供前端高亮定位',
    `char_end`       INT                  DEFAULT NULL COMMENT '切片在文档全文中的结束偏移',
    `content_length` INT         NOT NULL DEFAULT 0    COMMENT '切片字符数，BM25 文档长度归一化用(冗余，避免每次 CHAR_LENGTH)',
    `create_time`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    -- (doc_id, chunk_no) 唯一：既是幂等入库的保证，也是与 Milvus 切片对齐的键
    UNIQUE KEY `uk_doc_chunk` (`doc_id`, `chunk_no`),
    KEY `idx_doc_id` (`doc_id`),
    -- ngram 分词器：MySQL 默认分词按空格切，对中文等于不分词，
    -- 加 ngram 解析器后按 2-gram 切分，"退货政策"可被"退货""货政""政策"命中
    FULLTEXT KEY `ft_content` (`content`) WITH PARSER ngram
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='知识切片表(BM25 关键词召回与溯源用)';
