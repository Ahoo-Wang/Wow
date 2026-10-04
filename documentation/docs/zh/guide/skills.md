---
title: Agent Skills
description: 选择、安装并验证六个面向下游应用的 Wow Agent Skills。
---

# Agent Skills

本页回答：**下游 Wow 任务由哪个 Skill 负责，以及完成如何被证明？**

Wow 仓库拥有 Skill 源码和验证夹具；分发仓库与客户端拥有安装和发现流程。Skills 提供工作流、架构不变量、授权边界和证据门禁，不替代目标版本的 API、配置或生成契约。

V9 是当前维护基线和默认术语。`wow-develop` 仍可处理 V8 下游任务，但必须先从目标构建与解析依赖确认实际 Wow 版本；V8 到 V9 的旧类型、配置与行为映射仅由 `wow-migrate` 保存。无法确认版本时，任何版本专属结论都必须标记为未验证。

## 六个 Skill

按用户要求的交付结果选择 Skill；客户端依据 description 选择，被选中的 Skill 负责完整任务：

| Skill | 负责 | 不负责 |
|---|---|---|
| `wow-develop` | 设计、实现、测试、重构、解释、审查或诊断下游 Wow 行为：首次采用、常规同主版本升级、findings 与合并准备度（授权后修复），以及失败、hang 或错误状态的根因（授权后修复） | 跨主版本或破坏性迁移与数据切换（`wow-migrate`）、非 Wow 代码 |
| `wow-migrate` | 跨主版本、已知破坏性 source/config/generated/runtime 变化，或 Wow 管理的存储/历史数据切换，包括对它们的审查与诊断 | 无历史转换的首次采用、常规同主版本非破坏升级 |
| `wow-client` | 调用 Wow 的 TypeScript 代码：用 `@ahoo-wang/wow-client` 与 `@ahoo-wang/wow-react` 编写命令、查询与 React 查询 hook，以及用 `wow-generator` CLI 从 OpenAPI 文档生成客户端；从 `@ahoo-wang/fetcher-wow` 或 `@ahoo-wang/fetcher-generator` 换过来 | Kotlin/Java 服务端工作、视图定义与宿主接入 |
| `wow-data-query` | 读取运行中服务的查询能力描述，用只读查询回答业务数据问题，交付答案而非代码 | 编写查询代码（`wow-client`）、诊断查询报错或结果异常（`wow-develop`） |
| `wow-view-definition` | 依据已提交的查询能力描述，用 `defineView` 决定并写出 `@ahoo-wang/wow-view-engine` 的视图定义：列哪些字段、用什么词、收窄什么、记录与分析系统视图、看板；以引擎的 `admit` 自检 | 接入宿主（`wow-view-host`）、运行时客户端代码（`wow-client`）、回答数据问题（`wow-data-query`）、修改视图引擎本身 |
| `wow-view-host` | 把视图引擎接入宿主：一个引擎与它的资源和视图存储（`MemoryViewStore`、`localStorageSnapshot`、CoSec 网关之后的 `WowViewStore`，含存储的系统视图）、`ViewHost`、`bind` 与路由，把 Wow 命令声明成操作，以及从已弃用的 `@ahoo-wang/fetcher-viewer` 迁来；以 `actionHarness`、`resolveNavigation` 与 `admit` 自检 | 定义里声明什么（`wow-view-definition`）、引擎之外的客户端代码（`wow-client`）、修改视图引擎本身 |

**插件 0.2.0 起的改名。** `wow-review` 与 `wow-debug` 并入 `wow-develop`，`wow-generator` 并入 `wow-client`。旧名称不保留别名：刷新或重新安装 `ahoo-wow-skills` 即可得到这六个 Skill。

如果任务只是通用 Kotlin、Gradle、Dashboard、文档或 DDD/CQRS 讨论，没有范围内的 `me.ahoo.wow` import、`wow-*` 依赖或明确下游 Wow 请求，则不激活这些 Skills。Wow 框架仓库自身（包括开发 `typescript/` 下的包）也不属于任何 Skill 的目标，唯一的例外是本仓库内的视图定义（`typescript/storybook/stories/view-engine/` 下的 Storybook 场景与故事、补偿控制台的 `compensation/dashboard/src/views/`）可以激活 `wow-view-definition`，这两处的宿主接线（引擎与资源、`ViewHost`、路由、视图存储与声明式操作，如控制台的 `src/views/engine.ts`、`routes.ts`、`executionActions.ts`、`viewStore.ts` 与 `src/features/App/ConsoleHost.tsx`）可以激活 `wow-view-host`；修改视图引擎本身或 `@ahoo-wang/wow-view-store` 包不激活任何 Skill。

`wow-develop` 与 `wow-migrate` 面向 Kotlin/Java 服务；`wow-client` 面向调用这些服务的下游 TypeScript 应用，客户端无论由 OpenAPI 生成还是手写。`wow-data-query` 交付的是来自运行中服务数据的答案：先读能力描述，只在其允许的范围内查询；默认用开发或预发环境，访问生产须经用户明确同意。`wow-view-definition` 交付视图定义，只讲选择：事实经 `defineView` 从已提交的描述来，定义只列出并命名受众需要的、只为受众收窄，每个词都是键、在叶子上说出，自检就是引擎对已提交描述的 `admit`。`wow-view-host` 交付接入：一个引擎、视图存储、带路由的 `ViewHost`，以及把人对一条记录发出的每个命令声明成操作，其 `run` 等读模型反映了命令才结束。

源契约：[`skills/README.md`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/README.md)、[`wow-develop`](https://github.com/Ahoo-Wang/Wow/tree/main/skills/wow-develop)、[`wow-migrate`](https://github.com/Ahoo-Wang/Wow/tree/main/skills/wow-migrate)、[`wow-client`](https://github.com/Ahoo-Wang/Wow/tree/main/skills/wow-client)、[`wow-data-query`](https://github.com/Ahoo-Wang/Wow/tree/main/skills/wow-data-query)、[`wow-view-definition`](https://github.com/Ahoo-Wang/Wow/tree/main/skills/wow-view-definition)、[`wow-view-host`](https://github.com/Ahoo-Wang/Wow/tree/main/skills/wow-view-host)。

## 所有权与安装边界

| 边界 | 所有者 | 使用方式 |
|---|---|---|
| Skill 行为与 references | Wow 仓库 `skills/` | 在本仓库修改并运行本地 validator；不要修改聚合仓库中的生成副本 |
| 可分发插件清单 | Wow 仓库 [`skills/plugins.json`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/plugins.json) | 当前清单包含这六个 Skill；`agents/openai.yaml` 提供客户端显示信息与默认提示 |
| 聚合与分发 | [Ahoo-Wang/skills](https://github.com/Ahoo-Wang/skills) | 从聚合市场安装或刷新 `ahoo-wow-skills`，不把该仓库当作源内容编辑点 |
| 当前安装说明 | [Ahoo Skills](https://skills.ahoo.me/zh-CN/) | 按客户端对应页面执行；安装命令和发布状态可能独立变化 |
| 通用格式 | [Agent Skills specification](https://agentskills.io/) | 只定义通用 Skill 格式，不证明 Wow Skill 的行为正确 |

本仓库不会在应用构建中自动安装 Agent Skills。安装成功只证明客户端发现了插件，不证明某次任务选择正确或结果可靠。

## 使用请求

请求至少给出四项：

```text
目标：为 Order 增加取消行为
范围：只修改下游 order-domain
授权：允许改代码和测试，不允许发布
证据：运行 :order-domain:test，并报告兼容性与缺失的运行证据
```

Skill 随后应从目标 checkout 建立事实：读取定义、消费者、测试、配置与生成契约；只在授权范围内写入；运行最窄有效检查；准确报告结果与缺失证据。

完整注解参数、DSL 方法、配置键、默认值和后端列表必须从目标版本重新发现。references 只提供稳定决策和发现方法，不能被当作冻结 API 手册。

## 完成证据

一次 Skill 任务只有在最终报告包含下列内容时才算完成：

- 实际目标版本、范围和授权边界；
- 读取或修改的行为及其事实来源；
- 准确的命令、退出结果和失败数；
- 公开、生成、数据或运行兼容性影响；
- 未执行的外部、生产、数据、发布或回滚验证，明确标为缺失证据。

对于 `wow-develop`，审查与诊断没有授权就保持只读，故障先复现和定位再修复；对于 `wow-migrate`，代码、数据、切换和发布权限彼此独立。

## 维护验证

修改本仓库中的 Skills 后运行：

```bash
python3 -S scripts/validate_wow_skills.py
python3 -S -m unittest scripts.test_validate_wow_skills
```

这些命令验证 metadata、agent manifest、插件 include、本地资源路径，以及每个 Skill 的 `claude plugin eval` 套件形状（`evals/<case>/prompt.md` 与 `graders/*.md`）。它们不执行用例。要衡量触发与答案质量，在本地运行套件；每次运行都是一次真实 Agent 会话，按你的 Claude 登录计费，CI 不执行：

```bash
node scripts/eval-skills.mjs [skill…]
```

`SKILLS_EVAL_RUNS`、`SKILLS_EVAL_MAX_COST`（每个 Skill 的美元上限）、`SKILLS_EVAL_CONCURRENCY` 与 `CLAUDE_BIN` 调整运行；报告写入不纳入版本控制的 `skills/<name>/evals/results/`。

## 优先下一步

1. 说明要的交付结果，并在请求中给出范围、授权与证据。
2. 若任务是首次采用，先用[快速上手](./getting-started.md)建立可运行基线。
3. 若任务涉及破坏性契约或历史数据，先阅读[迁移](./migration.md)并固定精确源/目标版本。
