-- =============================================================================
-- 08 业务数据表（订单 / 商品 / 物流 / 退款政策）
--
-- 背景
--   阶段 6 起四个业务工具的数据一直是进程内模拟（MOCK_ 常量），本脚本把它们
--   迁到真实 MySQL 表。AGENTS.md 分层约定本就是"tool 通过 service 取数"，
--   落库只是兑现这条约定。
--
-- 表命名
--   订单表叫 `orders` 而不是 `order`：ORDER 是 MySQL 保留字。
--
-- 执行顺序
--   01 → 02 → 03 → 04 → 05 → 07 → 08 → （可选）09（种子数据）→ 06（演示账号）
--   本脚本不可重复执行（无 ADD COLUMN IF NOT EXISTS / CREATE TABLE 用了 IF NOT EXISTS）。
--
-- 字段与模拟数据的对应关系见 sql/09 的注释。
-- =============================================================================

-- 订单表：owner_user_id 是归属客户（候选订单按它过滤，安全红线见 OrderTool 注释）
CREATE TABLE IF NOT EXISTS `orders` (
    `id`              BIGINT       NOT NULL COMMENT '雪花ID',
    `order_no`        VARCHAR(64)  NOT NULL COMMENT '订单号',
    `owner_user_id`   BIGINT       NOT NULL COMMENT '归属用户ID（候选按此过滤）',
    `status`          VARCHAR(32)  NOT NULL COMMENT '订单状态：待发货/已发货/已完成',
    `product_name`    VARCHAR(200) NOT NULL COMMENT '商品名（与商品表保持同名）',
    `quantity`        INT          NOT NULL DEFAULT 1 COMMENT '数量',
    `amount`          DECIMAL(12,2) NOT NULL COMMENT '实付金额',
    `order_time`      DATETIME     NOT NULL COMMENT '下单时间',
    `receiver_address` VARCHAR(500) NOT NULL COMMENT '收货地址',
    `receiver_name`   VARCHAR(64)  NOT NULL COMMENT '收件人（脱敏值）',
    `receiver_phone`  VARCHAR(32)  NOT NULL COMMENT '收件人手机（脱敏值）',
    `carrier`         VARCHAR(64)  DEFAULT NULL COMMENT '承运商（未发货为 NULL）',
    `tracking_no`     VARCHAR(64)  DEFAULT NULL COMMENT '运单号（未发货为 NULL）',
    `deleted`         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_owner_user` (`owner_user_id`, `order_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订单（客服工具数据源）';

-- 商品表：公开目录，不挂归属（与订单刻意相反，见 ProductTool 注释）
CREATE TABLE IF NOT EXISTS `product` (
    `id`           BIGINT        NOT NULL COMMENT '雪花ID',
    `product_name` VARCHAR(200)  NOT NULL COMMENT '商品名（订单里的商品名与此同名）',
    `category`     VARCHAR(64)   NOT NULL COMMENT '类目（必须能在 refund_policy 中查到）',
    `price`        DECIMAL(12,2) NOT NULL COMMENT '售价',
    `spec`         VARCHAR(500)  NOT NULL COMMENT '规格说明',
    `stock`        INT           NOT NULL DEFAULT 0 COMMENT '库存',
    `on_sale`      TINYINT       NOT NULL DEFAULT 1 COMMENT '是否在售（候选只列在售商品）',
    `deleted`      TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_product_name` (`product_name`),
    KEY `idx_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商品（客服工具数据源）';

-- 物流表：traces 用 JSON 存轨迹列表（倒序，第一条即最新轨迹），先例见 conversation.pending_clarification
CREATE TABLE IF NOT EXISTS `logistics` (
    `id`              BIGINT      NOT NULL COMMENT '雪花ID',
    `tracking_no`     VARCHAR(64) NOT NULL COMMENT '运单号',
    `carrier`         VARCHAR(64) NOT NULL COMMENT '承运商',
    `status`          VARCHAR(32) NOT NULL COMMENT '物流状态：待揽收/运输中/已签收',
    `estimated_delivery_time` DATETIME DEFAULT NULL COMMENT '预计送达时间（已签收为 NULL）',
    `traces`          JSON        NOT NULL COMMENT '轨迹列表（倒序），元素 {time, description}',
    `deleted`         TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tracking_no` (`tracking_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='物流（客服工具数据源）';

-- 退款政策表：变更必须即时生效（客服照旧政策答复用户是会产生客诉的错误，见 AGENTS.md 阶段 6）
-- 因此工具侧不做长缓存，每次查询现读
CREATE TABLE IF NOT EXISTS `refund_policy` (
    `id`                  BIGINT       NOT NULL COMMENT '雪花ID',
    `category`            VARCHAR(64)  NOT NULL COMMENT '类目（商品表 category 必须能在此查到）',
    `return_window_days`  INT          NOT NULL COMMENT '退货期限（天），0 = 不支持',
    `exchange_window_days` INT         NOT NULL COMMENT '换货期限（天），0 = 不支持',
    `rules`               JSON         NOT NULL COMMENT '政策要点列表',
    `note`                VARCHAR(500) NOT NULL COMMENT '补充说明',
    `deleted`             TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='退款政策（客服工具数据源）';
