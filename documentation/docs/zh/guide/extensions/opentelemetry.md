---
title: OpenTelemetry
description: 为 Wow 运行时添加 OpenTelemetry 链路追踪，不接管 SDK 或 exporter。
---

# OpenTelemetry

`wow-opentelemetry` 为命令/事件发送、聚合、EventStore、SnapshotStore、投影、Saga、事件处理和等待计划创建 tracing instrumenter。需要跨服务定位延迟与错误时使用；只需要 Micrometer 指标时无需引入它。

该模块只创建 span 和传播上下文，不初始化 OpenTelemetry SDK、Agent、sampler、exporter 或 Collector。`GlobalOpenTelemetry` 必须在 Wow instrumenter 创建前完成初始化。

## 安装

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities { requireCapability("me.ahoo.wow:opentelemetry-support") }
}
```

非 Spring 应用可直接依赖 `me.ahoo.wow:wow-opentelemetry`，但必须自行装饰 bus/store/gateway 并注册 filters。classpath 只有模块并不会自动完成这些工作。

## OTLP 快速接入

最小运行路径是让 Java Agent 在应用启动前初始化全局实例：

```bash
export JAVA_TOOL_OPTIONS="-javaagent:/opt/otel/opentelemetry-javaagent.jar"
export OTEL_SERVICE_NAME=order-service
export OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318
java -jar app.jar
```

endpoint、protocol、headers、采样和资源属性属于 Agent/SDK。Wow module check 或本地 span 只证明 instrumentation 行为；Collector/backend receipt 才证明导出成功。

## 配置

Wow 只有一个开关，默认开启：

```yaml
wow:
  opentelemetry:
    enabled: true
```

设为 `false` 只停止 Wow tracing filters/decorators，不停止 Java Agent 或其他库的 span。不要重复配置两套 exporter；指标仍由 Micrometer Registry 负责，详见[可观测性配置](../../reference/config/observability.md)。

## 链路追踪如何装配

`WowOpenTelemetryAutoConfiguration` 注册 aggregate、projection、snapshot、stateless Saga、event processor 五类 filter。`TracingBeanPostProcessor` 以最高优先级装饰受支持的 local/distributed buses、EventStore、SnapshotStore 和 CommandGateway，并通过 `Traced` 防止重复包装。

Reactor publisher 在订阅时创建 span，在完成、错误、取消或同步 subscribe 异常时结束。消息 producer 注入上下文，consumer/exchange 从消息恢复；属性只出现在注册了对应 extractor 的 instrumenter 上，不能假设所有 span 都有全部 `wow.message.*`/`wow.aggregate.*` 字段。

已验证失败边界：全局 SDK 初始化过晚会让已创建 instrumenter 使用旧全局实例；底层 publisher 错误会记录到 span 并继续传播；关闭开关后只移除 Wow 埋点，不改变业务调用。聚焦检查：

```bash
./gradlew :wow-opentelemetry:check
```

## 链路追踪示例

一条命令通常形成 command send/wait → aggregate handler → event append/send → projection/Saga/snapshot 的父子链。实际 span 是否出现取决于该路径是否运行、采样是否保留以及 exporter 是否成功。

下一步阅读[可观测性](../advanced/observability.md)了解 span/属性合同，再用目标 Collector 保存真实 trace receipt。
