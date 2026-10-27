---
title: 在已有服务中引入 Wow
description: 把服务按 api、domain 和 server 三个模块组织，应用 Gradle 插件，命名上下文，并在 Spring Boot 应用中承载领域。
outline: deep
---

# 在已有服务中引入 Wow

[快速开始](./quickstart)把服务放在一个模块里。真实的服务会拆成三个模块，这样其他服务可以只依赖它的契约而不依赖它的领域，它的领域也只依赖内核而不依赖宿主。本页以 eShop 的 catalog 为例完成这样的设置；[eShop](https://github.com/Ahoo-Wang/Wow/tree/main/example/eshop) 的每个服务都是这个形状。

## 你需要什么 {#what-you-need}

- JDK 21。
- Gradle 9 和 Kotlin JVM 插件。Java 源码也可以：KSP 在 Kotlin 编译中读取它们（[用 Java 编写领域](../guide/java)）。
- 服务器需要 Spring Boot 4，处理 JSON 的领域代码需要 Jackson 3（`tools.jackson`）。

## 组织服务 {#lay-out-the-service}

| 模块 | 包含 | 依赖 |
| --- | --- | --- |
| `catalog-api` | 命令、事件以及它们携带的值：上下文的契约 | `wow-api` |
| `catalog-domain` | 聚合根、状态、错误码、Saga、投影和反应器 | `catalog-api`、`wow-kernel`；插件 `me.ahoo.wow` |
| `catalog-server` | Spring Boot 应用：宿主、一个后端，以及作为 Bean 的领域服务 | `catalog-domain`、`wow-spring-boot`、一个后端 |

其他服务只依赖 `catalog-api`，从不依赖领域模块。领域模块只依赖内核，从不依赖运行时或宿主。

## 准备构建 {#set-up-the-build}

`settings.gradle.kts`：

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("jvm") version "2.4.21"
        id("me.ahoo.wow") version "10.0.0-alpha.6"
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

`gradle.properties` 只写一次 Wow 的版本：

```properties
wowVersion=10.0.0-alpha.6
```

`catalog-api/build.gradle.kts`：

```kotlin
plugins {
    kotlin("jvm")
}

val wowVersion: String by project

kotlin {
    jvmToolchain(21)
}

dependencies {
    api("me.ahoo.wow:wow-api:$wowVersion")
    // The commands' constraints (@field:Positive, @field:NotBlank); Spring Boot's version, which wow-api brings.
    api("jakarta.validation:jakarta.validation-api")
}
```

`catalog-domain/build.gradle.kts` 应用 Gradle 插件：

```kotlin
plugins {
    kotlin("jvm")
    id("me.ahoo.wow")
}

val wowVersion: String by project

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":catalog-api"))
    // The generated models and the binding index build on the kernel only.
    api("me.ahoo.wow:wow-kernel:$wowVersion")
    // The aggregate test DSL, package me.ahoo.wow.test.
    testImplementation("me.ahoo.wow:wow-test:$wowVersion")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
```

应用了 Kotlin JVM 插件之后，`me.ahoo.wow`：

- 应用 KSP 和与插件同版本的 `wow-compiler`，让模块的聚合编译成模型和它那部分契约；
- 用模块中类型的 JSON Schema 补全那部分契约（`generateWowManifestFragment`）；
- 把运行时类路径上的每个部分合并成服务的 Manifest `build/wow/manifest.json`（`mergeWowManifest`），并写出它的 OpenAPI 文档（`generateWowOpenApi`）；
- 把每次构建的 Manifest 与提交的基线比较（`checkManifestCompatibility`，`check` 的一部分；`updateManifestBaseline` 记录基线）；
- 把对端需要的契约打包进领域的 api 模块，即 `META-INF/wow/contract.json`，并在那里编译出 `<Context>Contract`（[编译器生成什么](../guide/generated-code#the-contract-code-of-the-api-module)）。

`wow { }` 块配置它：

| 属性 | 默认值 | 含义 |
| --- | --- | --- |
| `context` | 模块中唯一的 `@BoundedContext` | 模块中的聚合和处理器所属的上下文 |
| `addCompilerDependency` | `true` | 插件是否把 `wow-compiler` 加到 `ksp` |
| `schemaOverrides` | 空 | 每个 JVM 类名对应一个 JSON Schema，用于由自定义序列化器写出的类型 |
| `serviceName`、`serviceVersion` | 项目的名字和版本 | 合并后的 Manifest 所描述的服务 |
| `manifestBaseline` | `manifest/baseline.json` | 兼容检查使用的、提交到仓库的基线 |

`catalog-server/build.gradle.kts`：

```kotlin
plugins {
    kotlin("jvm")
    application
}

val wowVersion: String by project

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
    implementation("me.ahoo.wow:wow-spring-boot:$wowVersion")
    // A backend. Without one, the host runs in memory, on one node.
    implementation("me.ahoo.wow:wow-postgres:$wowVersion")
    implementation("com.zaxxer:HikariCP")
    // A Bean Validation provider: the host then checks the commands' Jakarta constraints.
    implementation("org.springframework.boot:spring-boot-starter-validation")
}
```

宿主会带上 Spring Boot 和 WebFlux。用 MongoDB 时，选择 `wow-mongo` 而不是 `wow-postgres`；访问其他上下文时加上 `wow-kafka`，使用数仓时加上 `wow-clickhouse`（[在 Spring Boot 上运行](../guide/spring-boot#what-the-class-path-decides)）。

## 命名上下文 {#name-the-context}

领域模块在它自己的一个 object 上声明一次它的限界上下文：

<!-- sample: example/eshop/catalog/catalog-domain/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/domain/CatalogBoundedContext.kt -->

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

命令函数接收的领域服务是应用的 Bean。`DataSource` 或 `MongoClient` 决定后端，可以是宿主用 `spring.datasource.url` 或 `spring.mongodb.uri` 建的，也可以是应用自己的 Bean；两者都没有时，宿主只在开发 profile 中或内部端口只绑定回环网卡时才在内存中运行（[在 Spring Boot 上运行](../guide/spring-boot#choose-a-backend)）。`CallerResolver` Bean 认证调用方，开发环境之外没有它宿主不会启动（[提供和调用 HTTP API](../guide/http#who-the-caller-is)）。

## 运行 {#run-it}

`./gradlew :catalog-server:run` 在这台机器上启动一个节点。命令、状态读取和查询的提供方式与[快速开始](./quickstart#send-commands)中相同。上线生产之前，过一遍[运行 Wow](../operate/)。
