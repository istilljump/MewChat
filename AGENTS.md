# MewChat — 电商 AI 智能客服

本文档是本项目的**唯一规范来源（Single Source of Truth）**。任何代码生成、修改、评审都必须以本文档为准。
与本文档冲突的写法一律视为错误。

---

## 一、项目定位

面向电商场景的 AI 智能客服系统，核心能力：

- 多轮对话（支持 SSE 流式输出）
- Agent 编排（Supervisor 调度多个 Specialist 子 Agent）
- RAG 检索增强（Milvus 向量库 + 重排序）
- 业务工具调用（订单查询、商品查询、物流查询）
- 会话记忆（短期上下文 + 长期记忆）

---

## 二、技术栈（已按本地环境锁定）

| 组件 | 选型 | 版本 | 说明 |
| --- | --- | --- | --- |
| JDK | Java | **17**（LTS） | 本机已装 JDK 17.0.8 与 JDK 22；编译目标锁定 17，两者均可运行 |
| 框架 | Spring Boot | **3.5.13** | 对齐 LangChain4j 1.20.0-beta30 的编译目标；3.5.x 仍是 Boot 3 系列 |
| 安全 | Spring Security | 随 Boot 管理（6.5.9） | 当前 `config.SecurityConfig` 全量放行，认证模块落地后收窄 |
| LLM 框架 | LangChain4j | **1.20.0-beta30**（集成线）/ **1.20.0**（核心线） | ⚠️ 见下方"LangChain4j 双版本号"说明 |
| LLM 接入 | OpenAI 兼容协议 | — | `langchain4j-open-ai-spring-boot-starter`，改 `base-url` 即可切厂商 |
| ORM | MyBatis-Plus | **3.5.9** | ⚠️ Boot 3 必须用 `mybatis-plus-spring-boot3-starter`，**不能用** `mybatis-plus-boot-starter`（那是 Boot 2 的，本机缓存的 3.5.3.1 不适用） |
| 数据库 | MySQL | **8.0.34** | 本机已安装于 `E:\mysql\mysql-8.0.34-winx64`，注册为 Windows 服务 `MySQL`，默认未启动 |
| 驱动 | mysql-connector-j | **8.0.33** | 本机 Maven 仓库已缓存 |
| 向量库 | Milvus | `milvus-sdk-java` **2.5.9** | 官方 Java SDK；**服务端请部署 Milvus 2.5.x** |
| 构建 | Maven | **3.9.10** | 本机 `mvn` 不在 PATH，已通过项目内 `mvnw` 封装 |
| 流式输出 | Spring MVC `SseEmitter` | — | 不引入 WebFlux，避免两套 Web 栈混用 |

---

## 三、包结构（固定，不得增删顶层包）

```
com.mewchat
├── MewChatApplication.java   // 启动类
├── common       // 通用工具、常量、异常、统一返回
│   ├── result/      // Result、ResultCode 等统一响应包装
│   ├── exception/   // 业务异常、全局异常处理器
│   ├── constant/    // 常量（CommonConstants、ChatConstants）
│   ├── util/        // 工具类（HashUtils、QuestionClusterer）
│   ├── security/    // TokenCodec（令牌签发与校验）、AuthenticatedUser（principal）
│   └── observability/ // ChatTurnTrace（一轮对话摘要）、LangfuseClient（上报）
├── api          // 对外接口层
│   ├── chat/        // 对话接口（ChatController、SseStreamListener、dto/）
│   ├── auth/        // 登录换令牌（AuthController、dto/）
│   └── admin/       // 运营后台（知识库 / 工单 / 对话记录 / 统计 + AdminStatsAssembler）
├── agent        // Agent 编排核心
│   ├── ChatState / IntentType / ChatContext / ChatReply / ChatNode / HistoryTurn
│   ├── supervisor/      // ChatSupervisor、IntentRecognizer
│   │   └── node/        // 10 个编排节点（ContextLoad/GuardCheck/ResumeCheck/
│   │                    //   IntentRecognize/Route/ConfidenceCheck/Clarify/
│   │                    //   Reply/Fallback/Reject）
│   ├── specialist/      // RagSpecialist、ToolSpecialist
│   └── memory/          // ChatMemoryService
├── rag          // RAG 检索服务
│   ├── retrieval/   // KnowledgeRetriever（接口）、VectorRetriever、Bm25Retriever、
│   │                //   RrfFusion、RetrievalConfidenceCalculator、RetrievedChunk
│   ├── rerank/      // Reranker（接口）、HeuristicReranker
│   └── document/    // DocumentSplitter、DocumentIngestService
├── tool         // 业务工具集
│   ├── ToolInvoker（接口）、ToolResult、BusinessTool（工具契约）、BusinessToolInvoker（注册表）
│   └── order/ logistics/ refund/ product/   // 各业务工具：实现 BusinessTool + @Tool 注解
├── service      // 业务服务层（ConversationService、MessageService、AuthService、
│                //   TicketService、KnowledgeDocumentService / KnowledgeChunkService、
│                //   LowConfidenceQuestionService、QuestionClusteringService 及其 Impl）
├── dao          // 数据访问层（mysql、milvus）
│                //   mysql/entity 下含全部实体：Conversation、Message、User、Ticket、
│                //   KnowledgeDocument、KnowledgeChunk、LowConfidenceQuestion，
│                //   以及 JSON 值对象 PendingClarification、MessageRefDoc
├── config       // 配置类 + @ConfigurationProperties
└── job          // 定时任务
```

> `agent/supervisor/node` 是在原结构基础上新增的子包：7 个编排节点放在 supervisor 平级
> 会让该包臃肿到十几个文件，独立一层更清晰。`ChatNode` 接口放在 `agent` 根下而不是
> supervisor 内，是为了让 `specialist` 也能实现它、又不产生 specialist → supervisor 的反向依赖。
>
> `tool` 下的 `order` / `logistics` / `refund` 同理：每个业务工具一个子包，
> 新增业务能力不动编排层。

**分层调用规则（严格单向，禁止跨层/反向调用）**

```
api  →  agent / service  →  dao  →  (mysql / milvus)
                  ↑
                 tool（由 agent 调用）
```

- `api` 只做参数校验与响应封装，**不写业务逻辑**
- `agent` 负责编排与决策，**不直接访问 dao**，只能通过 `service` / `tool`
- `service` 承载业务逻辑，可调用 `dao`
- `dao` 只做数据存取，**不含业务逻辑**
- `tool` 是暴露给 Agent 的工具，内部通过 `service` 取数
  （当前三个工具的数据是进程内模拟数据，尚未接入 `service`；见阶段 6 的未验证部分）
- `config` / `common` 可被任何层依赖，自身不依赖业务层

---

## 四、编码规范

1. **注释**：所有类、所有 public 方法必须有 Javadoc；核心逻辑（编排流程、检索策略、状态流转）必须写清"为什么这么做"，而不是复述代码。
2. **分层严格**：见 §三，不跨层调用。
3. **数据库操作**：单表 CRUD 用 MyBatis-Plus（`BaseMapper` / `IService`）；**手写 SQL 一律放 XML**（`src/main/resources/mapper/*.xml`），并用 `@MapperScan` 扫描。
4. **配置**：全部写在 `application.yml`，通过 `@Value` 或 `@ConfigurationProperties` 注入，禁止硬编码。
5. **接口返回**：统一用 `com.mewchat.common.result.Result<T>` 包装；流式接口用 `SseEmitter`，其 data 亦为 `Result` 或 `Result` 的 JSON 串。
6. **异常**：业务失败抛 `BizException`，由全局异常处理器统一转成 `Result`，禁止在 Controller 里 try-catch 后返回裸字符串。
7. **命名**：类名 `UpperCamelCase`，方法/变量 `lowerCamelCase`，常量 `UPPER_SNAKE_CASE`，数据库表/字段 `snake_case`。
8. **实体分层**：数据库实体放 `dao/mysql/entity`，对外传输对象放 `api/*/dto`，两者不混用。

---

## 五、构建与运行

本机 `mvn` 不在 PATH，统一使用项目内 Wrapper：

```bash
./mvnw clean compile     # 编译
./mvnw clean package     # 打包
./mvnw spring-boot:run   # 运行
```

Maven 发行版：`C:\Users\<你的用户名>\.m2\wrapper\dists\apache-maven-3.9.10-bin\...`

### 基线验证记录（2026-09-24）

`./mvnw -B clean package` —— **BUILD SUCCESS**，`Tests run: 2, Failures: 0, Errors: 0`。
可执行 jar 产出正常（`target/mewchat-0.0.1-SNAPSHOT.jar`，含 `BOOT-INF/` 嵌套依赖）。

实际解析到的关键版本（已确认无 Boot 2 旧包混入）：

| 依赖 | 解析版本 | 说明 |
| --- | --- | --- |
| Spring Boot | 3.5.13 | 与 LangChain4j 编译目标一致 |
| Spring Framework | 6.2.17 | |
| spring-boot-starter-tomcat | 10.1.53 | Jakarta EE 10，符合 Boot 3 |
| spring-security-bom | 6.5.9 | |
| mybatis-plus-spring-boot3-starter | 3.5.9 | |
| com.baomidou:mybatis-plus-jsqlparser | 3.5.9 | ⚠️ 见下方"分页插件需单独引入"说明 |
| com.github.jsqlparser:jsqlparser | 5.0 | 由 mybatis-plus-jsqlparser 传递引入 |
| org.mybatis:mybatis-spring | 3.0.4 | Boot 3 专用线 |
| org.mybatis:mybatis | 3.5.16 | 由 MyBatis-Plus 传递引入 |
| dev.langchain4j:langchain4j | 1.20.0 | 核心模块 |
| langchain4j-spring-boot-starter | 1.20.0-beta30 | 集成模块 |
| langchain4j-open-ai-spring-boot-starter | 1.20.0-beta30 | |
| langchain4j-milvus | 1.20.0-beta30 | |
| io.milvus:milvus-sdk-java | 2.5.9 | |
| io.grpc:* | 1.59.1 | 由 Milvus SDK 传递引入 |
| com.google.protobuf:protobuf-java | 3.25.5 | 已用 dependencyManagement 仲裁 |
| com.mysql:mysql-connector-j | 8.0.33 | runtime |
| com.fasterxml.jackson.core:jackson-databind | 2.21.2 | |
| org.projectlombok:lombok | 1.18.36 | |

**依赖冲突**：引入 Milvus SDK 后曾出现 4 条真实冲突（protobuf-java 在 3.23.2/3.24.0/3.25.5 间裁决，
error_prone_annotations 被降级到 2.20.0），已在 pom 的 `dependencyManagement` 中钉死为
`protobuf-java:3.25.5` 与 `error_prone_annotations:2.41.0`。当前依赖树无冲突。

**分页插件需单独引入（MyBatis-Plus 3.5.9 的坑）**：从 3.5.9 起，依赖 JSqlParser 的
`InnerInterceptor`（含 `PaginationInnerInterceptor`、`BlockAttackInnerInterceptor`）
被拆到独立模块，**不再随 starter 传递引入**。缺了它编译期就会报
`找不到符号: 类 PaginationInnerInterceptor`。已在 pom 中补上 `mybatis-plus-jsqlparser`。

**分页插件必须在插件链最后 add**（官方要求），当前配置已遵循。

### 端到端验证结论

| 项目 | 结果 |
| --- | --- |
| Spring 上下文加载 | 通过，启动耗时约 3.3s |
| `ChatModel` Bean 创建 | 通过（断言 LangChain4j 通用接口，换厂商不用改测试） |
| Tomcat 监听 8080 | 通过 |
| Security 放行 | 通过：`POST /api/chat` 返回 404 而非 401 |
| 统一返回与异常处理 | 通过：`{"code":10002,"message":"请求路径不存在: /api/chat/x","timestamp":"2026-09-24 09:26:08","success":false}` + HTTP 404 |

唯一启动警告：`No MyBatis mapper was found in '[com.mewchat.dao]'` —— 阶段 1 尚无 Mapper，正常现象。

### 阶段 2/3 落地与验证（2026-09-24）

已落地的层次：

| 层 | 内容 |
| --- | --- |
| `dao.mysql.entity` | 6 个实体 + `MessageRefDoc`（JSON 值对象，非独立表） |
| `dao.mysql.mapper` | 6 个 Mapper，仅继承 `BaseMapper`，无自定义方法 |
| `dao.milvus` | `MilvusVectorDao`：插入 / 检索 / 删除 + 元数据键名常量 |
| `config` | MyBatisPlus、Milvus、AiModel、Sse、Jackson 五个配置类 + 三个 Properties |
| `sql` | `01_schema.sql`（已在 MySQL 8.0.34 实测执行） |
| `docs` | `data-model.md`（字段设计理由 + Milvus 集合结构） |

验证结果（`./mvnw test` → `Tests run: 9, Failures: 0, Errors: 0`）：

- **3 个装配测试**：`ChatModel`、`StreamingChatModel`、`EmbeddingModel` 三个 Bean 均创建成功；
  6 个 Mapper 全部注册
- **6 个真实 MySQL 映射测试**（`MysqlSchemaMappingTest`）：覆盖列名 camelCase↔snake_case 映射、
  雪花ID生成、审计字段自动填充、`ref_docs` JSON 往返（含 19 位 docId 不丢精度）、
  逻辑删除与物理删除的差异
- 该测试默认跳过（`@EnabledIfSystemProperty`），所以无 MySQL 的机器上 `mvn test` 依然是绿的。
  要执行它：
  ```bash
  ./mvnw test -Dmewchat.it.mysql=true \
    -Dmewchat.it.mysql.url="jdbc:mysql://127.0.0.1:3306/mewchat?useSSL=false&allowPublicKeyRetrieval=true"
  ```

**关键配置决策：关闭了 LangChain4j 的 OpenAI 自动配置。**
`config.AiModelConfig` 独占 `ChatModel`/`StreamingChatModel`/`EmbeddingModel` 三个 Bean 的创建，
因此启动类上 `exclude` 了 `dev.langchain4j.openai.spring.AutoConfig`。
后果：**大模型配置项在 `mewchat.llm.*` 下，写 `langchain4j.open-ai.*` 不会生效**。

**未验证的部分（务必知悉）**：Milvus 相关代码只做到**编译通过**。
本机没有 Milvus 服务端，`MilvusEmbeddingStore` 的建集合、写入、检索、
按元数据删除这条链路没有实际运行过。`mewchat.milvus.enabled` 默认 `false`，
启用前请先在真实 Milvus 2.5.x 上完整验证一遍。

### 阶段 4 落地与验证（2026-09-24）

Agent 编排核心已落地：

| 位置 | 内容 |
| --- | --- |
| `agent` 根 | `ChatState`（含转移表）、`IntentType`（含路由目标与必需参数）、`ChatContext`、`ChatReply`、`ChatNode`、`HistoryTurn` |
| `agent/supervisor` | `ChatSupervisor`（状态机驱动）、`IntentRecognizer`（含输出容错与降级） |
| `agent/supervisor/node` | 7 个编排节点 |
| `agent/specialist` | `RagSpecialist`、`ToolSpecialist` |
| `agent/memory` | `ChatMemoryService`（短时/长时记忆、指代消解） |
| `rag/retrieval` | `KnowledgeRetriever` 接口 + `KnowledgeChunk`（**实现待阶段 5**） |
| `tool` | `ToolInvoker` 接口 + `ToolResult`（**实现待阶段 6**） |
| `service` | `ConversationService` / `MessageService` / `LowConfidenceQuestionService` |

**流程怎么走不由大模型决定。** 模型只在三个点被调用（意图识别、文本生成、记忆压缩），
每次调用都有严格的输出校验与降级路径；而状态转移由 `ChatState` 的转移表决定，
每次跳转都要过 `canTransitionTo` 校验。三道防线：

1. **启动时**校验每个非终态有且仅有一个节点，缺失即启动失败（编排错误暴露在开发期）
2. **运行时**校验每次转移合法性，节点跳出错误状态立即抛异常
3. **兜底**：任何未预期异常收敛为一次兜底回复，用户不会收到空回复或 500

验证结果（`./mvnw test -Dmewchat.it.mysql=true` → `Tests run: 23, Failures: 0, Errors: 0`）：

- `ChatStateTest`（7 项，默认执行）：状态可达性、终态无后继、每个状态都能到 END、
  非法转移被拒、意图路由目标必须落在 ROUTE 的合法后继内、意图解析对非法输入降级
- `ChatSupervisorIntegrationTest`（7 项，需 MySQL）：追问/回复/兜底三条主路径的
  **状态轨迹逐项断言**、模型输出不合规时降级为 UNKNOWN、消息与会话落库及统计字段、
  **短时记忆真的进入了第二轮提示词**、指代消解结果被下游使用、兜底问题进低置信度池
- 默认 `mvn test` 不连数据库也能通过（13 项 gated 测试自动跳过）

**测试挖出的一个真实设计缺陷（已修）**：`ChatReply.finalState` 原先取流程游标
`context.getState()`，而流程走完游标必然停在 `END` —— 结果是 `finalState` 永远是 END、
`isFallback()`/`isClarifying()` 永远为 false，**并且连带导致 `persist()` 里
`state == FALLBACK` 的判断失效，兜底问题根本不会写入低置信度池**，整个"数据飞轮"静默失效。
修复方式：在 `ChatContext` 中把"流程游标"与"产出答案的状态"拆成 `state` / `finalState`
两个字段。这个缺陷编译期完全不可见，只有对状态轨迹做端到端断言才能发现。

**未接线/未验证的部分**：

- `ChatMemoryService.closeSession()`（生成摘要 + 置结束）**当前没有任何调用方**。
  它需要一个触发源：对话接口提供"结束会话"接口，或由 `job` 定时扫描长时间无新消息的会话。
  在触发源落地前，**长时记忆不会真正产生数据**。
- 真实大模型未接入：测试用的是确定性替身，`api-key` 仍是占位值。
  真实模型下的提示词效果、输出稳定性、指代消解准确率都需要另做验证。
- Milvus 链路依然只做到编译通过（见上）。

### 阶段 5 落地与验证（2026-09-24）

RAG 检索模块已落地：

| 位置 | 内容 |
| --- | --- |
| `rag/retrieval` | `ChunkTokenizer`、`VectorRetriever`、`Bm25Retriever`、`RrfFusion`、`RetrievalConfidenceCalculator`、`KnowledgeRetriever`、`RetrievalResult`、`RetrievedChunk` |
| `rag/rerank` | `Reranker`（接口）、`HeuristicReranker` |
| `rag/document` | `DocumentSplitter`、`DocumentIngestService`、`DocumentIngestRequest` |
| `rag` | `RagService`（门面，实现 `KnowledgeRetriever`） |
| `dao` / `service` | `KnowledgeChunk` 实体与 Mapper（**BM25 的 SQL 在 XML 里**）、`KnowledgeChunkService`、`KnowledgeDocumentService` |
| `sql` | `02_knowledge_chunk.sql`（新增切片表 + ngram 全文索引） |
| `job` | `SessionSummaryJob`（会话收尾，**长时记忆的触发源**） |

**新增了一张表 `knowledge_chunk`，这是被需求逼出来的**：BM25 要在"段落"粒度做关键词召回
并给出段落号，而阶段 2 的表结构里切片只存在 Milvus，MySQL 只有文档全文 ——
没有切片表就无法满足溯源要求。现在切片同时存 MySQL（关键词召回 + 溯源）与
Milvus（语义召回），两侧用 (docId, chunkNo) 对齐。

**BM25 是自己算的，不是用 MySQL 的 MATCH 打分**：`MATCH ... AGAINST` 返回的是
TF-IDF 风格的相对分，不是 BM25。这里把 MySQL 当作**可走索引的候选源与统计源**
（ngram 全文索引召回候选、`MATCH ... AGAINST` 统计文档频率 df、聚合查询取 N 与 avgdl），
BM25 公式在 Java 侧完成。分词按 2-gram 切分，对齐 `ngram_token_size=2`；
跨标点不组词（否则会产生索引里根本不存在的词项）。

**RRF 只按排名融合，不把两路分数相加**：向量相似度是 0~1 的余弦值，
BM25 是无上界的对数加权和，量纲不同；相加需要归一化，而归一化又依赖当次结果集、
跨查询不稳定。RRF 只使用排名，天然规避这个问题。

**重排是规则式的，不是交叉编码器**：`HeuristicReranker` 用"融合分 + 词项覆盖度 +
标题匹配"加权。真正意义上的重排应换成 cross-encoder（bge-reranker 之类）
或让大模型直接打分，效果通常明显更好 —— `Reranker` 接口就是替换点，
换实现不需要改 `RagService`。之所以没直接上模型：交叉编码器要额外部署模型服务，
大模型打分要为每个候选多付一次调用，两者都该先在真实数据上验证收益再进主链路。

**降级设计**：Milvus 未启用时向量通道返回空，整条链路降级为**纯关键词检索**，
知识问答依然可用（只是少了语义召回）。"向量库还没部署"不等于"知识库不能用"。

⚠ **InnoDB 全文索引在事务提交时才更新**。把"文档入库"与"检索"放进同一个大事务，
刚写入的切片会搜不到（`MATCH ... AGAINST` 看不到未提交的行）。
生产路径上 `DocumentIngestService.ingest()` 不开启外层事务、写完即提交，
所以入库后可立即检索；但**不要把它包进长事务**。

验证结果（`./mvnw test -Dmewchat.it.mysql=true` → `Tests run: 59, Failures: 0, Errors: 0`）：

- **纯单测（默认执行，不依赖数据库）**：分词粒度与 ngram 对齐 / 标点断词 / 去重 / 数量上限；
  RRF 归一化、"两路都命中优于单路第一名"、同片段去重合并；置信度公式
  （单个高分不满分、三条满分、低于下限不计数量、权重越界裁剪）；
  分片器的句子合并、重叠、偏移与原文一致、无标点强制切分
- **集成测试（需 MySQL）**：入库分片与段落号连续、**关键词召回带出 docId/标题/段落号**、
  置信度随命中变化、孤儿切片被剔除、重新入库不重复、删除后不再被检索到、
  Milvus 未启用时"状态如实标记失败但关键词索引可用"
- **全链路测试** `ChatWithRagIntegrationTest`：让**真实的 `RagService` 接进编排链路**，
  验证场景一「知识问答有出处」—— 回复分支、引用来源齐全、引用随消息落库；
  以及"库里没有相关内容时走兜底并进低置信度池"的对照用例

**集成测试已做到跑完零残留**（user/ticket/conversation/message/knowledge_chunk/
knowledge_document/low_confidence_question 计数全为 0），可以安全地在开发库上执行。
其中 `ChatSupervisorIntegrationTest` 与 `MysqlSchemaMappingTest` 用事务回滚；
两个 RAG 测试因上述全文索引的提交限制改为真实提交 + 物理清理（`JdbcTemplate`）。

**未验证的部分（务必知悉）**：

- **向量召回通道 `VectorRetriever` 只做到编译通过**：本机没有 Milvus，
  `EmbeddingModel` 的 api-key 仍是占位值。上面所有检索验证走的都是
  **BM25 单通道**路径。接上真实 Milvus 与 embedding 模型后必须重新验证
  （尤其要确认 `mewchat.milvus.dimension` 与 embedding 模型输出维度一致）。
- **重排效果未验证**：规则式重排与交叉编码器的实际差距需要真实数据对比。
- **长时记忆的效果未验证**：`SessionSummaryJob` 需要真实模型才能生成有意义的摘要。

### 阶段 6 落地与验证（2026-09-24）

业务工具集已落地（对应场景二：多轮对话查订单 / 物流）：

| 位置 | 内容 |
| --- | --- |
| `tool` | `BusinessTool`（单个工具的契约）、`BusinessToolInvoker`（注册表，实现 `ToolInvoker`）；`ToolResult` 新增 `notFound` 与 `clarifyHint` |
| `tool/order` | `OrderTool` —— 订单查询（`order_query`）：状态、商品、金额、下单时间、收货地址 |
| `tool/logistics` | `LogisticsTool` —— 物流查询（`logistics_query`）：状态、最新轨迹、预计送达时间 |
| `tool/refund` | `RefundTool` —— 按商品类目查退款政策（`refund_policy_query`）：退款规则、退换货期限 |
| `agent` | `ToolSpecialist` 改用工具名常量、支持工具自拟追问话术；`IntentType.LOGISTICS_QUERY` 不再声明必需参数；`IntentRecognizer` 提示词补充 `trackingNo` / `category` |

**工具名只有一个来源。** `@Tool(name = ...)` 与 `BusinessTool.name()` 引用**同一个常量**，
注册表按 `name()` 分发，并在启动时校验"存在与工具名一致的 `@Tool` 方法"，不一致直接启动失败。
之所以不用 `switch` 分发：漏写一个分支是**静默故障** —— 工具类写好了、注解也标了，
调用时却报"未注册的工具"，而自动收集让这一幕在结构上不可能发生。

**三件"输入问题"都不许走转人工。** 缺参数 → `needMoreInfo`；单号查不到 → `notFound`；
两者都导向追问分支。理由：用户把单号写错/记错是最常见的输入问题，反问一句就能解决；
若按失败处理，置信度归零会走到兜底，用户听到的是"系统暂时处理不了，建议转人工" ——
把"您的单号可能不对"说成了"我答不上来"，还凭空多出一张人工工单。
为此 `ToolResult` 增加了 `clarifyHint`：**"缺参数"与"参数给了但值不对"是两种成因，
话术必须不同**（"麻烦提供订单号" vs "没查到订单号 X，麻烦核对"），
而只有工具知道它拿什么去查、查的结果是什么。参数没给时仍由 `ToolSpecialist` 按参数名生成话术。

**物流只认运单号，订单号要经订单换算。** 用户手上通常只有订单号（运单号在面单上），
所以 `LogisticsTool.invoke` 会依次尝试"直接给的运单号 → 由订单号反查出的运单号 →
把给的值也当订单号试一次"（模型把订单号填进 `trackingNo` 键是最常见的抽取错误）。
订单号→运单号这一段是**工具之间的依赖**（`LogisticsTool` → `OrderTool`），
而不是在物流数据里再抄一份映射 —— 两份映射迟早不一致。

**`IntentType` 表达不了"二者取一"。** `requiredParams` 是"全都必需"的语义，
而物流查询是"运单号或订单号任一"。写 `orderNo` 会把"只给了运单号"这种完全正常的问法
挡在门外，因此 `LOGISTICS_QUERY` 的必需参数**置空**，改由工具按自身最小信息需求校验。
两道校验不是重复，而是分工不同（前者是声明式前置拦截，后者是工具自保）。

**出参分两路，互不解析对方的表达。** `summary()` 是给大模型读的自然语言，
`toData()` 是给接口层渲染的结构化数据（金额是 `BigDecimal`、期限是天数，
不是"7天"这类字符串 —— 给字符串等于把解析工作推给每个下游）。
物流的"最新轨迹"**不单独存字段**，而是取轨迹列表第一条（列表按时间倒序）：
存两份迟早出现"最新轨迹"与"轨迹明细"互相矛盾，而排查要翻两处代码。

**脱敏在数据层就做掉**：模拟数据里收件人姓名与手机号本身就是脱敏值。
客服场景不需要完整 PII，一旦进了提示词就等于把用户隐私交给了第三方模型服务商。

**踩到的两个坑（记下来避免重犯）**：

- **接口的静态方法不会被实现类继承**。`BusinessTool.stringParam(...)` 在实现类的嵌套
  record 里不能不加限定地调用，必须写 `BusinessTool.stringParam(...)`，否则编译报"找不到符号"。
- **`ObjectProvider` 不是函数式接口**（同时继承 `ObjectFactory` 与 `Iterable`），
  测试里不能用一个 lambda 冒充它，必须写成匿名类。

验证结果（`./mvnw clean package` → **BUILD SUCCESS**，`Tests run: 110, Failures: 0, Errors: 0, Skipped: 27`）：

- **新增 46 个默认执行的单测**：三个工具各自的"正常 / 没给参数 / 值查不到"三条分支；
  注册表的分发、未注册工具名、重名与注解校验（**重名与注解缺失都在启动期报错**，
  断言的是根因而非被包了一层的 `BeanCreationException`）；
  `ToolSpecialistTest` 用**真实注册表**驱动节点，断言"缺参数时不发工具调用"
  （用记录型包装器验证）、"查不到时用工具拟的话术追问"、"工具服务缺失或抛异常时降级不抛异常"
- **启动日志确认注册**：`业务工具注册完成，共 3 个：[order_query, logistics_query, refund_policy_query]`
- 全量 110 项含此前的 RAG / 编排 / 映射测试，无回归

**未验证的部分（务必知悉）**：

- **`ToolCallIntegrationTest`（5 项，需 MySQL）没有实际跑过**：本机 MySQL 服务已停止，
  启动需管理员权限（`net start MySQL` 返回"发生系统错误 5：拒绝访问"）。
  这 5 项里最关键的一条是**"工具查到的业务数据真的进了回复提示词"** ——
  工具与回复节点之间隔着 `ChatContext.toolResult` 这一跳，
  断了同样表现为"回答里没有订单信息"且不报任何错，单测覆盖不到。MySQL 可用后请务必执行：
  ```bash
  ./mvnw test -Dmewchat.it.mysql=true \
    -Dmewchat.it.mysql.url="jdbc:mysql://127.0.0.1:3306/mewchat?useSSL=false&allowPublicKeyRetrieval=true"
  ```
- **三个工具的数据都是进程内模拟数据**（`OrderTool.MOCK_ORDERS`、`LogisticsTool.MOCK_LOGISTICS`、
  `RefundTool.POLICIES`），只有 3 个订单 / 3 条运单 / 6 个类目。
  真实接入时替换 `queryOrder` / `queryLogistics` / `queryPolicy` 的取数实现即可，
  调用方只依赖 `BusinessTool` 契约。按分层约定应把取数封装到 `service` 层，工具改为注入该服务。
  退款政策还要额外注意**变更要能即时生效**：政策表被缓存住而运营改了规则，
  客服就会照旧规则答复用户，这是会产生客诉的错误。
- **`RefundTool` 当前没有被编排调用**：`REFUND_ASK` 意图路由到 RAG
  （政策原文按语义检索更合适）。工具本身可用（按工具名调用、供将来的模型自主调用路径使用），
  要接进确定性路径只需在 `ToolSpecialist` 的"意图 → 工具名"映射里加一行，工具层不用改。
- **商品查询（`ProductTool`）没有实现**：阶段 6 的指令清单里只列了订单 / 物流 / 退款三个工具。
- **"模型自主选择工具"这条路径还没有接**：`@Tool` 注解当前的作用是
  ①声明工具对模型的契约（名称、用途、参数结构）②启动期校验。
  运行期的工具选择仍然由意图查表决定，没有 `AiService` / 工具调用循环。

### 阶段 7 落地与验证（2026-09-24）

对话接口 + SSE 流式 + 鉴权已落地（"把核心能力暴露成接口，前端可以对接"）：

| 位置 | 内容 |
| --- | --- |
| `api/chat` | `ChatController`（三个接口）、`SseStreamListener`（SSE 事件流）、`dto/`（`ChatSendRequest`、`ChatMessageView`） |
| `api/auth` | `AuthController`（登录换令牌）、`dto/`（`LoginRequest`、`LoginResponse`） |
| `common/security` | `TokenCodec`（HMAC-SHA256 令牌签发与校验）、`AuthenticatedUser`（principal） |
| `service` | `AuthService`（凭证校验 + 签发）；`ConversationService` 新增 `createForUser` / `getOwnedBySessionId` / `resolveOwnedSession` |
| `agent` | `ChatStreamListener`（流式回调）；`ChatContext` 携带回调；`ChatSupervisor` 新增 `processStream` 与 userId 入参；`ReplyNode` 支持真实逐字流式 |
| `config` | `SecurityConfig`（收窄授权 + Bearer 过滤器 + 统一 401/403 响应）、`AuthProperties`；`SseConfig`/`SseProperties` 复用既有配置 |

接口清单与 SSE 事件：

| 接口 | 认证 | 说明 |
| --- | --- | --- |
| `POST /api/auth/login` | 免认证 | 换访问令牌（**唯一免认证入口**） |
| `POST /api/chat/session` | 需要 | 新建会话，返回 sessionId |
| `POST /api/chat/send` | 需要 | 发起对话，SSE 流式返回 |
| `GET /api/chat/session/{sessionId}/history` | 需要 | 历史消息，含引用来源 |

| SSE 事件 | data | 说明 |
| --- | --- | --- |
| `session` | `Result<String>` | 会话ID，首帧 |
| `state` | `Result<String>` | 流程状态中文名，进度提示 |
| `message` | `Result<String>` | 回复文本片段 |
| `done` | `Result<ChatReply>` | **本轮权威结果**，含引用来源、置信度、是否转人工 |
| `error` | `Result<Void>` | 失败原因 |

**流式是"真流式"，不是把完整回答切片。** `ReplyNode` 在上下文带回调时走
`StreamingChatModel`，片段来自模型客户端的回调；不带回调时走原同步模型。
两条路径**共用同一份提示词** —— 各写一份的话，流式与非流式的回答质量会悄悄分叉，
而这种差异极难被发现。把异步回调桥回同步流程用 `CountDownLatch`，
并且**必须有等待上界**（取配置超时的 3 倍）：没有上界时，客户端一旦不回调就会永久占住一条 SSE 线程。

**"半句话"问题（务必知悉）。** 推送中途失败时，已经到客户端的片段收不回来。
这里仍然降级为兜底 —— 把半截回答伪装成完整回答、让用户以为"这就是全部"更糟。
因此约定：**`done` 事件里的 `ChatReply` 是权威结果，前端应当替换而非追加气泡**。
另外追问与兜底的话术是固定文本、完全不经过模型，一个片段都没有，
SSE 层会在结束时补发一次完整内容，否则前端会渲染出一个空气泡。

**片段与完整响应不一致时采信片段。** 部分兼容端点在 `onCompleteResponse` 里给的是另一份
（被截断或去过空白的）文本；若采信它，"用户看到的"和"落库的"就不是同一段话，
刷新页面后"回答变了"。不一致时记 WARN。

**鉴权用自包含令牌（JWS 的最小可用子集）。** payload 为
`userId|过期时间戳|用户名`，HMAC-SHA256 签名，用 JDK 的 `Mac` 实现，**不引入第三方依赖**：
这里没有自创密码学，自创的只是令牌格式（怎么拼字段），算法、密钥、比较都来自 JDK。
签名比较用 `MessageDigest.isEqual`（常数时间），逐字节比较会泄露签名内容。
密钥缺失或短于 16 位直接**启动失败**，而不是退化成弱密钥悄悄跑起来。

> **已知取舍（部署前必读）**：①无法撤销单个令牌，要强制下线只能换密钥（那会让所有人一起失效）；
> ②不校验用户是否仍存在/被禁用（无状态换来"每请求零次查库"，禁用账号的令牌在有效期内仍可用）；
> ③payload 只是 Base64 编码、**不是加密**，任何人可读，因此不放敏感信息。
> 若需要吊销、密钥轮转、RS256，应当换成成熟库（JJWT/Nimbus），而不是在这里继续加。

**会话归属必须校验，两端都校验。** 会话ID 泄露一次就等于对话内容泄露，
因此 `history` 与 `send` 都会校验归属，且"会话不存在"与"会话不属于你"返回**同一句提示**
（不透露存在性，否则可被用来枚举他人的会话ID）。会话ID 由服务端 UUID 生成，
**不接受调用方传入** —— 交给各调用方自己造（时间戳、自增数）时，只要有一处可被枚举就全部失守。
`send` 的归属校验刻意放在**建立 SSE 连接之前**：拿别人的会话ID 发消息应直接收到一个 JSON 错误，
而不是先收到一串状态事件、再收到错误（那会让前端以为对话已经开始）。

**两种失败形态并存（前端要同时判断）**：Security 过滤器给出的 401/403 是**真正的 HTTP 状态码**；
而业务层归属校验失败抛出的 `BizException` 按 §四.6 的约定转成 **HTTP 200 + 业务码 20002**。
这是既有统一返回约定的取舍，若要改成一致的 HTTP 状态码需另行决定。

验证结果（`./mvnw clean package` → **BUILD SUCCESS**，`Tests run: 137, Failures: 0, Errors: 0, Skipped: 27`）：

- **新增 27 个默认执行的测试**：
  - `TokenCodecTest`（11）：合法令牌往返、过期时间与配置一致、过期令牌被拒、改 payload / 改签名 /
    换密钥签发一律被拒、畸形输入不抛异常、弱密钥启动失败、用户名含分隔符不被截断
  - `ReplyNodeStreamTest`（7）：片段按序回调、非流式不碰流式模型、**片段优先于完整响应**、
    推前失败降级、推后失败降级（并确认片段已不可撤回）、空回复降级、回调不触发时按上界超时
  - `ChatControllerTest`（9，MockMvc + 真实安全链）：无令牌 401、伪造令牌 401、建会话、
    读他人会话被拒且不透露存在性、历史映射（含引用来源与雪花ID序列化为字符串）、
    **SSE 事件帧序**（session→state→message→done）、无令牌的流式请求同样被拒、
    参数校验、固定话术补发
- **真容器冒烟测试**（`java -jar` + curl，非 MockMvc）：无令牌 `401 + code 20001`、
  伪造令牌 `401`、登录接口确实放行（无 MySQL 时走到业务层报 10003 而非 401）、
  参数校验返回 `10001` + 字段级提示；启动日志确认 **9 个编排节点 / 3 个业务工具 / SSE 线程池**均已装配

**测试抓到的真实缺陷（已修）**：为让"令牌过期"可测而给 `TokenCodec` 加了第二个构造器后，
Spring 遇到**多个都未标注 `@Autowired` 的构造器**时会退回去找无参构造器，
直接报 `No default constructor found` —— **整个应用的上下文起不来**。
修复：在公开构造器上标注 `@Autowired`。
这个缺陷纯单测看不到，只有真的加载 Spring 上下文才会暴露（`ChatControllerTest` 顺带把上下文加载也覆盖了）。

**未验证的部分（务必知悉）**：

- **没有跑过真实模型的流式回答**：`api-key` 仍是占位值，本机也没有可用的模型端点。
  SSE 链路、令牌鉴权、会话归属都用替身与真容器验证过（事件帧与状态码都对），
  但"接上真实模型后逐字输出是否顺畅、首字节延迟多少、长回答会不会被 SSE 超时掐断"
  需要真实模型才能回答。`mewchat.sse.timeout-ms` 默认 5 分钟，需按实际模型速度确认。
- **登录链路没有端到端跑过**：需要 MySQL（`user` 表），本机 MySQL 未启动、启动需管理员权限。
  `AuthService` 的凭证校验、BCrypt 比对、令牌签发目前只有编译期与 Mock 保证。
  注意：库里没有用户时，登录会返回"用户名或密码不正确"而非"用户不存在"（刻意的防枚举设计），
  别误判成配置错误。
- **浏览器原生 `EventSource` 用不了这个接口**：它无法自定义请求头，带令牌的 SSE 需要用
  `fetch` + `ReadableStream` 自行解析事件流（把令牌放查询参数也能跑，但令牌会进访问日志，不推荐）。
- **客户端断开后不取消生成**：断开后不再推送，但流程会跑完并落库（用户刷新后能在历史里看到完整回答）。
  真正的取消（省下模型 token）需要把中断信号传回编排层，属于后续优化。
- **跨域需显式配置**：`mewchat.cors.allowed-origins` 默认留空（仅同源），前后端分离部署时必须配置，
  且不要用通配符。
- **没有 refresh token / 续期 / 登出**：令牌到期后只能重新登录；无状态令牌下"登出"只能靠前端丢弃令牌。
- **`ProductTool` 仍未实现**（阶段 6 遗留），`RefundTool` 仍未接入意图路由。

### 阶段 8 落地与验证（2026-09-24）

对应场景三、四（不乱说、不瞎猜）。三块机制全部落地：

| 机制 | 位置与内容 |
| --- | --- |
| 置信度三档闸值 | `AgentProperties` 把单个 `confidence-threshold` 拆成 `high/low-confidence-threshold`；`ConfidenceCheckNode` 重写；`RagSpecialist` 支持按 `retry-top-k-multiplier` 放大召回做补充检索 |
| 兜底 + 自动工单 | `FallbackNode` 用固定话术；新增 `TicketService`，由 `ChatSupervisor.createHandoffTicket` 在兜底分支自动建单 |
| 安全护栏 | `GuardrailProperties` / `GuardrailService` / `GuardCheckNode` / `RejectNode`；`ChatState` 新增 `GUARD_CHECK`、`REJECT` |
| 澄清追问与断点续接 | `ClarificationOption` + `BusinessTool.listOptions`（默认无候选）+ `ToolInvoker.listOptions`；`PendingClarification`（JSON 值对象）+ `conversation.pending_clarification` 列 + `sql/03_pending_clarification.sql`；`ResumeCheckNode`；`ChatState` 新增 `RESUME_CHECK` |

状态机从 9 个状态增至 12 个，流转图已更新（见 `ChatState` 类注释）：

```
CONTEXT_LOAD ─▶ GUARD_CHECK ─▶（违规）─▶ REJECT ─▶ END
                    │
                    ▼
              RESUME_CHECK ─▶（用户选了候选）─▶ TOOL_CALL（跳过意图识别与路由）
                    │
                    ▼
              INTENT_RECOGNIZE ─▶ ROUTE ─▶ {RAG_RETRIEVE, TOOL_CALL, CLARIFY}
                                            │
              CONFIDENCE_CHECK ◀────────────┘
                    │  ▲
        （中档）────┘  └── 补检索 ──┐
                    │             │
              {REPLY, FALLBACK, CLARIFY, RAG_RETRIEVE}
```

> ⚠️ **升级须知（会直接影响能不能启动正常跑）**：`conversation` 实体新增了一列，
> 所有会话查询都会带上 `pending_clarification`。**执行过 `sql/03_pending_clarification.sql`
> 之前，涉及会话的功能会直接报"未知的列"**。脚本不可重复执行（MySQL 不支持
> `ADD COLUMN IF NOT EXISTS`）。

**中档判据用的是「回答置信度」而不是「有效置信度」。** 三档里最容易写错的是中档：
有效置信度取 `min(意图置信度, 回答置信度)`，若直接用有效值判断该不该补检索，
就会出现"意图分只有 0.5、检索分高达 0.95"时也去补检索的情况 ——
而<b>补检索只能改善检索分数，改善不了意图置信度</b>，再跑一次有效值依然是 0.5，纯属白费。
因此补检索的条件里明确要求"回答分本身落在中档"。

**补检索只许一次。** `ChatContext.retrievalRetry` 是显式标记（不是去数轨迹里出现过几次检索）：
轨迹是给人看的，拿它当控制条件会把关键逻辑藏在历史里。补检索走的就是原来的
`RAG_RETRIEVE` 状态（不新增状态、不新增节点），`RagSpecialist` 按标记放大 `topK`。
反复放宽标准直到满意为止，等于把置信度变成摆设。

**兜底话术有两句，虽然需求只给了一句。** 没找到依据时说
"知识库中没有找到可靠答案，已为您转接人工客服"（需求原文）是准确的；
但模型超时、工具报错时说同一句话就是**误导** —— 问题不在知识库而在系统，
用户按"知识库没有"去理解会反复重问。因此系统故障走另一句，两句都不含内部细节。

**工单：同一会话只留一张未关闭工单。** 用户答不上来时常会换个说法连问几次，
每问一次建一张单，客服工作台会被同一个会话刷屏。因此建单是幂等的（已有未关闭工单则复用）。
两个现实约束写进了代码：`ticket.user_id` 是 `NOT NULL`，所以**游客会话不建单**（只记日志）；
`type` 统一为 `other`，因为更细的类型需要按意图区分，而意图是编排层的概念、
`service` 层不该认识它（等客服工作台真要按类型分派时，由编排层映射好再传进来）。

**护栏：归一化后再匹配，这是它有没有价值的分水岭。** 复用
`HashUtils.normalizeQuestion`（去空白与标点、统一小写）后再做"包含即命中"，
因此"刷 单""刷-单""刷【单】"都会被拦下；不做归一化的话，绕过护栏只需插一个空格。
三条硬约束：①词表不能放常见单字或常用词（"赌"字会把"押金赌博纠纷"这类正常投诉一并拦下）；
②命中的词只进服务端日志，<b>不告诉用户"因为哪个词被拦"，也不在话术里复述它</b>，
否则等于把规则边界交出去；③护栏位置在上下文加载<b>之后</b>、意图识别<b>之前</b> ——
之后是为了让违规消息留审计痕迹，之前是为了不给模型"顺着违规话题答下去"的机会。

**`REJECT` 与 `FALLBACK` 必须是两个状态。** 兜底的含义是"我答不上来"（转人工 + 建工单 +
进低置信度池补知识）；拒答的含义是"这类内容我不回答"，是策略决定而不是能力不足。
把违规提问当成"知识盲区"喂进补知识流程，只会污染那套数据飞轮。

**续接只认「序号式」输入。** 用户如果把订单号原样打出来（"MC202409240001"），
走正常流程本来就能被意图识别正确抽取 —— <b>序号是唯一"正常流程处理不了"的输入</b>，
只有它需要续接。反之若把"消息里含订单号"也当成选中，用户接着问
"MC202409240001 这个能退吗"就会被误判成"他选了第一个订单"而直接去查订单状态。
三个必需的收尾动作：①`resolvedMessage` 必须被覆盖 —— 指代消解会把"1"原样返回，
而 `effectiveQuery()` 优先取它，不覆盖的话模型看到的用户问题就是一个数字；
②意图置信度给满分（意图是上一轮识别过的，只是用户确认了候选），
否则有效置信度被压低，"明明查到了订单"却走兜底；③续接后立即清掉挂起状态。

**挂起状态必须落库，不能放内存。** 用户回复"1"时可能已过几分钟、可能落在另一台实例、
可能中间重启过 —— 这三件事都会让"1"变成一个无法理解的新问题。与会话记忆不放内存同理。
不建独立表：它与会话严格一对一、生命周期完全跟随会话。

**MyBatis-Plus 的两个坑（都在这一个功能上踩到）**：①实体字段上加了
`typeHandler = JacksonTypeHandler` 还不够，`@TableName` 必须带 `autoResultMap = true`，
否则**查回来是一串未解析的 JSON 文本**；②`@TableField` 默认**忽略值为 null 的字段**，
而"清空挂起状态"正是把列置 null —— 必须显式 `updateStrategy = FieldStrategy.ALWAYS`，
否则清空语句里根本没有这一列，表现为用户早就答完了、之后随口回一个"1"又被续接回旧追问。
写这一列用 `updateById` 带实体（而非 `lambdaUpdate().set(列, 值)`），
因为 wrapper 的 `set` 不会带上字段的 typeHandler。

验证结果（`./mvnw clean package` → **BUILD SUCCESS**，`Tests run: 167, Failures: 0, Errors: 0, Skipped: 27`）：

- **新增 30 个默认执行的测试**：
  - `ConfidenceCheckNodeTest`（10）：达标直答、中档补检索、低于兜底线兜底、
    **已补过不再补**、**意图分低但回答分高时不补**（避免白跑）、无召回不补、追问优先于置信度、
    闸值写反/相等时启动失败
  - `GuardrailServiceTest`（8）：命中配置词、**空格/连字符/括号/换行的规避写法都拦得住**、
    正常咨询不误伤、关闭时不拦、空词表不拦、空白词条不被当成"命中一切"、
    话术不含命中词、null/纯标点输入不抛异常
  - `ResumeCheckNodeTest`（11）：数字与中文序号续接、序号变体（`1)`/`1、`/`第1个`/`一`）、
    保留原意图、**直接打订单号走正常流程**、换话题清挂起、越界序号不猜、过期不续接、
    无挂起/读库失败/意图名解析失败都降级为正常流程
  - `ToolSpecialistTest`（10，其中 3 个新增）：追问<b>列出候选并挂起状态</b>、
    物流缺标识时候选项填的是 `orderNo`、单号查不到时用工具话术且**不挂起**
- 全量 167 项含此前各阶段测试，无回归（新增状态导致原有 8 处轨迹断言同步更新；
  `ChatWithRagIntegrationTest` 改为断言流程"形状"，因为它用真实 BM25、分数会落在中档触发补检索）

**未验证的部分（务必知悉）**：

- **三块新机制都没有跑过真实 MySQL**：本机 MySQL 未启动（启动需管理员权限）。
  `sql/03` 的 DDL 未在真实库执行过，`pending_clarification` 这一列
  <b>与 `JacksonTypeHandler` 的实际往返没有实测</b>（写入与读回的类型转换是纯推理结论，
  依据是 `message.ref_docs` 已在阶段 2 验证过同一条路径）。
  工单落库、挂起状态落库同样只有编译期与 Mock 保证。
  MySQL 就绪后请按顺序执行 `sql/01`、`sql/02`、`sql/03`，再跑：
  ```bash
  ./mvnw test -Dmewchat.it.mysql=true \
    -Dmewchat.it.mysql.url="jdbc:mysql://127.0.0.1:3306/mewchat?useSSL=false&allowPublicKeyRetrieval=true"
  ```
- **候选订单列表没有按用户过滤**（`OrderTool.listOptions`）：模拟数据本身没有归属信息，
  硬编一个用户ID 只会让任何真实登录用户看到一个空列表，候选功能等于不存在。
  <b>真实实现必须按 userId 过滤</b> —— 列出别人的订单是泄露他人交易信息，
  这一点在代码注释与 AGENTS.md 里都标了。物流侧复用同一份候选（不另造一份订单列表）。
- **护栏是词表黑名单，挡不住同义改写、拆字、拼音、外语表达**：
  它是一道"提高绕过成本"的护栏，不是不可突破的防线。真正的兜底应当是
  "模型侧的内容安全策略 + 人工复核"。另外词表目前在 yml 里，生产应改为配置中心或词库表。
- **兜底建单的类型统一为 `other`**：更细的类型（退款/物流/商品）需要按意图映射，
  而 `service` 层不该认识编排层的意图枚举，等客服工作台真的需要按类型分派时再做。
- **没有工单处理侧的接口**：工单只创建、没有人接单/关单/查询的接口（属管理端 `api.admin`，尚未开始）。
- **续接是"硬续接"**：用户选中候选项后直接进工具层，不会再确认一次"您要查的是这个订单吗"。
  若选错，用户需要重新描述问题。
- 阶段 6/7 遗留未变：`ProductTool` 未实现、`RefundTool` 未接进意图路由、
  三个工具仍是进程内模拟数据、真实大模型的流式回答未验证。

### 阶段 9 落地与验证（2026-09-24）

对应"数据飞轮 + 运营后台 + 可观测性"：

| 要求 | 落地情况 |
| --- | --- |
| 低置信度问题自动进池 | **阶段 2/5 已实现，本阶段未改动**：`LowConfidenceQuestionService.record()` 按归一化哈希聚合（累加 `hitCount`、保留最低置信度），兜底分支由 `ChatSupervisor` 自动调用 |
| 定时任务：去重 → 聚类 → 待优化清单 | 去重已在写入侧；本阶段新增 `QuestionClusterer`（纯算法）+ `QuestionClusteringService`（逻辑）+ `QuestionClusteringJob`（每日调度）+ 问题池两列 `cluster_key` / `cluster_size`（`sql/04`） |
| 运营后台接口 | 新增 `api/admin` 下 4 个控制器共 13 个接口、7 个视图/请求 DTO、1 个统计装配器；`/api/admin/**` 仅管理员可访问 |
| Langfuse 可观测 | `ObservabilityProperties` + `LangfuseClient` + `ChatTurnTrace`；由 `ChatSupervisor.reportTurn` 在每轮结束时上报 |

后台接口清单：

| 分组 | 接口 |
| --- | --- |
| 知识库管理 | `POST /api/admin/knowledge/documents`（录入）、`GET .../documents`（分页）、`POST .../documents/{id}/reindex`（重建索引）、`DELETE .../documents/{id}`（删除） |
| 工单处理 | `GET /api/admin/tickets`（分页，可按状态筛）、`POST .../tickets/{id}/assign`（指派）、`POST .../tickets/{id}/resolve`（解决）、`POST .../tickets/{id}/close`（关闭） |
| 对话记录 | `GET /api/admin/conversations`（分页，可按用户/状态筛）、`GET .../conversations/{sessionId}/messages`（完整对话，含 token/耗时/失败原因/引用） |
| 数据统计 | `GET /api/admin/analytics/overview`（总览）、`GET .../analytics/optimization-checklist`（待优化清单）、`POST .../analytics/clustering/recluster`（立即重算聚类） |

**去重与聚类是两件事，别混。** 问题池在写入时已按归一化哈希聚合，但那只能合并
<b>完全相同的问法</b>。运营要看的是主题 —— "最近 20 个人都在问赠品什么时候发货"
这 20 条问法各不相同，哈希聚合把它们放在 20 行里，看清单的人得自己肉眼归并。
聚类就是把这一步自动化。两步的分工写在 `LowConfidenceQuestionService` 的类注释里。

**聚类用的是字面相似度，局限必须一起记住。** `QuestionClusterer` 对归一化文本切 2-gram、
算 Jaccard 系数，因此它归得动"赠品什么时候发货"与"赠品什么时候发货呢"，
但<b>归不动同义改写</b>（"赠品何时寄出"）。它是"能自动归并一部分、剩下的仍需人工看"的清单，
不是语义聚类。真正该做的是用 embedding 算相似度 —— 项目里已有 embedding 模型与 Milvus，
`QuestionClusterer.cluster(...)` 就是那个可以整体替换的算法入口，换实现时调用方不用改。
阈值 `similarity-threshold` 默认 0.55 只是起点，必须拿真实问题池调。

**聚类结果只写两列，不建簇表。** 簇键取<b>代表元（被问得最多的那条）的 questionHash</b>：
它有唯一键、稳定、且能直接追溯到"这簇是以哪条问题命名的"。
存进去的两列让清单页按规模排序时不必把整池捞出来在内存分组。
每天<b>全量重算</b>而不是增量维护：昨天孤立的两个问题今天可能被一条新问题连起来，
增量要处理合并与拆分，复杂度远高于收益。

**聚类逻辑放在 service、任务只做调度。** 因为它有两个触发源：每日定时与后台"立即重算"。
逻辑写在任务里，后台接口就得反过来依赖定时任务，把"什么时候跑"与"跑什么"耦合在一起。

**统计数据放在接口层（`AdminStatsAssembler`），这是一次有意的取舍。**
统计的产出形状<b>就是接口契约本身</b>；放回 service 就得再定义一套 service 视图、
再写一遍逐字段映射，那份镜像没有独立价值，只会制造"改了 DTO 忘了改镜像"的隐患。
它只读不写、不改变任何业务状态。代价是报表口径与接口定义放在了一起 ——
等报表复杂到需要多页复用或下推到数仓时，应当整体迁到独立读模型，而不是继续往这个类里加。
另外两个口径上的刻意选择：平均值<b>只统计助手消息</b>（把用户消息算进去会稀释成无意义的数字）；
`dailyMessages` 不补零（缺数据的日子由前端按自己的时区处理更合适）。

**后台权限：令牌里带了用户类型。** 用户在 `user` 表里本来就有 `userType`（1客户 2客服 3管理员），
现在把它签进令牌（payload 变为 `userId|过期时间|userType|用户名`），
过滤器据此授予 `ROLE_CUSTOMER/AGENT/ADMIN`，`/api/admin/**` 要求 `ROLE_ADMIN`。
取舍与"不校验账号是否被禁用"同类：<b>改权限不会立即生效</b>，要等令牌过期。
另：<b>客服（userType=2）当前也不能访问后台</b> —— 要放开必须先设计
"客服能看哪些工单/会话"的权限模型，而不是把后台整体开放（`AdminApiTest` 里有一条断言记录了这个口径）。
注意令牌 payload 只是 Base64 编码、不是加密，所以前端可以直接解码它来知道自己的角色，
无需额外加一个"我是谁"的接口。

**Langfuse：三条不可动摇的约束。** ①<b>绝不影响业务</b>（异步发送、异常全吞只记日志）；
②<b>未配置时静默跳过</b>（没有密钥就不发请求、只提示一次，否则没接 Langfuse 的部署会被连接失败日志刷屏）；
③<b>不默认打印内容</b>（payload 含用户问题与回答，需显式开 `log-payload`）。
用 JDK 自带的 `HttpClient` 而不是引入 Langfuse SDK：这里只需要往 ingestion 接口 POST 一个 JSON。
**只上报轮次级 trace**：因此"意图识别花了多少 token""回复生成花了多久"这类明细看不到，
要拿到它们应挂 LangChain4j 的 `ChatModelListener`（它能拿到每次模型调用的提示词与返回），
那才是模型调用级埋点的正确做法，届时本类退化成"只负责发送"。

**两个依赖既有代码的顺带修正**：①`TicketService` 原本是注入 Mapper 的普通 `@Service`，
分页/条件更新/计数都用不了，本阶段改为继承 `ServiceImpl`（与其它业务服务一致）；
②`DocumentIngestRequest` 是 `@Builder` + final 字段、没有 setter，Jackson 无法从请求体反序列化，
因此后台接口单独定义了带校验的 `CreateDocumentRequest`，而不是硬给它加一套反序列化注解。

验证结果（`./mvnw clean package` → **BUILD SUCCESS**，`Tests run: 196, Failures: 0, Errors: 0, Skipped: 27`）：

- **新增 29 个默认执行的测试**：
  - `QuestionClustererTest`（9）：近似问法归并、不同问题不混、**同义改写归不动（把已知局限写成断言）**、
    标点空白不影响、空问题不参与（否则会凭"空集合相似"聚成一大簇）、单字问题可用、
    阈值调高更保守、**结果确定可复现**、空输入不抛异常
  - `LangfuseClientTest`（7，用 JDK 自带 `HttpServer` 起真实本地服务端）：
    **Basic 认证头拼接正确**、payload 的 `trace-create` 形状与 metadata 齐全
    （token/耗时/检索命中/工具调用）、userId 为空时省略字段、
    未配置与显式关闭时一个请求都不发、**服务端 400 与端口不可达都不抛异常**
  - `AdminApiTest`（12，MockMvc + 真实安全链）：匿名 401、**逐路径断言普通用户被 403**、
    客服同样被拒（记录当前口径）、工单列表/结单、**读任意用户对话记录**、
    会话不存在返回 404 而非空列表、知识库列表/录入/参数校验、统计总览、手动重算聚类
  - `TokenCodecTest` +1：**用户类型原样往返**（错位就会把普通用户认成管理员，而功能上毫无症状）
- 全量 196 项含此前各阶段测试，无回归

**未验证的部分（务必知悉）**：

- **`sql/04_question_cluster.sql` 未在真实 MySQL 上执行过**：问题池新增两列与一个索引的 DDL
  只有语法层面的把握。聚类写回（`assignCluster` 的批量更新）、后台各接口的查询、
  统计的聚合 SQL（`AVG`、`DATE()` 分组）都<b>没有跑过真实 MySQL</b> ——
  它们分别靠 Mock 与单测覆盖，SQL 本身未验证。升级时按 01 → 02 → 03 → 04 顺序执行。
- **Langfuse 没有对真实服务验证过**：客户端这一侧用本地 HTTP 服务端验了认证头与 payload 形状，
  但 Langfuse 服务端是否接受这个 payload（字段名与类型）没有实测。
  第一次接上时请临时打开 `mewchat.observability.langfuse.log-payload: true`，
  被拒绝时会打出响应体，能直接看出是哪个字段的问题。
- **聚类阈值未用真实数据调过**，0.55 只是起点；且字面聚类对同义改写无能为力（见上）。
- **统计是实时扫表**：单表计数 + 一次聚合，数据量大后会成为慢查询，
  届时应做预聚合（定时任务算好落表），而不是让后台首页继续扫全表。
- **飞轮闭环缺最后一步**：`optimized` 状态与 `knowledgeDocId` 字段早已存在，
  但后台没有"把某个问题标记为已优化并关联到新文档"的接口 ——
  运营看完清单去补文档后，问题池里那几行的状态无法回写，清单不会自动收敛。
  这是下一步最该补的接口。
- **后台没有分权**：客服与管理员共用同一套接口，只有管理员能进（见上）。
- **工单只有处理侧、没有客服工作台的"我的工单"视角**（按 handlerId 查询未提供）。
- **知识库只支持文本录入，不支持文件上传**：PDF/Word 的正文抽取需要额外的解析库（尚未引入）。
- 阶段 6/7/8 遗留未变：`ProductTool` 未实现、`RefundTool` 未接进意图路由、
  三个业务工具仍是进程内模拟数据（且候选订单未按用户过滤）、真实大模型的流式回答未验证。

### 阶段 10 全量复查与缺陷修复（2026-09-24）

对既有代码做了一次全量审查（构建 + 逐模块读码 + 真容器冒烟）。**默认测试集此前是红的**：
`TokenCodecTest.tamperedSignatureShouldBeRejected` 失败。修完之后 `./mvnw clean package`
→ **BUILD SUCCESS**，`Tests run: 205, Failures: 0, Errors: 0, Skipped: 27`。

#### 修掉的最严重缺陷：每一轮对话都直接兜底（已修）

`ContextLoadNode` 的出口写的是 `INTENT_RECOGNIZE`，而转移表只允许
`CONTEXT_LOAD → GUARD_CHECK`（阶段 8 引入护栏时漏改了这一个节点）。
`ChatSupervisor` 每次转移都校验合法性，于是**第一步就抛 `IllegalStateException`**，
被 catch 收敛成兜底：意图识别、检索、工具、回复生成<b>一次都不会执行</b>，
用户永远收到"处理您的请求时出现了异常"；同时每轮都按兜底处理 —— 问题全进低置信度池
（污染数据飞轮）、每条消息建一张工单；`GUARD_CHECK`/`RESUME_CHECK`/`REJECT` 变成不可达，
护栏形同不存在。

**为什么之前 196 项测试全绿也看不出来**：能覆盖它的都是 MySQL 门控的集成测试
（默认跳过 27 项），而唯一默认执行的接口测试 `ChatControllerTest` 用
`@MockitoBean` 把 `ChatSupervisor` 整个替换掉了 —— 状态机的走向根本不在它的视野里。
`ChatStateTest` 只验转移表自身的性质，验不到"节点返回的是不是表里允许的后继"。

**补的防线**：新增 `ChatFlowWiringTest`（4 项，默认执行、不依赖 Spring 与数据库），
把 12 个真实节点用 Mockito 替身接起来跑真实状态机，逐项断言状态轨迹
（知识命中 → REPLY、护栏命中 → REJECT、工具成功 → REPLY、无依据 → FALLBACK）。
它同时钉住了下面那条"查到数据却走兜底"的修复。
**不引入 H2 建表脚本**是刻意的：那份脚本与 MySQL 版一旦产生偏差，
测试会在错误的前提下通过 —— 这正是本文件反复强调的假绿灯。

#### 其余已修缺陷（按严重度）

| 缺陷 | 后果 | 修法 |
| --- | --- | --- |
| `application.yml` 给 `token-secret` 配了可用的默认值 `dev-only-secret-...` | `TokenCodec` 的"密钥缺失即启动失败"是**死代码**；密钥随仓库公开，任何人可签出 `userType=3` 的令牌拿到 `ROLE_ADMIN`，而应用看起来正常 | 改为 `${MEWCHAT_TOKEN_SECRET:}`，让既有校验真正生效（见下方"运行前置条件"） |
| `SseStreamListener.finish()` 见 `closed` 就直接 return | 客户端断开的请求**永不调用 `complete()`**，异步请求与连接一直挂到 5 分钟超时 | 拆出独立的 `finished` 标志；连接失效也照样归还异步请求 |
| `TokenCodec` 接受非规范 Base64URL 签名（末字符填充位） | 同一签名有多个等价字符串，改动末位字符的令牌依然有效（签名可变形） | 验签前先比对"重新编码是否与原串一致" |
| `ChunkTokenizer` 的 `maxTerms` 在"分隔符触发中途落笔"时不生效 | 一段带标点的中文提问会一次写入上百词项，**一个词项一次 df 查询** → 单轮上百条 SQL | 上限传入 `flush*`，写满即停；收尾裁剪仅作兜底 |
| `ConfidenceCheckNode` 只看 `min(意图分, 回答分)` | 工具**已查到订单**但意图分 0.60 < 0.70 时被判不可信 → 答复"处理不了、已转人工"，数据被丢弃，还进问题池并多建一张工单（`RESUME_CHECK` 早已修过同类问题，正常路径漏改） | 工具 `success=true`（确定性数据）时直接 REPLY |
| `RagService` 先重排、后补标题 | 重排器读到的 `docTitle` 恒为 null，`weight-title` 那一路**从未生效**（不报错、无日志） | 顺序对调：先补标题（顺带剔除孤儿片段）再重排 |
| 分页插件全局上限 100 会静默改小内部批量读取 | 聚类配置 `batch-size: 500` 实际只处理前 100 条，且按 `hit_count` 降序 → **每次都是同一批**，其余问题永远轮不到聚类 | 内部批量读取显式 `setMaxLimit(limit)`（聚类/清单/收尾/短时记忆/向量化补偿 5 处） |
| `RefundTool.matchByAlias` 按插入顺序做子串匹配 | 单字别名"衣"是"洗衣机"的子串 → 洗衣机被判成服饰鞋包，答复"吊牌需完整、未洗涤未穿着"：**内容错误但格式完全正常** | 别名按关键词长度降序匹配 |
| `KnowledgeChunkMapper.searchByKeyword` 有 `LIMIT` 无 `ORDER BY` | 命中超过上限时返回哪 200 行取决于引擎读取顺序，真正的依据可能在打分前被排除，且同一查询两次结果不同 | 按全文相关性 `ORDER BY ... DESC` |
| `AuthService` 把 `null` 密文交给 BCrypt | BCrypt 对空密文直接返回，**时间差反而加大**（1ms vs 几十 ms），可据此枚举有效用户名 —— 与注释声称的防护相反 | 用户不存在时用固定陪跑密文真跑一次比对 |
| `ConversationServiceImpl.getOrCreate` 注释说"由唯一键兜住"但没有 catch | 并发建同一会话时 `DuplicateKeyException` 直接抛给调用方（用户看到流程异常），而另一条请求其实已建好 | `try/catch DuplicateKeyException` 后回查 |
| `message.error_msg` 长度 500 而错误文本常带上千字符 | 严格模式下 1406 → **整条助手回复插不进去**，用户在历史里看不到回答，日志只有一句"落库失败" | 入库前截断到 500 |
| `low_confidence_question.question` 列宽 1000 < 消息上限 2000 | 1001~2000 字的兜底问题写入报 1406，**最该被优化的问题反而进不了池**且无报错 | `sql/05_question_length.sql` 加宽到 2000 + 入库前截断 |
| `SecurityConfig` 的 CORS 方法表缺 `DELETE` | 跨域部署时"删除知识文档"的预检被浏览器拦下：其余接口全正常，只有它"点了没反应"，服务端日志里连请求都没有 | 方法表补 `DELETE` |
| `KnowledgeAdminController.create` 恒回"文档已入库" | 向量库未启用/写入失败时（默认配置就是这样）运营以为语义检索可用；同一控制器的 `reindex` 却会如实报错 | 按 `embed_status` 组一句如实的说明 |

新增 9 项默认执行的回归测试：`ChatFlowWiringTest`(4)、`SseStreamListenerTest`(2)、
`ChunkTokenizerTest`(+1)、`RefundToolTest`(+1)、`TokenCodecTest`(+1，覆盖非规范编码)。

#### 顺手修掉的两个"随机变红"的测试

默认测试集此前还有两处**偶发失败**，它们比真缺陷更耗人 —— 全量跑才复现、单独跑永远通过，
很容易被当成"环境抖动"而忽略：

- **`TokenCodecTest.tamperedSignatureShouldBeRejected`**：该用例把签名段末字符翻转一位
  当作"篡改"，但 32 字节签名编码成 43 字符后末字符只有 2 位有效数据，
  末字符为 `A` 时翻转成 `B`（低 4 位填充位不同、解码结果完全相同）**签名根本没变**，
  于是"篡改后的令牌依然有效"。约 1/16 的签名会命中 → 用例随机失败。
  这<b>不是测试写错而已</b>：它暴露了 `TokenCodec` 接受同一签名的多种字符串写法（签名可变形），
  两处都修了（见上表）。
- **`ChatControllerTest.sendShouldReturnSseEventStream`**：SSE 任务在线程池里跑，
  而 MockMvc 的响应对象不是线程安全的 —— 工作线程写事件、测试线程读响应体，
  并发时抛 `ConcurrentModificationException` 或读到空/半截响应体（全量跑时线程争用更激烈，
  因此只在全量跑偶发）。修法是把 `sseTaskExecutor` 替身成**同类型**的 Mock
  （类型必须一致，否则 `SseConfig.configureAsyncSupport` 的自引用会以
  "被不兼容实例覆盖"让上下文启动失败）并让它当场同步执行，
  把并发从用例里彻底去掉；同时以 `event:done` 为等待条件兜底。
  异步能力本身不必用 MockMvc 验证，"断开后要归还异步请求"已由 `SseStreamListenerTest` 覆盖。

修完后连续 4 次 `./mvnw clean test` 全部 `Tests run: 205, Failures: 0, Errors: 0, Skipped: 27`。

#### 冒烟验证（真容器 `java -jar` + curl）

| 项 | 结果 |
| --- | --- |
| 不设 `MEWCHAT_TOKEN_SECRET` | **启动失败**，`IllegalStateException: mewchat.auth.token-secret 未配置或长度不足…` —— 证明 fail-fast 已真正生效 |
| 设密钥后启动 | 成功，4.0s；Tomcat 8080；日志确认编排节点与业务工具均已装配 |
| 无令牌 `POST /api/chat/session` | `401` + `{"code":20001}` |
| 伪造令牌 | `401` |
| 合法令牌（手工按同一算法签发） | 通过鉴权，落到 DB 层报 `10003`（本机无 MySQL），**不是 401** |
| `userType=1` 访问 `/api/admin/tickets` | `403` + `{"code":20002,"message":"无访问权限"}` |
| `userType=3` 访问同一接口 | 通过鉴权，落到 DB 层 |
| `POST /api/auth/login` 空参 | `10001` + 字段级提示 |

#### 已发现但**未修**（要么是设计取舍，要么需要产品决策）

- **`SessionSummaryJob` 收尾过的会话不再被复用**：任务把 `status` 置 2 之后，
  `touchOnNewMessage` 不会把状态改回进行中，`listIdleActiveSessions` 又只查 `status=1` ——
  会话在后台显示"已结束"却仍在接收消息，且该会话的长时记忆<b>再也不会刷新</b>
  （Agent 一直用旧摘要）。两种修法（拒绝写入已结束会话 / 有新消息就重新激活）是产品选择，
  故未擅自定夺。
- **RRF 按当次最大分归一化**，头部片段融合分恒为 1.0，导致
  `RetrievalConfidenceCalculator` 的 `relevance-floor` 拦不住"唯一一条低质召回"：
  只要有结果就必然 `>= 1` 条"有效片段"，置信度有硬下限，容易落进中档白跑一次补检索。
  改这里要动置信度口径，需先拿真实数据看收益。
- **垃圾工单幂等的竞态**：`TicketService.createFallbackTicket` 是 check-then-act，无事务也无唯一键，
  并发兜底仍可能为同一会话建出两张单；`assign` 不校验 `handlerId` 是否为真实客服。
- **`OrderTool.listOptions` 未按 userId 过滤**（阶段 8 已记录）：真实数据下会列出他人订单。
- **`LogisticsTool` 两个潜在问题**：订单存在但未发货时会回"没查到该订单号"（话术误导，
  当前模拟数据都有运单号故不可达）；`LogisticsInfo.traces` 为 null 时 `summary()/toData()` 会 NPE
  而 `latestTrace()` 做了空判断 —— 同一个字段两处假设不一致。
- 阶段 6~9 遗留未变：`ProductTool` 未实现、`RefundTool` 未接进意图路由、
  三个工具仍是进程内模拟数据、飞轮闭环缺"标记已优化并关联文档"的接口、
  后台无分权、知识库不支持文件上传、真实大模型的流式回答未验证。

### 阶段 11 真库联调与遗留项修复（2026-09-24）

MySQL 可用之后补做的两件事：把阶段 10 记录"未修"的遗留项按判断修掉，
以及把此前"只有编译期保证"的持久化行为真正跑在真库上。

#### 真库暴露的头号缺陷：JSON 列写不进去（已修）

**`PendingClarification.createdAt` 是 `LocalDateTime`，MyBatis-Plus 的
`JacksonTypeHandler` 用的是它自己 new 的 ObjectMapper —— 那个实例没有注册任何模块，
序列化 `java.time` 类型直接抛 `InvalidDefinitionException`。**

后果比"这一列写不进去"严重得多：挂起状态存不下来，
**"追问列候选 → 用户回序号续接"整条链路在运行时是断的**。
`ToolSpecialist` 每次要把候选挂起时都会在写库这一步失败，
用户看到的是流程异常兜底，而不是"请从下面的订单里选一个"。

**为什么此前所有测试都发现不了**：Mock 的 Mapper 不做 JSON 序列化，
单元测试里 `PendingClarification` 是内存对象；而唯一会走到这条路径的
集成测试需要 MySQL（此前一直跳过）。这正是"Mock 验证这类行为等于自证"的实例。

修法是在 `MyBatisPlusConfig` 里给 `JacksonTypeHandler` 注册一个专用 ObjectMapper
（注册 JavaTimeModule、时间写成 ISO-8601 字符串）。**刻意不复用 Spring 的
ObjectMapper**：那个实例被 `JacksonConfig` 定制成"所有 Long 序列化成字符串"
（为避免前端丢雪花ID精度），复用它会让接口层的序列化偏好渗进数据库 ——
JSON 列里原本是数字的 ID 变成字符串，日后调整接口配置就会让存量数据与新数据格式分叉。

#### 阶段 10 遗留项的处置

| 遗留项 | 处置 |
| --- | --- |
| `SessionSummaryJob` 收尾过的会话不再被复用 | **已修**：`touchOnNewMessage` 把 `status` 改回进行中并清空 `end_time`。选"重新激活"而不是"拒绝写入已结束会话"——后者会让正在对话的用户突然收到报错。不修的话收尾任务只扫 `status=1`，该会话的摘要再也不会刷新，Agent 一直用旧摘要回答 |
| `TicketService.createFallbackTicket` 幂等是 check-then-act | **已修**：按 `sessionId` 哈希分 64 段加进程内锁，把"查 + 插"闭环。单实例内幂等成立；多实例仍需数据库层兜底（生成列 + 唯一索引）或分布式锁，因为 MySQL 不支持条件唯一索引，而 `(session_id, status)` 唯一键会把"同一会话关过两张单"也一起禁掉 |
| `assign` 不校验 `handlerId` | **已修**：`TicketService` 注入 `UserMapper`，校验用户存在且 `userType ∈ {2,3}`。不校验的后果是打错的ID（甚至客户的ID）也让工单变"处理中"，从待处理队列消失却没人接手 |
| `OrderTool.listOptions` 未按 userId 过滤 | **已修**，且给模拟数据补了归属信息（三条示例订单归属 `DEMO_OWNER_USER_ID`）。这样过滤是**真的在生效**（换 userId 就得到空列表）而不是"看起来过滤了、实际返回全部"。拿不到归属就返回空列表，退化成不带候选的普通追问——宁可少列，不能多列 |
| `LogisticsTool` 订单存在但未发货时报"没查到该订单号" | **已修**：区分"订单查不到"与"订单存在但没生成运单号"，后者按成功返回并说明"还未生成运单号"，因为让用户去核对一个完全正确的号码是错误的引导。同时补了一条"待发货且无运单号"的模拟订单，否则这条分支在真实数据接入前永远执行不到 |
| `LogisticsInfo.traces` 为 null 时 NPE | **已修**：在记录的紧凑构造器里规范成空列表，把"可能为 null"的假设从三处收敛到一处 |
| RRF 归一化导致无关召回也可能拿到不低的置信度 | **不改算法，改为把安全性质钉成断言**。新增 `RagConfidenceCeilingTest`：走完融合→重排→置信度，断言与提问**零词面重叠**的召回（3 条或 1 条）置信度都是 0.45，落在中档 → 至多多跑一次补检索，绝不会进入 REPLY。不改的原因写在类注释里：找回绝对相关性要么上交叉编码器、要么给 BM25 定跨语料绝对阈值，都得先拿真实数据看收益；而"按词面无重叠否决"会误杀向量通道的语义召回，那恰恰是它存在的理由 |

#### 真库联调（新增 `MysqlPersistenceFixesIntegrationTest`，7 项）

把此前只有编译期保证的行为落到真库上，每条都针对"失败时也不报错"的坑：

- 超长 `error_msg`（1200 字）被截断到 500 后**助手回复仍然落库**（不截断时整行 INSERT 被拒，用户在历史里看不到回答）
- 1500 字的兜底问题**能进池**（列宽已加宽到 2000），否则数据飞轮漏掉最复杂的那批问题
- 挂起状态 **JSON 列往返 + 清空真的变成 null**（清空不生效时，用户随口回一个数字会被续接回旧追问）
- 已收尾的会话收到新消息后 `status` 回到 1、`end_time` 清空、计数递增
- 同一会话重复兜底**只留一张单**
- 指派校验：客户身份｜不存在的ID 被拒，客服身份通过
- 聚类结果**写回问题池**（阶段 9 遗留的"未在真库验证"项）

#### 顺带修掉的另一个缺陷：请求体格式错被报成 500

线上冒烟时（终端把中文按 GBK 发出去，触发了实打实的 `Invalid UTF-8 start byte`）
发现请求体解析失败会落到兜底的 500 + `10003 系统繁忙`。
但这类失败的原因是**客户端数据有问题**，不是服务端故障 —— 调用方会以为该重试，
服务端的错误率与告警也被这些本可避免的 500 污染。已改为 200 + `10001`，
提示里点明编码（这是最常见的实际成因），解析细节只写日志。

#### 验证结果

| 项 | 结果 |
| --- | --- |
| `./mvnw test`（不带 MySQL） | `Tests run: 219, Failures: 0, Errors: 0, Skipped: 34` |
| `./mvnw test -Dmewchat.it.mysql=true -Dmewchat.it.mysql.password=***` | **`Tests run: 219, Failures: 0, Errors: 0, Skipped: 0`** —— 34 项需要 MySQL 的测试全部真实执行 |
| sql 脚本 | 01 → 05 顺序执行全部成功；7 张表、`pending_clarification` JSON 列、`cluster_key/cluster_size`、`ft_content` ngram 全文索引、`question` 已加宽到 varchar(2000) 均已核实 |
| 库内残留 | user 1（联调用的演示账号）、其余 7 张表全为 0 |
| **真实端到端**（`java -jar` + curl，真库） | 登录 → 建会话 → SSE 对话全部成功 |

端到端那一轮的 SSE 事件流与状态轨迹（**这是"编排链路真的通了"的直接证据**）：

```
event:session → state×7 → message → done
visitedStates = [CONTEXT_LOAD, GUARD_CHECK, RESUME_CHECK, INTENT_RECOGNIZE, ROUTE, CLARIFY, END]
finalState = CLARIFY, costMs = 467, 落库 conversation(message_count=2) + user/assistant 两条消息
```

意图识别返回 `UNKNOWN` 是**预期**的：`api-key` 仍是占位值，模型调用必然失败，
而它被正确降级成了 `UNKNOWN` 并走追问 —— 顺带验证了"降级而不是抛异常"这条设计。
修复前这条轨迹会是 `[CONTEXT_LOAD, FALLBACK, END]`。

#### 仍未验证（阶段 11 之后）

- **真实大模型的回答质量与流式输出**：`api-key` 是占位值，模型调用全部失败。
  链路本身已验证（降级路径、SSE 帧序、落库），但"逐字输出顺不顺、首字节延迟多少、
  长回答会不会被 SSE 超时掐断"仍需要真实模型才能回答。
- **Milvus 向量通道**：依然只到编译通过（本机未部署，`mewchat.milvus.enabled=false`），
  所有检索验证走的都是 BM25 单通道。
- **`SessionSummaryJob` 的真实摘要**：需要真实模型。
- 阶段 9 遗留未变：聚类阈值未用真实数据调过、统计接口在大数据量下的性能、
  飞轮闭环缺"标记已优化并关联文档"的接口、后台无分权、知识库不支持文件上传。
- `ProductTool` 未实现；`RefundTool` 未接进意图路由。

### 阶段 12 未验证能力的收口与遗留项加固（2026-09-24）

阶段 11 之后剩下的三块"未验证"（真实大模型 / Milvus / 摘要任务）
卡在同一个前提上：**都要模型凭证**（Milvus 的语义通道也要 embedding key），
而本机 `api-key` 是占位值、没有 Docker 可起 Milvus 服务端。
因此本阶段的做法是：**把"能验的全部验掉，只留下一个凭证开关"**，
再补掉几个遗留项，最后把代码纳入版本管理。

#### 一、模型链路：用"协议级替身"把它验掉（不需要厂商凭证）

新增 `OpenAiProtocolIntegrationTest`（6 项，默认执行）。它**不替换模型 Bean**，
而是用生产用的 `AiModelConfig` 构建真实的
`OpenAiChatModel` / `OpenAiStreamingChatModel` / `OpenAiEmbeddingModel`，
只把 `base-url` 指向一个 JDK 自带的本地 HTTP 服务端（对外说 OpenAI 协议）。

这样被真实执行到的是：客户端的请求构造、SSE 分片解析与累积、`[DONE]` 终止、
超时与重试、我们提示词的出参契约、以及 `ReplyNode` 把它桥回同步流程的那段逻辑。
**唯一的替代品只是"对面那台服务器"**，所以覆盖到的是最容易出错、又最不容易发现的一段。

| 用例 | 断言的事 |
| --- | --- |
| 意图识别 | 真实客户端能按提示词契约解析出意图/置信度/改写后查询；请求体是标准 chat/completions（含 model、temperature、messages 结构） |
| 流式回答 | 真实 SSE 分片**按序、一片不少**地穿过 `ReplyNode` 推到回调，最终正文等于分片拼接；请求体显式带 `stream:true` |
| 401 降级 | 密钥错/过期 → 意图识别降级为 `UNKNOWN`（不抛异常） |
| 500 降级 | 同步与流式两条路径都返回 `FALLBACK`，且失败时不推送任何片段 |
| 向量维度 | embedding 真实返回配置声明的维度，且该维度随请求发给厂商 |

**剩下的唯一未验证项就是厂商凭证与模型本身的质量**：换厂商只需改
`base-url` / `model-name` / `api-key` 三个配置，本类保证这条链路本身是通的。

#### 二、Milvus 的前置：把维度校验从"告警"改成"失败"

`MilvusConfig.checkDimension` 原来有两个问题，都已修：

1. **发现不一致时只告警不失败**。而它的语义是确定的（两者必须相同，否则向量写入失败、
   集合建错后还只能删掉重建）。只在启动日志里告警，问题会在第一次写向量时才暴露。
   现在：**配置对不上直接启动失败**，错误信息同时点出两个配置项与"需要重建集合"。
2. **校验方式本身会发起付费请求**：它调用 `EmbeddingModel.dimension()`，
   而该方法的默认实现是"真的 embed 一段文本再数长度" —— 把一次远端调用放进了启动路径，
   与项目"Milvus 客户端只建通道、不在启动阶段连服务"的原则相矛盾；拿不到值时还会
   静默降级成一条日志（校验形同虚设）。现在改为**只比对两个配置项**
   （`mewchat.llm.embedding.dimensions` vs `mewchat.milvus.dimension`），
   确定性、零成本；`dimensions` 未配置时只提醒不失败（模型维度固定的厂商无需声明，不该替用户拍板）。
   新增 `MilvusDimensionCheckTest`（4 项），其中一条用<b>方法签名</b>钉住
   "不得引入需要远端调用的模型参数"这个约束。

Milvus 服务端本身仍未部署（本机无 Docker），`VectorRetriever` 依然只到编译通过。

#### 三、长时记忆的触发源：摘要任务落到真库上

新增 `SessionSummaryJobIntegrationTest`（3 项，需 MySQL）。此前这个任务没有任何测试，
而它是**长时记忆唯一的触发源**。验了三件真事：空闲会话被收尾（摘要落库、状态与结束时间正确）、
**刚刚还在聊的会话不能被动到**（判定条件写反就会把正在对话的用户判成已结束）、
以及"收尾 → 用户继续聊 → 再次收尾"这条链能闭合（任务只扫 `status=1`，
不闭合的话该会话的摘要会永远停在第一次收尾的时刻）。

写这条测试时我把消息计数算错了（种数据时已累加 3 次），改成断言**增量**而非绝对值。

#### 四、遗留项加固

| 项 | 处置 |
| --- | --- |
| 飞轮缺最后一步：运营补完文档后问题状态无法回写 | **已补接口** `POST /api/admin/analytics/questions/{id}/optimize`（body：`knowledgeDocId`）+ `LowConfidenceQuestionService.markOptimized`。会先确认文档存在（关联不存在的ID既无意义，又会让"这篇文档解决了多少问题"的统计指向不了任何东西）；只更新"待优化"的行，因此重复点击是无害操作、也不会把优化时间刷新掉。新增 4 项接口测试 + 2 项真库测试（**断言它真的从待优化清单里消失**——这套机制的价值全在"清单会收敛"上） |
| 统计的"低置信度消息数"硬编码 0.40 | **已修**：改为读 `AgentProperties.lowConfidenceThreshold`。原先与真正驱动兜底的阈值各写一份，运营一调阈值，仪表盘的数字就与真实行为不一致 —— 恰恰在人最需要它准确时失效 |
| 非管理员的 403 清单只覆盖 GET | 新增接口是 POST，已补进"逐路径 403"断言：否则会出现"后台写操作比读操作更容易访问" |

`RefundTool` 未接进意图路由这条**保持原样**：AGENTS.md 里记录的"政策原文按语义检索更合适"
是一个有理由的设计选择，不是疏漏。要改它得先有数据说明结构化政策比原文更好，
否则就是拿产品行为去赌。

#### 五、验证结果

| 项 | 结果 |
| --- | --- |
| `./mvnw clean package`（默认） | **`Tests run: 238, Failures: 0, Errors: 0, Skipped: 39`** + BUILD SUCCESS |
| `./mvnw clean test -Dmewchat.it.mysql=true -Dmewchat.it.mysql.password=***` | **`Tests run: 238, Failures: 0, Errors: 0, Skipped: 0`** |
| 库内残留 | 除联调用的 1 个演示账号外，其余 7 张表全为 0 |
| 凭证体检 | 令牌密钥与数据库密码**均未进入仓库**（`application.yml` 里全是 `${ENV:默认}` 占位；`git grep` 逐项确认过） |

本阶段新增 4 个测试类共 15 项（协议级 6 / 维度校验 4 / 摘要任务 3 / 飞轮回写 2 并入既有真库类）。

#### 六、端到端跑通：与一处被它揪出来的标定错误

真实跑了一遍（`java -jar` + curl + 真库），并把应用指向一个本地假 OpenAI 服务端，
让"真实 LangChain4j 客户端 + 真实 SSE 流式"在真容器里走完整链路：

| 场景 | 结果 |
| --- | --- |
| 模型不可用（`api-key` 占位） | 轨迹 `CONTEXT_LOAD→GUARD_CHECK→RESUME_CHECK→INTENT_RECOGNIZE→ROUTE→CLARIFY→END`，意图降级为 `UNKNOWN` 后走追问 —— 即"降级而非崩掉" |
| 假模型 + 订单提问 | 轨迹 `…→ROUTE→TOOL_CALL→CONFIDENCE_CHECK→REPLY→END`，3 个流式片段逐字下发；提示词里确认带着工具查到的事实（订单状态/商品/金额/承运商），落库 `agent_name=ToolSpecialist`、`confidence=0.94` |
| 假模型 + 知识提问 | 轨迹 `…→RAG_RETRIEVE→CONFIDENCE_CHECK→REPLY→END`，引用（标题+段落号+分数）随消息落库 |
| 管理端 | 待优化清单正常聚合；标记已优化后清单从 1 条收敛到 0 条；重复标记如实报错；客户访问后台 `403` |
| 知识录入 | 回执如实写明"文档已录入（关键词检索可用），但向量化未完成：向量库未启用"（阶段 11 那处修复在运行中可见） |

**跑通的过程揪出一个标定错误（已修）**：知识类提问精准命中了正确片段
（引用正确、重排分 0.714、覆盖度 0.29），却算出置信度 **恰好 0.60**、
落进中档 → 白跑一次补检索 → 兜底，而答案就在手上。

算一下就明白：`expected-chunk-count` 原为 3，单片命中时数量因子只有 1/3，
要让置信度够到达标线 0.70，需要重排分 ≥ 0.857，换算成词项覆盖度约 0.89 ——
等于要求"提问的二元词几乎全都出现在这一片里"。新知识库往往只召回一两片，
这等于"知识刚录进去也答不上来"（假阴性）。

**改为 1**，并且这次把两边的后果都写成可推导的算式：

```
置信度 = 0.7 × 最高分 + 0.3 × min(1, 有效片段数 / expected-chunk-count)
① 单片好命中（重排分 0.714）：0.7×0.714 + 0.3 = 0.8  ≥ 0.70 → 可作答
② 单片零词面覆盖（重排分 = 融合项下限 0.5）：0.7×0.5 + 0.3 = 0.65 < 0.70 → 仍判兜底
```

②是关键：**"排第一"本身依然不足以作答**，必须有词面或标题上的实质匹配 ——
因此修掉假阴性并没有削弱假阳性防线。这一条同时被两个方向的断言钉住：
`RagConfidenceCeilingTest`（无关召回答不了）与
`RetrievalConfidenceCalculatorTest`（单片命中要能答），加上一条反向用例。

顺带改掉了 `RetrievalConfidenceCalculatorTest` 里"单个高分不满分"那条断言：
它原本记录的是"单条依据不如多条互相印证"，而正是这条阻尼造成了上面的假阴性；
数量因子现在退化为"至少有一条达标片段"这道门槛，佐证关系由最高分体现。语义变化写在该用例注释里。

**另外踩到一个操作顺序的坑**：应用还在运行时执行 `mvn clean` 会失败 ——
Windows 上 jar 被运行中的进程占用，`maven-clean-plugin` 删不掉它，
而报错出现在 clean 阶段、看起来像"构建坏了"。先停应用再构建。

#### 仍未验证（阶段 12 之后，全部只差凭证或服务端）

- **真实厂商模型**：需要 `LLM_API_KEY`（以及 `LLM_BASE_URL`/`LLM_MODEL`）。
  链路已验证，未验证的是模型回答质量、逐字速度、以及长回答是否会被 SSE 超时掐断。
- **Milvus 服务端**：需要部署 Milvus 2.5.x（本机无 Docker）。
  已验证 embedding 维度这一前置条件；未验证集合创建、写入、按元数据删除、
  以及 `VectorRetriever` 的召回效果 —— 所有检索验证仍走 BM25 单通道。
- 阶段 9 遗留未变：聚类阈值未用真实数据调过、统计接口在大数据量下的性能、
  后台无分权（客服进不去）、知识库不支持文件上传。
- `ProductTool`（商品查询）未实现。商品查询与订单查询的取数方式不同，
  不是加一个工具类就够，需要先确定商品数据的归属与来源。

### 阶段 13 补齐「商品查询」与工具契约的一次修正（2026-09-24）

§一 项目定位里写的是「业务工具调用（订单查询、**商品查询**、物流查询）」，
而 `tool/` 下只有 order / logistics / refund —— 这是"项目声明的能力"与
"代码实际提供的能力"之间最后一处明显缺口。本阶段把它补上，
并顺手修掉它顺带触发的一处设计问题。

#### 一、`ProductTool`（商品查询）

新增 `tool/product/ProductTool`（`product_query`），按商品名查价格、规格、库存与类目。

**为什么商品查询需要工具而不是都交给知识检索**：商品描述、使用说明这类非结构化内容
适合走 RAG；而"多少钱""还有货吗""什么规格"是**结构化事实**。
让模型从知识片段里"读出"价格，是编造数字最常见的入口 ——
片段里可能写着"原价 599，活动价 499"，模型挑错一个数字，用户就会按错价格下单。

三个设计要点：

- **关键词匹配按长度降序**："耳机"要落到"无线蓝牙耳机 Pro"。这与退款类目别名
  踩过的是同一个坑（单字/短词会抢走更具体的词），处理方式也一致。同时**不放单字关键词**：
  "杯"会把"茶杯垫"也算成保温杯——宁可匹配不到、由追问把商品列出来让用户选，
  也不要匹配错，查错商品给出的价格比查不到更糟。
- **候选不按 userId 过滤，与订单工具相反**：商品是公开的商品目录，谁都能看；
  订单属于某个用户，列出别人的订单就是泄露交易信息。处理方式不同，是因为数据的归属性质不同。
  候选只列在售商品 —— 把缺货商品列进去让用户挑，挑完得到的回答是"没货"，不如一开始就不列。
- **类目取值与退款政策表对齐**，并由 `ProductToolTest.everyCategoryShouldExistInRefundPolicy`
  钉住：两处各写一份类目，迟早会出现"商品工具说这是数码配件、退款工具查不到这个类目"，
  而用户看到的是一个自相矛盾的答复。真实接入后两份数据来自不同系统，这个风险只会更大。

`IntentType` 新增 `PRODUCT_QUERY("商品查询", TOOL_CALL, List.of("productName"))`，
提示词里补了与 `KNOWLEDGE_QA` 的分界规则（问"一个确定的值"走工具，问"什么规则/怎么用"走检索）。

#### 二、候选项填哪个参数，改由工具自己声明

`ToolSpecialist.resolveTargetParam` 原先在**猜**：缺失参数里有 orderNo/trackingNo 就返回 orderNo，
否则取第一个。这个映射过去只有一个候选来源（订单）时能凑合，而本阶段引入了第二个来源（商品）。

这正是 AGENTS.md 早就写明的事 ——「将来若出现别的候选来源（例如让用户选"是哪件商品"），
这个映射必须由提供候选的工具自己声明，不能继续在这里猜」，而它举的例子就是商品。
**推错的后果是静默的**：用户选了第 1 项，那个值被填进了另一个参数，
工具拿着它去查、查出"没找到"，整条链路不报任何错。

改法：`BusinessTool.clarificationParam()`（默认 null = 不提供候选）由工具声明，
经 `ToolInvoker` 透出，编排层照用；工具给了候选却没声明时退化为旧行为并打 WARN。
启动日志现在会把声明一并打出来，一眼能看出哪个工具声明了什么：

```
业务工具注册完成，共 4 个：[order_query, logistics_query, refund_policy_query, product_query]
（候选参数：order_query->orderNo, logistics_query->orderNo, product_query->productName）
```

`ToolSpecialistTest.clarificationParamShouldComeFromToolDeclaration` 用一个"声明了猜不出来
的参数名"的替身工具钉住这条：一旦有人把它改回推断，用例立刻失败。

**这里踩到一个真实的陷阱**：测试里的 `RecordingToolInvoker` 包装器只转发了
`invoke`/`listOptions`，没转发新方法 —— 声明被丢掉、静默退化成推断，
于是"物流候选该填 orderNo 而不是 trackingNo"那条用例立刻变红。
装饰器漏转发新方法是个通用风险，转发处已写上"漏掉它会静默退化"的注释。

#### 三、端到端跑通（真库 + 本地假模型）

| 场景 | 结果 |
| --- | --- |
| "耳机多少钱" | `PRODUCT_QUERY → TOOL_CALL → REPLY`（9 个 state 事件、3 个流式片段）；关键词"耳机"落到"无线蓝牙耳机 Pro"；回答里的 499 元/128 件只在提示词含工具真实数据时才会被假服务端说出，等于反证了"工具事实进了提示词" |
| "我想问个商品" | 模型没抽出商品名 → `CLARIFY` 列出 5 个**在售**商品（缺货的充电宝被正确排除）；`conversation.pending_clarification` 落库为 `{"missingParam":"productName", options:5}` |
| 回 "1" | 轨迹 `…RESUME_CHECK → TOOL_CALL → … → REPLY`（跳过意图识别与路由），置信度 1，候选值被填进 `productName` 并查出正确商品，挂起状态清空为 null |

这一轮同时把阶段 11 修的 `JSON 列 + JacksonTypeHandler` 在运行中又验了一次
（挂起状态写入与读回都正常）。

**跑通时发现并修掉一处文案缺陷**：候选列表已经不止来自订单，而追问话术写死成
"请从下面的**订单**里选一个（直接回复序号或订单号都可以）" —— 给商品列表配这句话
会让用户以为自己点错了地方。已改为与工具无关的措辞，并保留"回复序号"与"直接说名字"
两条路径的说明（后者走正常流程，意图识别会把它捞回来）。

#### 四、验证结果

| 项 | 结果 |
| --- | --- |
| `./mvnw clean package` | **`Tests run: 253, Failures: 0, Errors: 0, Skipped: 39`** + BUILD SUCCESS |
| 加 `-Dmewchat.it.mysql=true ...password=***` | **`Tests run: 253, Failures: 0, Errors: 0, Skipped: 0`** |

新增 14 项测试：`ProductToolTest`(11，含与退款类目的跨工具一致性)、
`ToolSpecialistTest`(+2：商品候选填 productName、候选参数以工具声明为准)、
`BusinessToolInvokerTest`(+1：声明透出与未注册工具返回 null)。

#### 五、演示工具与演示脚本（面向演示 / 答辩）

接入真实厂商模型需要一个 API Key，而演示与答辩现场常常不便（或不该）拿真 Key 去打。
为此补两样东西，让"没有 Key 也能把产品形态跑一遍"：

- **`tools/fake-openai/FakeOpenAi.java`**：只依赖 JDK 的本地假 OpenAI 端点。
  它说 OpenAI 协议，因此**应用侧真实客户端、真实 SSE 解析、真实编排链路全都被执行到**，
  被替换的只是"对面那台服务器"。按提示词特征分流意图，刻意覆盖演示需要的七类问句
  （订单 / 商品 / 知识命中 / 知识未命中兜底 / 未知追问 / 列候选 / 指代追问）。
  启动方式与"换成真厂商"的写法（同一组 `LLM_*` 环境变量）都写在文件头注释里。
- **`docs/demo-runbook.md`**：照着做就能演示的脚本 —— 8 个步骤，每步都写了
  「命令 → 该看到什么」，外加"评审常问的四问"与"演示前 5 分钟自检"。

**写这个假端点时踩到三个坑，都只有真跑才会暴露**，记下来避免重犯：

1. **关键词不能拿整段提示词匹配**。意图识别的提示词里带着判断规则原文，
   里面本身就出现"发票""耳机""价格"这些词 —— 拿整段去匹配，**每条提问都会命中
   同一个分支**（实测四类问题全被判成 KNOWLEDGE_QA）。必须先抠出"用户当前输入"
   再匹配；而历史也被拼在同一段里，所以还要再细一层（只取分节标记之后的部分）。
2. **JSON 转义要还原成真字符**。提示词里的换行在请求体里是 `
` 两个字符；
   若原样收下字母 `n`，整段提示词变成一行，"按行取最后一条用户发言"就什么都取不到，
   表现为"指代消解返回了占位文本"。
3. **指代消解的提示词分节名与意图识别不同**（【最新一句】 vs 【用户当前输入】）。
   猜错就会取到上一轮的问题，而**指代消解的输出会覆盖用户原话** ——
   下游的意图识别与检索用的就是它，取错相当于把用户的问题整句换掉。
   这也是排查时的一条线索：**各类问题都被判成同一个意图时，先看指代消解的输出**。

#### 六、真实厂商模型已验证（DeepSeek）

拿到 Key 后把上面那条链路换成了真模型（**只通过环境变量传入，不落文件、不进仓库**），
`base-url`/`model-name` 用默认值即可 —— 这正是"换厂商只改三个配置"的兑现。
实测（`deepseek-chat`，真库，真容器）：

| 步骤 | 意图 | 结果 | 耗时 | 流式片段 | tokens |
| --- | --- | --- | --- | --- | --- |
| MC202409240001 这单到哪了 | `LOGISTICS_QUERY` | `REPLY`，回答带运单号/承运商/轨迹/预计送达 | 1.63s | 134 | 487 |
| 那大概什么时候能到 | `LOGISTICS_QUERY` | `REPLY`，**引用了上一轮的运单号**（多轮记忆生效） | 1.96s | 57 | 542 |
| 耳机多少钱 | `PRODUCT_QUERY` | `REPLY`，价格/规格/库存均来自工具 | 1.96s | 47 | 505 |
| 七天无理由退货怎么操作 | `KNOWLEDGE_QA` | `REPLY` + 引用（七天无理由退换货规则/第1片） | 2.18s | 76 | 591 |
| 那这个有时间限制吗 | `KNOWLEDGE_QA` | `REPLY` + 同一篇引用（**指代消解生效**） | 1.86s | 52 | 650 |
| 我想看看你们有什么商品 | `PRODUCT_QUERY` | `CLARIFY` + 5 个在售候选 + 挂起落库 | 0.57s | 1 | — |
| 2（选序号） | `PRODUCT_QUERY` | 轨迹跳过意图识别与路由，`REPLY`，查到所选商品 | 1.12s | 54 | 455 |
| 你们支持开发票吗 | `KNOWLEDGE_QA` | `FALLBACK` + 自动建单（知识库无此主题） | 1.70s | 1 | — |

**三条值得单独记下的结论**：

1. **指代消解真的能工作**，而且这是多轮体验的关键。DEBUG 日志里能看到检索用的 query
   是 `那七天无理由退货有时间限制吗` —— 模型把"这个"补全成了上一轮的话题，
   于是省略式提问也能检索到同一篇文档。反过来，若上一轮问的是订单，
   模型会把"这个"理解成订单、知识库没有订单主题而走兜底 —— **那是正确行为**，
   演示脚本因此把指代追问安排在知识轮之后（见 `docs/demo-runbook.md` 步骤 4b）。
2. **模型不乱说**。知识问答里它引用规则原文，并在问不到的地方（"具体申请入口"）
   明确说"我这边无法确认""帮您转接人工客服确认"，没有编造一个操作路径 ——
   这正是"不乱说、不瞎猜"这条卖点在真模型上的现场证据。
3. **意图判断会与预期不同但要接受**："这单到哪了"被判成 `LOGISTICS_QUERY` 而非
   `ORDER_QUERY`。两者都走工具、都能答对，属于模型的合理判断，不必也不该去拧。
   真正要守住的是"流程由代码控制"（轨迹可复现），而不是"意图名必须与预期一致"。

**观察到的一处外观问题（未改，仅记录）**：个别回答里字段之间丢了换行与空格
（如 `-承运方：顺丰速运-当前状态：运输中`、`2024-09-2308:15`）。
工具出参本身是有换行的自然语言摘要，"压平"发生在模型组织语言这一步。
改提示词能改善，但这类改动需要拿一批真实问句做前后对比才谈得上调优，
不适合在演示前凭单个样例改 —— 记录在此，等有评测集再动。

#### 仍未验证（只剩服务端）

- **Milvus 服务端**：需要部署 Milvus 2.5.x（本机无 Docker）；所有检索验证仍走 BM25 单通道。
- 其余遗留未变：聚类阈值未用真实数据调过、统计接口在大数据量下的性能、
  后台无分权（客服进不去）、知识库不支持文件上传、无 refresh token/登出、
  客户端断开不取消生成。

### 阶段 14 Windows 一键启动脚本（2026-09-24）

新增 `start.bat`（预检 → 按需建库/构建 → 启动，含 `check` / `fake` / `rebuild` 三种模式）
与 `stop.bat`（按端口停应用与假端点，因为构建前必须先停，否则 Windows 锁住 jar）。
两者都是 **GBK 编码**：应用的中文日志是 GBK，控制台用 936 代码页才能同时正确显示
脚本提示与应用日志。首次运行自动生成 `local.env.bat`（本机 MySQL 目录/密码、大模型 Key、
随机令牌密钥），该文件已被 `.gitignore` 忽略。

**写这两个脚本时踩到两个真缺陷，都属于"只有真跑才会暴露"的类型，记下来**：

1. **`FakeOpenAi.main()` 返回导致 JVM 立刻退出**（已修：`new CountDownLatch(1).await()`）。
   `HttpServer` 的调度线程是守护线程，而 `Executors` 的线程池是按需创建的（启动时一个
   非守护线程都没有），因此 main 一返回进程就结束。症状极具迷惑性：**日志里打印了
   "已启动"、端口短暂 LISTENING、随后所有请求连不上**，而且从进程表看像"起了但不应答"。
2. **假端点往自己 stdout 重定向的那个文件追加日志**（已修：日志写入 try/catch 不再
   影响服务，且 `run.bat` 的 stdout 另写一个文件）。两次打开同一文件在 Windows 上会失败，
   异常抛出去等于把请求直接掐断 —— 表现同样是"端口通了但接口不应答"，
   而 `/`（404，走不到处理器）却一切正常。**日志只是排查辅助，不能变成故障点。**

排查过程中还浪费了很多时间在一个低级错误上：**用 `tasklist | grep -ci java` 数进程
一直得到空结果**（输出编码/匹配问题），据此误判"进程已消失"、走了很久的弯路。
查 Windows 进程请用 `powershell -Command "(Get-Process java).Count"`，
或在 netstat 里按端口找 PID 再查它的命令行。

**新建库这条路径又是"只有真跑才暴露"的一类**，一并记下（都已修）：

3. **batch 括号块内的 `%VAR%` 取的是块解析时的值**。判断"库是否已初始化"的那段整体
   处在 `) else (` 里，于是 `if not "%TABLE_COUNT%"=="0"` 里的 TABLE_COUNT 永远是空的，
   `if not ""=="0"` 为真 → **新库被误判成"已就绪"，建表整段跳过**。改用 `!VAR!`
   延迟展开。有库时永远走不到这个分支，因此只有测全新库才发现。
4. **`DB_NAME` 只用于脚本的建库检查，没传给应用**（应用的库名来自 `application.yml`）。
   两者一旦不一致就会出现"脚本检查 A 库、应用连 B 库"的静默错位 —— 现在启动时
   通过 `--spring.datasource.url` 把库名传给应用，两处始终同一个库。

顺带记两条 Windows 排查经验：查进程用
`powershell -Command "(Get-Process java).Count"`（`tasklist | grep` 在本机编码下
会给出空结果，据此误判过"进程已消失"）；清场要按映像名
`taskkill /IM java.exe /F`，按 PID 杀容易漏掉子进程，而漏掉的那个会继续占着端口与日志文件。

### 阶段 15 后台分权与客服工作台（2026-09-25）

收掉阶段 8 起被连续标记的最后两个后台遗留项：**后台无分权（客服进不去）**
与**没有"我的工单"视角**。这不是两个独立小改动，而是一组配套：
分权打开的口子（客服能进工单/对话）必须立刻配上工作台，
否则客服进得来却没有自己的一亩三分地。

| 位置 | 内容 |
| --- | --- |
| `config/SecurityConfig` | `/api/admin/**` 从一刀切 `hasRole("ADMIN")` 拆成两层：`tickets/**` 与 `conversations/**` 放开给 `AGENT+ADMIN`（一线工作面）；`knowledge/**` 与 `analytics/**` 仍只对 `ADMIN`（治理面）；**兜底规则仍是 `/api/admin/**` → ADMIN** |
| `api/admin` | `TicketAdminController` 新增 `GET /my`（我的工单）与 `POST /{id}/claim`（接单） |
| `service/TicketService` | 新增 `pageMyTickets`（按处理人过滤）与 `claim`（条件更新闭环的接单） |
| `docs/demo-runbook.md` | 种子账号补客服 `bob`；步骤 8 改为演示三层权限边界（客户 403 / 客服工作面 200 / 客服治理面 403） |

**分权的切分依据是"工作面"，不是接口读写。** 客服接单后必须能看到用户的完整对话，
否则拿着一行工单描述（"用户问题：赠品什么时候发货；置信度 0.00"）根本无法还原上下文 ——
所以对话记录必须随工单一起放开；而改知识库影响此后**所有**回答、统计与飞轮收口是运营决策，
这两个权限面不该给一线（客服能改知识库的世界里，一次误操作的代价是全量回答质量）。
因此按"工单+对话 / 知识库+统计"两块切，而不是按 GET/POST 切。

**兜底规则必须保留，而且要写在分权规则之后。** `requestMatchers` 按声明顺序匹配，
新增的后台分组若忘了声明会落到 `/api/admin/**` → ADMIN 的兜底 —— 表现是
"新接口客服调不通"，而不是"新接口悄悄对一线敞开"。宁可前者被及时发现。

**接单与指派是两个动作，语义刻意不同。** 指派（assign）是管理员的安排动作：
处理人来自参数、允许改派处理中的工单；接单（claim）是客服的自助动作：
处理人就是令牌里的自己、**只允许从待处理队列拿走**。后者不能"抢"已被同事接走的单 ——
两个客服同时点接单时，先写的毫不知情地继续处理一张已不属于他的单，这是真事故。

**接单的并发安全用条件更新闭环，不用先查后写。** `claim` 把"待处理"写进
UPDATE 的 WHERE 条件（`UPDATE ... WHERE id=? AND status=0`），后到一方更新 0 行后
如实报错。接单是工作台上最典型的竞争操作，先查后写会让两个并发接单都成功。
状态只会单向流转（待处理→处理中→已解决/关闭），更新 0 行后重读不可能是待处理，
因此错误信息只有一种；`requireAssignableHandler` 复用指派的校验 ——
"处理人合法性"的定义只有一份。

**工作台的"我的"边界由令牌决定，不设参数。** `GET /tickets/my` 的处理人取
`@AuthenticationPrincipal`，请求里根本没有 handlerId 可填。放成参数的话，
任何能进工作台的账号都能冒别人的身份翻看队列 —— 角色校验挡得住"角色不对"，
挡不住"角色对但人不对"。接单同理。测试里用 `verify` 钉住"服务收到的是令牌里的ID"。

**权限测试要区分两件事：HTTP 状态码与业务返回。** 分权用例只断言"能调通/被拦"，
业务服务必须打桩返回合法空值 —— Mock 默认返回 null 时，控制器里
`PageView.of(null,…)` 会 NPE 成 500，把"权限放行了"误报成"服务器错了"
（本次就先踩了这个：`Status expected:<200> but was:<500>`）。

验证结果（`./mvnw clean test` 默认 → **257 项 / 0 失败 / Skipped 41**；
`-Dmewchat.it.mysql=true` → **257 项 / 0 失败 / Skipped 0**，连续两次）：

- `AdminApiTest` 重写分权口径：客户逐路径 403（含 `/my` 与 `claim` 两个新端点）、
  **客服工作面放行 + 治理面读写全拦**（逐路径）、我的工单/接单的
  **处理人必须等于令牌ID**（verify 钉死）；原"客服一律 403"的断言按新口径翻转
- `MysqlPersistenceFixesIntegrationTest` 新增 2 项真库测试：
  接单的状态门槛（非待处理一律如实报错）、**"我的工单"真的按处理人过滤**
  （过滤失效不报错、只是把所有人的队列显示给每个人，正是 Mock 验证不了的）
- 门控测试零残留（回滚与物理清理均生效）；库中现存的演示会话与知识文档
  均为 2026-09-24 演示/联调数据（`create_time` 可证），属演示资产，未清理
- **一次未复现的偶发失败（记录在案）**：第一次门控运行报过一个失败，
  用例名被输出过滤吞掉、surefire 报告已被后续运行覆盖，未能定位；
  随后两次全量门控运行均全绿且零残留。按阶段 10 的经验，偶发失败不该当
  "环境抖动"放过 —— 但在拿到第二次复现前无法归因，标记为**需继续观察**。

**未验证/仍开放的部分**：客服工作台只有接口、没有前端页面（本项目的接口验证口径）；
对话记录放开后客服能读**任意用户**的会话（含未产生工单的）——
"按工单关联收紧到只读涉事会话"是更细的权限模型，等有真实合规要求再做；
`claim` 的条件更新保证单实例与多实例下都不会抢单，但指派（assign）仍是后写覆盖先写。

### 阶段 16 全量复查与验收修复（2026-09-25）

复查方式三条并行：全量测试（默认 + 门控）、**一个只读审查代理通读全部
`src/main/java`（110+ 文件）+ 配置 + SQL**、以及**真容器端到端冒烟**。
结果：审查代理报 0 条 P0/P1、1 条 P2、5 条 P3；而**真容器冒烟抓到一个它没发现的 P1**。

#### 真容器抓到的 P1：统计总览在空库上直接 500（已修）

`GET /api/admin/analytics/overview` 在问题池为空时抛
`NullPointerException: "aggregate" is null` → 500。

成因：不带 `GROUP BY` 的 `SUM/AVG` 在空表上返回**一行全 NULL**，
而 MyBatis 把这种行映射成 **`null` 元素**（不是"空列表"）。
`AdminStatsAssembler.firstRow` 的 Javadoc 写着"一行全 null 或没有行，两种都要接住"，
实现却只判了 `rows.isEmpty()`，于是 `rows.get(0)` 返回 null、随后 `.get(...)` 炸。
`dailyMessages()` 遍历聚合行时对同一个坑也没有防守。

**这一条恰好命中"全新部署第一次打开后台"这个最该能用的场景**，
而此前所有测试都发现不了：`AdminApiTest` 用 `@MockitoBean` 把整个装配器替换掉了，
真库集成测试只覆盖了清单与落库、没覆盖总览聚合。

修法：
- `firstRow` 在元素为 `null` 时返回空表（并把"两种返回形态"写进注释，附上这次的报错形态）；
- `dailyMessages` 跳过 `null` 行 —— 该查询带 `GROUP BY`，实际很难触发，
  但把"聚合行可能为 null"当作本类的**前置事实统一处理**，好过在每个遍历点各假设一次；
- 新增 `AdminStatsAssemblerTest`（3 项，默认执行、纯 Mockito、不连库）：
  替身刻意返回**驱动在空表上的真实形态** `Arrays.asList((Map) null)`，
  而不是更好处理的空列表 —— 用后者等于把缺陷留在原地。
  同时钉住"有数据时聚合值真的被用上"与"清单为空时返回空清单而非 null"。

#### 审查代理发现并已修的问题

| 级别 | 问题 | 修法 |
| --- | --- | --- |
| P2 | 统计口径**魔数复刻**：会话 `1/2/3`、工单 `0~3`、`optimized 0/1`、`embedStatus 3`、`role="assistant"` 在 api 层裸写，与 service 层常量各写一份，改了服务层就会静默按旧口径统计 | 常量化并引用定义处（`ConversationServiceImpl` / `TicketService` / `LowConfidenceQuestionServiceImpl` / `DocumentIngestService` / `ChatConstants`），并补上**此前根本没有常量**的 `STATUS_HANDOFF = 3`（已转人工） |
| P3 | `ReplyNode` 同一参数两段互相矛盾的 Javadoc（"两倍再加 30 秒" vs 代码的三倍） | 数值只在 `streamWaitTimeout()` 一处说明，另一处改为指向它 |
| P3 | 免认证清单里的 `/actuator/health` 是死配置（pom 无 actuator，实际 404），会让部署方以为有存活探测端点 | 删掉该条目并写明"真要有得先补依赖" |
| P3 | `application.yml` 注释声称 api-key"留空会导致启动失败"，实际默认占位值让非空校验永远通过，带假 key 也能正常启动 | 注释改为如实描述（占位值是有意保留：让无凭证环境能启动并走降级路径）；`AiModelConfig` 识别占位值并在启动日志打 WARN，避免把"应用起来了"误读成"模型接好了" |
| P3 | `ChatConstants.HEADER_SESSION_ID` 是死代码（全仓无引用，续接实际靠请求体的 sessionId），会误导前端对接者 | 删除 |
| P3 | AGENTS.md **自身口径分叉**（§三写"7 个编排节点"、`KnowledgeChunk` 位置写错、service/dao 清单过时） | 按实际修订（10 个节点、实体在 `dao/mysql/entity`、补齐服务与实体清单） |
| P2 | **`docs/demo-runbook.md` 明文写着本机开发库口令**（6 处），而仓库是公开的 —— 上传等于发布一个凭证 | 改为统一从 `MYSQL_PASSWORD` 环境变量读取，口令只落在被忽略的 `local.env.bat` 里。⚠️ **该口令已随早前提交进入公开仓库历史**，清理工作树不能撤回历史：建议改掉本机库口令（口令见 local.env.bat 这类弱口令尤其），若不改则视为已公开 |

#### 一处**未修**（如实记录，不是遗漏）

`AdminStatsAssembler` 的三处聚合（消息质量均值、每日消息量、命中次数合计）仍把
`AVG(...)`/`DATE(...)` 写在 Wrapper 的 `.select()` 里，与 §四.3"手写 SQL 一律放 XML"
不完全一致。不搬的理由：它们只是单表聚合、参数由 Wrapper 参数化，而这段 SQL
**没有任何真库测试**（搬迁要同时动 mapper 与 service 接口），
"改完只有冒烟验证"的风险大于"可 grep、可复用"的收益。
若后续要给统计补真库测试或做预聚合，应连同这段 SQL 一起搬进 XML。

#### 验收结果

| 项 | 结果 |
| --- | --- |
| `./mvnw clean package`（默认） | **`Tests run: 260, Failures: 0, Errors: 0, Skipped: 41`** + BUILD SUCCESS |
| `./mvnw test -Dmewchat.it.mysql=true` | **`Tests run: 260, Failures: 0, Errors: 0, Skipped: 0`** |
| 真容器冒烟（`java -jar` + 真库 + 本地假模型） | 分权矩阵 **8/8 符合预期**：客户→工单 `403`、客服→工单/我的工单/对话记录 `200`、客服→知识库/统计 `403`、管理员→统计 `200`、无令牌 `401` |
| 兜底闭环 | `你们支持开发票吗` → 轨迹 `CONTEXT_LOAD→GUARD_CHECK→RESUME_CHECK→INTENT_RECOGNIZE→ROUTE→RAG_RETRIEVE→CONFIDENCE_CHECK→FALLBACK→END`，自动建单 1 张 |
| 工作台闭环 | 客服接单 `200/处理中`、**重复接单被如实拒绝**（"只有待处理的工单才能接单…"）、我的工单含该单、结单 `已解决` |
| 统计总览 | 由修复前的 500 变为 **200**，且数字与刚才的操作一致（工单 total 1 / resolved 1、飞轮 pending 1） |
| 占位密钥告警 | 不设 `LLM_API_KEY` 启动时打出 WARN，点明配置项与后果 |

顺带记两个操作坑（都在这轮踩到）：
- **应用还在运行时 `mvn clean` 会失败**（Windows 锁住 jar，报错出现在 clean 阶段、
  看起来像"构建坏了"）。先停应用再构建 —— 阶段 12 记过一次，这次又踩了。
- **Git Bash 里带中文的 `curl -d` 会按 GBK 发出**，服务端如实回
  `10001 请求体格式不正确`（阶段 11 的修复在真容器上再次可见）。
  验收脚本改用 Python 以 UTF-8 发请求，顺带把权限矩阵与工单闭环一次跑完。

**遗留**：验收冒烟在开发库留下了 1 个会话、1 张已解决工单、1 条待优化问题
（与昨天的演示数据同类，未清理，便于下次直接演示工作台）；
阶段 15 记录的那次**未复现偶发失败**，其后共跑了 4 次门控全量（含本阶段这次），
**均全绿**，仍未复现、仍无归因。

#### 凭证处置：改为专用账号，而不是改 root 口令

口令明文从本文档清掉之后，问题还差最后一半：**被公开过的口令仍在被使用**。
原计划把本机库口令改掉，但查明两件事后改成了另一个做法：

1. **这台机器上 root 是被共用的**：其它项目、`test/06`~`test/14` 的 其它项目 系列、
   `其它库` 等约 8 个项目、36 处配置都用 `root`/同一口令连库。
   改 root 口令会让它们**全部连不上库**，而且报错出现在各自的运行期、很难第一时间归因。
2. **换成 `123456` 并不解决暴露问题**：它本身就是暴力破解字典的第一梯队，
   而且已经作为演示账号口令写在公开的 `docs/demo-runbook.md` 里 ——
   等于"换了一个同样不安全、且同样已知的值"。

最终做法：**给应用建专用账号** `mewchat`（生成 32 位随机口令），
只授权 `mewchat` 库（`CREATE USER` + `GRANT ALL ON \`mewchat\`.*`，`localhost` 与
`127.0.0.1` 各一份），root 保持不动、其它项目零影响。口令只落在 `local.env.bat`（已忽略）。
实测确认：

| 检查 | 结果 |
| --- | --- |
| 专用账号连库、查 `mewchat` 表数 | 通过（7 张表） |
| `CREATE DATABASE IF NOT EXISTS mewchat`（start.bat 的建库路径） | 通过（退出码 0；schema 级授权也覆盖"换机器首次建库"） |
| 越权建别的库 / 读别的库（另一张表） | **均被拒**（`Access denied` / `SELECT command denied`）——权限确实收在一个库内 |
| 门控全量测试用该账号 | **`Tests run: 260, Failures: 0, Errors: 0, Skipped: 0`** |

README 与 `docs/demo-runbook.md` 同步：新增"给应用建专用账号"的步骤与
"为什么值得多这一步"（应用只需一个库的权限，用 root 连库意味着账号泄露时同实例上的
**其它库**也一起交出去），并把脚本里的 `-u root` 改为 `-u "$MYSQL_USER"`。

> 若确实要用 root 直连：把 `MYSQL_USERNAME` 设回 `root` 即可，
> 但那 8 个共用该账号的项目需要在改口令时同步更新（36 处配置，含 `target/classes` 副本），
> 且新口令同样别用 `123456`。

#### 换账号顺带暴露的一键启动缺陷（已修，同类坑第二次）

改账号把 `start.bat` 的两个问题挤了出来 —— 都是"用 root 时看不出来"的那一类：

1. **账号名没有转交（`MYSQL_USER` → `MYSQL_USERNAME`）**。脚本的配置项叫 `MYSQL_USER`，
   而应用读的是 `MYSQL_USERNAME`（`application.yml` 里 `${MYSQL_USERNAME:root}`）。
   脚本只把**库名**通过 `--spring.datasource.url` 传了过去，账号一直是应用的默认值 `root`，
   之所以以前能跑，是因为**口令恰好同名**（`MYSQL_PASSWORD`）被脚本进程继承给了子进程 ——
   "用 root 连库 + root 的口令"刚好凑对。一旦账号不是 root（只授权单库的专用账号），
   就变成"脚本检查 mewchat、应用用 root 连库"，直接连不上。
   修法：启动前 `set "MYSQL_USERNAME=%MYSQL_USER%"`。
   **口令刻意不放进命令行参数**：进程参数列表可以被别的进程看到，环境变量不会那样暴露。
   这与阶段 14 修过的"库名没传给应用"是同一类静默错位（脚本查 A、应用连 B），
   教训是：**脚本里凡是被检查过的值，都要确认它以正确的名字传到了应用**。

2. **预检是假绿灯**：配置读空（或文件被写坏）时，预检依然打印
   "[OK] 本地配置 local.env.bat（库 mewchat，账号 ）"并给出"预检通过" ——
   空账号要到 `start.bat` 走到连接数据库那一步才以"连不上库"的形态失败，
   报错指向数据库而不是配置。原因正是上面那种情况：脚本被写坏过一次，
   而预检只打印不校验。修法：`MYSQL_USER` / `MYSQL_PASSWORD` / `DB_NAME`
   任一为空即 `[X]` 点名报错并中止，提示里同时给出两种常见原因（没填 / 换行不是 CRLF）。

改完实测四种情形：正常配置 → 预检通过；账号读空 + LF 换行 → `[X]` 点名
`MYSQL_USER`、`MYSQL_PASSWORD` 并"启动中止"；恢复后 → 预检通过；
**完整跑一次 `start.bat`（非 check 模式）→ 应用 4.1s 起来、日志无认证失败、
`POST /api/auth/login` 用 `alice` 登录成功**（登录要查 `user` 表，等于证明应用
真的用它自己的账号连上了库），随后 `stop.bat` 正常停止。

### 运行前置条件

- **`MEWCHAT_TOKEN_SECRET` 现在是必填项**（阶段 10 变更）：`application.yml` 不再提供默认值，
  未设置时应用**直接启动失败**。这是刻意的 —— 给一个可用的默认值等于把签名密钥写进仓库，
  任何人都能签出管理员令牌。本地启动：
  ```bash
  export MEWCHAT_TOKEN_SECRET="至少16位的随机串"   # Windows: set MEWCHAT_TOKEN_SECRET=...
  ./mvnw spring-boot:run
  ```
  已有的测试类都在 `@SpringBootTest(properties=...)` 里显式给了测试密钥，不受影响。
- **应用启动不依赖 MySQL**（已实测）：Spring Boot 的 Hikari 连接池延迟到首次取连接时才建立，
  因此 MySQL 未启动时应用照样能起，只是涉及数据库的功能会失败。
- **MySQL：可以不启动服务，直接手动拉起 mysqld**（阶段 11 实测，**不需要管理员权限**）。
  服务 `MySQL` 用管理员启动会报"发生系统错误 5：拒绝访问"，
  但 `E:\mysql\mysql-8.0.34-winx64\data` 对当前用户可写，因此可以直接跑：
  ```bash
  cd /e/mysql/mysql-8.0.34-winx64 && ./bin/mysqld.exe --console   # 或后台运行
  ```
  元数据：8.0.34，端口 3306，`ngram_token_size=2`（与 `ChunkTokenizer` 的 2-gram 对齐），
  sql_mode 含 `STRICT_TRANS_TABLES`（所以超长文本会直接报 1406，阶段 10 的截断修复正是为此）。
  机器上**还有第二套安装**：服务 `MySQL80` 指向 `C:\Program Files\MySQL\MySQL Server 8.0`，
  数据目录在 `C:\ProgramData\MySQL\MySQL Server 8.0\Data`（当前用户无权限访问）。
  两者都配了 3306，只能起一个 —— 本项目用的是 `E:\...` 那一套。
- **数据库凭证**：应用**不再用 root 连库** —— 见阶段 16 的处置记录。本机应用账号是
  `mewchat`（口令见 `local.env.bat`，**刻意不写进本文件**：这是会被提交的文档，
  落一个真实口令等于把凭证提交进仓库）；该账号只被授权 `mewchat` 库。
  **建库与授权仍需 root**（一次性管理动作）。库名 `mewchat`，字符集 utf8mb4。
- **建库与执行脚本**（用 root，属管理动作）：
  ```bash
  MV=/e/mysql/mysql-8.0.34-winx64/bin/mysql.exe
  "$MV" -u root -p"$MYSQL_PWD" --default-character-set=utf8mb4 \
        -e "CREATE DATABASE IF NOT EXISTS mewchat DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
  for f in sql/01_schema.sql sql/02_knowledge_chunk.sql sql/03_pending_clarification.sql \
           sql/04_question_cluster.sql sql/05_question_length.sql; do
    "$MV" -u root -p"$MYSQL_PWD" --default-character-set=utf8mb4 --database=mewchat < "$f"
  done
  ```
  `--default-character-set=utf8mb4` 不能省：脚本是 UTF-8，而 Windows 终端默认可能是 GBK。
  **库名由 `--database` 指定**：六个脚本都不含 `USE`（原先 01/02 自带 `USE \`mewchat\``、
  03~06 靠 `--database`，用别的库名时前两个会静默把表建到 `mewchat`，两边都不报错 ——
  阶段 14 克隆验证时踩到）。要换库名，改建库语句、`--database` 与应用连接串三处即可。
- **跑集成测试**（`mvn` 不在 PATH，用项目内 wrapper；账号与密码都必须显式传入，
  测试里 `mewchat.it.mysql.username` 默认 `root`、密码默认为**空**）：
  ```bash
  export MEWCHAT_TOKEN_SECRET="至少16位的随机串"
  ./mvnw test -Dmewchat.it.mysql=true \
    -Dmewchat.it.mysql.username=mewchat -Dmewchat.it.mysql.password="$MYSQL_PWD"
  ```
  不带这些参数时 34 项需要 MySQL 的测试会跳过（构建依然是绿的）。
- **Milvus 未部署**：19530 / 9091 均无监听；`mewchat.milvus.enabled` 暂为 `false`。
- **联调用演示账号**：`user` 表里 `id=1727138400000000001` / `username=alice`，
  它是 `OrderTool.DEMO_OWNER_USER_ID`，用它登录才能看到"候选订单"列表
  （阶段 11 端到端联调时种入；`sql/01_schema.sql` 不含种子数据）。

### 环境坑位记录

- **`.bat` / `.cmd` 文件必须保持 CRLF 换行**。用脚本（Python 等）改这类文件时，
  若以文本模式读写，`\r\n` 会被归一化成 `\n` 写回，**cmd 解析时会把 `set "MYSQL_USER=..."`
  从中间劈开**，表现为 `'QL_USER' 不是内部或外部命令` 之类的报错、变量静默为空
  （阶段 16 把 `local.env.bat` 写坏过一次，起因就是 `io.open(..., newline='')` 写成了 LF）。
  正确做法：**二进制读写 + 显式 CRLF**，改完校验 `文件里 \n 的数量 == \r\n 的数量`。
  文件本身是 GBK 编码，读写都要指定 `encoding='gbk'`。
- **改 `.bat` 时字符串锚点会命中"调用行"**：想往 `:init_local_env` 标签前插入内容、
  锚点只写 `:init_local_env`，会先命中 `call :init_local_env` 那一行，
  把子程序插进主流程（阶段 16 踩到：脚本从此对任何配置都报错）。
  锚点要带上前导换行（`\r\n:标签\r\n`）并断言替换次数为 1。
- **本机 `grep` 实为 ugrep 7.8.4**，会把含 CRLF 的 Maven 日志判定为二进制文件而**静默不匹配**
  （返回码 1、无输出），极易造成"构建失败"的误判。查此类日志必须加 `-a`：
  `grep -a "BUILD SUCCESS" build.log`
- 引用 Maven 构建日志时统一用 `-a`，或用 `tail` / `sed` 查看。
- **重定向出来的启动日志是平台编码（GBK）而非 UTF-8**：执行
  `java -jar target/mewchat-0.0.1-SNAPSHOT.jar > app.log` 之后，`file app.log` 显示
  `ISO-8859 text`，此时用 UTF-8 模式 grep 中文**一条都匹配不到** ——
  `grep -a "业务工具注册完成" app.log` 返回 0，看起来像"这行日志没打出来"，
  实际是编码不匹配（阶段 7 冒烟测试时踩到，差点误判成工具没注册）。
  查这类日志请优先用 **ASCII 关键字（类名、英文标识）**：
  `grep -a -o "BusinessToolInvoker.*" app.log`。
  注意 surefire 报告是 UTF-8，中文可以直接 grep，两者行为不同。
- **用 curl 发中文必须走 UTF-8 文件**：在 Git Bash 里直接 `-d '{"message":"帮我查订单"}'`
  会把中文按 GBK 发出去，服务端报 `Invalid UTF-8 start byte 0xb0`，
  看起来像服务端故障（阶段 11 冒烟时踩到）。可靠写法是用 Python 写一个 UTF-8 的 JSON 文件再
  `curl --data-binary @file`（Python 是 Windows 版，路径要给 `C:/...` 而不是 MSYS 的 `/tmp/...`）。
- **生成 BCrypt 哈希**（种测试账号用）：`spring-security-crypto` 依赖 `spring-jcl` 提供
  `org.apache.commons.logging.LogFactory`，只给 crypto 一个 jar 会报 `NoClassDefFoundError`。
  而 `-cp "a.jar;b.jar"` 里的 `;` 会被 MSYS 的路径转换搞坏，可靠做法是先用
  `./mvnw dependency:build-classpath -Dmdep.outputFile=target/cp.txt` 导出，
  再写进 `@argfile` 用 `java @args.txt` 启起来。

> **待办（需要用户确认）**：本机 `~/.m2/settings.xml` **不存在**，即所有依赖都从 Maven Central 拉取。
> 实测 Aliyun 镜像比 Central 快约 10 倍。建议添加阿里云镜像（属于用户级全局配置，故未擅自创建）：
>
> ```xml
> <!-- ~/.m2/settings.xml -->
> <settings>
>   <mirrors>
>     <mirror>
>       <id>aliyun</id>
>       <mirrorOf>central</mirrorOf>
>       <url>https://maven.aliyun.com/repository/public</url>
>     </mirror>
>   </mirrors>
> </settings>
> ```

---

## 六、版本要点与未决事项

### LangChain4j 双版本号（别被绕晕）

LangChain4j 的版本号分两套，写 pom 时必须区分：

- **核心模块**为纯版本号：`dev.langchain4j:langchain4j:1.20.0`、`langchain4j-core:1.20.0`
  —— 由 starter 传递引入，通常不需要手写
- **集成模块**带 `-betaNN` 后缀：`langchain4j-spring-boot-starter:1.20.0-beta30`、
  `langchain4j-open-ai-spring-boot-starter:1.20.0-beta30`、`langchain4j-milvus:1.20.0-beta30`

因此 pom 里的 `${langchain4j.version}` = `1.20.0-beta30`，指的是集成线。

### 已确定

1. **Milvus 版本** —— `langchain4j-milvus:1.20.0-beta30` 内部钉住 `milvus-sdk-java:2.5.9`，
   属 2.x SDK 线。**服务端必须部署 Milvus 2.5.x**，装 3.x 会连不上。
2. **大模型接入方式** —— 走 LangChain4j + OpenAI 兼容协议（`base-url` 可切换厂商）。

### 仍未确定（需要你确认）

1. **具体 LLM 厂商** —— `application.yml` 里目前填的是 DeepSeek 示例值
   （`https://api.deepseek.com/v1` + `deepseek-chat`），确认后替换 `base-url` / `model-name` / api-key。
   DeepSeek、通义千问（兼容模式）、智谱 GLM 都是 OpenAI 兼容端点，换 `base-url` 即可，不用改依赖。
2. **Embedding 模型维度** —— RAG 阶段要用。`mewchat.milvus.dimension` 当前填 1024，
   必须与最终选定的 embedding 模型输出维度一致，否则向量入库报错、需要重建集合。
3. **Milvus 与 MySQL 的部署形态** —— 本机 Docker 还是远程服务器？影响连接配置。

---

## 七、协作方式

**分层拆解、先定义后实现、单指令单目标、逐步对齐。**

每次只推进一个模块：先确认接口与数据结构（定义），再写实现。不一次性生成跨模块的大段代码。
