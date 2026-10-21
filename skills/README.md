# Wow Agent Skills

本目录提供六个 Wow Agent Skills，面向在 **Wow V10** 上构建应用的人。每个 Skill 对应读者手上的一项工作，交付一种结果，并负责从取证到完成验证的整个任务。9.x 应用使用 `v9` 分支上的插件（0.x），本插件只教 V10。

这些 Skills 不复制框架 API 文档，而是补充工作流、架构不变量、授权边界和完成证据。具体 API、配置、默认值、模块名和生成契约必须在目标 checkout 或精确的目标 tag 中重新确认。

## 按工作选择

| 你在做的事 | Skill | 交付物 | 不负责 |
|---|---|---|---|
| 开发、审查或诊断一个 V10 服务（Kotlin/Java）：聚合、拒绝、Saga、投影、查询、测试、Spring Boot 宿主 | `wow-develop` | 代码与测试；审查结论与合并准备度；根因与修复（修复需授权） | 9.x 服务的迁移（`wow-migrate`）；TypeScript 代码 |
| 写调用 Wow 服务的 TypeScript 代码：从 Manifest 生成客户端，发命令并等待，查询与流，React hooks，CoSec 鉴权 | `wow-client` | 生成的或手写的客户端代码，以及在 CI 里重新生成的检查 | 服务端代码；视图引擎的定义与接入；9.x 客户端的迁移（`wow-migrate`） |
| 用运行中服务的实时数据回答业务问题 | `wow-data-query` | 答案、所用的只读查询、注意事项（含数据集 409、舱壁 429） | 交付代码（`wow-client`）；查询报错或结果异常的诊断（`wow-develop`） |
| 为视图引擎写视图定义：列哪些字段、用什么词、收窄什么、系统视图与看板 | `wow-view-definition` | `defineView` 定义、提交的能力描述快照、各语言的词、`admit` 测试 | 宿主接线（`wow-view-host`）；客户端代码；数据问答；改视图引擎本身 |
| 把视图引擎接到宿主应用：引擎与数据源、数据集、视图存储、`ViewHost`、路由、把命令声明成操作 | `wow-view-host` | 宿主接线代码，以及 `actionHarness`、`resolveNavigation`、`admit` 测试 | 定义里声明什么（`wow-view-definition`）；引擎之外的客户端代码；改视图引擎本身 |
| 把 9.x 服务迁到 V10：Kotlin 源码、配置、HTTP 与 TypeScript 客户端、数据导入与校验、切换与回滚 | `wow-migrate` | 改写后的源码与 `migration-map.json`、数据演练与切换的证据、回滚方案 | 首次采用（`wow-develop`）；V10 内部的常规升级；与 Wow 无关的数据库迁移 |

**按交付物选，不按涉及的组件名选。** 答案来自运行中服务的数据选 `wow-data-query`；视图定义、系统视图、看板或它们的故事选 `wow-view-definition`；引擎在宿主里的接线选 `wow-view-host`；下游 TypeScript 应用里的代码选 `wow-client`。Kotlin/Java 服务中，起点是 9.x 的源码、配置、客户端或数据时选 `wow-migrate`（包括对这类迁移的审查与诊断），其余开发、审查与诊断都选 `wow-develop`。运维集群与处置故障暂由 `wow-develop` 的诊断部分覆盖（ops 路由只读）。

## 何时激活

所有 Skill 只服务于使用或引入 Wow 的下游应用。目标必须确实以 Wow 为交付对象：源码里有 `me.ahoo.wow` import 或 `wow-*` 依赖（V10 的 Gradle 插件 `me.ahoo.wow` 与 BOM `me.ahoo.wow:wow-bom`），TypeScript 侧有 `@ahoo-wang/wow-client`、`@ahoo-wang/wow-react`、`@ahoo-wang/wow-generator`、`@ahoo-wang/wow-view-engine` 或 `@ahoo-wang/wow-view-store` 依赖或 import，或任务明确要引入、使用、解释或迁移到 Wow。仅在否定、比较或排除语境中提到 Wow 不触发；DDD、CQRS、Event Sourcing、aggregate、saga、projection、Spring、Reactor、Kotlin、Java 等通用词也不触发。

Wow 框架仓库自身一律不激活，例外只有两处：

- **视图**：仓库内的视图定义可以激活 `wow-view-definition`，即 Storybook 场景（`typescript/storybook/stories/view-engine/`）与 ops 控制台的定义（`platform/ops/console/src/views/` 里的 `parked.ts`、`overview.ts` 与它们的词）；这两处的宿主接线（ops 控制台的 `engine.ts`、`bindings.ts`、`parkedActions.ts`、`parkedCommands.ts`）可以激活 `wow-view-host`。修改视图引擎本身（`typescript/wow-view-engine/`）或 `@ahoo-wang/wow-view-store` 包不激活任何 Skill。
- **平台服务的移植**：把仓库里 9.x 的平台模块（它的源码在 `main` 与 `v10-9x-baseline` 上）移植为 `platform/` 下的 V10 项目，按下游迁移使用 `wow-migrate`。

## 安装

插件 `ahoo-wow-skills`（`plugins.json`）包含这六个 Skill。两种安装方式：

1. **从 Ahoo Skills Hub 安装插件** `ahoo-wow-skills`，用所用 Agent 的插件命令。Hub 负责同步、生成和验证插件产物；Wow 仓库拥有并维护 Skill 内容。
2. **直接拷贝**：把本目录下六个 Skill 目录（每个含 `SKILL.md`、`agents/`、`references/`，以及有的 `assets/`、`scripts/`）从精确的 Wow tag 拷到 Agent 的 Skills 目录，例如项目里的 `.claude/skills/<skill>/`。`evals/` 是维护用例，不需要拷贝。

安装后的 Skill 只依赖各自目录里的内容，不依赖仓库根目录的脚本。插件版本与框架版本独立；升级后刷新或重新安装插件，再确认六个 Skill 都能被发现。9.x 应用安装 `v9` 分支上的插件。

## Content model

- `SKILL.md` 只保存入口契约、核心流程、授权边界和 reference 选择规则。
- `references/` 保存稳定决策、源码发现方法和风险边界，按需加载。
- `assets/` 保存可复制到输出中的模板，不作为推理资料默认加载。
- Skill 内的 `scripts/` 只承载重复、确定且容易手写出错的操作。
- `evals/<case>/prompt.md` 与 `evals/<case>/graders/*.md` 是 `claude plugin eval` 的用例：`prompt.md` 是交给 Agent 的请求（setup 文件内联在请求中），frontmatter 的 `tags` 恰含 `activation` 或 `behavior` 之一。
  - `activation` 用例设 `max_turns: 2`，只有一个 `tool_used: Skill` grader：`trigger` 用例要求本 Skill 加载；`negative` 用例（`min: 0`、`max: 0`、`arm: both`）要求它不加载，包括应由相邻 Skill 或相邻工具（Axon、Spring Data、axios、openapi-generator 等）处理的请求。
  - `behavior` 用例用 `llm` grader 的 PASS 条目评判答案，可再用 `regex` grader 要求答案点出关键符号；其 `tool_used: Skill` grader 只是加载指示。
- eval 用例不属于安装后工作流，也不由 Skill 加载。

## Validation

运行轻量结构校验与边界回归测试（CI 的 Skills 工作流在 `skills/` 或校验脚本变更时执行同样两条命令）：

```bash
python3 -S scripts/validate_wow_skills.py
python3 -S -m unittest scripts.test_validate_wow_skills
```

validator 只使用 Python 标准库，检查：

- `SKILL.md` frontmatter、Skill 名称和目录一致性；
- `agents/openai.yaml` 必需字段及 `$skill-name` 默认提示；
- `plugins.json` include 与六个 Skill 目录的一致性；
- `references/`、`assets/`、`scripts/` 引用存在且不能越出 Skill 目录；
- 运行时 Skill 内容不能引用父目录或本机绝对文件系统路径；
- 每个 Skill 的 eval 套件形状：至少 3 个用例、kebab-case 目录、`prompt.md` frontmatter 键与 `claude plugin eval` 一致、grader 类型与 `arm` 合法、至少一个加载用例和一个 `arm: both` 的不加载用例；
- `SKILL.md` description 不超过 60 个词（超出即失败）。

`wow-view-definition` 与 `wow-view-host` 的 TypeScript 示例另由文档站的测试对照构建后的包编译（`documentation/test/typescript-samples.mjs`，与包 README 的示例同一机制）；`wow-client` 的示例多为片段，不编译，但同一测试检查它从 `@ahoo-wang/*` 入口导入的每个名称都由该入口导出。`ci.yml` 的 docs 作业在这三个 Skill 的 Markdown（`evals/` 除外）变更时运行。

静态校验不会证明自然语言触发或答案正确。用 `claude plugin eval` 在本地执行用例（每次运行都是一次真实 Agent 会话，按登录账号计费，CI 不执行）：

```bash
node scripts/eval-skills.mjs                    # 全部 Skill
node scripts/eval-skills.mjs wow-client         # 指定 Skill
SKILLS_EVAL_RUNS=3 SKILLS_EVAL_MAX_COST=5 SKILLS_EVAL_CONCURRENCY=2 node scripts/eval-skills.mjs
```

脚本在每个 Skill 目录内分两轮调用 `claude plugin eval`：`activation` 用例以 `--ablation none` 运行（不加载 Skill 的对照臂对触发判断没有意义），且用例设 `max_turns: 2`（Skill 是否加载在第一轮就已确定，更多轮次只为无人评判的后续工作付费；达到轮次上限的运行照常评分，不计为错误）；`behavior` 用例默认以 with/without 对照运行，`SKILLS_EVAL_ABLATION=none` 去掉不加载 Skill 的对照臂（费用约减半，用于比较同一 Skill 的两个版本；此时 `tool_used: Skill` grader 计入分数）；随后汇总每个 Skill 的触发召回率与精确率、行为通过率（含不加载 Skill 的基线）和费用。只有被测 Skill 会加载，所以相邻 Skill 的竞争不在评估范围内。环境变量：`CLAUDE_BIN`（默认 `claude`）、`SKILLS_EVAL_RUNS`（默认 1）、`SKILLS_EVAL_MAX_COST`（每个 Skill 两轮共享的美元上限，默认 2）、`SKILLS_EVAL_CONCURRENCY`（1–8，默认 1）、`SKILLS_EVAL_PASSES`（`activation`、`behavior`，默认两者）、`SKILLS_EVAL_ABLATION`（behavior 轮的 `--ablation`：`with-without` 或 `none`，默认 `with-without`）、`SKILLS_EVAL_MODEL` 与 `SKILLS_EVAL_CASE`（两轮共用的 `--case` 用例名 glob，只重跑部分用例；只支持一个含 `*`、`?` 的 glob，如 `a07-*`；默认全部）。报告写入不纳入版本控制的 `skills/<name>/evals/results/`。分数只记录、不设门槛（`--threshold 0`）：脚本仅在某一轮未完成（费用上限、登录失效、错误）时退出 1，找不到 `claude` 时退出 127。

## Distribution

`plugins.json` 显式列出可分发的六个 Skill。本架构不分发旧名称或兼容别名。源码验证、插件发布和安装刷新应分别报告状态：仓库源码修订不代表 Hub 已分发，也不会更新既有安装。
