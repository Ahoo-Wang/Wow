---
title: 可观测性配置
description: OpenAPI 规范生成、OpenTelemetry 追踪和指标导出的配置选项。
---

# 可观测性配置

## OpenAPI

| 属性 | 类型 | 默认值 | 描述 |
|----------|------|---------|-------------|
| `wow.openapi.enabled` | Boolean | `true` | 启用 OpenAPI 规范生成 |

```yaml
wow:
  openapi:
    enabled: true
```

启用后，Wow 会在**运行时**根据边界上下文中注册的命令和事件模型构建 OpenAPI 规范
（由 `OpenAPIAutoConfiguration` 构建的 `RouterSpecs` Bean）。`wow-compiler` 模块在编译期
贡献命令路由元数据，但规范本身——包括路由、Schema 和内置的 Swagger UI——是在应用上下文启动时组装的。

## OpenTelemetry

| 属性 | 类型 | 默认值 | 描述 |
|----------|------|---------|-------------|
| `wow.opentelemetry.enabled` | Boolean | `true` | 启用命令/事件管线的 OpenTelemetry 追踪埋点 |

```yaml
wow:
  opentelemetry:
    enabled: true
```

当 `wow-opentelemetry` 模块和 `WowInstrumenter` 类位于类路径上时，默认启用
（`matchIfMissing = true`）。设为 `false` 可禁用横跨命令总线、事件存储、投影和 Saga 的分布式追踪链路。

## 指标

| 属性 | 类型 | 默认值 | 描述 |
|----------|------|---------|-------------|
| `wow.metrics.enabled` | Boolean | `true` | 启用 Micrometer/Prometheus 指标采集 |

```yaml
wow:
  metrics:
    enabled: true
```

默认启用（`matchIfMissing = true`）。控制框架指标的导出（命令处理延迟、事件存储追加计数、
投影延迟等）。禁用可抑制 Wow 特有的指标。

## 商业智能脚本

`wow.bi.script.*` 配置树（ClickHouse/BI 脚本部署）请参阅
[BI 部署与恢复](/zh/guide/bi-operations) 页面。

## 集成设置

### 启用指标导出（Prometheus）

Wow 指标写入 Micrometer 的全局注册表。要通过 Prometheus 暴露指标，添加 Spring Boot Actuator + Prometheus 注册表依赖并暴露端点：

```yaml
management:
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
  endpoints:
    web:
      exposure:
        include:
          - health
          - prometheus    # Micrometer/Prometheus 抓取端点
          - threaddump
  metrics:
    tags:
      application: ${spring.application.name}   # 所有 meter 的公共标签

springdoc:
  show-actuator: true   # 在 OpenAPI 中包含 actuator 端点
```

```kotlin
implementation("org.springframework.boot:spring-boot-starter-actuator")
implementation("io.micrometer:micrometer-registry-prometheus")
```

从 Prometheus 抓取 `/actuator/prometheus` 端点。Wow 特有的 meter
（`wow.command.*`、`wow.eventstore.*`、`wow.snapshot.*`、`wow.projection.*` 等）将与标准
JVM/Reactor meter 一起出现。完整目录参见 [Metrics](/zh/guide/advanced/metrics)。

### 启用分布式链路追踪（OpenTelemetry）

启用链路追踪的推荐方式是使用 OpenTelemetry Java Agent，它会在 Spring 上下文启动前引导初始化
`GlobalOpenTelemetry`：

```bash
java -javaagent:opentelemetry-javaagent.jar \
     -Dotel.service.name=${spring.application.name} \
     -Dotel.exporter.otlp.endpoint=http://otel-collector:4317 \
     -jar your-app.jar
```

在依赖中添加 `wow-opentelemetry`。自动配置会检测 Agent 已初始化的 `GlobalOpenTelemetry`，
并自动注册 Wow 追踪过滤器与装饰器。仅当需要禁用 Wow 的 span 但保留 Agent 的其他仪表时，
才设置 `wow.opentelemetry.enabled=false`。

```kotlin
implementation("me.ahoo.wow:wow-opentelemetry")
```

仪表覆盖范围参见 [可观测性](/zh/guide/advanced/observability)，
仪表器列表参见 [OpenTelemetry 扩展](/zh/guide/extensions/opentelemetry)。
