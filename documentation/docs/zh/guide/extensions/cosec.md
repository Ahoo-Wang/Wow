---
title: CoSec
description: 在 Wow WebFlux 命令与查询中提取并传播 CoSec 上下文。
---

# CoSec

`wow-cosec` 把 `CoSec-*` 请求头映射到 Wow 命令 header/builder 和查询 space filter，并把 app/device 上下文传播到下游消息。只有应用已经采用这些 CoSec header 约定时使用。

::: danger 安全边界
该模块不认证请求、不验证 header 真伪、不自动授权命令，也不把 tenant/owner/space 与服务端主体绑定。认证、路由授权和 fail-closed 数据权限仍由应用安全链负责。
:::

## 工作原理

四个行为构成完整集成：`CoSecCommandRequestHeaderAppender` 提取 app/device，`CoSecCommandBuilderExtractor` 补充 request/space，service-loaded `CoSecMessagePropagator` 传播 app/device，`CoSecRewriteRequestFilter` 为查询解析 space。Wow 只拥有上下文搬运；安全框架拥有可信身份与策略决定。

## 安装

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities { requireCapability("me.ahoo.wow:cosec-support") }
}
```

`cosec-support` 带入 `wow-cosec`，后者依赖 `wow-webflux` 实现类；HTTP 路由仍需要 WebFlux 自动配置实际启用。没有 `wow.cosec.*` 配置树。

## 自动配置

`CoSecAutoConfiguration` 只要求 Wow 启用且 `CoSecCommandRequestHeaderAppender` 在 classpath，然后注册三个 WebFlux 扩展 Bean。它没有 `enabled` 开关，也没有 `@ConditionalOnMissingBean`；若不需要该行为，移除 capability 或显式排除该自动配置，而不是信任空配置。

## 使用方式

最小运行配置是 capability 加上应用自己的认证/授权链；CoSec 集成本身无需 YAML。发送 header 前先确保网关删除不可信的外部身份 header，并由服务端重新建立可信上下文。

### 发送 CoSec 头

| Header | 目标 |
|---|---|
| `CoSec-App-Id` | 命令 header `app_id` |
| `CoSec-Device-Id` | 命令 header `device_id` |
| `CoSec-Request-Id` | `CommandBuilder.requestIdIfAbsent` |
| `CoSec-Space-Id` | `CommandBuilder.spaceIdIfAbsent` 与查询 space fallback |

标准 Wow request/space 值已存在时，`IfAbsent`/rewrite 优先保留 Wow 值；CoSec header 只补充。缺失 header 不产生上下文，也不会自行失败。

### 上下文如何流转

app/device 随 Wow message header 传播到下游命令/事件；request ID 与 space ID 进入命令 identity/scope。查询 filter 先读取 Wow space，再回退 `CoSec-Space-Id`。传播值仅可用于审计或已验证策略，不能因“来自 header”就视为授权事实。

已验证失败/边界：缺失 header 得到空上下文；已有 request/space 不被 CoSec 覆盖；查询 space 只形成 `SpaceIdFilter`，不执行主体授权；伪造 header 会被忠实传播，因此安全链缺失是部署失败而非模块可修复的输入校验问题。

## 完成门禁

- 匿名、伪造 header、跨 tenant/owner/space 请求被服务端策略拒绝；
- 查询缺少授权标签时 fail closed，不退化为 match-all；
- 下游处理器区分传播上下文与可信主体；
- 候选环境覆盖正常、匿名、越权与跨作用域测试；
- 聚焦模块检查通过：

```bash
./gradlew :wow-cosec:check
```

下一步阅读[数据权限](../data-access.md)完成认证、授权、过滤与审计闭环。
