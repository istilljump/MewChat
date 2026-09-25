# MewChat — 电商 AI 智能客服

面向电商场景的 AI 智能客服系统。不是"接个大模型就完事"的聊天框：
**流程由代码控制、业务数据由工具查出、答不上来时诚实转人工**，每一步都有可复现的轨迹。

| 能力 | 说明 |
| --- | --- |
| 多轮对话 | `SseEmitter` 真流式逐字输出；短时记忆（最近若干轮）+ 长时记忆（会话摘要） |
| Agent 编排 | Supervisor 驱动 12 状态状态机，协调多个 Specialist；**流程走向由转移表决定，不由模型自由发挥** |
| RAG 检索增强 | BM25 关键词 + 向量双通道 → RRF 融合 → 重排 → 置信度；**回答带引用出处**（文档标题 + 段落号 + 分数） |
| 业务工具 | 订单查询 / 物流查询 / 商品查询 / 退款政策：价格、库存、物流轨迹等**事实一律由工具查出**，不让模型编 |
| 不乱说 | 三档置信度闸值（达标直答 / 中档补检索 / 不达标兜底）；兜底自动转人工并建工单 |
| 数据飞轮 | 答不好的问题入池 → 聚类成主题 → 运营补文档 → 标记已优化，清单收敛 |
| 运营后台 | 知识库管理、工单处理、对话记录、数据统计（管理员专属） |
| 可观测 | 每轮上报 Langfuse（未配置时静默跳过，绝不影响业务） |

**技术栈**：Spring Boot 3.5.13 · JDK 17 · LangChain4j 1.20.0（OpenAI 兼容协议）· MyBatis-Plus 3.5.9 · MySQL 8 · Milvus 2.5（可选）

---

## 快速开始

### 0. 最省事的启动方式（Windows）：双击 `start.bat`

```bat
start.bat            :: 预检 → 按需建库/构建 → 启动应用（前台，Ctrl+C 停止）
start.bat check      :: 只做环境预检，不启动（部署前先跑一下）
start.bat fake       :: 顺带启动本地假模型端点（没有大模型 Key 时用）
start.bat rebuild    :: 强制重新构建（含跑测试）后再启动
stop.bat             :: 停掉应用与假端点（构建前先停，否则 jar 被占用无法删除）
```

它会自己处理这几件事：检查 JDK 与 Maven Wrapper、MySQL 没起就试着拉起、
**库不存在就按 01 → 06 顺序建库建表**（含两个演示账号）、jar 不存在就构建。

首次运行会生成 `local.env.bat`（本机配置：MySQL 目录/密码、大模型 Key、
自动生成的令牌密钥）。**它含密码，已被 `.gitignore` 忽略，不会进仓库**。
默认密码按 `root` 写入；不是的话改这一行后重跑即可：

```bat
set "MYSQL_PASSWORD=你的密码"
```

想用真实模型，把 Key 填进同一个文件的 `LLM_API_KEY`（`LLM_BASE_URL`/`LLM_MODEL`
默认已是 DeepSeek，换厂商改这三行即可）。

> **关于 `start.bat fake`**：它启动本地假端点作为"没有 Key 也能看效果"的便利。
> 假端点起不来时**不会阻塞启动**，只会告警 —— 此时提问会走追问（意图识别降级），
> 应用本身完全正常。也可以在另一个窗口双击 `tools/fake-openai/run.bat` 手动启动。

### 0. 前置条件

| 需要什么 | 说明 |
| --- | --- |
| JDK 17+ | 本项目编译目标 17，实测 17 与 22 均可 |
| MySQL 8 | 8.0.x 即可。**必须** `ngram_token_size=2`（默认值就是 2），关键词检索依赖它 |
| 大模型 Key | 可选。**没有也能跑**，见下方"没有 Key 怎么办" |
| Maven | **不需要**，用项目内 `./mvnw`（Windows 用 `mvnw.cmd`） |

> 首次执行 `./mvnw` 会自行下载 Maven 发行版，需要网络。国内网络慢的话可在
> `~/.m2/settings.xml` 配阿里云镜像。

### 1. 建库与建表

> **库名由 `--database` 指定，脚本里不含 `USE`**。因此想换库名，
> 只需改下面两处（建库语句 + `--database`）以及应用连接串里的库名。
> 脚本刻意不写死库名：写死时若调用方指定了别的库，建表会静默落到写死的那个库上，
> 而"未选择数据库"是一个立刻能看见的错误。

```bash
# 建库
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS mewchat DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"

# 按顺序执行建表脚本（01 → 05 顺序不能乱，03/04 是 ALTER，不可重复执行）
for f in sql/01_schema.sql sql/02_knowledge_chunk.sql sql/03_pending_clarification.sql \
         sql/04_question_cluster.sql sql/05_question_length.sql; do
  mysql -u root -p --default-character-set=utf8mb4 --database=mewchat < "$f"
done

# 可选：两个演示账号（alice / admin，密码都是 123456）
mysql -u root -p --default-character-set=utf8mb4 --database=mewchat < sql/06_demo_seed.sql

# 推荐：给应用建专用账号，只授权本库（这三步要用 root，是一次性的管理动作）
# 口令自己生成（例如 32 位随机串），别用弱口令、别提交进仓库
mysql -u root -p -e "
CREATE USER IF NOT EXISTS 'mewchat'@'localhost' IDENTIFIED BY '<你的口令>';
CREATE USER IF NOT EXISTS 'mewchat'@'127.0.0.1' IDENTIFIED BY '<你的口令>';
GRANT ALL PRIVILEGES ON \`mewchat\`.* TO 'mewchat'@'localhost';
GRANT ALL PRIVILEGES ON \`mewchat\`.* TO 'mewchat'@'127.0.0.1';"
# 之后启动应用时给 MYSQL_USERNAME=mewchat、MYSQL_PASSWORD=<你的口令>
```

> **为什么值得多这一步**：应用只需读写 `mewchat` 一个库，用 root 连库意味着
> 账号泄露的那天，同一台 MySQL 上的**其它库**也一起交出去（本机就还有别的项目在用这个实例）。
> 专用账号把影响面收到一个库，也让"库里那些脚本为什么需要 root"变得清楚：
> 建库与授权是管理动作，应用连库是日常动作，两者本就不该共用一个身份。

> `--default-character-set=utf8mb4` 不要省：脚本是 UTF-8，而 Windows 终端默认可能是 GBK，
> 不指定会让建表注释变乱码。
>
> 若 `mysql` 不在 PATH，用绝对路径调用（例如 Windows 下
> `"E:/mysql/mysql-8.0.34-winx64/bin/mysql.exe"`）。

### 2. 配置

只需两个环境变量就能起来：

```bash
# Git Bash
export MEWCHAT_TOKEN_SECRET="dev-secret-0123456789abcdef"   # 必填：令牌签名密钥，≥16 位随机串
export MYSQL_PASSWORD="你的数据库密码"

# Windows cmd:   set MEWCHAT_TOKEN_SECRET=dev-secret-0123456789abcdef
# PowerShell:    $env:MEWCHAT_TOKEN_SECRET="dev-secret-0123456789abcdef"
```

> `MEWCHAT_TOKEN_SECRET` **没有默认值，不设置会直接启动失败** —— 这是刻意的：
> 给一个默认值等于把签名密钥写进仓库，任何人都能伪造管理员令牌。
> 生产环境请用足够随机的值，并通过环境变量或配置中心注入。

### 3. 启动

```bash
./mvnw clean package          # 首次会跑测试（默认不连数据库，全绿）
java -jar target/mewchat-0.0.1-SNAPSHOT.jar

# 或者直接跑
./mvnw spring-boot:run
```

启动成功的标志（日志会逐条打出装配结果）：

```
对话状态机装配完成，共 12 个节点
业务工具注册完成，共 4 个：[order_query, logistics_query, refund_policy_query, product_query]
Tomcat started on port 8080
```

> **别在应用运行时执行 `mvn clean`**：Windows 上 jar 被运行中的进程占用，
> `clean` 阶段会报"无法删除 jar"，看起来像构建坏了。先停应用再构建。

### 4. 试一下

```bash
# 登录换令牌（唯一免认证入口）
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"alice","password":"123456"}' \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

# 新建会话
SID=$(curl -s -X POST http://127.0.0.1:8080/api/chat/session \
  -H "Authorization: Bearer $TOKEN" | sed -n 's/.*"data":"\([^"]*\)".*/\1/p')

# 发起对话（SSE 流式）
printf '{"sessionId":"%s","message":"MC202409240001 这单到哪了"}' "$SID" > /tmp/body.json
curl -N -X POST http://127.0.0.1:8080/api/chat/send \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json; charset=UTF-8' \
  --data-binary @/tmp/body.json
```

返回是一条 SSE 事件流：

```
event:session   ← 会话ID，前端据此确认续接
event:state ×N  ← 进度：加载上下文 / 护栏校验 / 续接判断 / 意图识别 / 路由 /
                  工具调用 / 置信度校验 / 生成回复 / 结束
event:message ×N ← 逐字文本片段
event:done      ← 本轮权威结果（含引用来源、置信度、状态轨迹）
```

`done` 里的 `visitedStates` 是本轮走过的完整状态轨迹 —— 它是"流程可控、可复现"的直接证据：

```json
{"intent":"LOGISTICS_QUERY","finalState":"REPLY","confidence":0.95,
 "citations":[],"handoffRequired":false,
 "visitedStates":["CONTEXT_LOAD","GUARD_CHECK","RESUME_CHECK","INTENT_RECOGNIZE",
                  "ROUTE","TOOL_CALL","CONFIDENCE_CHECK","REPLY","END"]}
```

> ⚠️ **用 curl 发中文必须走 UTF-8 文件**（如上例的 `--data-binary @file`）。
> 直接在 `-d` 里写中文会被终端按 GBK 发出去，服务端报 `Invalid UTF-8 start byte`。
> 浏览器原生 `EventSource` 无法自定义请求头，带令牌的 SSE 请用 `fetch` + `ReadableStream`。

---

## 没有大模型 Key 怎么办

项目自带一个**只依赖 JDK 的本地假 OpenAI 端点**，用它可以把整条链路跑起来 ——
应用侧的真实客户端、真实 SSE 解析、真实编排链路全都会被走到，
被替换的只是"对面那台服务器"（回答文本是固定的，模型质量另说）。

```bash
# 终端 A：起假端点
cd tools/fake-openai && java FakeOpenAi.java 18124

# 终端 B：把应用指过去
export LLM_BASE_URL=http://127.0.0.1:18124/v1
export LLM_API_KEY=local-dev-key
export LLM_MODEL=fake-model
./mvnw spring-boot:run
```

换成真实厂商时只改这三个变量，代码与其它配置都不用动：

```bash
export LLM_BASE_URL=https://api.deepseek.com/v1   # 通义千问/智谱等 OpenAI 兼容端点同理
export LLM_API_KEY=sk-你的真实密钥
export LLM_MODEL=deepseek-chat
```

支持任意 OpenAI 兼容端点（DeepSeek、通义千问兼容模式、智谱 GLM、自建网关等）。
`LLM_EMBEDDING_*` 同理，留空则复用 chat 的密钥与端点。

---

## 配置项一览

全部通过环境变量覆盖（`src/main/resources/application.yml` 里有完整默认值与注释）：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `MEWCHAT_TOKEN_SECRET` | **无（必填）** | 令牌签名密钥，≥16 位；改它会让所有已签发令牌失效 |
| `MYSQL_USERNAME` / `MYSQL_PASSWORD` | `root` / `root` | 数据库账号。**别让应用用 root 连库**：给它建一个只授权本库的专用账号，见下方"建库与建表"最后一步。密码不要写进仓库（本机用 `local.env.bat`，已被 `.gitignore` 忽略） |
| `LLM_API_KEY` | 占位值 | 模型密钥，不填则所有意图识别降级、对话走追问 |
| `LLM_BASE_URL` | `https://api.deepseek.com/v1` | 模型端点 |
| `LLM_MODEL` | `deepseek-chat` | 模型名 |
| `LLM_EMBEDDING_API_KEY` / `_BASE_URL` / `_MODEL` | 留空复用 chat | 向量化模型 |
| `MILVUS_ENABLED` | `false` | 向量库开关。关闭时整条检索链路降级为**纯关键词检索**，知识问答依然可用 |
| `MILVUS_HOST` / `MILVUS_PORT` | `localhost` / `19530` | 需部署 **Milvus 2.5.x** |
| `MILVUS_USERNAME` / `MILVUS_PASSWORD` | 空 | 未开鉴权的部署留空 |
| `MEWCHAT_CORS_ORIGINS` | 空（仅同源） | 前后端分离部署时必须配置，**不要用 `*`** |
| `LANGFUSE_SECRET_KEY` / `_PUBLIC_KEY` / `_HOST` | 空 | 可观测上报；不配置则静默跳过 |

> ⚠️ `mewchat.milvus.dimension` 必须与 embedding 模型输出维度一致（默认 1024）。
> 两处配置不一致时**应用启动即失败**并指出该改哪里 —— 集合按固定维度创建，
> 维度对不上会导致向量写入失败，而且建好的集合只能删掉重建。

---

## 接口一览

| 接口 | 认证 | 说明 |
| --- | --- | --- |
| `POST /api/auth/login` | 免认证 | 换访问令牌（唯一免认证入口） |
| `POST /api/chat/session` | 需要 | 新建会话，返回 sessionId |
| `POST /api/chat/send` | 需要 | 发起对话，SSE 流式返回 |
| `GET /api/chat/session/{id}/history` | 需要 | 历史消息（含引用来源） |
| `GET /api/admin/analytics/overview` | 管理员 | 数据总览 |
| `GET /api/admin/analytics/optimization-checklist` | 管理员 | 待优化清单（聚类后） |
| `POST /api/admin/analytics/questions/{id}/optimize` | 管理员 | 标记已优化并关联文档（飞轮收口） |
| `POST /api/admin/knowledge/documents` | 管理员 | 录入知识文档 |
| `GET /api/admin/tickets` · `POST .../{id}/assign` · `resolve` · `close` | 管理员 | 工单处理 |
| `GET /api/admin/conversations` · `.../{sessionId}/messages` | 管理员 | 对话记录 |

后台接口要求 `userType=3`（管理员）；令牌里带了用户类型，权限判定零次查库。
业务失败按项目约定返回 **HTTP 200 + 业务码**（如 `10001` 参数不合法、`10002` 资源不存在），
而认证失败是**真正的 HTTP 401/403** —— 前端两者都要判断。

---

## 测试

```bash
# 默认测试集：不依赖数据库与外部服务，全绿即可
./mvnw clean package

# 含数据库集成测试（需要上面建好的库）
./mvnw test -Dmewchat.it.mysql=true -Dmewchat.it.mysql.password="你的数据库密码"
```

当前基线：

| 命令 | 结果 |
| --- | --- |
| `./mvnw clean package` | `Tests run: 253, Failures: 0, Errors: 0, Skipped: 39` |
| 加 `-Dmewchat.it.mysql=true ...` | `Tests run: 253, Failures: 0, Errors: 0, Skipped: 0` |

被跳过的那 39 项是需要 MySQL 的集成测试；集成测试**跑完不在库里留数据**，
可以放心在开发库上执行。模型链路用本地假端点做协议级验证
（真实 LangChain4j 客户端 ↔ 假服务端），不消耗 token。

---

## 项目结构

```
src/main/java/com/mewchat/
├── api/         对外接口层（chat SSE / auth / admin）
├── agent/       Agent 编排：ChatState 状态机、supervisor（含 7 个节点）、specialist、memory
├── rag/         检索增强：retrieval（BM25+向量+RRF+置信度）、rerank、document、RagService
├── tool/        业务工具：order / logistics / product / refund
├── service/     业务服务层
├── dao/         数据访问（mysql 实体与 Mapper、milvus 封装）
├── config/      配置类（模型、MyBatis-Plus、Milvus、SSE、安全、Jackson）
├── job/         定时任务（会话收尾、问题聚类）
└── common/      统一返回、异常、工具、令牌、可观测

start.bat / stop.bat  Windows 一键启动 / 停止
sql/             建表脚本（01 → 06）
tools/fake-openai/ 本地假模型端点（只依赖 JDK；run.bat 单独启动）
docs/demo-runbook.md 演示脚本（8 步，每步写清"命令 → 该看到什么"）
docs/data-model.md   数据模型设计
AGENTS.md        项目规范与逐阶段落地记录（Single Source of Truth）
```

---

## 设计要点（面试/答辩常被问到）

**流程不由大模型控制。** 模型只在三个点被调用：意图识别、文本生成、记忆压缩，
每次都有严格的输出校验与降级路径；流程走向由 `ChatState` 的转移表决定，
每次跳转都校验合法性。三道防线保证"永远能返回点什么"：
启动时校验节点完整性、运行时校验转移合法性、任何未预期异常收敛为一次兜底回复。

**出参与入参分离，互不解析对方的表达。** 每个工具同时给两份结果：
给模型读的自然语言摘要、给接口层渲染的结构化数据（金额是 `BigDecimal`、
期限是天数）。给字符串等于把解析工作推给每个下游。

**"缺参数"与"值不对"是两种成因，话术必须不同。** 没给订单号 → "麻烦提供订单号"；
给了但查不到 → "没查到订单号 X，麻烦核对"。两者都导向追问而不是兜底 ——
把"您的单号可能不对"说成"我答不上来"，还会凭空多出一张人工工单。

**"我答不上来"与"这类内容我不回答"是两个状态。** 兜底的含义是能力不足（转人工 + 建工单 +
进低置信度池补知识）；拒答是策略决定（命中安全护栏）。把违规提问当知识盲区喂进补知识流程，
只会污染数据飞轮。

更多设计取舍与逐阶段的"已落地/未验证"清单见 **[AGENTS.md](AGENTS.md)**。

---

## 已知限制

- **会话与挂起状态存在 MySQL，不放内存**：多实例部署天然可用，但**工单幂等的分段锁是进程内的**，
  多实例下需要数据库层兜底（生成列 + 唯一索引）或分布式锁。
- **无 refresh token / 登出 / 令牌吊销**：令牌是自包含的（无状态换零查询），
  要强制下线只能换密钥（会让所有令牌一起失效）。需要吊销请换 JJWT/Nimbus 等成熟库。
- **护栏是词表黑名单**，能挡住"刷 单""刷-单"这类规避写法，但挡不住同义改写与外语表达；
  它是"提高绕过成本"的护栏，不是不可突破的防线。
- **聚类用的是字面相似度**（2-gram Jaccard），能归并"问法几乎一致"的，归不动同义改写。
- **Milvus 通道未经真实服务端验证**：默认关闭，检索走 BM25 单通道；
  开启后请先确认 embedding 维度与 `mewchat.milvus.dimension` 一致。
- **知识库仅支持文本录入**，文件上传（PDF/Word 解析）未实现。
- **商品/订单/物流数据是进程内模拟数据**：真实接入时替换对应工具的取数实现即可，
  上游只依赖 `BusinessTool` 契约。**候选订单已按用户归属过滤**，真实实现同样必须按 userId 过滤。
- **后台无分权**：当前只有管理员能进，客服需要另行设计"能看哪些工单/会话"的权限模型。
- **客户端断开不取消生成**：断开后不再推送，但流程会跑完并落库（用户刷新后能看到完整回答）。

---

## 文档索引

| 文档 | 内容 |
| --- | --- |
| **[AGENTS.md](AGENTS.md)** | 项目规范（Single Source of Truth）+ 逐阶段落地与验证记录 + 已知缺陷与取舍 |
| **[docs/demo-runbook.md](docs/demo-runbook.md)** | 演示脚本：8 个步骤 + "评审常问的四问" + 演示前自检 |
| **[docs/data-model.md](docs/data-model.md)** | 数据模型与字段设计理由 |

## 许可

本项目为学习/演示用途。第三方依赖各自遵循其原始许可。
