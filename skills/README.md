# Wow Agent Skills

本目录提供九个按用户主要交付结果划分的 Wow Agent Skills：四个面向 Kotlin/Java 服务，两个面向调用 Wow 服务的 TypeScript 应用，一个面向运行中 Wow 服务的数据问答，两个面向 Wow View Engine：一个写视图定义，一个接入宿主。每次任务只选择一个 Primary Skill，由它负责从取证到完成验证，不在执行过程中切换到其他 Wow Skill。

这些 Skills 不复制框架 API 文档，而是补充工作流、架构不变量、授权边界和完成证据。具体 API、配置、默认值、模块名和生成契约必须在目标 checkout 或精确目标 tag 中重新确认。

所有 Skill 仅服务于使用或引入 Wow 的下游应用，Wow 框架仓库自身一律不激活，唯一的例外见下一段。下游任务还必须确认主要交付对象确实是 Wow 行为、代码或迁移：目标应用源码存在 `me.ahoo.wow` import 或 `wow-*` Gradle/Maven dependency/starter，或任务明确要引入、使用、解释或迁移到 Wow。TypeScript 侧的对应标记是 `@ahoo-wang/wow-client`、`@ahoo-wang/wow-react`、`@ahoo-wang/wow-generator` 依赖或 import，或迁移前的 `@ahoo-wang/fetcher-wow`、`@ahoo-wang/fetcher-generator`。Wow 仓库 `typescript/` 下这些包自身的开发同样不激活本包。视图定义与宿主接入的对应标记是 `@ahoo-wang/wow-view-engine`（或 `@ahoo-wang/wow-view-store`）依赖或 import。仅在否定、比较或排除语境中提到 Wow 不能触发本包；DDD、CQRS、Event Sourcing、aggregate、saga、projection、command gateway、Spring、Reactor、Kotlin 或 Java 等通用词也不能。

例外（D10）：Wow 仓库内的视图定义可以激活 `wow-view-definition`，即 Storybook 场景中的定义、系统视图与故事（`typescript/storybook/stories/view-engine/`），以及补偿控制台的视图定义（`compensation/dashboard/src/views/`）；这两处的宿主接线（引擎与资源、`ViewHost`、路由、存储与声明式操作，如控制台的 `src/views/engine.ts`、`routes.ts`、`executionActions.ts`、`viewStore.ts` 与 `src/features/App/ConsoleHost.tsx`）可以激活 `wow-view-host`。修改视图引擎本身（`typescript/wow-view-engine/`）或 `@ahoo-wang/wow-view-store` 包仍不激活任何 Skill。

V9 是当前维护基线和默认术语。`wow-develop`、`wow-review` 与 `wow-debug` 仍可服务 V8 下游应用，但必须先从目标构建与解析依赖确认实际 Wow 版本，再应用精确符号、默认值或 V9 规则；无法确认时标记版本结论未验证。V8 到 V9 的旧类型、配置和行为映射只保存在 `wow-migrate`。

同一主版本也可能有实现 SPI 变更。查询参考按目标是否具备 `QueryFilter.prepare`、`QueryPolicy.evaluate` 和 `QueryAdmission`（Backend 原语接收 `AdmittedQuery`）区分固定管道与历史实现；Backend 仍接收 `(query, schema)` 的中间目标只共享阶段顺序，不能把 around chain、验证模式或旧构造器套到所有 V9 目标。评估用例中的固定 commit 是源码基线，不是已发布制品证明；带明确历史版本的案例继续按其原始合同评估。

## Skills

| Skill | Primary outcome | Boundary |
|---|---|---|
| `wow-develop` | 设计、实现、测试、重构或解释 Wow 行为 | 不用于已有 diff 审查、已有故障诊断或数据切换迁移 |
| `wow-review` | 输出 findings、合并准备度，或完成 review-and-fix | 不用于症状驱动诊断或迁移专项审查 |
| `wow-debug` | 复现、定位已有故障，或完成 diagnose-and-fix | 不用于主动功能开发或普通 diff review |
| `wow-migrate` | 破坏性版本、生成/运行时契约或 Wow-managed 存储/数据迁移 | 不用于无历史/兼容转换的首次采用、无已知破坏且无数据迁移的常规 v8 升级或普通故障 |
| `wow-generator` | 用 `wow-generator` CLI 或 `CodeGenerator` 从 OpenAPI 生成 TypeScript 模型与 Wow CQRS 客户端，或从 `@ahoo-wang/fetcher-generator` 换过来 | 不用于手写运行时客户端代码或 Kotlin/Java 服务端工作 |
| `wow-client` | 用 `@ahoo-wang/wow-client`、`@ahoo-wang/wow-react` 编写 TypeScript 命令、查询与 React 查询 hook 代码，或从 `@ahoo-wang/fetcher-wow` 换过来 | 不用于 OpenAPI 代码生成或 Kotlin/Java 服务端工作 |
| `wow-data-query` | 读取运行中 Wow 服务的查询能力描述，执行只读查询回答业务数据问题，交付答案、所用查询与注意事项 | 不交付代码（属于 `wow-client`）；查询报错或结果异常的诊断属于 `wow-debug` |
| `wow-view-definition` | 依据业务场景与已提交的查询能力描述，用 `defineView` 决定并写出视图定义：列哪些字段、用什么词、收窄什么、系统视图（记录、分析）与看板；自检就是引擎的 `admit` | 不接入宿主（属于 `wow-view-host`），不写运行时客户端代码（属于 `wow-client`），不回答数据问题（属于 `wow-data-query`），不改视图引擎本身 |
| `wow-view-host` | 把视图引擎接入宿主应用：一个引擎与它的资源（定义 + Wow 查询源与描述）、视图存储（`MemoryViewStore`、`localStorageSnapshot`、`WowViewStore` 与 CoSec 网关规则）、`ViewHost`、`bind` 与路由，以及把 Wow 命令声明成操作；以 `actionHarness`、`resolveNavigation` 与 `admit` 自检 | 不决定定义里声明什么（属于 `wow-view-definition`），不写引擎之外的客户端代码（属于 `wow-client`），不改视图引擎本身 |

## Selection order

交付物是来自运行中服务数据的答案而非代码时，选 `wow-data-query`。交付物是视图引擎的视图定义、系统视图、仪表盘或它们的故事时，选 `wow-view-definition`；交付物是引擎在宿主里的接线（资源与数据源、视图存储、`ViewHost`、路由、记录上的命令操作）时，选 `wow-view-host`。交付物是下游 TypeScript 应用中的代码时，先按交付物选择：生成代码或生成器配置选 `wow-generator`；在运行时使用客户端或查询 hook 的代码（包括从 fetcher 旧包名换过来）选 `wow-client`。下列顺序只用于 Kotlin/Java 服务。

按主要交付结果选择，不按涉及的组件名选择：

1. 跨主版本、同主版本 Wow source/config/generated/runtime 破坏性变化，或 Wow-managed 存储/历史数据的转换、对账、切换及不兼容写入回滚是主问题：`wow-migrate`。
2. 存在失败、hang、错误状态或可复现症状，目标是根因：`wow-debug`。
3. 目标是 findings、批准或合并准备度：`wow-review`。
4. 目标是设计、修改、测试或解释 Wow：`wow-develop`。
5. 与 Wow 行为或 API 无直接关系：不激活本包。

`review-and-fix` 始终由 `wow-review` 完成；`diagnose-and-fix` 始终由 `wow-debug` 完成。

## Content model

- `SKILL.md` 只保存入口契约、核心流程、授权边界和 reference 选择规则。
- `references/` 保存稳定决策、源码发现方法和风险边界，按需加载。
- `assets/` 保存可复制到输出中的模板，不作为推理资料默认加载。
- Skill 内的 `scripts/` 只承载重复、确定且容易手写出错的操作；当前仅 `wow-migrate/scripts/audit-v6-usage.sh` 符合这一边界。
- `evals/<case>/prompt.md` 与 `evals/<case>/graders/*.md` 是 `claude plugin eval` 的用例：`prompt.md` 是交给 Agent 的请求（setup 文件内联在请求中），frontmatter 的 `tags` 恰含 `activation` 或 `behavior` 之一。
  - `activation` 用例只有一个 `tool_used: Skill` grader：`trigger` 用例要求本 Skill 加载；`negative` 用例（`min: 0`、`max: 0`、`arm: both`）要求它不加载，包括应由相邻 Skill 或相邻工具（Axon、Spring Data、axios、openapi-generator 等）处理的请求。
  - `behavior` 用例用 `llm` grader 的 PASS 条目评判答案，可再用 `regex` grader 要求答案点出关键符号；其 `tool_used: Skill` grader 只是加载指示。
- eval 用例不属于安装后工作流，也不由 Skill 加载。

安装后的九个 Skill 仅依赖各自目录中的 `SKILL.md`、`agents/` 和按需资源，不依赖仓库根目录的维护脚本。

## Validation

运行轻量结构校验与边界回归测试（CI 的 Skills 工作流在 `skills/` 或校验脚本变更时执行同样两条命令）：

```bash
python3 -S scripts/validate_wow_skills.py
python3 -S -m unittest scripts.test_validate_wow_skills
```

validator 只使用 Python 标准库，检查：

- `SKILL.md` frontmatter、Skill 名称和目录一致性；
- `agents/openai.yaml` 必需字段及 `$skill-name` 默认提示；
- `plugins.json` include 与九个 Skill 目录的一致性；
- `references/`、`assets/`、`scripts/` 引用存在且不能越出 Skill 目录；
- 运行时 Skill 内容不能引用父目录或本机绝对文件系统路径；
- 每个 Skill 的 eval 套件形状：至少 3 个用例、kebab-case 目录、`prompt.md` frontmatter 键与 `claude plugin eval` 一致、grader 类型与 `arm` 合法、至少一个加载用例和一个 `arm: both` 的不加载用例；
- `SKILL.md` description 超过 60 个词时给出警告（暂不失败）。

`wow-view-definition` 与 `wow-view-host` 的 TypeScript 示例另由文档站的测试对照构建后的包编译（`documentation/test/typescript-samples.mjs`，与包 README 的示例同一机制）；`wow-client` 与 `wow-generator` 的示例多为片段，不编译，但同一测试检查它们从 `@ahoo-wang/*` 入口导入的每个名称都由该入口导出。`typescript.yml` 的 docs 作业在这四个 Skill 的 Markdown（`evals/` 除外）变更时运行。

静态校验不会证明自然语言触发或答案正确。用 `claude plugin eval` 在本地执行用例（每次运行都是一次真实 Agent 会话，按登录账号计费，CI 不执行）：

```bash
node scripts/eval-skills.mjs                    # 全部 Skill
node scripts/eval-skills.mjs wow-client         # 指定 Skill
SKILLS_EVAL_RUNS=3 SKILLS_EVAL_MAX_COST=5 SKILLS_EVAL_CONCURRENCY=2 node scripts/eval-skills.mjs
```

脚本在每个 Skill 目录内分两轮调用 `claude plugin eval`：`activation` 用例以 `--ablation none` 运行（不加载 Skill 的对照臂对触发判断没有意义），`behavior` 用例以默认的 with/without 对照运行；随后汇总每个 Skill 的触发召回率与精确率、行为通过率（含不加载 Skill 的基线）和费用。只有被测 Skill 会加载，所以相邻 Skill 的竞争不在评估范围内。环境变量：`CLAUDE_BIN`（默认 `claude`）、`SKILLS_EVAL_RUNS`（默认 1）、`SKILLS_EVAL_MAX_COST`（每个 Skill 两轮共享的美元上限，默认 2）、`SKILLS_EVAL_CONCURRENCY`（默认 1）、`SKILLS_EVAL_PASSES`（`activation`、`behavior`，默认两者）与 `SKILLS_EVAL_MODEL`。报告写入不纳入版本控制的 `skills/<name>/evals/results/`。

## Distribution

`plugins.json` 显式列出可分发的九个 Skill。Ahoo Skills Hub 负责同步、生成和验证插件产物；Wow 仓库拥有并维护 Skill 内容。本架构不分发旧名称或兼容别名；发布后，既有安装必须刷新或重新安装插件，再确认九个 Skill 均可发现。

框架版本与 Skill 插件版本独立。仓库源码修订不代表 Hub 已分发，也不会更新既有安装；源码验证、插件发布和安装刷新应分别报告状态。
