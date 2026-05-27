# Wow Agent Skills

本目录存放面向 Wow 框架开发的 Agent Skills。它们遵循通用的 `SKILL.md` 目录约定，目标是在 Claude Code、Codex 以及其他支持本地 skills 的 agent 环境中复用。

这些 skills 不绑定某一个客户端。它们的目标不是复述框架文档，而是把当前源码、DDD/Event Sourcing/CQRS 方法论、测试策略和开发工作流组织成可执行、可迁移的 agent 指南。

## 兼容性目标

- 每个 skill 目录以 `SKILL.md` 作为入口。
- `SKILL.md` 使用标准 YAML frontmatter，至少包含 `name` 和 `description`。
- 大段参考资料放入 `references/`，由 agent 按需加载。
- 验证脚本放在仓库级 `scripts/`，不依赖特定客户端运行时。
- 文档避免使用只属于某个 agent 产品的术语，除非是在说明兼容范围。

## 总体结构

```mermaid
graph TD
    A[用户任务] --> B[wow Router]
    B --> C{任务类型}
    C --> D[wow-development-workflow]
    C --> E[wow-code-review]
    C --> F[wow-debugging]
    C --> G[wow references]

    D --> D1[Align]
    D1 --> D2[Discover]
    D2 --> D3[Model]
    D3 --> D4{Aggregate 或 Saga}
    D4 --> D5[Aggregate Flow]
    D4 --> D6[Saga Flow]
    D5 --> D7[AggregateSpec]
    D6 --> D8[SagaSpec]
    D7 --> D9[Enhance]
    D8 --> D9
    D9 --> D10[Review]
    D10 --> D11[Verify]
```

## Skills

| Skill | 职责 |
|-------|------|
| `wow` | 总入口和路由器。识别 Wow 任务，按场景加载 workflow、review、debugging 或 reference。 |
| `wow-development-workflow` | 端到端开发工作流。覆盖需求确认、源码发现、领域建模、Aggregate Flow、Saga Flow、测试、增强、审查和验证。 |
| `wow-code-review` | Wow 语义优先的代码审查。重点检查事件溯源、聚合边界、Saga 编排、测试覆盖和 API metadata。 |
| `wow-debugging` | Wow 管线问题定位。按命令、事件、溯源、Saga、等待策略、Query DSL、配置和测试阶段定位根因。 |

## 工作流哲学

- 源码第一，文档第二，记忆最后。
- 命令表达意图，领域事件表达已经发生的事实，状态只由事件溯源得到。
- 聚合负责不变量，Saga 负责跨聚合编排，不把 Saga 写成隐藏聚合。
- 命令和领域事件应携带 `@Summary` 与 `@Description`，为 schema/API metadata 提供可读信息。
- 重要且重复的领域字段应抽象为 `<FieldName>Capable` 接口，形成共享领域词汇。
- `AggregateSpec` 验证聚合行为，`SagaSpec` 验证 Saga 编排行为。
- KDoc、测试场景文档和设计报告属于证据，不是装饰。

## Reference Files

`wow/references/` 提供按需加载的事实参考：

| Reference | 内容 |
|-----------|------|
| `modeling.md` | 聚合建模、命令/事件 metadata、字段能力接口、生命周期和路由。 |
| `annotations.md` | Wow 注解，包括 `@Summary`、`@Description`、命令、溯源、Saga、Retry 等。 |
| `testing.md` | `AggregateSpec`、`SagaSpec`、verifier、fork/ref 和 FluentAssert。 |
| `command-gateway.md` | Command Gateway、等待策略、幂等、LocalFirst、HTTP wait header。 |
| `dsl.md` | Query DSL、condition、pagination、projection、sort。 |
| `configuration.md` | Spring Boot starter 与模块配置。 |
| `prepare-key.md` | PrepareKey 唯一性和预留流程。 |

`wow-development-workflow/references/` 提供 workflow 产物模板：

| Reference | 内容 |
|-----------|------|
| `comment-standards.md` | KDoc、`@Summary`、`@Description` 和字段能力接口注释规则。 |
| `test-case-template.md` | 聚合行为和 Saga 编排的测试场景文档模板。 |
| `design-report-template.md` | 聚合/Saga 设计报告模板。 |
| `test-patterns.md` | workflow 到 `AggregateSpec` / `SagaSpec` 的测试映射。 |

## 使用路径

常见任务应从 `wow` 开始：

| 任务 | 路径 |
|------|------|
| 新增或完善聚合/Saga 能力 | `wow` -> `wow-development-workflow` |
| 编写或补强聚合测试 | `wow` -> `wow-development-workflow` -> `Aggregate Flow` |
| 编写或补强 Saga 测试 | `wow` -> `wow-development-workflow` -> `Saga Flow` |
| 审查 PR 或 diff | `wow` -> `wow-code-review` |
| 定位失败或异常行为 | `wow` -> `wow-debugging` |
| 查询单点 API 或注解规则 | `wow` -> `wow/references/*` |

## 维护规则

- 修改 skills 前，优先检查当前 Wow 源码；文档只能作为辅助。
- 不把长篇框架知识塞回 `wow/SKILL.md`，保持它是 Router。
- 重复、细节性材料放入 `references/`，由 workflow 按需加载。
- 不恢复旧的 `wow-aggregate-enhance` 顶层入口；聚合增强已经并入 `wow-development-workflow` 的 `Enhance` 阶段。
- 新增规则后，优先补充 lint 或 eval，避免知识继续漂移。

## 事实依据

高风险事实优先对照这些源码入口：

| 主题 | 当前依据 |
|------|----------|
| API metadata | `wow-api/src/main/kotlin/me/ahoo/wow/api/annotation/Summary.kt`, `Description.kt`; `wow-schema/src/main/kotlin/me/ahoo/wow/schema/*Resolver.kt` |
| Command Gateway | `wow-core/src/main/kotlin/me/ahoo/wow/command/CommandGateway.kt`, `CommandResult.kt`; `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/aggregate/command/CommandComponent.kt` |
| Wait Chain | `wow-core/src/main/kotlin/me/ahoo/wow/command/wait/chain/SimpleWaitingForChain.kt`; `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/command/AggregateRequest.kt` |
| Testing DSL | `test/wow-test/src/main/kotlin/me/ahoo/wow/test/AggregateSpec.kt`, `SagaSpec.kt`, `AggregateVerifier.kt`, `SagaVerifier.kt` |
| Query DSL | `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/`; `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/QueryDsl.kt` |
| Configuration | `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/**/*Properties.kt` |
| Saga retry policy | `wow-api/src/main/kotlin/me/ahoo/wow/api/annotation/Retry.kt` |
| PrepareKey | `wow-core/src/main/kotlin/me/ahoo/wow/infra/prepare/PrepareKey.kt`, `PreparedValue.kt`; `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/prepare/PrepareKeyAutoRegistrar.kt` |

## 验证

修改本目录后运行：

```bash
python3 scripts/skill_lint.py
python3 scripts/test_skill_lint.py
jq empty skills/wow/evals/evals.json
git diff --check
```
