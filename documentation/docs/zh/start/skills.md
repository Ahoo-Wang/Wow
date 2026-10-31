---
title: Agent Skills
description: 安装六个 Wow Agent Skills，为手头的工作选择合适的那个，并了解每个 Skill 交付什么、把什么留给其他 Skill。
---

# Agent Skills

六个 Agent Skills 帮助 AI Agent 在 Wow 10 上构建应用。每个 Skill 对应读者手上的一项工作，交付一种结果，并负责从取证到完成验证的整个任务。它们补充工作流、架构规则、授权边界和完成证据；它们不复制 API 文档，并对照应用所用的确切 Wow 版本确认每个 API、配置项和默认值。

## 安装 {#install}

插件 `ahoo-wow-skills` 包含全部六个 Skill。两种方式任选其一：

- **安装插件**：用所用 Agent 的插件命令，从 Ahoo Skills Hub 安装 `ahoo-wow-skills`；或者
- **拷贝 Skills**：把精确的 Wow tag 中 [`skills/`](https://github.com/Ahoo-Wang/Wow/tree/main/skills) 下的六个 Skill 目录拷到 Agent 的 Skills 目录，例如项目里的 `.claude/skills/<skill>/`。每个目录含 `SKILL.md`、`agents/` 和 `references/`，有的还有 `assets/` 和 `scripts/`；`evals/` 是维护用的，不需要拷贝。

安装后的 Skill 只读取它自己的目录。插件的版本与框架独立：升级之后，刷新或重新安装插件，并确认六个 Skill 都能被发现。

9.x 应用安装 `v9` 分支上的插件，它教的是 9.x；本插件只教 Wow 10。

## 按交付物选择 {#pick-by-what-you-deliver}

| 你在做的事 | Skill | 交付物 | 留给其他 Skill |
| --- | --- | --- | --- |
| 用 Kotlin 或 Java 开发、审查或诊断 Wow 10 服务：聚合、拒绝、Saga、投影、查询、测试、Spring Boot 宿主；也包括首次采用 | `wow-develop` | 代码与测试；审查结论与合并准备度；根因，经授权后修复 | 9.x 迁移（`wow-migrate`）、TypeScript |
| 编写调用 Wow 服务的 TypeScript：从 Manifest 生成的客户端、带等待的命令、查询与流、React hooks、CoSec | `wow-client` | 生成的或手写的客户端代码，以及在 CI 中重新生成它的检查 | 服务端代码、视图引擎的定义与宿主、9.x 客户端迁移 |
| 用运行中服务的实时数据回答业务问题 | `wow-data-query` | 答案、它运行的只读查询，以及注意事项 | 查询代码（`wow-client`）、诊断失败的查询（`wow-develop`） |
| 编写视图引擎的定义：字段、词、收窄、系统视图、仪表盘 | `wow-view-definition` | `defineView` 定义、提交的描述符快照、各语言的词、`admit` 测试 | 宿主接线（`wow-view-host`）、客户端代码 |
| 把视图引擎接到宿主应用：数据源、数据集、视图存储、`ViewHost`、路由、把命令作为操作 | `wow-view-host` | 宿主接线，以及 `actionHarness`、`resolveNavigation` 和 `admit` 测试 | 定义声明什么（`wow-view-definition`） |
| 把 9.x 服务迁到 Wow 10：源码、配置、HTTP 与 TypeScript 客户端、数据导入、切换与回退 | `wow-migrate` | 改写后的源码与 `migration-map.json`、数据演练与切换的证据、回退方案 | 首次采用（`wow-develop`）、10 之内的升级 |

按交付物选择，而不是按任务提到的组件选择。来自实时数据的数字选 `wow-data-query`；视图定义、系统视图或仪表盘选 `wow-view-definition`；引擎在宿主中的接线选 `wow-view-host`；应用中的 TypeScript 选 `wow-client`。在 Kotlin 或 Java 服务中，起点是 9.x 源码、配置、客户端或数据的工作选 `wow-migrate`；其余一切选 `wow-develop`。

## 何时适用 {#when-they-apply}

Skill 只适用于使用 Wow 的应用，或明确要引入、使用、解释或迁移到 Wow 的任务：`me.ahoo.wow` import 或 `wow-*` 依赖（Gradle 插件 `me.ahoo.wow`、BOM `me.ahoo.wow:wow-bom`），或 TypeScript 侧的 `@ahoo-wang/wow-*` 包。DDD、CQRS、事件溯源、聚合或 Saga 这类通用词不会触发它们，Wow 框架仓库本身也不会，但它自己的视图定义与宿主（Storybook、ops 控制台）以及移植它的 9.x 平台模块除外。

## 它们怎样工作 {#how-they-work}

每个 Skill 把它的入口契约和核心流程放在 `SKILL.md` 中，只在任务需要时才加载它的 `references/`：迁移时加载 9.x 到 10 的映射，切换时加载切换的证据。审查或诊断保持只读，除非你授权修复；对于迁移，编写代码、写入数据和发布到生产是三种不同的权限：任何一种都不意味着另一种。因为存储、日志或部署无法触及而无法验证的东西，它们会报告为缺失的证据，而不是假定它成立。
