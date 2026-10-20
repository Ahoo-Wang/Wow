---
title: Write a V10 Service
description: Write a new Wow V10 service in its three modules, from the Gradle build to a running node that answers its first command.
outline: deep
---

# Write a V10 Service

This page walks through a new Wow V10 service laid out as a real one is, in three modules, from an empty Gradle build to a node that takes its first command over HTTP. To try V10 in one module first, start with the [Quick Start](./quickstart.md). The rest of the V10 guide goes deeper into each step:

| Page | Covers |
| --- | --- |
| [Programming Model](./programming-model.md) | Aggregates, commands, events, rejections, sagas, projections, reactors, scheduled commands, other contexts |
| [What the Compiler Generates](./generated-code.md) | The model factories, the `ModelProvider`, the Manifest fragments, the routes |
| [Testing](./testing.md) | `aggregateSpec` and where it lives, saga tests, server tests |
| [HTTP API](./http-api.md) | Command routes, `Wow-Wait`, state reads, query routes, datasets |
| [Configuration Keys](./configuration.md) | Every `wow.*` key with its default |
| [Deployment](./deployment.md) | PostgreSQL or MongoDB, the internal port, Kafka, ClickHouse, personal data, tenants |

::: warning V10 is a pre-release
V10 is developed on the [`v10` branch](https://github.com/Ahoo-Wang/Wow/tree/v10). Its first pre-release, `10.0.0-alpha.3`, is for evaluation only: it carries no compatibility promise, and migrating 9.x data is unfinished (see the [Quick Start](./quickstart.md)). Its modules are on Maven Central as `me.ahoo.wow:wow-*`, with the BOM `me.ahoo.wow:wow-bom` and the Gradle plugin `me.ahoo.wow`. The rest of this guide (outside this V10 section) describes 9.x.
:::

## What you need

- JDK 21. Every V10 module builds and runs on it.
- Kotlin with KSP. The Gradle plugin applies KSP for you. Java sources work too: KSP reads them, but the build still needs the Kotlin Gradle plugin.
- Spring Boot 4 for the server, and Jackson 3 (`tools.jackson`) in domain code that touches JSON.

## Lay out the service

A service is three Gradle modules. The example below is a `catalog` context; each context of the in-repo eShop, [`example/eshop`](https://github.com/Ahoo-Wang/Wow/tree/v10/example/eshop) (`catalog`, `basket`, `ordering`, `inventory`, `payment`), has the same shape.

| Module | Holds | Depends on |
| --- | --- | --- |
| `catalog-api` | Commands, events and value types: the context's contract | `wow-api` |
| `catalog-domain` | Aggregate roots, states, rejection codes, sagas, projections and reactors | `catalog-api`, `wow-kernel`; the plugin `me.ahoo.wow` |
| `catalog-server` | The Spring Boot application: the host, a backend, the domain's services as beans | `catalog-domain`, `wow-spring-boot`, a backend module |

Other services depend on `catalog-api` only, never on the domain. A domain module depends on the kernel only, never on the runtime or the host.

## Set up Gradle

`settings.gradle.kts`:

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

`gradle.properties` names the Wow version once:

```properties
wowVersion=10.0.0-alpha.3
```

`catalog-api/build.gradle.kts`:

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

`catalog-domain/build.gradle.kts` applies the Gradle plugin:

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

Once the Kotlin JVM plugin is applied, `me.ahoo.wow`:

- applies KSP with `me.ahoo.wow:wow-compiler` of the plugin's version, in `generate` mode, and names the module's Manifest fragment after the project;
- registers `generateWowManifestFragment`, which completes the fragment with the JSON schemas of the module's types;
- registers `mergeWowManifest`, which merges every fragment on the runtime class path into `build/wow/manifest.json`, published as the `wow-manifest` variant;
- registers `collectWowPeers` and the `wowPeers` configuration, which put the Manifests of other services on the class path (see [Other contexts](./programming-model.md#other-contexts)).

The `wow { }` block configures it:

| Property | Default | Means |
| --- | --- | --- |
| `context` | The module's one `@BoundedContext` | The context of the module's aggregates and processors (the KSP option `wow.context`) |
| `addCompilerDependency` | `true` | Whether the plugin adds `wow-compiler` to `ksp` |
| `schemaOverrides` | Empty | A JSON Schema per JVM class name, for a type written by a custom serializer |
| `serviceName` | The project's name | The service the merged `manifest.json` describes |
| `serviceVersion` | The project's version | The service's version in it |

`catalog-server/build.gradle.kts`:

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

The host brings `spring-boot-starter` and WebFlux with it. Add `wow-mongo` instead of `wow-postgres` for MongoDB, `wow-kafka` to reach other contexts, and `wow-clickhouse` for the ClickHouse Sink and datasets. A server that takes other services' Manifests as peers applies `me.ahoo.wow` too, for its `wowPeers` configuration.

### Without the plugin

The plugin cannot be applied inside the build that produces it, so the examples in the Wow repository apply KSP by hand and pass the plugin's options themselves:

```kotlin
plugins {
    alias(libs.plugins.ksp)
}

ksp {
    arg("wow.model", "generate")
    arg("wow.manifest.module", project.name)
}

// The plugin's generateWowManifestFragment: the fragment completed with the schemas of the module's types.
apply(from = rootProject.file("example/wow-manifest-fragment.gradle.kts"))

dependencies {
    api(project(":catalog-api"))
    api(project(":kernel"))
    ksp(project(":compiler"))
    testImplementation(project(":test-dsl"))
}
```

[`example/wow-manifest-fragment.gradle.kts`](https://github.com/Ahoo-Wang/Wow/blob/v10/example/wow-manifest-fragment.gradle.kts) runs what the plugin's `generateWowManifestFragment` runs, and applies [`example/wow-contract.gradle.kts`](https://github.com/Ahoo-Wang/Wow/blob/v10/example/wow-contract.gradle.kts), which packages the domain's contract into its api modules as the plugin does. The eShop's servers apply [`example/eshop/wow-manifest-compatibility.gradle.kts`](https://github.com/Ahoo-Wang/Wow/blob/v10/example/eshop/wow-manifest-compatibility.gradle.kts) after setting `extra["wowService"]`, in place of `mergeWowManifest` and the Manifest compatibility check. The KSP options are:

| Option | Means |
| --- | --- |
| `wow.model` | `generate` compiles the models, the binding index and the fragment; `report` only writes `META-INF/wow/model-report.json`; unset, the processor does nothing |
| `wow.manifest.module` | The fragment's file name, `META-INF/wow/manifest/<module>.json`; the context's name when unset |
| `wow.context` | The context of the module; the module's one `@BoundedContext` when unset |

Inside the repository, Gradle runs with `-Pwow.scope=v10` to build only the V10 projects.

## Name the context

A domain module declares its bounded context once, on an object of its own:

```kotlin
package com.example.catalog.domain

import me.ahoo.wow.api.annotation.BoundedContext

@BoundedContext(name = "catalog", description = "Products and their prices")
object CatalogBoundedContext
```

The name is part of every type name (`catalog.product.ChangePrice`), every route (`/catalog/product/{id}/change-price`), every topic (`wow.catalog.product.state`) and every table. Choose it once:

- **Context names** match `[a-z][a-z0-9-]*`: lower case, digits and hyphens, starting with a letter (decision D169). A dot is not allowed: it separates the parts of a type name such as `catalog.product.ChangePrice`, so `eshop.catalog` would be ambiguous. Use `eshop-catalog`. The compiler fails on a `@BoundedContext` (or a `wow.context` option) that breaks the rule, and so does reading a Manifest, a module's fragment or a peer's:

  ```text
  @BoundedContext: context name "eshop.catalog" is not valid: a context name is lower-case letters, digits and hyphens, starting with a letter ([a-z][a-z0-9-]*), such as eshop-catalog; a dot separates the context from the aggregate in type names such as context.aggregate.Command (decision D169)
  ```

- **Aggregate names** are the root's `@Name`, or its simple name in `snake_case` (`Product` is `product`, `PriceList` is `price_list`).
- **Command and event names** are `<context>.<aggregate>.<Name>`, the class's simple name unless `@Name` gives another.
- **Processor and subscription names** are `<processor>.<function>`, such as `PriceSaga.onPriceChanged`, the class's `@Name` or simple name.

The compiler needs the context to write the fragment: either one `@BoundedContext` in the module, or the plugin's `wow { context = "catalog" }`. The generated factories take the context as a parameter all the same (see [What the Compiler Generates](./generated-code.md#model-factories)).

## Write the contract

`catalog-api` holds the commands, the events and the values they carry. A command carries no identity field: the aggregate's id comes from the route (or from the saga that sends it), the owner and the space from the caller.

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

## Write the aggregate

`catalog-domain` holds the state, the root and the codes it rejects with:

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

object ProductRejections {
    @ErrorCode(description = "A price is never negative.")
    const val NEGATIVE_PRICE = "catalog.product.negative-price"
}

/** Every property has a value before the first event: the kernel serializes the state as it is. */
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
            Decision.reject(ProductRejections.NEGATIVE_PRICE, "${command.price} is negative.")
        command.price.compareTo(state.price) == 0 -> Decision.NoOp
        else -> Decision.events(PriceChanged(command.price))
    }
}
```

Functions named `onCommand` and `onSourcing` need no annotation. `@OnCommand(produces)` tells the Manifest which events a function returning `Decision` can produce. A rejection is a value, not an exception: nothing is written, and the caller gets the code. [Programming Model](./programming-model.md) has the full rules.

## Test it

`aggregateSpec` runs the generated model on the real kernel with an in-memory log:

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
        whenCommand(ChangePrice(BigDecimal("-1"))) { expectRejected(ProductRejections.NEGATIVE_PRICE) }
    }

    @Test
    fun `listing a product creates it`() = aggregateSpec(model, id = "apple") {
        whenCommand(ListProduct("Apple", BigDecimal("2.50"))) { expectEvents(ProductListed("Apple", BigDecimal("2.50"))) }
    }
}
```

`productModel` is the factory the compiler generated beside `Product`. Write the test functions so they return `Unit`, as above: JUnit skips a test method that returns a value. [Testing](./testing.md) lists every expectation.

## Write the server

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

`src/main/resources/application.yaml`:

```yaml
spring:
  application:
    name: catalog-service
server:
  port: 8080
wow:
  # One node, its data in memory, for trying the service out.
  backend: memory
  internal:
    port: 9090
```

The host finds the domain's binding index on the class path, builds the models with the application's beans, merges the Manifest and serves its routes. A domain service that a command function or a processor takes as a parameter is a bean of the application, found by type (and by `@Name` when it carries one). On PostgreSQL, the application declares a `DataSource` bean and sets `wow.backend: postgres` (see [Deployment](./deployment.md)).

## Run it and send a command

```bash
./gradlew :catalog-server:run
```

The node serves the public routes on port 8080 and the internal port on 9090. It warns that it runs on the memory backend, and that every request is served as the anonymous caller until the application declares a `CallerResolver` bean (see [HTTP API](./http-api.md#callers)).

```bash
curl -X POST localhost:8080/catalog/product/apple/list-product \
  -H 'Content-Type: application/json' -d '{"name":"Apple","price":2.50}'
curl -X POST localhost:8080/catalog/product/apple/change-price \
  -H 'Content-Type: application/json' -d '{"price":3.00}'
curl localhost:8080/catalog/product/apple/state
curl localhost:8080/wow/manifest
```

A command answers its `CommandResult` once the snapshot holds the commit, so the state read right after it sees the new price. A rejected command is an RFC 9457 problem with the type `urn:wow:error:catalog.product.negative-price` and the status of its `@ErrorCode` (422 by default).

## Where to go next

- Add a saga, a projection or a scheduled command: [Programming Model](./programming-model.md).
- Look at what the build generated: [What the Compiler Generates](./generated-code.md).
- Run several nodes on PostgreSQL, reach another context over Kafka: [Deployment](./deployment.md).
