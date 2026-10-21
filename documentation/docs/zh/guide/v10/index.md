---
title: 编写 V10 服务
description: 按三个模块从零写一个 Wow V10 服务：从 Gradle 构建到一个能回答第一条命令的运行节点。
outline: deep
---

# 编写 V10 服务

本页按真实服务的方式把一个新的 Wow V10 服务分成三个模块，从一个空的 Gradle 构建开始，直到节点通过 HTTP 接收第一条命令。想先在一个模块里试用 V10，请从[快速开始](./quickstart.md)读起。V10 指南的其余页面逐步展开每一步：

| 页面 | 内容 |
| --- | --- |
| [编程模型](./programming-model.md) | 聚合、命令、事件、拒绝；Saga、投影、反应器；定时命令；其他上下文 |
| [编译器生成什么](./generated-code.md) | 模型工厂、`ModelProvider`、Manifest 片段、路由 |
| [测试](../testing.md) | `aggregateSpec` 及其所在位置、Saga 测试、服务端测试 |
| [HTTP API](./http-api.md) | 命令路由、`Wow-Wait`、状态读取、查询路由、数据集 |
| [配置项](./configuration.md) | 全部 `wow.*` 配置项及默认值 |
| [部署](../../operate/topology.md) | PostgreSQL 或 MongoDB、内部端口、Kafka、ClickHouse、个人信息、租户 |

::: warning V10 处于预发布
V10 在 [`main` 分支](https://github.com/Ahoo-Wang/Wow/tree/main) 上开发；9.x 在 [`v9`](https://github.com/Ahoo-Wang/Wow/tree/v9) 上维护。第一个预发布 `10.0.0-alpha.3` 只用于试用与评估：它不做兼容性承诺，9.x 数据的迁移也尚未完成（见[快速开始](./quickstart.md)）。它的模块以 `me.ahoo.wow:wow-*` 发布在 Maven Central，BOM 为 `me.ahoo.wow:wow-bom`，Gradle 插件为 `me.ahoo.wow`。本 V10 栏目之外的指南描述的是 9.x。
:::

## 前提

- JDK 21：所有 V10 模块都在它上面构建和运行。
- Kotlin 与 KSP：Gradle 插件会替你应用 KSP。Java 源文件同样可以：KSP 能读取它们，但构建仍需要 Kotlin Gradle 插件。
- 服务端使用 Spring Boot 4；领域代码里涉及 JSON 的部分使用 Jackson 3（`tools.jackson`）。

## 划分模块

一个服务由三个 Gradle 模块组成。下面以 `catalog` 上下文为例；仓库内 eShop 示例 [`example/eshop`](https://github.com/Ahoo-Wang/Wow/tree/main/example/eshop) 的每个上下文（`catalog`、`basket`、`ordering`、`inventory`、`payment`）都是同样的结构。

| 模块 | 内容 | 依赖 |
| --- | --- | --- |
| `catalog-api` | 命令、事件与值类型：上下文的契约 | `wow-api` |
| `catalog-domain` | 聚合根、状态、拒绝码、Saga、投影与反应器 | `catalog-api`、`wow-kernel`；插件 `me.ahoo.wow` |
| `catalog-server` | Spring Boot 应用：宿主、一个后端、领域服务的 Bean | `catalog-domain`、`wow-spring-boot`、一个后端模块 |

其他服务只依赖 `catalog-api`，从不依赖领域模块。领域模块只依赖内核，从不依赖运行时或宿主。

## 配置 Gradle

`settings.gradle.kts`：

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("jvm") version "2.4.21"
        id("me.ahoo.wow") version "10.0.0-alpha.3"
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

`gradle.properties` 只写一次 Wow 版本：

```properties
wowVersion=10.0.0-alpha.3
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
    // 生成的模型与绑定索引只依赖内核。
    api("me.ahoo.wow:wow-kernel:$wowVersion")
    // 聚合测试 DSL，包名 me.ahoo.wow.test。
    testImplementation("me.ahoo.wow:wow-test:$wowVersion")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
```

应用 Kotlin JVM 插件之后，`me.ahoo.wow`：

- 以 `generate` 模式应用 KSP 与同版本的 `me.ahoo.wow:wow-compiler`，并以项目名命名模块的 Manifest 片段；
- 注册 `generateWowManifestFragment`，用模块中各类型的 JSON Schema 补全片段；
- 注册 `mergeWowManifest`，把运行时类路径上的全部片段合并为 `build/wow/manifest.json`，以 `wow-manifest` 变体发布；
- 注册 `collectWowPeers` 与 `wowPeers` 配置，把其他服务的 Manifest 放到类路径上（见[其他上下文](./programming-model.md#其他上下文)）。

`wow { }` 块用来配置它：

| 属性 | 默认值 | 含义 |
| --- | --- | --- |
| `context` | 模块中唯一的 `@BoundedContext` | 模块的聚合与处理器所属的上下文（KSP 选项 `wow.context`） |
| `addCompilerDependency` | `true` | 插件是否把 `wow-compiler` 加入 `ksp` |
| `schemaOverrides` | 空 | 按 JVM 类名给出 JSON Schema，用于由自定义序列化器写出的类型 |
| `serviceName` | 项目名 | 合并后的 `manifest.json` 描述的服务 |
| `serviceVersion` | 项目版本 | 其中的服务版本 |

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
    // 宿主：Spring Boot 自动配置、公开路由与内部端口。
    implementation("me.ahoo.wow:wow-spring-boot:$wowVersion")
    // 一个后端。没有后端时宿主在内存中运行，只能单节点。
    implementation("me.ahoo.wow:wow-postgres:$wowVersion")
    implementation("com.zaxxer:HikariCP")
    // Bean Validation 实现：宿主据此校验命令上的 Jakarta 约束。
    implementation("org.springframework.boot:spring-boot-starter-validation")
}
```

宿主自带 `spring-boot-starter` 与 WebFlux。用 MongoDB 时把 `wow-postgres` 换成 `wow-mongo`；要连接其他上下文加 `wow-kafka`；要用 ClickHouse Sink 与数据集加 `wow-clickhouse`。把其他服务的 Manifest 作为对端（peer）的服务端也要应用 `me.ahoo.wow`，以便使用它的 `wowPeers` 配置。

### 不用插件

插件无法应用在产出它的那个构建里，所以 Wow 仓库中的示例手动应用 KSP，并自己传入插件的选项：

```kotlin
plugins {
    alias(libs.plugins.ksp)
}

ksp {
    arg("wow.model", "generate")
    arg("wow.manifest.module", project.name)
}

// 相当于插件的 generateWowManifestFragment：用模块各类型的 Schema 补全片段。
apply(from = rootProject.file("example/wow-manifest-fragment.gradle.kts"))

dependencies {
    api(project(":catalog-api"))
    api(project(":kernel"))
    ksp(project(":compiler"))
    testImplementation(project(":test-dsl"))
}
```

[`example/wow-manifest-fragment.gradle.kts`](https://github.com/Ahoo-Wang/Wow/blob/main/example/wow-manifest-fragment.gradle.kts) 执行的正是插件 `generateWowManifestFragment` 执行的内容，并应用 [`example/wow-contract.gradle.kts`](https://github.com/Ahoo-Wang/Wow/blob/main/example/wow-contract.gradle.kts)，像插件一样把领域的契约打包进它的 api 模块。eShop 的服务端在设置 `extra["wowService"]` 之后应用 [`example/eshop/wow-manifest-compatibility.gradle.kts`](https://github.com/Ahoo-Wang/Wow/blob/main/example/eshop/wow-manifest-compatibility.gradle.kts)，代替 `mergeWowManifest` 与 Manifest 兼容性检查。KSP 选项如下：

| 选项 | 含义 |
| --- | --- |
| `wow.model` | `generate` 编译模型、绑定索引与片段；`report` 只写 `META-INF/wow/model-report.json`；不设置时处理器什么也不做 |
| `wow.manifest.module` | 片段的文件名 `META-INF/wow/manifest/<module>.json`；不设置时取上下文名 |
| `wow.context` | 模块的上下文；不设置时取模块中唯一的 `@BoundedContext` |

## 命名上下文

领域模块在一个单独的对象上声明一次它的限界上下文：

<!-- sample: fragment -->

```kotlin
package com.example.catalog.domain

import me.ahoo.wow.api.annotation.BoundedContext

@BoundedContext(name = "catalog", description = "Products and their prices")
object CatalogBoundedContext
```

这个名字出现在每个类型名（`catalog.product.ChangePrice`）、每条路由（`/catalog/product/{id}/change-price`）、每个主题（`wow.catalog.product.state`）与每张表里。一次定好：

- **上下文名**须匹配 `[a-z][a-z0-9-]*`：小写字母、数字与连字符，以字母开头（D169）。不允许 `.`：它是 `catalog.product.ChangePrice` 这类类型名的分隔符，`eshop.catalog` 会产生歧义，请用 `eshop-catalog`。违反规则的 `@BoundedContext`（或 `wow.context` 选项）会让编译失败，读取 Manifest（模块的片段或对端的 Manifest）时同样会被拒绝：

  ```text
  @BoundedContext: context name "eshop.catalog" is not valid: a context name is lower-case letters, digits and hyphens, starting with a letter ([a-z][a-z0-9-]*), such as eshop-catalog; a dot separates the context from the aggregate in type names such as context.aggregate.Command (decision D169)
  ```

- **聚合名**取聚合根的 `@Name`，否则取类的简单名的 `snake_case`（`Product` 为 `product`，`PriceList` 为 `price_list`）。
- **命令与事件名**为 `<context>.<aggregate>.<Name>`，取类的简单名，除非 `@Name` 另行指定。
- **处理器与订阅名**为 `<processor>.<function>`，如 `PriceSaga.onPriceChanged`，处理器取类的 `@Name` 或简单名。

编译器要知道上下文才能写出片段：模块中有一个 `@BoundedContext`，或插件配置了 `wow { context = "catalog" }`。生成的工厂函数仍然把上下文作为参数（见[编译器生成什么](./generated-code.md#模型工厂)）。

## 编写契约

`catalog-api` 放命令、事件与它们携带的值。命令不带身份字段：聚合 ID 来自路由（或发送它的 Saga），所有者与空间来自调用方。

<!-- sample: fragment -->

```kotlin
package com.example.catalog.api

import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.Summary
import java.math.BigDecimal

@CreateAggregate
@Summary("List a product")
data class ListProduct(val name: String, val price: BigDecimal)

@Summary("Change the product's price")
data class ChangePrice(val price: BigDecimal)

@Summary("A product was listed")
data class ProductListed(val name: String, val price: BigDecimal)

@Summary("The product's price changed")
data class PriceChanged(val price: BigDecimal)
```

## 编写聚合

`catalog-domain` 放状态、聚合根与它拒绝时使用的错误码：

<!-- sample: fragment -->

```kotlin
package com.example.catalog.domain.product

import com.example.catalog.api.ChangePrice
import com.example.catalog.api.ListProduct
import com.example.catalog.api.PriceChanged
import com.example.catalog.api.ProductListed
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.ErrorCode
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.decide.Decision
import java.math.BigDecimal

/** 第一个事件之前每个属性都要有值：内核按原样序列化状态。 */
class ProductState(val id: String) {
    var name: String = ""
        private set
    var price: BigDecimal = BigDecimal.ZERO
        private set

    fun onSourcing(event: ProductListed) {
        name = event.name
        price = event.price
    }

    fun onSourcing(event: PriceChanged) {
        price = event.price
    }
}

@AggregateRoot
class Product(private val state: ProductState) {
    fun onCommand(command: ListProduct): ProductListed = ProductListed(command.name, command.price)

    @OnCommand(produces = [PriceChanged::class])
    fun onCommand(command: ChangePrice): Decision = when {
        command.price.signum() < 0 ->
            Decision.reject(Rejections.NEGATIVE_PRICE, "${command.price} is negative.")
        command.price.compareTo(state.price) == 0 -> Decision.NoOp
        else -> Decision.events(PriceChanged(command.price))
    }

    object Rejections {
        @ErrorCode(description = "A price is never negative.")
        const val NEGATIVE_PRICE = "negative-price" // catalog.product.negative-price
    }
}
```

名为 `onCommand` 与 `onSourcing` 的函数不需要注解。`@OnCommand(produces)` 告诉 Manifest 返回 `Decision` 的函数可能产生哪些事件。拒绝是一个值，不是异常：不写入任何东西，调用方收到错误码。完整规则见[编程模型](./programming-model.md)。

## 测试

`aggregateSpec` 在真实内核与内存日志上运行生成的模型：

<!-- sample: fragment -->

```kotlin
package com.example.catalog.domain.product

import com.example.catalog.api.ChangePrice
import com.example.catalog.api.ListProduct
import com.example.catalog.api.PriceChanged
import com.example.catalog.api.ProductListed
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ProductSpec {
    private val model = productModel("catalog")

    @Test
    fun `a listed product takes a new price`() = aggregateSpec(model, id = "apple") {
        given(ProductListed("Apple", BigDecimal("2.50")))
        whenCommand(ChangePrice(BigDecimal("3.00"))) {
            expectEvents(PriceChanged(BigDecimal("3.00")))
            expectState { check(price == BigDecimal("3.00")) }
        }
    }

    @Test
    fun `a negative price is rejected`() = aggregateSpec(model, id = "apple") {
        given(ProductListed("Apple", BigDecimal("2.50")))
        whenCommand(ChangePrice(BigDecimal("-1"))) { expectRejected(Product.Rejections.NEGATIVE_PRICE) }
    }

    @Test
    fun `listing a product creates it`() = aggregateSpec(model, id = "apple") {
        whenCommand(ListProduct("Apple", BigDecimal("2.50"))) { expectEvents(ProductListed("Apple", BigDecimal("2.50"))) }
    }
}
```

`productModel` 是编译器在 `Product` 旁边生成的工厂函数。测试函数要像上面这样返回 `Unit`：JUnit 会跳过返回值的测试方法。全部断言见[测试](../testing.md)。

## 编写服务端

<!-- sample: fragment -->

```kotlin
package com.example.catalog.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(proxyBeanMethods = false)
class CatalogServer

fun main(args: Array<String>) {
    runApplication<CatalogServer>(*args)
}
```

`src/main/resources/application.yaml`：

```yaml
spring:
  application:
    name: catalog-service
server:
  port: 8080
wow:
  # 单节点，数据在内存中，用于试用。
  backend: memory
  internal:
    port: 9090
```

宿主在类路径上找到领域模块的绑定索引，用应用的 Bean 构建模型，合并 Manifest 并提供它的路由。命令函数或处理器以参数形式需要的领域服务，是应用中的 Bean，按类型查找（带 `@Name` 时按名字）。使用 PostgreSQL 时，应用声明一个 `DataSource` Bean 并设置 `wow.backend: postgres`（见[部署](../../operate/topology.md)）。

## 运行并发送命令

```bash
./gradlew :catalog-server:run
```

节点在 8080 端口提供公开路由，在 9090 提供内部端口。它会警告自己运行在内存后端上，并且在应用声明 `CallerResolver` Bean 之前，每个请求都按匿名调用方处理（见 [HTTP API](./http-api.md#调用方)）。

```bash
curl -X POST localhost:8080/catalog/product/apple/list-product \
  -H 'Content-Type: application/json' -d '{"name":"Apple","price":2.50}'
curl -X POST localhost:8080/catalog/product/apple/change-price \
  -H 'Content-Type: application/json' -d '{"price":3.00}'
curl localhost:8080/catalog/product/apple/state
curl localhost:8080/wow/manifest
```

命令在快照包含这次提交之后返回它的 `CommandResult`，所以紧接着读取状态就能看到新价格。被拒绝的命令返回 RFC 9457 问题详情，类型为 `urn:wow:error:catalog.product.negative-price`，状态码取自它的 `@ErrorCode`（默认 422）。

## 下一步

- 加一个 Saga、投影或定时命令：[编程模型](./programming-model.md)。
- 看看构建生成了什么：[编译器生成什么](./generated-code.md)。
- 在 PostgreSQL 上运行多个节点、经 Kafka 访问其他上下文：[部署](../../operate/topology.md)。
