---
title: "Agent Skills"
description: "安装并使用四个意图型 Wow Agent Skills，让 Agent 基于当前源码完成开发、评审、排障与 v6 到 v8 迁移。"
---

# Agent Skills

Wow Agent Skills 把框架特有的工作流、架构不变量、安全边界和完成证据组织成四个可复用 Skill。它们不复制 API 文档；注解参数、配置默认值、DSL 方法和生成契约必须在当前 checkout 或精确目标 tag 中重新确认。[`skills/README.md`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/README.md#L1-L5)

| 入口 | 用途 |
|---|---|
| [Wow `skills/`](https://github.com/Ahoo-Wang/Wow/tree/main/skills) | Skill 内容、references、assets、evals 与插件源元数据 |
| [Ahoo Skills 站点](https://skills.ahoo.me/zh-CN/) | 插件目录、安装命令与分发说明 |
| [Ahoo-Wang/skills](https://github.com/Ahoo-Wang/skills) | Codex 与 Claude Code 的聚合市场 |
| [Agent Skills 规范](https://agentskills.io/) | `SKILL.md` 格式与渐进式披露模型 |

Wow 仓库拥有 Skill 内容；Ahoo Skills Hub 定期同步、校验并生成 `ahoo-wow-skills` 插件。不要直接修改聚合仓库中的生成副本。[`skills/README.md`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/README.md#L54-L56)

## 四个 Primary Skills

客户端按用户的**主要交付结果**选择一个 Primary Skill，而不是按涉及的组件名选择。选定后，该 Skill 负责整个任务，不再切换到另一个 Wow Skill。[`skills/README.md`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/README.md#L7-L26)

| Skill | 何时使用 | 完整生命周期 |
|---|---|---|
| `wow-develop` | 设计、实现、测试、重构或解释 Wow 行为/API | Frame → Discover → Model → Prove → Change → Verify → Report |
| `wow-review` | 输出 findings、质量判断、合并准备度，或执行 review-and-fix | Scope → Context → Findings → 授权修复 → Post-fix review |
| `wow-debug` | 复现和定位已有失败，或执行 diagnose-and-fix | Capture → Reproduce → Locate → Hypothesize → Test → Fix/Conclude |
| `wow-migrate` | 将已有 Wow v6 应用迁移到固定的 Wow v8 版本 | Baseline → Target → Matrix → Adapt → Rehearse → Cut over → Roll back |

对应源文件：[`wow-develop`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/wow-develop/SKILL.md#L1-L27)、[`wow-review`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/wow-review/SKILL.md#L1-L38)、[`wow-debug`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/wow-debug/SKILL.md#L1-L39)、[`wow-migrate`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/wow-migrate/SKILL.md#L1-L47)。

```mermaid
flowchart TD
    Task["用户任务"] --> Intent{"主要交付结果"}
    Intent -->|设计、实现、测试、解释| Develop["wow-develop"]
    Intent -->|findings 或合并判断| Review["wow-review"]
    Intent -->|复现或根因| Debug["wow-debug"]
    Intent -->|已有 v6 应用迁移 v8| Migrate["wow-migrate"]
    Intent -->|与 Wow 行为无关| None["不激活"]

    classDef default fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Intent fill:#161b22,stroke:#30363d,color:#e6edf3
    linkStyle default stroke:#8b949e
```

### 选择顺序

1. v6→v8 兼容、数据或切换是主问题：`wow-migrate`。
2. 已有失败、hang、错误状态或 reproducer，目标是根因：`wow-debug`。
3. 目标是 findings、批准或合并准备度：`wow-review`。
4. 目标是设计、修改、测试或解释 Wow：`wow-develop`。
5. 纯 Kotlin/Gradle、dashboard 或不涉及框架语义的文档任务：不激活。

`review-and-fix` 始终留在 `wow-review`；`diagnose-and-fix` 始终留在 `wow-debug`。这样授权状态、证据和 diff 基线不会在 Skill 切换时丢失。

## 渐进式加载

每个 `SKILL.md` 只保存核心流程和选择规则，具体领域材料按需加载：[开发 Skill 的 reference 表](https://github.com/Ahoo-Wang/Wow/blob/main/skills/wow-develop/SKILL.md#L29-L41)。

| 任务 | 首个 reference |
|---|---|
| Aggregate、command、event、sourcing | `aggregate-sourcing.md` |
| Saga、Projection、EventProcessor | `saga-processors.md` |
| CommandGateway、wait、HTTP command route | `command-delivery.md` |
| Query DSL 与 read model | `query-read-model.md` |
| Starter、storage、bus | `starter-storage.md` |
| Runtime lifecycle | `runtime-lifecycle.md` |
| PrepareKey 唯一性与预留 | `prepare-key.md` |
| 测试层级与完成证据 | `verification-evidence.md` |

references 只保存稳定决策、源码发现方法和验证边界。完整注解参数、测试 DSL 方法表、配置键、默认值和后端枚举应直接从目标版本源码发现。[`skills/README.md`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/README.md#L28-L36)

## 安装

### Codex

```bash
codex plugin marketplace add Ahoo-Wang/skills --ref main
codex plugin add ahoo-wow-skills@ahoo-skills
```

### Claude Code

```text
/plugin marketplace add https://github.com/Ahoo-Wang/skills
/plugin install ahoo-wow-skills
```

安装命令和当前发布状态以 [Ahoo Skills 中文站点](https://skills.ahoo.me/zh-CN/) 为准。插件更新后，使用客户端的刷新机制或新任务确认四个 Skill 已被发现。

四 Skill 架构是有意的破坏性重写：不分发旧名称或兼容别名。既有安装需要在发布后刷新或重新安装插件。

## 使用方式

请求至少应说明目标、范围、授权模式和完成证据：

| 信息 | 示例 |
|---|---|
| 目标 | “为 `Order` 增加取消能力并补测试” |
| 范围 | “只修改 `example-domain`，保持公开 API 兼容” |
| 模式 | “只 review，不修改”或“定位并修复” |
| 验证 | “运行 `:example-domain:test` 并报告准确结果” |

```mermaid
sequenceDiagram
    autonumber
    participant User as 用户
    participant Client as Agent 客户端
    participant Skill as Primary Skill
    participant Repo as 当前 checkout
    participant Gate as 验证门禁

    User->>Client: 目标、范围、授权与完成标准
    Client->>Skill: 自动激活一个 Primary Skill
    Skill->>Repo: 读取定义、消费者、测试和当前 diff
    Repo-->>Skill: 返回目标版本的实际契约
    Skill->>Gate: 运行最窄的 test/check
    Gate-->>Skill: 返回结果与证据缺口
    Skill-->>User: 交付变更或证据化结论
```

## 验证与维护

维护者运行：

```bash
python3 scripts/validate_wow_skills.py
```

该命令校验四个 Skill 的标准结构、`openai.yaml`、显式插件清单、资源链接、迁移脚本和 eval schema。[`skills/README.md`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/README.md#L38-L52)

结构校验不能证明自动激活。每个 Skill 的 `evals/activation.jsonl` 必须通过全新任务和真实 activation trace 验证；`evals/behavior.jsonl` 用于检查 source-first、只读授权、测试证据和迁移安全门禁。回答“看起来正确”不等于路由或行为通过。

## 相关页面

| 页面 | 关系 |
|---|---|
| [快速上手](./getting-started.md) | 建立可运行的 Wow 应用 |
| [聚合建模](./modeling.md) | `wow-develop` 的 Aggregate 建模背景 |
| [测试套件](./test-suite.md) | 当前 Aggregate/Saga 测试 API |
| [故障排查](./troubleshooting.md) | `wow-debug` 的运行与配置排障背景 |
| [Wow v6 迁移到 v8](./migration/v6-to-v8.md) | `wow-migrate` 的框架迁移专题 |
