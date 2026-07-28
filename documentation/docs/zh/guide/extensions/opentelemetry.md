---
title: OpenTelemetry
description: OpenTelemetry 集成，提供厂商中立的分布式追踪和监控能力。
---

# OpenTelemetry

OpenTelemetry 是一个厂商中立的开源项目，旨在为跟踪和监控分布式应用程序提供标准 API、工具和库。
由 Cloud Native Computing Foundation（CNCF）和 OpenTelemetry 社区支持。

其主要目标是为开发人员提供一致的跟踪解决方案，帮助他们收集、生成和导出分布式系统的跟踪数据，以更好地理解应用程序的性能、行为和异常。
OpenTelemetry 支持多种编程语言和框架，如 Java、Python、Go、Node.js，使得开发人员可以轻松集成跟踪功能。

OpenTelemetry 提供以下核心功能：
- 分布式追踪：捕获请求在不同服务和组件之间的传递，形成调用链，以追踪整个分布式请求的路径和执行时间。
- 指标收集：收集和导出性能指标，如请求速率、响应时间、错误率等，助力开发人员监控和优化性能。
- 日志记录：收集应用程序的日志数据，与跟踪和指标数据相关联，提供深入了解应用程序行为和问题的视角。

Wow 框架的 _OpenTelemetry_ 模块通过提供一系列仪表器（_Instrumenter_）来记录框架的核心组件的操作，以帮助开发人员更好地理解应用程序的性能、行为和异常。

- `AggregateInstrumenter`: 聚合根仪表器，用于记录聚合根的操作。
- `EventProcessorInstrumenter`: 事件处理器仪表器，用于记录事件处理器的操作。
- `EventStoreInstrumenter`: 事件存储仪表器，用于记录事件存储的操作。
- `CommandProducerInstrumenter`: 命令生产者仪表器，用于记录命令生产者的操作。
- `EventProducerInstrumenter`: 事件生产者仪表器，用于记录事件生产者的操作。
- `StateEventProducerInstrumenter`: 状态事件生产者仪表器，用于记录状态事件生产者的操作。
- `ProjectionInstrumenter`: 投影仪表器，用于记录投影的操作。
- `StatelessSagaInstrumenter`: 无状态Saga仪表器，用于记录无状态Saga的操作。
- `SnapshotInstrumenter`: 快照仪表器，用于记录快照的操作。
- `SnapshotRepositoryInstrumenter`: 快照仓储仪表器，用于记录快照仓储的操作。
- `SnapshotStoreInstrumenter`: 快照存储仪表器，用于记录快照存储的操作。
- `WaitPlanInstrumenter`: 命令等待计划仪表器，用于记录命令等待/通知的操作。

支持以下属性标签：

- `wow.aggregate.context_name`: 聚合根的上下文名称。
- `wow.aggregate.tenant_id`: 聚合根的租户ID。
- `wow.aggregate.name`: 聚合根的名称。
- `wow.aggregate.id`: 聚合根的ID。
- `wow.message.id`: 消息ID
- `wow.message.request_id`: 命令消息的请求ID。
- `wow.message.trace_id`: 通过消息头传播的 Trace ID。

![可观测性](../../../public/images/observability/observability.png)

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

## 配置

当 `wow-opentelemetry` 位于 classpath 时，Wow 默认开启链路追踪自动配置。无需移除依赖即可关闭：

```yaml
wow:
  opentelemetry:
    enabled: false
```

请在 Wow 应用上下文创建 tracing filters 和 decorators 前初始化 `GlobalOpenTelemetry`。可以使用 OpenTelemetry Java Agent，或在应用启动阶段注册 SDK；若在 Wow tracing instrumenters 初始化后才注册 SDK，则时机过晚。

## 链路追踪如何装配

`WowOpenTelemetryAutoConfiguration` 注册了五个 `ExchangeFilter` Bean（均为 `@ConditionalOnMissingBean`，可逐一覆盖），
以及一个 `TracingBeanPostProcessor`，用于装饰命令/事件/状态事件总线、事件存储和快照存储：

| Bean | 过滤阶段 | Span 名称模式 |
|---|---|---|
| `TraceAggregateFilter` | 聚合命令处理 | `{aggregateName}.{commandName}` |
| `TraceProjectionFilter` | 投影事件处理 | `{aggregateName}.projection.{eventName}` |
| `TraceStatelessSagaFilter` | Saga 事件处理 | `{aggregateName}.saga.{eventName}` |
| `TraceSnapshotFilter` | 快照创建 | `{aggregateName}.snapshot` |
| `TraceEventProcessorFilter` | 通用事件处理器 | `{aggregateName}.event-processor.{eventName}` |

每个 span 都携带上文列出的 `wow.aggregate.*` 与 `wow.message.*` 属性，以及 OpenTelemetry 传播上下文，
因此一条命令的完整路径（命令总线 → 聚合 → 事件存储 → 投影 → Saga）会呈现为一条分布式链路。

## 链路追踪示例

对于 `order` 聚合上的 `CreateOrder` 命令（触发一个 Saga 和一个投影），链路层级如下：

```text
order.create_order              (TraceAggregateFilter —— 聚合命令)
├── wow-mongo append            (TracingEventStore —— 事件持久化)
├── order.event-store.send      (TracingCommandBus/EventBus —— 消息发布)
├── order.snapshot.save         (TraceSnapshotFilter —— 快照创建)
├── order.saga.OrderCreated     (TraceStatelessSagaFilter —— Saga onOrderCreated)
└── order.projection.OrderCreated (TraceProjectionFilter —— 投影 onOrderCreated)
```

确切的 span 名称取决于仪表器的 `SpanNameExtractor`；关键在于一条命令的所有 span 通过上下文传播共享同一个 trace ID，
从而让你从 HTTP 请求到读模型更新获得端到端可观测性。
