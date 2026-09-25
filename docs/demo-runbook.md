# 演示脚本（Runbook）

面向**演示 / 答辩 / 参赛**的一条最短闭环：把产品形态完整跑一遍。
每一步都写了「命令 → 该看到什么」，照着做即可，不需要先读源码。

> 本文件是 `docs/data-model.md` 之外的第二个文档：那份讲数据结构，这份讲怎么把它跑给评审看。

---

## 0. 一次性准备

### 0.1 环境变量（四种终端各选一种写法）

本机 `mvn` 不在 PATH，统一用项目内 `mvnw`。以下变量**必须设置**：

| 变量 | 作用 | 说明 |
| --- | --- | --- |
| `MEWCHAT_TOKEN_SECRET` | 令牌签名密钥 | **必填**，不设则应用启动失败（刻意如此）。至少 16 位随机串 |
| `MYSQL_PASSWORD` | 数据库密码 | 本机开发库账号是 `root`，口令**不写进本文档** —— 见 `local.env.bat`（已被 `.gitignore` 忽略） |
| `LLM_API_KEY` | 模型密钥 | 演示用真模型时填；用本地假端点时随便填一个非空值 |
| `LLM_BASE_URL` | 模型端点 | 真厂商示例：`https://api.deepseek.com/v1` |
| `LLM_MODEL` | 模型名 | 真厂商示例：`deepseek-chat` |

```bash
# Git Bash
export MEWCHAT_TOKEN_SECRET="demo-secret-0123456789abcdef"
export MYSQL_PASSWORD="<你本机的库口令，见 local.env.bat>"   # 口令不入仓库
export LLM_BASE_URL=http://127.0.0.1:18124/v1
export LLM_API_KEY=local-dev-key
export LLM_MODEL=fake-model

# Windows cmd:        set MEWCHAT_TOKEN_SECRET=demo-secret-0123456789abcdef
# PowerShell:         $env:MEWCHAT_TOKEN_SECRET="demo-secret-0123456789abcdef"
```

### 0.2 启动 MySQL

本机以管理员启动服务会报"拒绝访问"，但数据目录对当前用户可写，直接手动拉起即可：

```bash
cd /e/mysql/mysql-8.0.34-winx64 && ./bin/mysqld.exe --console
```

**本机 `mysql` 客户端不在 PATH**，本文档统一用它：

```bash
MV=/e/mysql/mysql-8.0.34-winx64/bin/mysql.exe      # 打印sql时用它
q()  { "$MV" -h 127.0.0.1 -P 3306 -u root -p"$MYSQL_PASSWORD" --default-character-set=utf8mb4 "$@"; }
```

首次或换机器时建库并按 `sql/01 → sql/05` 顺序执行脚本：

```bash
q -e "CREATE DATABASE IF NOT EXISTS mewchat DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
for f in sql/01_schema.sql sql/02_knowledge_chunk.sql sql/03_pending_clarification.sql          sql/04_question_cluster.sql sql/05_question_length.sql; do
  "$MV" -h 127.0.0.1 -P 3306 -u root -p"$MYSQL_PASSWORD" --default-character-set=utf8mb4 --database=mewchat < "$f"
done
```

> `--default-character-set=utf8mb4` 不能省：脚本是 UTF-8，而 Windows 终端默认可能是 GBK。

### 0.3 三个演示账号

`sql/01_schema.sql` **不含种子数据**，演示前先造三个账号（密码都是 `123456`）：

```bash
q --database=mewchat <<'SQL'
INSERT INTO user (id, username, password, nickname, user_type, status, deleted) VALUES
 (1727138400000000001,'alice','$2a$10$lzo09L/d0SShiOJsIDv/rOfzqURgoH015W0lfSXhbUGwdAHLuT1XC','演示客户',1,1,0),
 (1727138400000000005,'bob','$2a$10$lzo09L/d0SShiOJsIDv/rOfzqURgoH015W0lfSXhbUGwdAHLuT1XC','演示客服',2,1,0),
 (1727138400000000009,'admin','$2a$10$lzo09L/d0SShiOJsIDv/rOfzqURgoH015W0lfSXhbUGwdAHLuT1XC','演示管理员',3,1,0)
ON DUPLICATE KEY UPDATE password=VALUES(password);
SQL
```

`alice` 的 ID 是 `OrderTool.DEMO_OWNER_USER_ID`，**用它登录才能看到候选订单列表**；
`bob` 是客服账号，用于步骤 8 演示后台分权。

### 0.4 选一条模型路线

**路线 A：真实厂商模型（推荐用于正式演示）**

```bash
export LLM_BASE_URL=https://api.deepseek.com/v1   # 或通义千问/智谱等兼容端点
export LLM_API_KEY=sk-你的真实密钥
export LLM_MODEL=deepseek-chat
```

**路线 B：本地假端点（没有 Key 时排练用）**

```bash
cd tools/fake-openai
java FakeOpenAi.java 18124        # 另开一个终端常驻
```

它说 OpenAI 协议，因此**应用侧真实客户端、真实 SSE 解析、真实编排链路都会被跑到**，
被替换的只是"对面那台服务器"。回答文本是固定的，但追问、工具调用、检索、兜底、
落库这些产品行为与真模型完全一致。
（**它替代不了模型效果**：回答质量、逐字速度、对提示词的遵循，只有真 Key 能体现。）

### 0.5 启动应用

```bash
./mvnw -DskipTests package
java -jar target/mewchat-0.0.1-SNAPSHOT.jar
```

启动日志里可以直接指给评审看的三行（证明装配正确）：

```
对话状态机装配完成，共 12 个节点
业务工具注册完成，共 4 个：[order_query, logistics_query, refund_policy_query, product_query]
（候选参数：order_query->orderNo, logistics_query->orderNo, product_query->productName）
Tomcat started on port 8080
```

> **注意**：应用运行时不要再执行 `mvn clean` —— Windows 会把 jar 锁住，
> `clean` 阶段报错，看起来像"构建坏了"。先停应用再构建。

### 0.6 预置演示知识（应用起来之后执行）

步骤 2b 与步骤 4 只有在知识库里有对应内容时才会答出**带引用**的结果。
先把这两篇录进去（用管理端接口，顺带演示知识录入）：

```bash
TMPW="$LOCALAPPDATA/Temp/mwdemo"   # UTF-8 请求体临时目录
mkdir -p "$TMPW"

token_of() { curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' -d "{\"username\":\"$1\",\"password\":\"123456\"}" \
  | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])"; }
ATOKEN=$(token_of admin)

ingest() {   # 用法：ingest <标题> <正文>
  python -c "
import io,json,sys
io.open(r'$TMPW/doc.json','w',encoding='utf-8').write(
  json.dumps({'title':sys.argv[1],'content':sys.argv[2],'category':'售后'},ensure_ascii=False))
" "$1" "$2"
  curl -s -X POST http://127.0.0.1:8080/api/admin/knowledge/documents \
    -H "Authorization: Bearer $ATOKEN" -H "Content-Type: application/json; charset=UTF-8" \
    --data-binary @"$TMPW/doc.json"; echo
}

ingest "七天无理由退换货规则" "签收之日起 7 天内，商品完好可申请无理由退货。退换货时限从签收当日算起，超期不再受理。"
ingest "赠品发货时效说明" "赠品随主商品一起发出；若赠品缺货，将在到货后 3 个工作日内单独寄出。"
```

**该看到**：两次 `code=0`，且 message 里如实写明向量化状态。默认 `MILVUS_ENABLED=false` 时是
"文档已录入（关键词检索可用），但向量化未完成：…向量库未启用…"——
**这不是失败**：关键词通道已经能检索，语义通道要等 Milvus。演示时这句话本身就是
"系统如实上报自身状态、不假装一切正常"的例子。

---

## 1. 演示步骤

以下命令假定已在 Git Bash 里，且 `TMPW` 指向一个可写目录（用于放 UTF-8 请求体）：

```bash
TMPW="$LOCALAPPDATA/Temp"
# 登录并取出令牌
login() { curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' -d "{\"username\":\"$1\",\"password\":\"123456\"}"; }
TOKEN=$(login alice | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
```

> **中文请求体必须走 UTF-8 文件**：直接在 `-d` 里写中文会被终端按 GBK 发出去，
> 服务端报 `Invalid UTF-8 start byte`。用下面这个函数：

```bash
ask() {   # 用法：ask <sessionId> <中文问题>
  python -c "
import io,json,sys
io.open(r'$TMPW/q.json','w',encoding='utf-8').write(
  json.dumps({'sessionId':sys.argv[1],'message':sys.argv[2]},ensure_ascii=False))
" "$1" "$2"
  curl -s -N -X POST http://127.0.0.1:8080/api/chat/send \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json; charset=UTF-8" \
    --data-binary @"$TMPW/q.json"
}
new_session() { curl -s -X POST http://127.0.0.1:8080/api/chat/session \
  -H "Authorization: Bearer $TOKEN" | sed -n 's/.*"data":"\([^"]*\)".*/\1/p'; }
```

### 步骤 1：接口有身份，不是裸奔

```bash
curl -s -o /dev/null -w "无令牌 -> HTTP %{http_code}\n" -X POST http://127.0.0.1:8080/api/chat/session
curl -s -o /dev/null -w "伪造令牌 -> HTTP %{http_code}\n" -X POST http://127.0.0.1:8080/api/chat/session \
  -H "Authorization: Bearer abc.def"
```

**该看到**：两个都是 `401`。登录接口是**唯一免认证入口**。

### 步骤 2：多轮对话 + 逐字流式

```bash
S=$(new_session); echo "session=$S"
ask "$S" "MC202409240001 这单到哪了"
```

**该看到**（SSE 事件流）：

```
event:session      ← 首帧给会话ID，前端据此确认续接
event:state        ×9   ← 进度提示：加载上下文 / 护栏校验 / 续接判断 / 意图识别 / 路由 /
                          工具调用 / 置信度校验 / 生成回复 / 结束
event:message      ×3   ← 逐字片段（真模型下这里会明显看到"打字机"效果）
event:done              ← 本轮权威结果
```

`done` 里重点看四个字段：`intent=ORDER_QUERY`、`finalState=REPLY`、`confidence`、
`visitedStates` 轨迹。**轨迹是"流程由代码控制、不由模型自由发挥"的证据**。

再追一句，体现"读到了上一轮"：

```bash
ask "$S" "那大概什么时候能到"
```

**该看到**：轨迹里仍有 `CONTEXT_LOAD`（说明读到了历史），`done.totalTokens` 明显大于首轮
（提示词里带上了上一轮内容）；真模型会用上"那"指代的订单，而不是把它当成新问题。

> 演示时不必纠结模型把"这单到哪了"判成 `ORDER_QUERY` 还是 `LOGISTICS_QUERY` ——
> 实测真模型倾向判成**物流查询**，两条路径都走工具、都能答对，属于模型的合理判断。

**指代消解值得单独讲，它是这条链路里最隐蔽的一处风险**：它会把 `resolvedMessage`
覆盖成模型输出的那句话，而下游的意图识别与检索用的就是它 ——
**模型在这一步返回垃圾，等于把用户的问题整句换掉**。
排查线索：发现"每类问题都被判成同一个意图"时，先看日志里指代消解的输出。
步骤 4b 是专门演示这一环的地方。

### 步骤 3：业务工具查的是真数据，不是模型编的

```bash
ask "$S" "耳机多少钱"          # 商品查询：关键词"耳机"落到具体商品
```

**该看到**：`intent=PRODUCT_QUERY` → 轨迹含 `TOOL_CALL` → 回答里是价格与库存。
**数字来自工具、不是模型编的**，这一点两种模型路线都一样。

### 步骤 4：知识问答带引用出处

> 知识库内容由 0.4 预置。若跳过那一步，这里会走兜底（也是有效演示，但要换个说法讲）。

```bash
ask "$S" "七天无理由退货怎么操作"
```

**该看到**：`done.citations` 非空，含 `docTitle`（"七天无理由退换货规则"）、`chunkNo`、`score`；
轨迹含 `RAG_RETRIEVE`。落库后 `message.ref_docs` 里能看到同样的引用。

> **真模型实测**：回答会引用规则原文（时限从签收当日算起、超期不受理、商品需完好），
> 并在问不到的地方（如"具体操作入口"）**明确说"我这边无法确认"**，而不是编一个路径出来 ——
> 这正是"不乱说"这一条卖点的现场证据。

### 步骤 4b：指代追问（多轮记忆 + 指代消解）

**这一轮必须紧跟在上一轮之后**：只有紧接着知识轮问"这个"，指代对象才是退货政策。

```bash
ask "$S" "那这个有时间限制吗"
```

**该看到**：`finalState=REPLY` 且**仍然带引用**。

**现场讲解点**：真模型把"这个"消解成了"七天无理由退货"
（应用 DEBUG 日志里能看到检索用的 query 是 `那七天无理由退货有时间限制吗`），
所以这一轮能检索到同一篇文档并作答 —— **指代消解把省略式提问补全成了可检索的问句**。

> 反过来说，如果上一轮问的是订单、这一轮问"那这个有时间限制吗"，
> 模型会把"这个"理解成订单，而知识库里没有订单主题 → 走兜底。
> **这是正确行为，不是 bug**：演示时按本文档的顺序问即可。

看指代消解的实际输出（可选，答辩时很有说服力）：

```bash
grep -a -o "query='[^']*'" app.log | tail -3 | iconv -f GBK -t UTF-8
```

### 步骤 5：答不出来时兜底，并自动建单

```bash
ask "$S" "你们支持开发票吗"   # 知识库里没有这个主题
```

**该看到**：`finalState=FALLBACK`、`handoffRequired=true`，话术是
"知识库中没有找到可靠答案，已为您转接人工客服"。同时后台多了两样东西：
一条低置信度问题（数据飞轮的入口）、一张人工工单。

```bash
q --database=mewchat -e "
SELECT question, hit_count, confidence, optimized FROM low_confidence_question;
SELECT id, type, status, LEFT(description,40) FROM ticket;"
```

### 步骤 6：信息不足时反问，并给出可选项

```bash
S2=$(new_session)
ask "$S2" "我想看看你们有什么商品"   # 模型不会凭空编一个商品名
```

**该看到**：`finalState=CLARIFY`，追问里列出**在售**商品清单（缺货的不列）。
挂起状态落库：

```bash
q --database=mewchat -e "
SELECT JSON_EXTRACT(pending_clarification,'\$.missingParam') AS param,
       JSON_LENGTH(JSON_EXTRACT(pending_clarification,'\$.options')) AS options
FROM conversation WHERE session_id='$S2';"
```

然后用户回一个序号（这里选 2，换个商品以证明填进去的是他选的那一项）：

```bash
ask "$S2" "2"
```

**该看到**：轨迹变成 `CONTEXT_LOAD → GUARD_CHECK → RESUME_CHECK → TOOL_CALL → … → REPLY`
—— **跳过了意图识别与路由**（"1"拿去识别意图只会得到 UNKNOWN，所以续接必须绕开它），
置信度为 1，挂起状态被清空。这是"断点续接"最直观的一屏。

### 步骤 7：运营后台与数据飞轮

```bash
ATOKEN=$(login admin | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

# 总览
curl -s "http://127.0.0.1:8080/api/admin/analytics/overview" -H "Authorization: Bearer $ATOKEN"
# 待优化清单（问题池聚类后的结果）
curl -s "http://127.0.0.1:8080/api/admin/analytics/optimization-checklist" -H "Authorization: Bearer $ATOKEN"
# 录入一篇知识文档（补知识这一步）
python -c "
import io,json
io.open(r'$TMPW/doc.json','w',encoding='utf-8').write(json.dumps({
 'title':'发票开具说明',
 'content':'支持开具电子发票。下单时在备注里填写抬头与税号，发货后 24 小时内开具并发送到订单预留邮箱。',
 'category':'售后'},ensure_ascii=False))"
curl -s -X POST http://127.0.0.1:8080/api/admin/knowledge/documents \
  -H "Authorization: Bearer $ATOKEN" -H "Content-Type: application/json; charset=UTF-8" \
  --data-binary @"$TMPW/doc.json"
```

**该看到**：录入回执里如实写明向量化状态。默认 `MILVUS_ENABLED=false`，
因此会返回"文档已录入（关键词检索可用），但**向量化未完成**：向量库未启用…"——
**这不是失败，是如实告知**：关键词通道已经可用，语义通道要等 Milvus。

最后把问题标记为已优化，演示**清单收敛**：

```bash
QID=$("$MV" -h 127.0.0.1 -P 3306 -u root -p"$MYSQL_PASSWORD" -N --default-character-set=utf8mb4 mewchat \
  -e "SELECT id FROM low_confidence_question WHERE optimized=0 LIMIT 1;")
curl -s -X POST "http://127.0.0.1:8080/api/admin/analytics/questions/$QID/optimize" \
  -H "Authorization: Bearer $ATOKEN" -H "Content-Type: application/json" \
  -d '{"knowledgeDocId":1}'
# 再看清单：该问题应消失
curl -s "http://127.0.0.1:8080/api/admin/analytics/optimization-checklist" -H "Authorization: Bearer $ATOKEN"
```

### 步骤 8：权限边界（后台分权）

```bash
# 客户访问后台 → 403：任何后台分组都不对客户开放
curl -s -o /dev/null -w "客户访问工单列表 -> HTTP %{http_code}\n" \
  "http://127.0.0.1:8080/api/admin/tickets" -H "Authorization: Bearer $TOKEN"

GTOKEN=$(login bob | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

# 客服（userType=2）能进一线工作面：工单与对话记录
curl -s -o /dev/null -w "客服访问工单列表 -> HTTP %{http_code}\n" \
  "http://127.0.0.1:8080/api/admin/tickets" -H "Authorization: Bearer $GTOKEN"
curl -s -o /dev/null -w "客服看我的工单 -> HTTP %{http_code}\n" \
  "http://127.0.0.1:8080/api/admin/tickets/my?status=1" -H "Authorization: Bearer $GTOKEN"

# 但治理面（知识库 / 统计）仍然只有管理员能进
curl -s -o /dev/null -w "客服改知识库 -> HTTP %{http_code}\n" \
  "http://127.0.0.1:8080/api/admin/knowledge/documents" -H "Authorization: Bearer $GTOKEN"
```

**该看到**：`403` → `200` → `200` → `403`。后台按"工作面"分权：
一线（工单 + 对话记录，客服接单要看完整对话）与治理面（知识库 + 统计，
改知识库影响所有回答，只放给管理员）。令牌里带了用户类型，权限判定零次查库。

---

## 2. 评审常问到的四个问题

**Q：流程是不是让大模型自由发挥的？**
不是。模型只在三个点被调用（意图识别、文本生成、记忆压缩），且每次都有输出校验与降级路径；
流程走向由 `ChatState` 的转移表决定。`done.visitedStates` 就是每轮路径的完整证据。

**Q：回答里的订单/价格是模型编的吗？**
不是。订单状态、金额、库存、价格一律由工具查出（`agent_name=ToolSpecialist` 可见），
模型只负责组织语言。让模型"顺手"生成业务数据是这类系统里最危险的写法。

**Q：答不上来怎么办？**
兜底不装作知道：固定话术转人工 + 自动建一张工单 + 把问题写进低置信度池。
后者是数据飞轮的入口 —— 运营补文档后标记已优化，清单收敛。

**Q：跑不通 / 报错怎么定位？**
- 启动失败且提示 `token-secret 未配置` → 没设 `MEWCHAT_TOKEN_SECRET`（刻意 fail-fast）。
- 请求体报 `Invalid UTF-8 start byte` → 终端把中文按 GBK 发出去了，改用 UTF-8 文件。
- `mvn clean` 报 `Failed to delete ...jar` → 应用还在跑、锁住了 jar，先停应用。
- 回答全是"抱歉，我没太理解您的意思" → 模型调用失败（Key/端点不对），
  意图识别降级为 UNKNOWN 后走了追问。查应用日志里的 `意图识别失败` 一行。
- **各类问题都被判成同一个意图** → 先看指代消解的输出：它会覆盖用户原话，
  返回垃圾就会把问题整句换掉（用 `tools/fake-openai` 时对应的坑是"它必须回用户那句原话"）。
- 候选清单里全是**订单**、而你的问题是关于**商品**的 → 看是不是上一轮的挂起状态还在
  （`conversation.pending_clarification`）：用户没回答完就换话题时，续接会优先命中旧的挂起项。

---

## 3. 演示前 5 分钟自检

```bash
./mvnw -B test                                        # 默认测试集应全绿
./mvnw -B test -Dmewchat.it.mysql=true -Dmewchat.it.mysql.password="$MYSQL_PASSWORD"  # 真库测试应全绿
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/api/admin/analytics/overview  # 401 = 应用活着
```

当前基线：默认 `Tests run: 253, Failures: 0, Skipped: 39`；
加 MySQL 参数 `Tests run: 253, Failures: 0, Skipped: 0`。

**演示前务必清掉上一轮的脏数据**（否则"清单收敛"那一步会被历史数据干扰）：

```bash
q --database=mewchat -e "
DELETE FROM message; DELETE FROM conversation; DELETE FROM low_confidence_question; DELETE FROM ticket;"
```
