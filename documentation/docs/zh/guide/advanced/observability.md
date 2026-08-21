---
title: 可观测性
description: Wow 框架的端到端可观测性集成。
---

# 可观测性

![可观测性](/images/observability/observability.png)

Wow 通过两项互补的集成提供端到端可观测性：

- **指标（Metrics）**（Micrometer/Reactor）—— 命令、事件、事件存储、快照、投影、Saga 与分发器操作的计数器与计时器。完整指标目录及 `wow.metrics.enabled` 开关参见 [Metrics](./metrics)。
- **分布式链路追踪（Tracing）**（OpenTelemetry）—— `wow-opentelemetry` 为每个横切组件注入 OpenTelemetry span。仪表器列表与属性标签参见 [OpenTelemetry](../extensions/opentelemetry)。

## OpenTelemetry 模块注入的内容

当 `wow-opentelemetry` 位于类路径时，`WowOpenTelemetryAutoConfiguration` 会为以下组件注册追踪装饰器：

| 类别 | 被注入的组件 |
|---|---|
| **命令路径** | `CommandGateway` 等待计划、`CommandBus` 生产者 |
| **事件路径** | `DomainEventBus` / `StateEventBus` 生产者、事件处理器、投影、无状态 Saga |
| **持久化** | `EventStore`、`SnapshotStore`、快照仓储 |
| **聚合** | 聚合处理过滤器链 |

必须在 Wow 应用上下文创建追踪过滤器与装饰器**之前**初始化 `GlobalOpenTelemetry`（通过 OpenTelemetry Java Agent 或在引导阶段注册的 SDK）。在 Wow 追踪仪表器初始化之后再注册 SDK 为时已晚。

## 关联你自己的 Span

Wow 通过 Reactor 管道传播 OpenTelemetry `Context`（存储在 Reactor 上下文中）。你在命令处理器、Saga 或投影中创建的任何子 span 都会自动链接到 Wow 命令的 trace —— 无需手动传递上下文。

### 在命令处理器中

```kotlin
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer

@AggregateRoot
class Order(private val state: OrderState) {
    private val tracer: Tracer = GlobalOpenTelemetry.getTracer("order-domain")

    @OnCommand
    fun onCommand(command: CreateOrder, exchange: ServerCommandExchange<*>): OrderCreated {
        val span = tracer.spanBuilder("validate-inventory")
            .setAttribute("order.item_count", command.items.size)
            .startSpan()
        try {
            // 你的业务逻辑 —— 此 span 会作为 Wow 生成的
            // "order.create_order" 聚合 span 的子 span 出现
            validateItems(command.items)
            return OrderCreated(...)
        } finally {
            span.end()
        }
    }
}
```

### 在响应式处理器中（Mono/Flux）

对于响应式处理器，OTel 上下文携带在 Reactor 上下文中。使用感知 `ContextView` 的 span 创建方式，
以便在异步边界之间保留父 trace：

```kotlin
@ProjectionProcessor
class OrderProjection {
    private val tracer = GlobalOpenTelemetry.getTracer("order-projection")

    @OnEvent
    fun onOrderCreated(event: OrderCreated): Mono<Void> {
        return Mono.deferContextual { ctx ->
            // Wow 的 TraceFilter 已将 OTel 上下文存储在 Reactor 上下文中。
            // 在此处创建 span 时 GlobalOpenTelemetry 会自动获取它。
            val span = tracer.spanBuilder("project-order-summary")
                .setAttribute("order.id", event.orderId)
                .startSpan()
            orderSummaryRepository
                .save(buildSummary(event))
                .doFinally { span.end() }
                .then()
        }
    }
}
```

在 Jaeger/Zipkin/Tempo 中查看生成的 trace，你会看到业务 span 嵌套在 Wow 框架 span 内部，
从而从 HTTP 请求到领域逻辑再到读模型更新获得端到端可观测性。

## 安装

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-opentelemetry")
```
```groovy [Gradle(Groovy)]
implementation 'me.ahoo.wow:wow-opentelemetry'
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-opentelemetry</artifactId>
    <version>${wow.version}</version>
</dependency>
```
:::
