---
title: Agent Skills
description: 选择、安装并验证四个面向下游应用的 Wow Agent Skills。
---

# Agent Skills

本页回答：**下游 Wow 任务应使用哪个 Primary Skill，以及完成如何被证明？**

Wow 仓库拥有 Skill 源码和验证夹具；分发仓库与客户端拥有安装和发现流程。Skills 提供工作流、架构不变量、授权边界和证据门禁，不替代目标版本的 API、配置或生成契约。

V9 是当前维护基线和默认术语。三个日常 Skill 仍可处理 V8 下游任务，但必须先从目标构建与解析依赖确认实际 Wow 版本；V8 到 V9 的旧类型、配置与行为映射仅由 `wow-migrate` 保存。无法确认版本时，任何版本专属结论都必须标记为未验证。

## 选择一个 Primary Skill

按用户要求的主要交付结果选择一次，并由该 Skill 负责完整任务：

| Skill | 选择条件 | 不选择的情况 |
|---|---|---|
| `wow-migrate` | 跨主版本、已知破坏性 source/config/generated/runtime 变化，或 Wow 管理的存储/历史数据切换 | 无历史转换的首次采用、常规同主版本非破坏升级 |
| `wow-debug` | 已有失败、hang、错误状态或 reproducer，目标是定位根因；可在授权后修复 | 主动开发、普通 diff review、数据切换 |
| `wow-review` | 目标是 findings、合并准备度或 review-and-fix | 症状驱动诊断、主动功能开发、破坏性迁移专项 |
| `wow-develop` | 设计、实现、测试、重构或解释下游 Wow 行为，包括首次采用 | 已有 diff 审查、已有故障诊断、破坏性迁移 |

如果任务只是通用 Kotlin、Gradle、Dashboard、文档或 DDD/CQRS 讨论，没有范围内的 `me.ahoo.wow` import、`wow-*` 依赖或明确下游 Wow 请求，则不激活这些 Skills。Wow 框架仓库自身也不属于四个 Skill 的目标。

源契约：[`skills/README.md`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/README.md)、[`wow-develop`](https://github.com/Ahoo-Wang/Wow/tree/main/skills/wow-develop)、[`wow-review`](https://github.com/Ahoo-Wang/Wow/tree/main/skills/wow-review)、[`wow-debug`](https://github.com/Ahoo-Wang/Wow/tree/main/skills/wow-debug)、[`wow-migrate`](https://github.com/Ahoo-Wang/Wow/tree/main/skills/wow-migrate)。

## 所有权与安装边界

| 边界 | 所有者 | 使用方式 |
|---|---|---|
| Skill 行为与 references | Wow 仓库 `skills/` | 在本仓库修改并运行本地 validator；不要修改聚合仓库中的生成副本 |
| 可分发插件清单 | Wow 仓库 [`skills/plugins.json`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/plugins.json) | 当前清单只包含四个 Primary Skills；`agents/openai.yaml` 提供客户端显示信息与默认提示 |
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

对于 `wow-review`，没有授权就保持只读；对于 `wow-debug`，先复现和定位再修复；对于 `wow-migrate`，代码、数据、切换和发布权限彼此独立。

## 维护验证

修改本仓库中的 Skills 后运行：

```bash
python3 -S scripts/validate_wow_skills.py
python3 -S -m unittest scripts.test_validate_wow_skills
```

这些命令验证 metadata、agent manifest、插件 include、本地资源路径和 eval JSONL 结构。它们不会执行行为用例，也不会证明自然语言触发、目标 API 或生产迁移正确；行为质量仍需在全新任务中用真实 diff 与命令结果评估。

## 优先下一步

1. 先选择一个 Primary Skill，并在请求中给出范围、授权与证据。
2. 若任务是首次采用，先用[快速上手](./getting-started.md)建立可运行基线。
3. 若任务涉及破坏性契约或历史数据，先阅读[迁移](./migration.md)并固定精确源/目标版本。
