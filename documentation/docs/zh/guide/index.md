---
title: Wow 文档导览
description: 按学习目标和工作任务选择 Wow 文档的最短阅读路径。
outline: deep
---

# Wow 文档导览

从第一次成功开始，再按当前任务继续。

## 30 分钟首次成功目标

依次完成以下确认门槛：

1. 从 [wow-project-template](https://github.com/Ahoo-Wang/wow-project-template) 创建项目。
2. 确认选定的 Wow 版本。
3. 通过领域测试。
4. 启动服务。
5. 发送一条真实 HTTP 命令并检查命令结果。
6. 读取版本化的事件溯源状态。

完整步骤和完成语义见[快速上手](./getting-started.md)。

功能门槛已经验证；由于尚未测量新开发者首次完成的墙钟时间，30 分钟仍是目标时长。

## 继续构建

从[领域模型](./domain/)、[命令](./command/)和[事件与协作](./event/)三个入口继续；再用[投影](./projection.md)和[查询](./query.md)建立读取侧。

## 准备生产运行

先阅读[生产最佳实践](./best-practices.md)，再验证[备份、恢复与重放](./recovery.md)、[应用测试](./application-testing.md)、[可观测性](./advanced/observability.md)和[故障排查](./troubleshooting.md)。

## 精确查阅

需要精确配置、示例或生态资源时，使用[配置参考](../reference/config/core.md)、[示例](../reference/example/order.md)和[生态资源](../reference/ecosystem.md)；需要 Kotlin 或 Java 符号和签名时，从顶部导航进入 API 文档。

## 按角色评估或参与

[入门导航](../onboarding/)按贡献者、Staff Engineer、管理者和产品经理的决策分流；具体取舍见[文章](../articles/)。

## 按任务继续

| 你要完成的任务 | 先读 | 然后读 | 完成标志 |
| --- | --- | --- | --- |
| 判断 Wow 是否适合项目 | [简介](./introduction.md) | [生产最佳实践](./best-practices.md) | 能说明收益、运行成本和不适用场景 |
| 运行第一个应用 | [快速上手](./getting-started.md) | [配置](./configuration.md) | 领域测试通过，真实命令到达 `SNAPSHOT`，状态可读回 |
| 接入现有 Spring Boot 服务 | [接入现有项目](./existing-project.md) | [Spring Boot Starter](./extensions/spring-boot-starter.md) | KSP 元数据、自动路由、命令和快照闭环均通过 |
| 学习完整 Kotlin 应用 | [订单与购物车](../reference/example/order.md) | [应用测试](./application-testing.md) | 能追踪命令、事件、状态、Saga、投影和重启恢复 |
| 设计聚合和业务约束 | [领域模型](./domain/) | [聚合与不变量](./domain/aggregate.md) | 命令产生领域事件，溯源后状态可验证 |
| 建立应用发布门禁 | [应用测试](./application-testing.md) | [生产最佳实践](./best-practices.md) | 领域、HTTP、真实 Adapter、恢复和安全反例都有证据 |
| 演进已持久化事件 | [事件演进](./domain/event-evolution.md) | [事件溯源](./domain/event-sourcing.md) | Upgrader 注册、顺序、历史回放与回滚均有证据 |
| 提供写入 API 和完成语义 | [命令](./command/) | [完成语义](./command/completion.md) | 能区分 `SENT`、`PROCESSED`、`SNAPSHOT` 和 `PROJECTED` |
| 建立查询模型 | [投影](./projection.md) | [查询](./query.md) | 投影可重试且幂等，查询边界清晰 |
| 编排跨聚合流程 | [事件与协作](./event/) | [Saga](./event/saga.md) | 正常、重试、不可恢复路径都有测试 |
| 选择存储和消息实现 | [模块依赖](./advanced/module-dependencies.md) | [扩展](./extensions/spring-boot-starter.md) | 只引入实际需要的后端和 Starter capability |
| 准备上生产 | [生产最佳实践](./best-practices.md) | [备份、恢复与重放](./recovery.md) | 幂等、恢复、容量、告警和回滚均有证据 |
| 处理异常或卡住 | [故障排查](./troubleshooting.md) | 对应的核心/扩展页 | 已定位失败阶段，而不只是扩大超时 |
| 迁移旧系统或旧版本 | [迁移指南](./migration.md) | 选定的迁移路径 | 库存、对账、切流、回滚门禁完整 |

## 如何使用不同类型的文档

- **指南**：解释为什么和怎样完成任务。
- **参考**：查找精确配置、示例和生态资源。
- **API**：从顶部导航进入 Dokka，查找 Kotlin/Java 符号和签名。
- **[入门导航](../onboarding/)**：按贡献者、架构师、管理者或产品经理角色阅读。
- **[文章](../articles/)**：从具体问题理解设计取舍，不代替 API 与配置参考。
- **[文档治理](./advanced/documentation-governance.md)**：定义规范目录、生命周期、证据与清理规则。

::: warning 版本和事实来源
文档解释仓库，但不代替仓库。当文字与当前 tag 的公开契约、配置类、测试或发布说明不一致时，以选定版本的源码为准。
:::
