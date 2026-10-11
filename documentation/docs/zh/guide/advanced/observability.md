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

## 各信号的文档在哪里

| 信号 | 页面 |
| --- | --- |
| 指标：名称、标签、存储批处理与查询指标、导出 | [指标](./metrics) |
| Trace：安装、埋点的阶段、span 名称与属性、自定义 span | [OpenTelemetry](../extensions/opentelemetry) |
| 开关与 exporter 装配 | [可观测性配置](/zh/reference/config/observability) |

## 排查一次事故

一次事故可以按以下证据链排查：

1. 用 `component`、`operation`、`context`、`aggregate`、`outcome` 选择指标阶段和时间窗口；
2. 按 service、operation、aggregate、request ID 或 message ID 查找对应 trace；
3. 确认 span 到达预期存储和下游 processor；
4. 另行对账最终 backend version/read model 与部署 revision。

<!-- Sources: wow-opentelemetry/src/main/kotlin/me/ahoo/wow/opentelemetry/, its TracePublisherTest and
TracingCommandGatewayWaitTest, and wow-spring-boot-starter/.../opentelemetry/ -->
