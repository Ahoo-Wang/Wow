---
title: 在已有服务中引入 Wow
description: 把服务按 api、domain 和 server 三个模块组织，应用 Gradle 插件，命名上下文，并在 Spring Boot 应用中承载领域。
outline: deep
---

# 在已有服务中引入 Wow

[快速开始](./quickstart)把服务放在一个模块里。真实的服务会拆成三个模块，这样其他服务可以只依赖它的契约而不依赖它的领域，它的领域也只依赖内核而不依赖宿主。本页以 eShop 的 catalog 为例完成这样的设置；[eShop](https://github.com/Ahoo-Wang/Wow/tree/main/examples/eshop) 的每个服务都是这个形状。

## 你需要什么 {#what-you-need}

- JDK 21。
- Gradle 9 和 Kotlin JVM 插件 2.3 或更高版本。Java 源码也可以：KSP 在 Kotlin 编译中读取它们（[用 Java 编写领域](../guide/java)）。
- 服务器需要 Spring Boot 4，处理 JSON 的领域代码需要 Jackson 3（`tools.jackson`）。

## 组织服务 {#lay-out-the-service}

| 模块 | 包含 | 插件 | 依赖 |
| --- | --- | --- | --- |
| `catalog-api` | 命令、事件以及它们携带的值：上下文的契约 | `me.ahoo.wow.api` | `wow-api` |
| `catalog-domain` | 聚合根、状态、错误码、Saga、投影和反应器 | `me.ahoo.wow` | `catalog-api`、`wow-kernel` |
| `catalog-server` | Spring Boot 应用：宿主、一个后端，以及作为 Bean 的领域服务 | `me.ahoo.wow.service` | `catalog-domain`、`wow-spring-boot`、一个后端 |

其他服务只依赖 `catalog-api`，从不依赖领域模块。领域模块只依赖内核，从不依赖运行时或宿主。没有对端按其契约编译的服务不需要 api 模块：只应用 `me.ahoo.wow.service` 的一个模块就是它的领域和服务器，[快速开始](./quickstart)即是如此。

## 准备构建 {#set-up-the-build}

`settings.gradle.kts` 只写一次 Wow 版本，即其插件的版本：

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("jvm") version "2.4.21"
        id("me.ahoo.wow") version "10.0.0-alpha.6"
        id("me.ahoo.wow.api") version "10.0.0-alpha.6"
        id("me.ahoo.wow.service") version "10.0.0-alpha.6"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "catalog"
include("catalog-api", "catalog-domain", "catalog-server")
```

每个插件都会加上 `platform("me.ahoo.wow:wow-bom:<其版本>")` 和其角色的 Wow 模块，所以 Wow 依赖都不写版本。每个插件只配置自己所在的模块，所以构建可以使用 Gradle 的 isolated projects 和配置缓存。

`catalog-api/build.gradle.kts` 声明它携带其契约的领域模块：

```kotlin
plugins {
    kotlin("jvm")
    id("me.ahoo.wow.api")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    wowContract(project(":catalog-domain"))
    // The commands' constraints (@field:Positive, @field:NotBlank); Spring Boot's version, which wow-api brings.
    api("jakarta.validation:jakarta.validation-api")
}
```

`me.ahoo.wow.api` 加上 `wow-api`，把所声明的领域模块的契约打进它的 jar（`META-INF/wow/contract.json`），并由此编译 `<Context>Contract` 与各个 `<State>View`（[编译器生成什么](../guide/generated-code#the-contract-code-of-the-api-module)）。领域模块的命令和事件所在的 api 模块没有声明它时，构建失败，并写出要加的那一行。

`catalog-domain/build.gradle.kts`：

```kotlin
plugins {
    kotlin("jvm")
    id("me.ahoo.wow")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":catalog-api"))
    // The aggregate test DSL, package me.ahoo.wow.test.
    testImplementation("me.ahoo.wow:wow-test")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
```

应用 Kotlin JVM 插件之后，`me.ahoo.wow`：

- 加上 `wow-kernel`，生成的模型和绑定索引建立在它之上；
- 应用 KSP 和插件同版本的 `wow-compiler`，把模块的聚合编译为模型和它的那部分契约；
- 用模块中类型的 JSON Schema 补全这部分（`generateWowManifestFragment`），并提供给声明它的 api 模块；
- 注册测试套件 `integrationTest`，供在容器上运行的测试使用，不属于 `check`（[测试领域代码](../guide/testing)）。

`wow { }` 块用于配置它：

| 属性 | 默认值 | 含义 |
| --- | --- | --- |
| `context` | 模块中唯一的 `@BoundedContext` | 模块中的聚合和处理器所属的上下文 |
| `schemaOverrides` | 空 | 每个 JVM 类名对应一个 JSON Schema，用于由自定义序列化器写出的类型 |
| `service` | 无 | 承载模块上下文的服务，写进其契约，服务的 Manifest 也取这个名字（[编译器生成什么](../guide/generated-code)） |

`catalog-server/build.gradle.kts`：

```kotlin
plugins {
    kotlin("jvm")
    id("me.ahoo.wow.service")
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.example.catalog.server.CatalogServerKt")
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation(project(":catalog-domain"))
    // The host: Spring Boot auto-configuration, the public routes and the internal port.
    implementation("me.ahoo.wow:wow-spring-boot")
    // A backend. Without one, the host runs in memory, on one node.
    implementation("me.ahoo.wow:wow-postgres")
    implementation("com.zaxxer:HikariCP")
    // A Bean Validation provider: the host then checks the commands' Jakarta constraints.
    implementation("org.springframework.boot:spring-boot-starter-validation")
}
```

`me.ahoo.wow.service` 对服务器自己的源码（它的路由、Saga 和投影；没有则什么也不做）做 `me.ahoo.wow` 做的事，并且：

- 把运行时类路径上的各部分合并成服务的 Manifest，即 `build/wow/manifest.json`（`mergeWowManifest`），并写出它的 OpenAPI 文档（`generateWowOpenApi`）；
- 每次构建都对照提交到仓库的基线检查 Manifest（`checkWowManifest`，属于 `check`；`updateWowManifestBaseline` 记录基线）。

合并后的 Manifest 描述其上下文的契约写明的服务（各领域模块的 `wow { service }`，包括它自己的），这些名字须一致，否则以服务器的项目路径命名（`:catalog-server` 即 `catalog-server`），版本取项目的版本。它的 `wow { }` 块另有：

| 属性 | 默认值 | 含义 |
| --- | --- | --- |
| `manifestBaseline` | `manifest/baseline.json` | 兼容检查使用的、提交到仓库的基线 |

宿主会带上 Spring Boot 和 WebFlux。用 MongoDB 时，选择 `wow-mongo` 而不是 `wow-postgres`；访问其他上下文时加上 `wow-kafka`，使用数仓时加上 `wow-clickhouse`（[在 Spring Boot 上运行](../guide/spring-boot#what-the-class-path-decides)）。

## 命名上下文 {#name-the-context}

领域模块在它自己的一个 object 上声明一次它的限界上下文：

<!-- sample: examples/eshop/catalog/catalog-domain/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/domain/CatalogBoundedContext.kt -->

```kotlin
package me.ahoo.wow.example.eshop.catalog.domain

import me.ahoo.wow.api.annotation.BoundedContext

/**
 * Names the bounded context of this module's aggregates for the compiler. The name is declared here, once: the api
 * module's generated `EshopCatalogContract.NAME` comes from it.
 */
@BoundedContext(name = "eshop-catalog", description = "The eShop catalog: products, their brands and types")
object CatalogBoundedContext
```

这个名字是每个类型名（`eshop-catalog.product.CreateProduct`）、每个路由（`/eshop-catalog/product/{id}/create-product`）、每个主题（`wow.eshop-catalog.product.state`）和每张表的一部分，所以只选一次。上下文名由小写字母、数字和连字符组成，以字母开头：要写 `eshop-catalog`，绝不能写 `eshop.catalog`，因为点用来分隔类型名的各个部分。编译器会拒绝违反这条规则的名字。

## 编写契约和领域 {#write-the-contract-and-the-domain}

api 模块包含命令和事件，它们是不带身份字段的数据类，其形状由 Jakarta 约束检查（[编写聚合](../guide/aggregates#write-the-commands)）。领域模块包含根、它的状态和它的错误码，即需要状态的规则（[编写聚合](../guide/aggregates#write-the-root)），并用测试 DSL 测试它们（[测试领域代码](../guide/testing)）。

## 编写服务器 {#write-the-server}

服务器是一个普通的 Spring Boot 应用。catalog 的服务器只需要它的应用类：

<!-- sample: examples/eshop/catalog/catalog-server/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/server/CatalogServer.kt -->

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

命令函数接收的领域服务是应用的 Bean。`DataSource` 或 `MongoClient` 决定后端，可以是宿主用 `spring.datasource.url` 或 `spring.mongodb.uri` 建的，也可以是应用自己的 Bean；两者都没有时，宿主只在开发 profile 中或内部端口只绑定回环网卡时才在内存中运行（[在 Spring Boot 上运行](../guide/spring-boot#choose-a-backend)）。`CallerResolver` Bean 认证调用方，开发环境之外没有它宿主不会启动（[提供和调用 HTTP API](../guide/http#who-the-caller-is)）。

## 运行 {#run-it}

`./gradlew :catalog-server:run` 在这台机器上启动一个节点。命令、状态读取和查询的提供方式与[快速开始](./quickstart#send-commands)中相同。上线生产之前，过一遍[运行 Wow](../operate/)。
