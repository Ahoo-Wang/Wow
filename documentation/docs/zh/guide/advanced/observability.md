---
title: 可观测性
description: Wow 命令、事件、存储与恢复管线的追踪和指标证据。
---

# 可观测性

![可观测性](/images/observability/observability.png)

Wow 从两个角度观察同一条运行时管线：

- [指标](./metrics) 使用低基数 Micrometer 标签聚合有限操作、receiver 流与批处理活动；
- `wow-opentelemetry` 创建 OpenTelemetry span，并跨命令、事件、状态、持久化和等待边界传播消息追踪
  上下文。

先用指标定位慢或失败的阶段，再用 trace 跟踪单次执行。两者都不能替代 backend 对账、投影延迟检查或
部署证据。

## OpenTelemetry 模块注入的内容

Spring starter 注册五个处理过滤器，并装饰受支持的基础设施 Bean：

| 运行阶段 | instrumentation scope | 代表性 span 名称 |
|---|---|---|
| 命令发布 | `me.ahoo.wow-commandProducer` | `<aggregate>.<command>.command send` |
| 聚合执行 | `me.ahoo.wow-aggregate` | `<aggregate>.<command>` |
| 事件持久化 | `me.ahoo.wow-eventStore` | `<aggregate>.<event>.event.append`、`<aggregate>.event.load` |
| 领域/状态发布 | `me.ahoo.wow-eventProducer`、`me.ahoo.wow-stateEventProducer` | `<aggregate>.<event>.event send`、`<aggregate>.<event>.state_event send` |
| 事件处理 | `me.ahoo.wow-eventProcessor`、`-projection`、`-statelessSaga` | 事件函数限定名 |
| 快照处理/存储 | `me.ahoo.wow-snapshot`、`-snapshotStore` | `<aggregate>.snapshot`、`.snapshot.save`、`.snapshot.load`、`.snapshot.version` |
| 命令等待计划 | `me.ahoo.wow-wait` | `<aggregate>.<command>.waiting` |

消息 span 会增加 `wow.message.id`、可选的 `wow.message.request_id` 与 `wow.message.trace_id`；存在聚合
身份时，还会增加 `wow.aggregate.context_name`、`wow.aggregate.name`、`wow.aggregate.id` 和
`wow.aggregate.tenant_id`。这些是 trace attribute，不是低基数指标标签。

producer instrumenter 把 OpenTelemetry 传播头注入 Wow message header，consumer filter 再提取它们。
`TraceMono` 与 `TraceFlux` 会在订阅和异步 signal 上恢复 OpenTelemetry `Context`，并在完成、错误或取消
时结束 span。wait decorator 保留 CommandGateway 的 runtime receiver/admission 契约。

所有 instrumenter 都在单例对象初始化时捕获 `GlobalOpenTelemetry`。必须在 Wow ApplicationContext 创建
filter 或 decorator 前初始化 SDK；OpenTelemetry Java Agent 在应用引导前启动，天然满足此顺序。

## 关联你自己的 Span

只为有意义的远程调用或昂贵领域工作创建业务 span。Aggregate ID 不应成为指标标签，但适合作为 trace
attribute。

### 在命令处理器中

Wow 调用嵌套工作时会把自身 span 设为 current；显式使用 `Context.current()` 作为 parent，并限定业务
调用的 scope：

```kotlin
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.context.Context

private val tracer = GlobalOpenTelemetry.getTracer("order-domain")

@OnCommand
fun handle(command: CreateOrder): OrderCreated {
    val span = tracer.spanBuilder("inventory.validate")
        .setParent(Context.current())
        .setAttribute("order.item_count", command.items.size.toLong())
        .startSpan()
    return try {
        span.makeCurrent().use {
            validateItems(command.items)
            OrderCreated(command.id)
        }
    } catch (error: Throwable) {
        span.recordException(error)
        throw error
    } finally {
        span.end()
    }
}
```

这是同步示例。不要让 scope 跨越异步边界或不同线程保持打开。

### 在响应式处理器中（Mono/Flux）

在订阅时创建 span，并用完整 Reactor 生命周期结束它：

```kotlin
@OnEvent
fun onOrderCreated(event: OrderCreated): Mono<Void> = Mono.defer {
    val span = tracer.spanBuilder("order_summary.save")
        .setParent(Context.current())
        .setAttribute("order.id", event.orderId)
        .startSpan()

    orderSummaryRepository.save(buildSummary(event))
        .doOnError(span::recordException)
        .doFinally { span.end() }
        .then()
}
```

如果自定义 operator 逃离已注入的 subscriber chain，应显式传播 OpenTelemetry `Context`，并用集成测试
覆盖该边界。模块测试已经验证 `publishOn`、嵌套 traced publisher、取消与 source error 的上下文恢复。

一次事故可以按以下证据链排查：

1. 用 `component`、`operation`、`context`、`aggregate`、`outcome` 选择指标阶段和时间窗口；
2. 按 service、operation、aggregate、request ID 或 message ID 查找对应 trace；
3. 确认 span 到达预期存储和下游 processor；
4. 另行对账最终 backend version/read model 与部署 revision。

## 安装

Spring Boot 应用应请求 starter capability，以包含自动配置：

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities {
        requireCapability("me.ahoo.wow:opentelemetry-support")
    }
}
```

非 Spring 组合可直接依赖模块，并自行应用 `Tracing.tracing()` decorator：

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-opentelemetry")
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-opentelemetry</artifactId>
    <version>${wow.version}</version>
</dependency>
```
:::

Exporter 配置与 `wow.opentelemetry.enabled` 开关参见
[可观测性配置](/zh/reference/config/observability)。

<!-- Sources: wow-opentelemetry/src/main/kotlin/me/ahoo/wow/opentelemetry/, its TracePublisherTest and
TracingCommandGatewayWaitTest, and wow-spring-boot-starter/.../opentelemetry/ -->
