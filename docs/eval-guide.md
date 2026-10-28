# 回答质量评测指南（tools/eval）

本目录是 MewChat 的**离线评测集**：对运行中的应用走真实 HTTP + SSE，逐条断言行为是否符合预期，输出通过率报告。它是提示词与检索改动的**量化对比基线** —— AGENTS.md 阶段 13 起多处"等有评测集再动"的欠账由它偿还。

## 组成

| 文件 | 说明 |
| --- | --- |
| `cases.json` | 评测用例（18 条，覆盖订单/物流/商品/知识命中/知识未命中/澄清续接/指代追问/护栏拒答） |
| `EvalRunner.java` | 评测执行器（单文件，仅依赖 JDK；先 `javac` 再 `java`，不要用单文件源码启动，原因同 `tools/fake-openai/run.bat`） |
| `report.txt` | 最近一次运行的完整报告（UTF-8，运行后生成） |

## 怎么跑

前置条件（与演示环境一致，见 `docs/demo-runbook.md`）：

1. 已执行 `sql/01`~`10` 与 `sql/06`（评测默认账号 `alice / 123456`；08/09 是工具的业务数据，评测断言依赖其中的具体值）
2. 应用已在 8080 运行（真模型或本地假端点均可）
3. 知识库已录入演示文档：「七天无理由退换货规则」「赠品发货时效说明」（runbook 0.6 节）

```bash
cd tools/eval
javac -encoding UTF-8 -d build EvalRunner.java
java -cp build EvalRunner
```

**⚠️ 应用须以关闭对话限流的方式启动**（阶段 26 引入了每用户每分钟 10 轮的限速，
评测一次连发约 20 轮，必超）：

```bash
MEWCHAT_RATELIMIT_ENABLED=false ./start.bat fake
```

未关闭时评测会在第 11 轮左右开始集体失败，报告里会给出同样的提示。

可用环境变量：`MEWCHAT_EVAL_BASE_URL`（默认 `http://127.0.0.1:8080`）、
`MEWCHAT_EVAL_USERNAME/PASSWORD`、`MEWCHAT_EVAL_CASES`（用例文件路径）。

控制台只输出 ASCII（进度与 PASS/FAIL）；完整报告（含每轮问答、意图、终态、断言明细）在 `tools/eval/report.txt`。**退出码**：全部通过为 0，否则 1 —— 可以直接接进 CI 或作为改动的验收门槛。

## 断言口径（重要）

用例只断言**确定性事实**，不断言模型措辞：

| 字段 | 含义 |
| --- | --- |
| `intent` / `intentAny` | done 事件里的意图（枚举名）。`intentAny` 用于"真实模型与假端点判断可能不同"的场景（阶段 13 结论：两者都走工具都能答对，不拧意图名） |
| `finalState` / `finalStateAny` | 流程终态：REPLY / CLARIFY / FALLBACK / REJECT |
| `mustContain` / `mustContainAny` | 回复内容必须包含的关键词；**匹配前双方都去掉全部空白**（"3 个工作日" 与 "3个工作日" 等价） |
| `citationTitle` | 至少一条引用的文档标题包含该词（RAG 检索结果是确定性的） |
| `handoffRequired` | 是否转人工 |

每条用例用**全新会话**执行，跑完自动删除会话（消息物理删除），可反复执行。
断言之所以敢写死关键词（如 499、已发货、3个工作日），是因为这些值来自**工具出参与知识文档**，两种模型端点下都必然出现在提示词里；模型的组织语言方式则不参与断言。

## 怎么加用例

1. 在 `cases.json` 的 `cases` 数组里加一条，`id` 用 ASCII（进控制台输出），`name` 可中文（进报告）。
2. 单轮用例写一个 `turns` 元素；多轮用例（澄清续接、指代追问）按顺序写多个，每轮都可带 `expect`。
3. 新增行为（新工具、新状态、新话术）时，先想清楚它的**确定性事实**是什么再写断言：
   状态/意图/引用标题/工具数值可以断言；自然语言措辞不要断言 —— 那会让人不敢改提示词。

## 已知边界（如实记录）

- **假端点基线不是满分也不该是满分**：三条用例在假端点下走了较松的断言 ——
  `logistics_by_tracking`（假端点没有物流意图分支，任何不含订单号的问句都不会被它判成
  LOGISTICS_QUERY）、`order_not_found`（假端点只会把 `MC202409240001` 填进参数，
  其余单号一律按"缺参数"处理）与 `order_status`（开启模型模式后两个工具的事实都会进提示词，
  但假模型的回复只展开其中一个，真模型下可收紧为 `mustContain: [已发货]`）。
  真模型下可分别把前两条收紧为 `finalState: REPLY` 与 `mustContainAny: [核对, 没有查到]`。
- 兜底用例（`knowledge_miss_invoice`）会因业务行为**留下工单与低置信度问题** —— 这是
  "兜底建单"的正确行为，不是评测残留；跑后台演示前可按 runbook 清理。
- 评测器是进程外黑盒：它验证"接口层的可观察行为"，编排轨迹（visitedStates）目前不参与断言，
  需要轨迹级断言时用仓库内的集成测试（`ChatFlowWiringTest` 等）。
- 报告写死在 `tools/eval/report.txt`（运行目录需可写）；每次运行覆盖。
