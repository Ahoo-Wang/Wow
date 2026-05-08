---
title: CoSec
description: CoSec 安全框架集成，处理命令和查询端点的安全上下文注入与传播。
---

# CoSec

CoSec 扩展将 [CoSec](https://github.com/Ahoo-Wang/CoSec) 安全框架与 Wow 的 WebFlux 命令和查询端点集成，处理安全上下文的注入与传播。

## 工作原理

CoSec 集成提供三个核心组件：

1. **CommandRequestHeaderAppender** — 从 HTTP 请求头中提取 `CoSec-App-Id` 和 `CoSec-Device-Id`，附加到命令 Header 中
2. **CommandBuilderExtractor** — 从 HTTP 请求头中提取 `CoSec-Request-Id` 和 `CoSec-Space-Id`，注入到 CommandBuilder 中
3. **MessagePropagator** — 在处理链中将 `app_id` 和 `device_id` 从上游消息 Header 向下游传播

## 安装

添加 `wow-cosec` 依赖，并在 Spring Boot Starter 中启用 `cosec-support` 能力：

```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities { requireCapability("cosec-support") }
}
```

## 自动配置

当 `wow-cosec` 和 CoSec 同时在 classpath 上时，`CoSecAutoConfiguration` 会自动注册安全集成 Bean，无需额外配置。
