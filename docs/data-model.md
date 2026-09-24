# MewChat 数据模型设计

> 阶段 2 第一步产出。DDL 见 [`sql/01_schema.sql`](../sql/01_schema.sql)。
> 本文档说明字段设计理由、Milvus 集合结构，以及需要确认的问题。

## 0. 验证记录（2026-09-24）

DDL **已在 MySQL 8.0.34 上实际执行验证**，不是仅凭语法推断：

- 用一个隔离的临时实例（独立数据目录 + 端口 3399）执行，**未触碰本机 3306 的既有数据**，
  验证完毕后实例已关停、临时目录已删除
- 6 张表全部建成，`EXIT=0`
- **重复执行第二次仍为 6 张表、无报错** —— `IF NOT EXISTS` 的幂等性成立
- 结构断言逐条通过：`message` 无 `deleted`/`update_time`；`low_confidence_question` 无 `deleted`；
  `message.ref_docs` 确为 `JSON` 类型；其余 4 张表均有 `deleted`
- 26 个索引全部按设计建立，含 `idx_user_last`、`idx_optimized_hit` 等 **DESC 排序索引**；
  3 个唯一键（`uk_username`、`uk_session_id`、`uk_question_hash`）均生效
- 库表排序规则统一为 `utf8mb4_general_ci`

> 说明：验证用的是临时实例的空库，因此只验证了**结构**，未验证与既有业务数据的兼容性
> （当前也没有历史数据，无兼容性负担）。

---

## 1. 实体关系

```
user ──1:n──> conversation ──1:n──> message
  │                 │
  │                 └──────────────> ticket
  │                                     │
  └──1:n(handler_id)────────────────────┘

knowledge_document ──1:n──> Milvus 分片集合（通过 metadata.doc_id 关联）
        ▲
        └──────── knowledge_doc_id ──── low_confidence_question
                        （低置信度问题优化后产出文档，形成闭环）
```

**关联键的选择**：`message`、`ticket` 用 `session_id`（业务ID）而非 `conversation.id`（主键）关联。
理由是对话接口全程以 `sessionId` 为标识（见 `ChatConstants.HEADER_SESSION_ID`），
用它可以省掉写入热路径上一次"拿 sessionId 查主键"的查询。
`conversation.session_id` 上有唯一键，逻辑完整性由应用层保证。
代价是索引比 BIGINT 宽、且没有数据库级外键约束 —— 如果你更看重强约束，改为 `conversation_id BIGINT` 即可。

---

## 2. 表清单

| 表名 | 用途 | 关键设计点 |
| --- | --- | --- |
| `user` | 客户/客服/管理员共用 | `user_type` 区分角色；工单处理人也指向本表 |
| `conversation` | 会话 | `session_id` 对外暴露；带两个冗余统计字段 |
| `message` | 对话记录 | **追加型**，不做逻辑删除；记录 token/耗时/Agent/置信度 |
| `knowledge_document` | 知识库文档 | 存全文（切片源头）+ 异步向量化状态 |
| `knowledge_chunk` | 知识切片 | **阶段 5 新增**，见 §2.1；BM25 关键词召回 + 溯源的数据源 |
| `ticket` | 人工工单 | 关联会话与处理人 |
| `low_confidence_question` | 低置信度问题池 | `question_hash` 唯一键实现去重聚合 |

### 2.1 为什么阶段 5 又加了一张 `knowledge_chunk`

本来的设计里切片只存在 Milvus，MySQL 只有文档全文。做 BM25 时发现这个结构不成立：

- 关键词召回必须在**段落**粒度做，否则无法与向量结果按同一粒度融合
- 溯源要求返回段落号，而文档级命中给不出段落号
- 向量库只擅长语义检索，用它来替代关键词召回会丢掉精确匹配能力
  （用户查某个具体型号、具体单号时尤其明显）

因此切片落两份，各司其职：**MySQL 负责关键词召回与溯源，Milvus 负责语义召回**，
两侧用 `(docId, chunkNo)` 对齐，删除与重建时同步处理。

该表在 `knowledge_chunk.content` 上建了 **ngram 全文索引**（MySQL 默认分词按空格切，
对中文等于不分词），使中文能被按 2-gram 检索。建表脚本见
[`sql/02_knowledge_chunk.sql`](../sql/02_knowledge_chunk.sql)。

> ⚠ **InnoDB 全文索引在事务提交时才更新**：把"文档入库"与"检索"放在同一个大事务里，
> 刚写入的切片会搜不到。生产路径不做外层事务包裹，入库即可检索；
> 集成测试也因此不能用 `@Transactional` 回滚。

---

## 3. 几个非显而易见的设计决策

### 3.1 为什么 `message` 是追加型、没有 `deleted`

消息是对话审计日志，一旦生成就不该被改写或删除。给它加 `update_time`、`deleted`
会让"这条记录到底有没有被篡改过"变得无法判断。用户删除会话时，我们软删 `conversation`，
消息作为审计留痕保留，查询时通过会话状态过滤。

> 如果后续合规要求"用户可彻底删除对话"，再给 `message` 补 `deleted` 即可，
> MyBatis-Plus 的逻辑删除是按实体字段生效的，不影响其他表。

### 3.2 为什么 `conversation` 有两个冗余字段

`message_count` 和 `last_message_time` 都能从 `message` 表聚合出来。
冗余它们是因为**会话列表是本项目最高频的查询**，不冗余就得对写入量最大的
`message` 表做 GROUP BY。写入消息时同步维护这两个字段，代价远小于每次列表查询都聚合。

### 3.3 为什么 `low_confidence_question` 用 `optimized` 三态而不是布尔 + `deleted`

`question_hash` 上有唯一键用于去重聚合。如果这张表也用逻辑删除，被软删的行仍占用
唯一键，同一个问题就再也插不进来了。所以这张表**不设 `deleted`**，把"忽略"语义
并进 `optimized`（0待优化 / 1已优化 / 2已忽略）。

> 同样的冲突也存在于 `user.username`：软删一个账号后，该用户名无法再被注册。
> 当前选择是接受这个限制（账号一般不做物理删除）。若不能接受，
> 常见解法是把唯一键改为 `(username, deleted)` 并让 `deleted` 存删除时间戳而非 0/1。

### 3.4 `knowledge_document.embed_status` 的必要性

切片 + 向量化是耗时操作，必须异步做。没有状态字段就无法回答
"用户刚上传的文档，现在到底能不能被检索到"。状态流转：
`0待处理 → 1处理中 → 2已入库`，失败进 `3失败` 并记录 `embed_error`。

### 3.5 `confidence` 字段的语义

`message.confidence` 记录本轮"检索命中的置信度"，`low_confidence_question.confidence`
记录该问题**历史最低**的一次置信度（不是最高）。理由：我们要捞的是"Agent 一直答不好"
的问题，用最低值更能反映问题严重程度。

---

## 4. Milvus 集合结构

### 4.1 先说一个硬约束（已通过反编译 LangChain4j 1.20.0-beta30 确认）

我们引入的 `langchain4j-milvus` 中，`MilvusEmbeddingStore` **自己管理集合 schema**，
且 Builder 只暴露 4 个字段名（`idFieldName`/`textFieldName`/`metadataFieldName`/`vectorFieldName`），
**无法新增标量字段**。它在集合不存在时会自动建表，字段类型固定为：

| 字段 | Milvus 类型 | 说明 |
| --- | --- | --- |
| `id` | `VarChar` | 主键，`primary_key=true`、**`auto_id=false`**（主键必须由我们提供） |
| `text` | `VarChar(65535)` | 切片原文 |
| `metadata` | **`JSON`** | 承载所有自定义字段 |
| `vector` | `FloatVector(dim)` | 向量，`dim` 必须等于 embedding 模型输出维度 |

**结论**：你要求的"文档id""段落号"无法作为独立标量字段，必须放进 `metadata` JSON。

### 4.2 集合定义

```
集合名：mewchat_knowledge        （对应 application.yml 的 mewchat.milvus.collection-name）
索引：  HNSW                     （需在代码中显式设置，不要依赖默认值）
度量：  COSINE                   （文本向量常用；需与 embedding 模型的训练度量一致）
```

### 4.3 你要的字段如何落地

| 你的要求 | 落地位置 |
| --- | --- |
| 切片向量 | `vector` 字段（`FloatVector`） |
| 原文内容 | `text` 字段 |
| 文档id | `metadata.doc_id` |
| 段落号 | `metadata.chunk_no` |

`metadata` 的 JSON 结构：

```json
{
  "doc_id": "1727138400000000001",
  "chunk_no": 3,
  "chunk_total": 12,
  "category": "退换货",
  "doc_title": "七天无理由退换货规则",
  "char_start": 1200,
  "char_end": 1680
}
```

### 4.4 三个容易踩坑的点

1. **`doc_id` 必须存字符串，不能存数字。**
   Milvus 的 JSON 数值按 double 存储，19 位雪花ID会丢精度（double 只有 53 位有效尾数），
   导致按 doc_id 删除时匹配不上。存成字符串 `"1727..."` 才安全。

2. **切片正文必须短于 65535 字符。**
   这是 `text` 字段 `VARCHAR` 的上限。常规切片 200~1000 字符，不会触碰，
   但切片器要设硬上限并截断保护。

3. **不要手工预建集合。**
   LangChain4j 会自动创建它期望的集合；手工建一个 schema 不一致的集合，
   运行时插入会直接失败。想改 schema 只有两条路：调 Builder 的字段名，
   或者放弃 `MilvusEmbeddingStore` 自己实现（见 §4.5）。

### 4.5 `id` 用确定性值，让重新入库幂等

因为 `auto_id=false`，主键由我们给出。建议用 **`{doc_id}_{chunk_no}`** 作为 id
（如 `1727138400000000001_3`）。这样文档改切片策略后重新向量化时，
可以先按 `doc_id` 删除旧分片再插入，不会产生重复分片。

按文档删除分片（LangChain4j 的 Filter 会翻译成 Milvus 的 JSON 表达式）：

```java
store.removeAll(metadataKey("doc_id").isEqualTo("1727138400000000001"));
```

Milvus 2.5 可以为 `metadata["doc_id"]` 建 JSON 路径索引来加速这类过滤/删除；
分片量到十万级以上时值得加。

### 4.6 升级路径（如果你要真正的标量字段）

若后续需要按分类做**分区键隔离**（多租户/大知识库），或觉得 JSON 过滤不够快，
那就放弃 `MilvusEmbeddingStore`，在 `dao/milvus` 下基于官方 `milvus-sdk-java`
自行实现 `dev.langchain4j.store.embedding.EmbeddingStore<TextSegment>` 接口 ——
既能自定义 schema（`doc_id`、`chunk_no` 变成 `Int64`/`VarChar` 标量字段 + 分区键），
又能继续接入 LangChain4j 的 RAG 链路。代价是要自己写建表、插入、检索、删除。

**当前建议**：先用 `MilvusEmbeddingStore`。它开箱即用、和 LangChain4j 的
`EmbeddingStoreContentRetriever` 直接对接，而 JSON 字段的过滤能力对我们这个数据量足够了。

---

## 5. 需要你确认的问题

1. **embedding 模型与维度** —— `application.yml` 里 `mewchat.milvus.dimension` 暂填 `1024`。
   这个值必须等于最终 embedding 模型的输出维度，写错则向量入库直接报错、且需重建集合。
   选定 LLM 厂商后请一并确认 embedding 模型。
2. **是否支持未登录游客对话** —— 我让 `conversation.user_id` 可为空以支持游客。
   如果要求必须先登录，可改为 `NOT NULL` 并把匿名入口去掉。
3. **`user.username` 唯一键与逻辑删除的冲突**（见 §3.3）—— 接受限制，还是改复合唯一键？
4. **工单是否需要面向用户的工单号** —— 目前对外只能用雪花ID（如 `1727138400000000001`），
   对客展示不好看。若需要 `TK20260924001` 这类编号，得加一列 + 发号逻辑。

---

## 6. 跨表的必修项：雪花ID 的 JS 精度问题

这不是表结构问题，但它由主键选型直接决定，必须在阶段 3 写接口前解决。

雪花ID是 19 位整数（约 1.7×10^18），而 JavaScript 的 `Number` 安全整数上限是
2^53 ≈ 9.0×10^15（16 位）。**前端拿到 JSON 里的大整数会被静默截断**，
表现为"列表点进去 ID 变了"、"详情查不到"这类很难查的 bug。

解决办法：在 `config` 下加一个 Jackson 定制，把所有 `Long` 序列化为字符串：

```java
// 示意，阶段 3 实现
@Bean
Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
    return builder -> builder.serializerByType(Long.class, ToStringSerializer.instance);
}
```

前端统一按字符串处理 ID 即可。**在写第一个返回 ID 的接口之前必须先加这个配置**，
否则等前端联调时才发现，改动面会很大。
