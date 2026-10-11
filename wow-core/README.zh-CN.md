# Wow Core

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](../LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/me.ahoo.wow/wow-core)](https://central.sonatype.com/artifact/me.ahoo.wow/wow-core)

`wow-core` 是 Wow 的响应式运行时核心，提供命令处理、事件溯源、消息分发、等待阶段、投影和 Saga 的公共接口与基础实现。

## 何时使用

- 实现领域运行时或非 Spring 集成，并直接使用 `CommandGateway`、`EventStore`、消息总线或 Dispatcher。
- 开发需要接入 Wow 运行时生命周期的扩展模块。

Spring Boot 服务通常依赖 `wow-spring-boot-starter`，而不是自行组装 `wow-core`。

## 依赖

Maven 坐标：`me.ahoo.wow:wow-core`。推荐用 Wow BOM 对齐版本：

```kotlin
dependencies {
    implementation(platform("me.ahoo.wow:wow-bom:<aligned-version>"))
    implementation("me.ahoo.wow:wow-core")
}
```

`wow-core` 通过 API 暴露 `wow-api` 及其响应式运行时合同。

## 公开边界

主要公开包包括：

- `me.ahoo.wow.command`：`CommandGateway`、命令消息构造和等待结果；
- `me.ahoo.wow.eventsourcing`：`EventStore`、快照与聚合恢复合同；
- `me.ahoo.wow.event`、`messaging`：领域事件与消息总线；
- `me.ahoo.wow.modeling`、`projection`、`saga`、`runtime`：领域执行与运行时生命周期。

具体 Kafka/Redis Bus、MongoDB/Redis/Elasticsearch 存储、WebFlux 路由和 Spring 自动配置由对应扩展模块负责。`wow-core` 不会替应用选择或部署这些基础设施。

## 最小示例

`CommandGateway` 返回 Reactor 类型；下面等待聚合处理达到 `PROCESSED`：

```kotlin
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.CommandResult
import reactor.core.publisher.Mono

fun send(
    commandGateway: CommandGateway,
    command: CommandMessage<AddCartItem>,
): Mono<CommandResult> = commandGateway.sendAndWaitForProcessed(command)
```

成功的 `PROCESSED` 不证明快照、投影、事件处理器或 Saga 已完成；需要这些事实时应选择相应等待计划。

## 验证

```bash
./gradlew :wow-core:check
```

## 继续阅读

- [架构概览](../documentation/docs/zh/guide/advanced/architecture.md)
- [命令网关](../documentation/docs/zh/guide/command-gateway.md)
- [事件存储](../documentation/docs/zh/guide/eventstore.md)
- [模块依赖](../documentation/docs/zh/guide/advanced/module-dependencies.md)
