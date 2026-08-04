---
title: Wow 入门中心
description: 根据读者角色选择参与、评估或采用 Wow 的阅读路径
---

# Wow 入门中心

欢迎来到 Wow。

本页按读者需要做出的决策，将你分流到最合适的入门指南。

Wow 是围绕 CQRS 与事件溯源构建的响应式领域驱动设计框架，项目的[能力概览](https://github.com/Ahoo-Wang/Wow/blob/main/README.md#L51-L84)对此进行了说明。
当前仓库基线为 Wow `8.10.2`、Kotlin `2.4.10`、Spring Boot `4.1.0`、Gradle `9.6.1` 与 Java `17`。
这些版本以仓库配置为准，而不是以本文为准：[项目版本](https://github.com/Ahoo-Wang/Wow/blob/main/gradle.properties#L18-L23)、[依赖版本](https://github.com/Ahoo-Wang/Wow/blob/main/gradle/libs.versions.toml#L1-L35)、[Gradle Wrapper](https://github.com/Ahoo-Wang/Wow/blob/main/gradle/wrapper/gradle-wrapper.properties#L1-L9) 与 [JVM Toolchain](https://github.com/Ahoo-Wang/Wow/blob/main/build.gradle.kts#L175-L190)。

## 选择阅读路径

| 读者 | 从这里开始 | 你将学到什么 | 建议时间 |
| --- | --- | --- | --- |
| 贡献者 | [贡献者指南](./contributor-guide.md) | 配置仓库、理解运行时、实现垂直切片、验证并准备可评审变更。 | 约 60 分钟 |
| Staff Engineer | [Staff Engineer 指南](./staff-engineer-guide.md) | 判断模块边界、扩展契约、运行时不变量、迁移与架构决策。 | 约 45 分钟 |
| 管理者 | [管理者指南](./executive-guide.md) | 理解产品形态、工程模型、战略优势、依赖与交付风险。 | 约 30 分钟 |
| 产品经理 | [产品经理指南](./product-manager-guide.md) | 将领域行为转换为命令、事件、验收标准、可观测性与发布范围。 | 约 30 分钟 |

## 推荐阅读顺序

第一次参与代码贡献，请从[贡献者指南](./contributor-guide.md)开始。

架构负责人可以先读贡献者指南，再继续阅读 [Staff Engineer 指南](./staff-engineer-guide.md)。

产品与管理读者可以直接进入对应指南，在需要实现细节时再回到贡献者指南。

## 事实来源原则

这些指南解释仓库，但不能替代仓库。

当文字与代码不一致时，应以已提交的 Gradle 配置、公开契约、实现、测试与 CI 工作流为准。
[模块清单](https://github.com/Ahoo-Wang/Wow/blob/main/settings.gradle.kts#L23-L85)、[测试任务编排](https://github.com/Ahoo-Wang/Wow/blob/main/build.gradle.kts#L54-L142)、[本地测试工作流](https://github.com/Ahoo-Wang/Wow/blob/main/.github/workflows/local-test.yml#L14-L70) 与 [集成测试工作流](https://github.com/Ahoo-Wang/Wow/blob/main/.github/workflows/integration-test.yml#L14-L77) 是首要事实来源。

## 这些指南刻意不做的事

指南不会承诺代码与运行配置没有保障的延迟、吞吐量、可用性、数据保留或合规指标。

指南也不会把 KSP 描述成 HTTP 路由生成器。
在本仓库中，KSP 属于编译器与元数据流水线；运行期 WebFlux 路由与 OpenAPI 支持仍由显式模块和自动配置承担：[示例 KSP 配置](https://github.com/Ahoo-Wang/Wow/blob/main/example/example-domain/build.gradle.kts#L1-L8)、[元数据 Processor 输出](https://github.com/Ahoo-Wang/Wow/blob/main/wow-compiler/src/main/kotlin/me/ahoo/wow/compiler/metadata/MetadataSymbolProcessor.kt#L61-L104)、[WebFlux 处理器](https://github.com/Ahoo-Wang/Wow/blob/main/wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/command/CommandHandlerFunction.kt#L43-L66) 与 [Starter Feature Variants](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/build.gradle.kts#L5-L44)。

## 常用入口

- [仓库 README](https://github.com/Ahoo-Wang/Wow/blob/main/README.md#L41-L84)
- [模块声明](https://github.com/Ahoo-Wang/Wow/blob/main/settings.gradle.kts#L23-L85)
- [Cart 示例 API](https://github.com/Ahoo-Wang/Wow/blob/main/example/example-api/src/main/kotlin/me/ahoo/wow/example/api/cart/AddCartItem.kt#L1-L26)
- [Cart 聚合](https://github.com/Ahoo-Wang/Wow/blob/main/example/example-domain/src/main/kotlin/me/ahoo/wow/example/domain/cart/Cart.kt#L32-L76)
- [Cart 规格测试](https://github.com/Ahoo-Wang/Wow/blob/main/example/example-domain/src/test/kotlin/me/ahoo/wow/example/domain/cart/CartSpec.kt#L28-L87)
- [运行时测试指南](../guide/test-runtime.md)
