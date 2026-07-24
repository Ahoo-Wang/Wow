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
