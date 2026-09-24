-- =============================================================================
-- MewChat 电商 AI 智能客服 —— MySQL 建表脚本
-- -----------------------------------------------------------------------------
-- 目标库：MySQL 8.0.34
-- 建库：CREATE DATABASE mewchat DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
--
-- 全局约定：
--   1. 主键统一 BIGINT，由 MyBatis-Plus 的 assign_id（雪花算法）生成，非自增
--      —— 多机部署不会撞号；但雪花ID有 19 位，超过 JS 的 2^53 安全整数范围，
--         API 返回给前端时必须序列化为字符串，方案见 docs/data-model.md 第 7 节
--   2. 审计字段：create_time / update_time 全表统一
--   3. 逻辑删除：deleted TINYINT，0未删除 1已删除（与 application.yml 的
--      mybatis-plus.global-config.db-config.logic-delete-field 对应）
--      注意：追加型日志表（message）与需唯一约束的表（low_confidence_question）
--      不做逻辑删除，原因见 docs/data-model.md 第 6 节
--   4. 不使用物理外键：关联靠应用层保证 + 索引加速。原因是外键会在写入时加锁、
--      影响后续分库分表与数据归档。如需改成强外键约束，可自行补 FOREIGN KEY。
--   5. 金额类字段本脚本未涉及；如后续需要，统一用 DECIMAL(12,2)，不用 FLOAT/DOUBLE
--
-- 本脚本不含 DROP TABLE，可在已有库上重复执行而不破坏数据。
-- =============================================================================

USE `mewchat`;


-- =============================================================================
-- 1. 用户表
--    客服与客户共用一张表，用 user_type 区分：工单的处理人(handler_id)也指向本表
-- =============================================================================
CREATE TABLE IF NOT EXISTS `user` (
    `id`              BIGINT       NOT NULL                COMMENT '主键(雪花ID)',
    `username`        VARCHAR(64)  NOT NULL                COMMENT '登录名，唯一',
    `password`        VARCHAR(100) NOT NULL                COMMENT '密码密文(BCrypt，固定60字符，留余量)',
    `nickname`        VARCHAR(64)  NOT NULL DEFAULT ''     COMMENT '显示昵称',
    `phone`           VARCHAR(20)           DEFAULT NULL   COMMENT '手机号',
    `email`           VARCHAR(128)          DEFAULT NULL   COMMENT '邮箱',
    `avatar`          VARCHAR(255)          DEFAULT NULL   COMMENT '头像URL',
    `user_type`       TINYINT      NOT NULL DEFAULT 1      COMMENT '用户类型:1客户 2客服 3管理员',
    `status`          TINYINT      NOT NULL DEFAULT 1      COMMENT '状态:1启用 0禁用',
    `last_login_time` DATETIME              DEFAULT NULL   COMMENT '最后登录时间',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除:0未删除 1已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_phone` (`phone`),
    KEY `idx_user_type_status` (`user_type`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='用户表(客户/客服/管理员)';


-- =============================================================================
-- 2. 会话表
--    一个 session 即一次"用户打开对话框到关闭"的过程
-- =============================================================================
CREATE TABLE IF NOT EXISTS `conversation` (
    `id`                BIGINT       NOT NULL              COMMENT '主键(雪花ID)',
    `session_id`        VARCHAR(64)  NOT NULL              COMMENT '会话业务ID(UUID)，对外暴露，接口都用它',
    `user_id`           BIGINT                DEFAULT NULL COMMENT '所属用户ID；允许为空以支持未登录游客',
    `title`             VARCHAR(100) NOT NULL DEFAULT ''   COMMENT '会话标题(取首条提问或AI生成)，供会话列表展示',
    `status`            TINYINT      NOT NULL DEFAULT 1    COMMENT '状态:1进行中 2已结束 3已转人工',
    `start_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '会话开始时间',
    `end_time`          DATETIME              DEFAULT NULL COMMENT '会话结束时间，未结束为空',
    `summary`           TEXT                  DEFAULT NULL COMMENT 'AI生成的会话摘要，供长期记忆与列表预览',
    `message_count`     INT          NOT NULL DEFAULT 0    COMMENT '消息条数(冗余字段，写入消息时同步+1)',
    `last_message_time` DATETIME              DEFAULT NULL COMMENT '最后一条消息时间(冗余字段，用于会话列表排序)',
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           TINYINT      NOT NULL DEFAULT 0    COMMENT '逻辑删除:0未删除 1已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_id` (`session_id`),
    -- 会话列表主查询：某用户的会话按最近活跃倒序
    KEY `idx_user_last` (`user_id`, `last_message_time` DESC),
    KEY `idx_status_start` (`status`, `start_time`),
    KEY `idx_last_message_time` (`last_message_time` DESC)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='会话表';


-- =============================================================================
-- 3. 消息表（追加型日志，不做逻辑删除）
--    这是全库写入量最大的表，热点查询是"按 session 取全部消息"
-- =============================================================================
CREATE TABLE IF NOT EXISTS `message` (
    `id`                BIGINT       NOT NULL              COMMENT '主键(雪花ID，同时用于同一会话内消息排序)',
    `session_id`        VARCHAR(64)  NOT NULL              COMMENT '所属会话业务ID(关联 conversation.session_id)',
    `role`              VARCHAR(16)  NOT NULL              COMMENT '角色:user/assistant/system/tool',
    `content`           MEDIUMTEXT   NOT NULL              COMMENT '消息正文',
    `cost_ms`           INT                   DEFAULT NULL COMMENT '本轮生成耗时(毫秒)',
    `prompt_tokens`     INT          NOT NULL DEFAULT 0    COMMENT '输入token数',
    `completion_tokens` INT          NOT NULL DEFAULT 0    COMMENT '输出token数',
    `total_tokens`      INT          NOT NULL DEFAULT 0    COMMENT '总token数(冗余，便于直接聚合统计)',
    `model_name`        VARCHAR(64)           DEFAULT NULL COMMENT '本轮使用的模型名',
    `agent_name`        VARCHAR(64)           DEFAULT NULL COMMENT '本轮由哪个子Agent处理(supervisor路由结果)',
    `confidence`        DECIMAL(5,4)          DEFAULT NULL COMMENT '本轮置信度(0.0000~1.0000)，写入低置信度问题池的判据',
    `ref_docs`          JSON                  DEFAULT NULL COMMENT 'RAG引用的知识片段摘要，供前端展示"参考来源"',
    `status`            TINYINT      NOT NULL DEFAULT 1    COMMENT '状态:1成功 0失败',
    `error_msg`         VARCHAR(500)          DEFAULT NULL COMMENT '失败原因，仅status=0时有值',
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    -- 取某会话全部消息（按 id 升序即时间序）
    KEY `idx_session_id` (`session_id`, `id`),
    -- 统计各Agent的表现
    KEY `idx_agent_time` (`agent_name`, `create_time`),
    -- 捞低置信度样本
    KEY `idx_confidence` (`confidence`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='消息表(对话记录，追加型)';


-- =============================================================================
-- 4. 知识库文档表
--    这里的 content 是文档全文，是"切片 -> 向量化"的源头。
--    改切片策略后可以只重跑向量化，不必让用户重新上传。
-- =============================================================================
CREATE TABLE IF NOT EXISTS `knowledge_document` (
    `id`           BIGINT       NOT NULL              COMMENT '主键(雪花ID)，同时作为向量库 metadata 里的 doc_id',
    `title`        VARCHAR(255) NOT NULL              COMMENT '文档标题',
    `content`      LONGTEXT     NOT NULL              COMMENT '文档全文(切片与向量化的数据源)',
    `category`     VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '知识分类，检索时可据此过滤；后续可升级为字典表',
    `file_name`    VARCHAR(255)          DEFAULT NULL COMMENT '原始文件名(上传方式录入时有值)',
    `file_type`    VARCHAR(32)           DEFAULT NULL COMMENT '文件类型:pdf/docx/md/txt',
    `file_size`    BIGINT                DEFAULT NULL COMMENT '原始文件大小(字节)',
    `file_path`    VARCHAR(512)          DEFAULT NULL COMMENT '原始文件存储路径',
    `chunk_count`  INT          NOT NULL DEFAULT 0    COMMENT '切片数量(向量化成功后回写)',
    `embed_status` TINYINT      NOT NULL DEFAULT 0    COMMENT '向量化状态:0待处理 1处理中 2已入库 3失败',
    `embed_error`  VARCHAR(500)          DEFAULT NULL COMMENT '向量化失败原因，仅embed_status=3时有值',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      TINYINT      NOT NULL DEFAULT 0    COMMENT '逻辑删除:0未删除 1已删除',
    PRIMARY KEY (`id`),
    KEY `idx_category` (`category`),
    -- 定时任务扫描待处理/失败的文档
    KEY `idx_embed_status` (`embed_status`, `create_time`),
    KEY `idx_title` (`title`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='知识库文档表';


-- =============================================================================
-- 5. 工单表
--    Agent 无法解决或用户主动要求转人工时创建
-- =============================================================================
CREATE TABLE IF NOT EXISTS `ticket` (
    `id`          BIGINT      NOT NULL              COMMENT '主键(雪花ID)',
    `session_id`  VARCHAR(64)          DEFAULT NULL COMMENT '关联会话业务ID(关联 conversation.session_id)，可为空',
    `user_id`     BIGINT      NOT NULL              COMMENT '提单用户ID(关联 user.id)',
    `type`        VARCHAR(32) NOT NULL DEFAULT 'other' COMMENT '工单类型:refund退款 logistics物流 product商品 other其他',
    `description` TEXT        NOT NULL              COMMENT '问题描述(可取会话摘要+用户补充)',
    `status`      TINYINT     NOT NULL DEFAULT 0    COMMENT '状态:0待处理 1处理中 2已解决 3已关闭',
    `handler_id`  BIGINT               DEFAULT NULL COMMENT '处理人ID(关联 user.id，user_type=2/3)',
    `finish_time` DATETIME             DEFAULT NULL COMMENT '处理完成时间',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除:0未删除 1已删除',
    PRIMARY KEY (`id`),
    -- 客服工作台：按状态捞待处理工单
    KEY `idx_status_create` (`status`, `create_time`),
    -- 客服工作台：我的工单
    KEY `idx_handler_status` (`handler_id`, `status`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_session_id` (`session_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='人工工单表';


-- =============================================================================
-- 6. 低置信度问题池
--    Agent 答不好的问题在此沉淀，用于知识库补漏 —— 这是本项目的"数据飞轮"
--    同一问题重复出现时累加 hit_count 而非新增行，所以需要 question_hash 唯一键
-- =============================================================================
CREATE TABLE IF NOT EXISTS `low_confidence_question` (
    `id`               BIGINT        NOT NULL              COMMENT '主键(雪花ID)',
    `question`         VARCHAR(2000) NOT NULL              COMMENT '用户问题原文(取首次出现的写法)，列宽与 ChatConstants.MAX_MESSAGE_LENGTH 对齐',
    `question_hash`    CHAR(64)      NOT NULL              COMMENT '问题归一化(去空白/标点/小写)后的SHA-256，用于去重聚合',
    `confidence`       DECIMAL(5,4)  NOT NULL              COMMENT '最低一次命中的置信度(越答不好的越该被优化)',
    `hit_count`        INT           NOT NULL DEFAULT 1    COMMENT '累计出现次数，用于排优先级',
    `session_id`       VARCHAR(64)            DEFAULT NULL COMMENT '最近一次出现的会话业务ID',
    `optimized`        TINYINT       NOT NULL DEFAULT 0    COMMENT '优化状态:0待优化 1已优化 2已忽略',
    `knowledge_doc_id` BIGINT                 DEFAULT NULL COMMENT '优化后生成的知识文档ID(关联 knowledge_document.id)，形成闭环',
    `optimize_time`    DATETIME               DEFAULT NULL COMMENT '优化完成时间',
    `create_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_question_hash` (`question_hash`),
    -- 优化工作台：待优化的问题按出现次数倒序（问得最多的最该先补）
    KEY `idx_optimized_hit` (`optimized`, `hit_count` DESC),
    KEY `idx_confidence` (`confidence`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='低置信度问题池(知识补漏用)';
