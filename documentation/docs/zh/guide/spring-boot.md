---
title: 在 Spring Boot 上运行
description: "在 Spring Boot 宿主上运行服务：它按类路径装配什么、怎样选择后端、从应用获取哪些 Bean，以及怎样检查它的配置。"
outline: deep
---

# 在 Spring Boot 上运行

一个服务就是类路径上带有它的领域模块和 Wow 宿主的一个 Spring Boot 应用。宿主找到编译器生成的模型，打开存储，提供路由，运行订阅；应用不写任何装配代码。本页说明宿主装配了什么，以及应用在哪里接入。

## 应用 {#the-application}

eShop 的 catalog 服务是它最小的服务：一个空的应用类。

<!-- sample: example/eshop/catalog/catalog-server/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/server/CatalogServer.kt -->

```kotlin
package me.ahoo.wow.example.eshop.catalog.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * The eShop's catalog service on the Wow host: the host finds the product model through `eshop-catalog-domain`'s binding
 * index and serves its routes on the public port and the cluster on the internal one. It runs on the memory backend.
 */
@SpringBootApplication(proxyBeanMethods = false)
class CatalogServer

fun main(args: Array<String>) {
    runApplication<CatalogServer>(*args)
}
```

它的构建依赖 catalog 的领域模块和宿主：

<!-- sample: example/eshop/catalog/catalog-server/build.gradle.kts#dependencies -->

```kotlin
dependencies {
    implementation(project(":eshop-catalog-domain"))
    implementation(project(":spring-boot"))
    // The `postgres` profile: the host runs on PostgreSQL when the application gives it a DataSource, and serves the
    // snapshot queries there, with the indexes the product's state declares.
    implementation(project(":postgres"))
    implementation("com.zaxxer:HikariCP")
    testImplementation("org.springframework:spring-test")
    // The springdoc bridge's acceptance (decision D233): the catalog's routes in springdoc's documents and Swagger UI,
    // the document checked against the OpenAPI 3.1 schema.
    testImplementation("org.springdoc:springdoc-openapi-starter-webflux-ui")
    testImplementation(libs.json.schema.validator)
}
```

宿主是制品 `me.ahoo.wow:wow-spring-boot`，一个 WebFlux 应用。它和其他所有 Wow 模块的版本都来自 BOM `me.ahoo.wow:wow-bom`。

## 类路径决定什么 {#what-the-class-path-decides}

类路径上有某个模块时，宿主就装配对应的部分；这部分需要设置时，再看它自己的设置：

| 模块 | 增加 |
| --- | --- |
| `wow-postgres` | PostgreSQL 后端，基于应用的 `DataSource` |
| `wow-mongo` | MongoDB 后端，基于应用的 `MongoClient` |
| `wow-kafka` | 设置了 `wow.kafka.bootstrap-servers` 时的 Kafka 传输：上下文之间发布的事件流、命令和回执（[Kafka](../operate/kafka)） |
| `wow-clickhouse` | 数仓 Sink 和数据集的查询 |
| `wow-elasticsearch` | 存储类型 `elasticsearch`，用于快照及其查询，以及为检索索引事件流的 Sink |
| `wow-opentelemetry` | 节点工作的追踪和指标 |
| `wow-cosec` | 公开路由的 CoSec 认证与授权（[保护部署](../operate/security)） |
| `wow-cosid` | CosId 的 ID 生成器 |
| `wow-reactor` | 返回 Reactor 的 `Mono` 和 `Flux` 的处理函数 |

类路径上有 Spring Boot Actuator 时，节点的健康状况会加入就绪和存活分组（[部署模型](../operate/topology#starting-and-stopping)）。

## 宿主装配什么 {#what-the-host-assembles}

- **模型。** 类路径上的每个领域模块都带有一个绑定索引。宿主加载每个模块的模型，用应用的 Bean 作为领域服务构建它们，并把各模块的 Manifest 片段合并成服务的 Manifest。`wow.contexts.include` 和 `wow.contexts.exclude` 选择节点提供哪些上下文；默认提供全部。
- **存储。** 每个上下文的日志、快照和定时器，放在宿主选择的后端上（见下文）。
- **端口。** 应用的 WebFlux 服务器上的公开路由，以及在它自己的服务器上的内部端口 `wow.internal.port`：集群通道、等待信号、服务之间的同步调用和读（[部署模型](../operate/topology#two-ports)）。
- **订阅。** 绑定索引列出的每个处理器处理函数，以及类路径上各模块的 Sink。
- **生命周期。** 节点在存储就绪、内部端口已绑定并加入了同伴之后启动，并按相反的顺序停止。

## 选择后端 {#choose-a-backend}

配置中没有指明存储实例时，`wow.backend` 选择数据放在哪里：

| `wow.backend` | 节点 |
| --- | --- |
| `auto`（默认） | 应用有 `DataSource` Bean 时用 PostgreSQL，有 `MongoClient` Bean 时用 MongoDB |
| `memory` | 有意把一切放在内存里：一个节点，数据不会比它活得更久 |

在 `auto` 下两个 Bean 都没有时，节点拒绝启动，除非处于开发 profile（`wow.internal.dev-profiles`），或者它的内部端口只绑定在回环网卡上；这时它在内存中运行并发出警告。生产节点绝不会意外地退回到内存。

聚合把数据放在不同存储里的服务，在 `wow.stores.<name>` 下命名存储实例（每个带它的 `type`，例如 `postgres`、`mongo`、`elasticsearch` 或 `memory`，以及它运行所基于的 Bean），并把每个聚合类型的日志和快照放到这些实例上：`wow.log.store`、`wow.snapshot.store`，以及按类型的 `wow.aggregates.[<context>.<aggregate>].log.store`。布局示例见 [PostgreSQL](../operate/postgresql)。

`wow.ddl` 决定存储是创建它们的表、集合和索引（`auto`，默认），只检查它们（`validate`），还是两者都不做（`none`）。

## 应用提供的 Bean {#beans-the-application-gives}

应用定义了这些 Bean 时，宿主就从应用获取，否则退回到它自己的：

| Bean | 作用 |
| --- | --- |
| 领域服务 | 命令函数和处理器的参数，按类型获取，或用 `@Name` 按名字获取 |
| `CallerResolver` | 把已认证的主体变成调用方；否则是匿名调用方 |
| `CommandAuthorizer` | 决定调用方能否向某个聚合发送命令 |
| `CommandTransformer`、`CommandValidator` | 在命令被决定之前改写或检查它 |
| `GlobalIdGenerator`、`AggregateIdGenerator` | 生成请求 ID 和聚合 ID；按类型由 `wow.aggregates.[<context>.<aggregate>].id-generator` 指定 |
| `FailureClassifier` | 为订阅处理函数的失败分类（[失败与重试](./failures)） |
| `ServiceCredential` | 同步调用或类型化客户端向另一个服务出示的凭据 |
| Bean Validation 实现 | 检查命令体的约束（[拒绝与错误码](./rejections#codes-the-framework-rejects-with)） |

## 配置会被检查 {#configuration-is-checked}

Spring 绑定它认识的键、忽略其余的键，所以拼错的键会悄悄什么都不做。宿主在启动时把环境中的每个 `wow.*` 键与类路径上的配置元数据比较：

- 未知的键记一条警告，并指明它。
- `wow.cosec.`、`wow.http.services.`、`wow.calls.` 或 `wow.internal.` 下的未知键会阻止启动：写错的安全设置会保留默认值，而默认值可能允许一切。

环境变量不检查，因为它们的名字无法列举。所有的键见[配置项](../reference/configuration)。

## 运行 {#run-it}

`./gradlew bootRun` 或应用的 main 函数会启动一个节点。没有 `DataSource` 或 `MongoClient`、也没有 Kafka 时，它在一台机器上运行，数据在内存中，前提是处于开发 profile 或内部端口只绑定在回环网卡上。生产服务需要什么，见[部署模型](../operate/topology)。
