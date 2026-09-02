---
title: 接入现有 Spring Boot 项目
description: 以明确的构建、元数据、路由、运行、失败和回滚门禁，把一个 Wow 聚合接入已有 Gradle、Kotlin 与 Spring Boot 服务。
outline: deep
---

# 接入现有 Spring Boot 项目

先把 Wow 作为一条可回退的垂直切片接入：

```text
KSP 元数据 → Spring 自动装配 → 生成 HTTP 路由
→ 命令聚合 → 事件存储 / 快照 → 版本化状态读取
```

不要在同一次改动中迁移所有写入链路或引入全部生产基础设施。第一个里程碑是：一个聚合在显式内存 Adapter 上运行，原有写入路径仍可用于回退。

## 版本基线

当前 Wow `9.0.5` 源码声明：

| 组件 | 版本 |
| --- | --- |
| JDK | 17+ |
| Wow | `9.0.5` |
| Spring Boot | `4.1.1` |
| Kotlin | `2.4.10` |
| KSP | `2.3.11` |
| CosId | `3.2.1` |
| Springdoc | `3.1.0` |

这些版本是一条兼容性列车，不是可以独立替换的建议。使用其他 Wow 版本时，先检查对应 tag 的 `gradle/libs.versions.toml`、发布说明和持久化事件要求，再修改应用。

## 1. 定义接入边界

选择一个聚合 ID 与业务不变量都清晰的用例。编辑前记录：

- 现有 HTTP/写入入口与当前事实来源；
- 将引入的 Wow 命令路由；
- 首次运行只在本地，还是会接收真实流量；
- 切换前需要的状态对比或对账；
- 最后一个只需移除 Wow 依赖即可回退的节点。

新路由尚无生产流量时，回退很简单。命令已经追加事件后，移除运行时并不等于数据回滚；详见[回滚边界](#回滚边界)。

## 2. 选择依赖与 Capability

每个包含 Wow 注解的模块都要应用 KSP 并依赖 `wow-compiler`。多模块服务可按最小职责拆分：

| 模块 | 职责 | 必需 Wow 组件 |
| --- | --- | --- |
| `api` | 限界上下文、命令、事件、`@CommandRoute` | `wow-api`、KSP、`wow-compiler` |
| `domain` | `@AggregateRoot`、`@OnCommand`、`@OnSourcing`、领域规约 | `wow-core` 或 `wow-spring`、KSP、`wow-compiler`、`wow-test` |
| `server` | Spring Boot 宿主与运行时 Adapter | 基础 Starter 与选定 Capability |

在注解模型模块中对齐平台与编译器：

```kotlin
dependencies {
    implementation(platform("me.ahoo.wow:wow-bom:9.0.5"))
    ksp(platform("me.ahoo.wow:wow-bom:9.0.5"))

    implementation("me.ahoo.wow:wow-api") // api 模块
    ksp("me.ahoo.wow:wow-compiler")

    testImplementation("me.ahoo.wow:wow-test") // domain 模块
}
```

在服务模块同时请求基础 Starter 与 `webflux-support` Feature：

```kotlin
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation(platform("me.ahoo.wow:wow-bom:9.0.5"))

    implementation("me.ahoo.wow:wow-spring-boot-starter")
    implementation("me.ahoo.wow:wow-spring-boot-starter") {
        capabilities {
            requireCapability("me.ahoo.wow:webflux-support")
        }
    }

    implementation("me.ahoo.cosid:cosid-spring-boot-starter:3.2.1")
}
```

基础 Starter 提供核心 Spring 装配；`webflux-support` 选择 `wow-webflux`，由它把 `RouterSpecs` 物化为命令与状态路由。它是 Gradle Feature Capability，不是配置属性开关。

只选择目标运行环境实际需要的后端：

| 需求 | Capability |
| --- | --- |
| 本地单进程验证 | 不需要；使用内存配置 |
| 分布式命令/事件/状态总线 | `kafka-support` |
| MongoDB 事件/快照存储与查询 | `mongo-support` |
| Redis 事件/快照存储或总线 | `redis-support` |
| Elasticsearch 存储与查询 | `elasticsearch-support` |
| 链路追踪仪表器 | `opentelemetry-support` |
| CoSec 授权 | `cosec-support` |

完整 Capability 表见 [Spring Boot Starter](./extensions/spring-boot-starter.md)。不要因为“未来可能使用”就提前请求 Kafka 或存储 Feature。

::: warning Gradle + KSP 边界
仓库当前验证过的自动元数据生成链路是 Gradle + KSP。Maven 可以声明运行时依赖，但本站不声称存在等价的已验证 Maven 生成链路。依赖生成处理器时，发布前必须证明 `META-INF/wow-metadata.json` 存在。
:::

## 3. 生成并检查元数据

按[聚合与不变量](./domain/aggregate.md)定义限界上下文、命令/事件、聚合、状态和至少一个 `AggregateSpec`。业务不变量保留在聚合中，不要复制到 Controller。

在实际注解模型模块中运行 KSP 与测试：

```shell
./gradlew clean :api:kspKotlin :domain:kspKotlin :domain:test
test -s api/build/generated/ksp/main/resources/META-INF/wow-metadata.json
test -s domain/build/generated/ksp/main/resources/META-INF/wow-metadata.json
```

按应用调整模块路径。只有配置且没有 Wow 注解的模块，不需要伪造元数据文件。

运行时 `MetadataSearcher` 会合并 classpath 中所有名为 `META-INF/wow-metadata.json` 的资源。服务模块必须依赖注解模型模块，确保这些资源进入运行 classpath。不要手写元数据，也不要提交 `build/` 产物。

## 4. 使用显式首跑 Adapter

Wow 的事件存储与快照默认选择 MongoDB；存在外部总线 Adapter 时，也可能激活外部实现。本地单进程验证应显式选择全部内存实现，并关闭依赖 MongoDB 或 Redis 的 `PrepareKey`：

```yaml
spring:
  application:
    name: demo-service

cosid:
  machine:
    enabled: true
    distributor:
      type: manual
      manual:
        machine-id: 1
  generator:
    enabled: true

wow:
  prepare:
    enabled: false
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
      strategy: all
    state:
      bus:
        type: in_memory
```

这不是生产配置：进程退出后数据消失，消息只在单进程内投递，手动 machine ID `1` 也只适用于一个实例。请把该本地 Profile 以不含密钥的方式纳入版本控制，并显式激活。

## 5. 证明运行时路由装配

启动应用实际使用的服务任务，例如：

```shell
./gradlew :server:bootRun
```

路由按以下链路完成装配：

1. KSP 资源进入运行 classpath；
2. `MetadataSearcher` 合并限界上下文、聚合、命令和处理函数元数据；
3. OpenAPI 自动配置创建 `RouterSpecs`；
4. WebFlux 自动配置注册命令/状态 Route Module；
5. `RouterFunctionBuilder` 把路由目录物化为 Spring `RouterFunction`。

先确认应用日志显示每个注解模块的 `META-INF/wow-metadata.json` 已加载，再检查 `/v3/api-docs` 或应用路由目录，确认命令和版本化状态路径存在。如果需要 Swagger UI，可按同一 Springdoc 基线作为应用选择加入；运行时路由本身不依赖 UI。

完成该 UI 配置后，本地约定入口为 [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)。

## 6. 证明命令 → 事件 → 状态

使用正在接入的聚合所生成的路由，不要用手写 Controller 作为测试捷径。

1. 使用固定聚合 ID 和唯一请求 ID 发送创建命令。
2. 本次接入证明至少请求 `SNAPSHOT`。
3. 同时要求 HTTP 成功、`succeeded: true`、`errorCode: Ok`、预期阶段、ID 与版本。
4. 按返回版本读取 `/tenant/{tenantId}/{aggregateName}/{id}/state/{version}`。
5. 对比溯源状态、预期领域结果与 `AggregateSpec` 断言。

具体且已验证的请求/响应见[快速上手](./getting-started.md#提交第一条真实命令)。请把 Demo 路由与载荷替换为应用生成的契约，不要把 Demo 复制进生产代码。

## 失败检查点

| 现象 | 首先检查 | 不要做 |
| --- | --- | --- |
| KSP 任务成功但元数据文件不存在 | 包含注解的模块是否应用 KSP 与 `wow-compiler` | 手写 `wow-metadata.json` |
| 元数据存在但 HTTP 路由缺失 | 注解模块是否进入服务运行 classpath；是否选择 `webflux-support` | 添加重复 Controller |
| 启动时请求 Kafka 或 MongoDB | 本地 Profile 是否加载；是否误选后端 Capability；内存配置键是否齐全 | 启动无关基础设施掩盖错误配置 |
| 第一条命令报告 ID 生成器未初始化 | CosId Starter、Generator 与单实例 machine ID 是否生效 | 用临时随机数回退生成生产 ID |
| 命令返回重复请求 | 是否复用了同一 `requestId` | 关闭幂等以让请求通过 |
| 状态路由返回 `404` | 完整上下文/聚合/租户/ID、命令结果与返回版本 | 假设命令 HTTP `200` 已证明状态持久化 |

若路由缺失，继续阅读[故障排查：元数据或处理函数未注册](./troubleshooting.md#元数据或处理函数未注册)。

## 回滚边界

真实流量进入前，回滚只是移除代码/配置：保持原写入路径不变，关闭新 Wow 路由或 Profile；切片未通过门禁时可移除新增依赖。

Wow 命令已经追加领域事件后，回滚含义发生变化：

- 不要删除事件来模拟数据库回滚；
- 没有明确双写与对账设计时，不要让同一业务写入同时经过新旧路径；
- 对比状态期间，把旧路径保持为只读或与新流量隔离；
- 把流量切回旧路径前，定义已接收 Wow 事件的重放、对账或业务补偿方式；
- 只有在选定持久化 Adapter 上验证备份恢复、幂等、监控与恢复后，才执行切换。

在生产证据完整前，迁移单元应始终保持为一个聚合/用例。

## 完成门禁

只有以下条件全部成立，接入才具备进入独立切换决策的资格：

- 领域测试通过，每个注解模型模块都存在元数据文件；
- 服务加载这些资源，并暴露生成路由；
- 真实命令到达声明的等待阶段；
- 版本化溯源状态与预期事件历史一致；
- 失败、幂等、存储和回滚流程都有环境证据。

## 下一步

- 选择持久化 Adapter：[Spring Boot Starter](./extensions/spring-boot-starter.md)
- 定义完成语义：[完成语义](./command/completion.md)
- 建立发布门禁：[Wow 应用测试](./application-testing.md)
- 建立持久化事件策略：[事件演进](./domain/event-evolution.md)
- 规划模块边界：[模块依赖](./advanced/module-dependencies.md)
