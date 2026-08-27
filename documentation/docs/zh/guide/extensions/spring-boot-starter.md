---
title: Spring Boot Starter
description: 通过 Spring Boot 条件装配 Wow 核心、运行时生命周期与可选基础设施能力。
---

# Spring-Boot-Starter

`wow-spring-boot-starter` 是 Spring Boot 应用的装配入口：绑定 `wow.*` 属性、注册核心 Bean、收集 `RuntimeComponent`，并让唯一 `WowRuntimeLifecycle` 管理启动与关闭。它不自动决定应用应使用哪个 Broker、存储、安全或 exporter。

## 安装

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter")
```

基础依赖提供 core/spring/schema/compensation 及 Spring WebFlux/Jackson 基线；Kafka、Mongo、Redis、Elasticsearch、OpenTelemetry、OpenAPI、CoSec 等仍是可选 capability。仅引入 starter 而保留默认 `kafka`/`mongo` 选择，不能证明相应实现已在 classpath。

## 自动配置原理

Spring Boot 从 `AutoConfiguration.imports` 加载 Wow 配置，再由 `@ConditionalOnClass`、`@ConditionalOnProperty`、storage/bus 选择和 `@ConditionalOnMissingBean` 决定 Bean。启动成功后的实际 Bean/route/runtime component 才是装配证据。

### Gradle 特性能力（Feature Variants）

| capability | 直接引入的主要能力 |
|---|---|
| `mongo-support` | `wow-mongo` + reactive Mongo starter |
| `redis-support` | `wow-redis` + reactive Redis starter |
| `mock-support` | `wow-mock` |
| `kafka-support` | `wow-kafka` |
| `webflux-support` | `wow-bi` API + `wow-webflux` |
| `elasticsearch-support` | `wow-elasticsearch` + Spring Data Elasticsearch |
| `opentelemetry-support` | `wow-opentelemetry` |
| `openapi-support` | `wow-bi` API + `wow-openapi` + springdoc common |
| `cosec-support` | `wow-cosec` |

请求完整坐标，例如 `requireCapability("me.ahoo.wow:mongo-support")`。Maven 不解析 Gradle feature variants，需要显式依赖相应模块。

## 自动配置类

核心配置按序列化、命令、事件、事件溯源、查询、投影、Saga、指标、runtime lifecycle 等拆分；扩展配置再按 classpath 与选择属性加入。不要从类名推断默认启用，检查其条件和 Bean 方法。

## 配置属性完整列表

完整 key/default 以[配置参考](../../reference/config/core.md)和各扩展页为准。本页只给最小启动边界，避免复制会漂移的属性表。

### 核心配置 (wow.*)

`wow.enabled=true`、`wow.shutdown-timeout=60s`、`wow.shutdown-quiet-period=1s`。`wow.context-name` 未设置时必须存在 `spring.application.name`；两者都缺失会在创建 current bounded context 时启动失败。

### 命令配置 (wow.command.*)

bus 默认 `kafka`、local-first 默认开启、idempotency 默认开启。若未请求 Kafka capability，应显式选择 `in_memory`、`redis` 或应用提供的实现。

### 事件配置 (wow.event.*)

领域事件 bus 默认 `kafka`，local-first 默认开启。其选择与 state-event bus 独立，不要只改一个 key。

### 事件溯源配置 (wow.eventsourcing.*)

EventStore 与 SnapshotStore 默认选择 `mongo`，state bus 默认 `kafka`。storage routing 通过具名 bindings 建立 primary router；普通同类型 Bean 不会自动替换已启用的 storage capability。

## Bean 装配与覆盖

只在源码标注 `@ConditionalOnMissingBean` 的扩展点提供替代 Bean。`WowRuntime` 必须是当前 context 中名为 `wowRuntime` 的唯一 singleton，并由唯一 `WowRuntimeLifecycle` 独占；重复 Spring `Lifecycle`、`DisposableBean`、destroy method 或 `AutoCloseable.close` owner 会 fail fast，避免双重关闭。

Starter 保持 backend-native 语义：Kafka offset、Mongo unique index、Redis Lua、Elasticsearch mapping 等由对应 adapter/backend 拥有，不在核心自动配置里复制校验。

## 多模块项目配置

API、domain 和 server 分层可保持依赖方向清晰；只有 server 请求 runtime capability。

### 项目结构

```text
order-api -> order-domain -> order-server
```

### API 模块配置

```kotlin
api("me.ahoo.wow:wow-api")
```

### 领域模块配置

```kotlin
implementation("me.ahoo.wow:wow-core")
ksp("me.ahoo.wow:wow-compiler")
testImplementation("me.ahoo.wow:wow-test")
```

### 服务模块配置

本地无外部基础设施的最小候选可请求 `mock-support` 与 `webflux-support`：

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities { requireCapability("me.ahoo.wow:mock-support") }
}
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities { requireCapability("me.ahoo.wow:webflux-support") }
}
```

```yaml
spring:
  application:
    name: order-service
wow:
  command:
    bus:
      type: in_memory
  event:
    bus:
      type: in_memory
  eventsourcing:
    store:
      storage: in_memory
    snapshot:
      storage: in_memory
    state:
      bus:
        type: in_memory
```

`mock-support` 只适合本地/测试，不是生产持久化证明。

## 元数据加载

KSP 生成 `META-INF/wow-metadata.json`；`MetadataSearcher` 惰性合并 classpath 中所有同名资源。缺少生成文件会导致聚合、处理器、route/schema 不完整，Starter 不从反射猜回全部合同。

## 处理器注册

聚合、Saga、投影等来自编译元数据与 Spring Bean discovery。注解存在但模块未被 server 引用、KSP 未运行或生成资源未打包，都不会形成完整 runtime 注册。

### 聚合处理器

聚合命令处理由 aggregate metadata 和 state factory 装配；用真实命令和状态读取验证，而不是只数 Bean。

### Saga 处理器

Saga 需要对应 event bus subscription 与 runtime component 启动；类存在不等于已经消费消息。

### 投影处理器

投影同样依赖 event subscription、processor discovery 和目标读模型；启动日志不是投影已追平的证据。

## 完整配置示例

生产配置应由所选 capability 的扩展页拼装，而不是复制一个“全家桶”示例。至少显式声明 context、三种 bus、event/snapshot storage，并配置对应 Spring backend connection。

## 最佳实践

- 只请求实际使用的 capability，并显式选择 bus/store；
- 以自动配置条件、实际 Bean、OpenAPI 和 runtime 状态验证装配；
- 保持 `WowRuntimeLifecycle` 单一所有权；
- backend 校验留给 adapter/backend，公共合同校验留在边界。

已验证失败包括缺失 context name、缺少所选 backend Bean、重复 runtime/lifecycle owner、非 singleton runtime component 和非法扩展属性。聚焦检查：

```bash
./gradlew :wow-spring-boot-starter:check
```

下一步阅读[接入现有项目](../existing-project.md)和[模块依赖](../advanced/module-dependencies.md)。
