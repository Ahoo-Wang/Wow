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

支持以下属性标签：

- `wow.aggregate.context_name`: 聚合根的上下文名称。
- `wow.aggregate.tenant_id`: 聚合根的租户ID。
- `wow.aggregate.name`: 聚合根的名称。
- `wow.aggregate.id`: 聚合根的ID。
- `wow.message.id`: 消息ID
- `wow.message.request_id`: 命令消息的请求ID。

![可观测性](/images/observability/observability.png)

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