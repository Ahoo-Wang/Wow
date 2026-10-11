---
title: OpenTelemetry
description: 为 Wow 运行时添加 OpenTelemetry 链路追踪，不接管 SDK 或 exporter。
---

# OpenTelemetry

`wow-opentelemetry` 为命令/事件发送、聚合、EventStore、SnapshotStore、投影、Saga、事件处理和等待计划创建 tracing instrumenter。需要跨服务定位延迟与错误时使用；只需要 Micrometer 指标时无需引入它。

该模块只创建 span 和传播上下文，不初始化 OpenTelemetry SDK、Agent、sampler、exporter 或 Collector。`GlobalOpenTelemetry` 必须在 Wow instrumenter 创建前完成初始化。

## 安装

Spring Boot 应用应请求 starter capability，以包含自动配置：

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities {
        requireCapability("me.ahoo.wow:opentelemetry-support")
    }
}
```

不用 Spring 时，直接依赖模块：

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

模块在 classpath 上不会自动完成任何装配：要用 `Tracing.tracing()` decorator 自行装饰受支持的 bus、store 与命令网关，并注册 filters。

## OTLP 快速接入

最小运行路径是让 Java Agent 在应用启动前初始化全局实例：

```bash
export JAVA_TOOL_OPTIONS="-javaagent:/opt/otel/opentelemetry-javaagent.jar"
export OTEL_SERVICE_NAME=order-service
export OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318
java -jar app.jar
```

endpoint、protocol、headers、采样和资源属性属于 Agent/SDK。Wow module check 或本地 span 只证明 instrumentation 行为；Collector/backend receipt 才证明导出成功。

所有 instrumenter 都在单例对象初始化时捕获 `GlobalOpenTelemetry`。必须在 Wow ApplicationContext 创建
filter、instrumentation 或 decorator 前初始化 SDK；OpenTelemetry Java Agent 在应用引导前启动，天然满足此顺序。

## 配置

Wow 只有一个开关，默认开启：

```yaml
wow:
  opentelemetry:
    enabled: true
```

设为 `false` 只停止 Wow tracing filters/decorators，不停止 Java Agent 或其他库的 span。不要重复配置两套 exporter；指标仍由 Micrometer Registry 负责，详见[可观测性配置](../../reference/config/observability.md)。

## Wow 埋点了什么

Spring starter 注册一个命令 instrumentation（`TraceCommandInstrumentation`，自 9.3.0 起；此前是命令过滤器 `TraceAggregateFilter`）和四个事件侧处理过滤器，并装饰受支持的基础设施 Bean：

| 运行阶段 | instrumentation scope | 代表性 span 名称 |
|---|---|---|
| 命令发布 | `me.ahoo.wow-commandProducer` | `<aggregate>.<command>.command send` |
| 聚合执行 | `me.ahoo.wow-aggregate` | `<aggregate>.<command>` |
| 事件持久化 | `me.ahoo.wow-eventStore` | `<aggregate>.<event>.event.append`、`<aggregate>.event.load` |
| 领域/状态发布 | `me.ahoo.wow-eventProducer`、`me.ahoo.wow-stateEventProducer` | `<aggregate>.<event>.event send`、`<aggregate>.<event>.state_event send` |
| 事件处理 | `me.ahoo.wow-eventProcessor`、`-projection`、`-statelessSaga` | 事件函数限定名 |
| 快照处理/存储 | `me.ahoo.wow-snapshot`、`-snapshotStore` | `<aggregate>.snapshot`、`.snapshot.save`、`.snapshot.load`、`.snapshot.version` |
| 命令等待计划 | `me.ahoo.wow-wait` | `<aggregate>.<command>.waiting` |

注册了 `MessageAttributesExtractor` 或 `ExchangeAttributesExtractor` 的 instrumenter 会增加
`wow.message.id`、可选的 `wow.message.request_id` 与 `wow.message.trace_id`；存在聚合身份时，还会增加
`wow.aggregate.context_name`、`wow.aggregate.name`、`wow.aggregate.id` 和 `wow.aggregate.tenant_id`。
使用 `AggregateIdAttributesExtractor` 的 store operation 只增加聚合属性。`WaitPlanInstrumenter` 没有注册
attribute extractor，因此 `.waiting` span 不会自动获得这些 Wow message 或 aggregate attribute。这些是
trace attribute，不是低基数指标标签。

## 链路追踪如何装配

`WowOpenTelemetryAutoConfiguration` 注册 aggregate、projection、snapshot、stateless Saga、event processor 五类 filter。`TracingBeanPostProcessor` 以最高优先级装饰受支持的 local/distributed buses、EventStore、SnapshotStore 和 CommandGateway，并通过 `Traced` 防止重复包装。

producer instrumenter 把 OpenTelemetry 传播头注入 Wow message header，consumer filter 与命令 instrumentation 再提取它们。
`TraceMono` 与 `TraceFlux` 会在订阅和异步 signal 上恢复 OpenTelemetry `Context`。wait decorator 保留 CommandGateway 的 runtime receiver/admission 契约。

Reactor publisher 在订阅时创建 span，在完成、错误、取消或同步 subscribe 异常时结束。属性只出现在注册了对应 extractor 的 instrumenter 上，不能假设所有 span 都有全部 `wow.message.*`/`wow.aggregate.*` 字段。

已验证失败边界：全局 SDK 初始化过晚会让已创建 instrumenter 使用旧全局实例；底层 publisher 错误会记录到 span 并继续传播；关闭开关后只移除 Wow 埋点，不改变业务调用。聚焦检查：

```bash
./gradlew :wow-opentelemetry:check
```

## 添加你自己的 Span

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

    Mono.defer {
        orderSummaryRepository.save(buildSummary(event))
    }.doOnError(span::recordException)
        .doFinally { span.end() }
        .then()
}
```

内层 `Mono.defer` 是有意的：`buildSummary` 或 `save` 同步抛出的异常会先转换成 error signal，再经过
`doOnError` 与 `doFinally`，因此完成、错误或取消都只结束一次 span。

如果自定义 operator 逃离已注入的 subscriber chain，应显式传播 OpenTelemetry `Context`，并用集成测试
覆盖该边界。模块测试已经验证 `publishOn`、嵌套 traced publisher、取消与 source error 的上下文恢复。

## 链路追踪示例

一条命令通常形成 command send/wait → aggregate handler → event append/send → projection/Saga/snapshot 的父子链。实际 span 是否出现取决于该路径是否运行、采样是否保留以及 exporter 是否成功。

然后阅读[可观测性](../advanced/observability.md)，了解排查事故时怎样把 trace 与指标结合使用，再用目标 Collector 保存真实 trace receipt。

<!-- Sources: wow-opentelemetry/src/main/kotlin/me/ahoo/wow/opentelemetry/, its TracePublisherTest and
TracingCommandGatewayWaitTest, and wow-spring-boot-starter/.../opentelemetry/ -->
