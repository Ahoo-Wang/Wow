---
title: Add Wow to an Existing Service
description: Lay a service out in api, domain and server modules, apply the Gradle plugin, name the context, and host the domain in a Spring Boot application.
outline: deep
---

# Add Wow to an Existing Service

The [Quickstart](./quickstart) fits a service in one module. A real service splits into three, so that other services can depend on its contract without its domain, and its domain on the kernel without the host. This page sets that up, with the eShop's catalog as the example; every service of the [eShop](https://github.com/Ahoo-Wang/Wow/tree/main/example/eshop) has the same shape.

## What you need

- JDK 21.
- Gradle 9 and the Kotlin JVM plugin. Java sources work too: KSP reads them within the Kotlin compilation ([Write the Domain in Java](../guide/java)).
- Spring Boot 4 for the server, and Jackson 3 (`tools.jackson`) in domain code that touches JSON.

## Lay out the service

| Module | Holds | Depends on |
| --- | --- | --- |
| `catalog-api` | Commands, events and the values they carry: the context's contract | `wow-api` |
| `catalog-domain` | Aggregate roots, states, error codes, sagas, projections and reactors | `catalog-api`, `wow-kernel`; the plugin `me.ahoo.wow` |
| `catalog-server` | The Spring Boot application: the host, a backend, the domain's services as beans | `catalog-domain`, `wow-spring-boot`, a backend |

Other services depend on `catalog-api` only, never on the domain. A domain module depends on the kernel only, never on the runtime or the host.

## Set up the build

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("jvm") version "2.4.21"
        id("me.ahoo.wow") version "10.0.0-alpha.5"
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
wowVersion=10.0.0-alpha.5
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
    // The commands' constraints (@field:Positive, @field:NotBlank); Spring Boot's version, which wow-api brings.
    api("jakarta.validation:jakarta.validation-api")
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

- applies KSP with `wow-compiler` of the plugin's version, so the module's aggregates compile into models and its part of the contract;
- completes that part with the JSON Schemas of the module's types (`generateWowManifestFragment`);
- merges every part on the runtime class path into the service's Manifest, `build/wow/manifest.json` (`mergeWowManifest`), and writes its OpenAPI document (`generateWowOpenApi`);
- checks every build's Manifest against the committed baseline (`checkManifestCompatibility`, part of `check`; `updateManifestBaseline` records it);
- packages the contract peers need into the domain's api module, `META-INF/wow/contract.json`, and compiles `<Context>Contract` there ([What the Compiler Generates](../guide/generated-code#the-contract-code-of-the-api-module)).

The `wow { }` block configures it:

| Property | Default | Means |
| --- | --- | --- |
| `context` | The module's one `@BoundedContext` | The context of the module's aggregates and processors |
| `addCompilerDependency` | `true` | Whether the plugin adds `wow-compiler` to `ksp` |
| `schemaOverrides` | Empty | A JSON Schema per JVM class name, for a type a custom serializer writes |
| `serviceName`, `serviceVersion` | The project's name and version | The service the merged Manifest describes |
| `manifestBaseline` | `manifest/baseline.json` | The committed baseline of the compatibility check |

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

The host brings Spring Boot and WebFlux with it. Choose `wow-mongo` instead of `wow-postgres` for MongoDB, add `wow-kafka` to reach other contexts and `wow-clickhouse` for the warehouse ([Host on Spring Boot](../guide/spring-boot#what-the-class-path-decides)).

## Name the context

A domain module declares its bounded context once, on an object of its own:

<!-- sample: example/eshop/catalog/catalog-domain/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/domain/CatalogBoundedContext.kt -->

```kotlin
package me.ahoo.wow.example.eshop.catalog.domain

import me.ahoo.wow.api.annotation.BoundedContext

/**
 * Names the bounded context of this module's aggregates for the compiler. The name is declared here, once: the api
 * module's generated `EshopCatalogContract.NAME` comes from it (decision D259).
 */
@BoundedContext(name = "eshop-catalog", description = "The eShop catalog: products, their brands and types")
object CatalogBoundedContext
```

The name is part of every type name (`eshop-catalog.product.CreateProduct`), every route (`/eshop-catalog/product/{id}/create-product`), every topic (`wow.eshop-catalog.product.state`) and every table, so choose it once. A context name is lower-case letters, digits and hyphens, starting with a letter: `eshop-catalog`, never `eshop.catalog`, since a dot separates the parts of a type name. The compiler refuses a name that breaks the rule.

## Write the contract and the domain

The api module holds the commands and events, data classes without identity fields, their shape checked by Jakarta constraints ([Write an Aggregate](../guide/aggregates#write-the-commands)). The domain module holds the root, its state and its error codes, the rules that need the state ([Write an Aggregate](../guide/aggregates#write-the-root)), and tests them with the test DSL ([Test Domain Code](../guide/testing)).

## Write the server

The server is an ordinary Spring Boot application. The catalog's needs nothing more than its class:

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

Domain services the command functions take are beans of the application. A `DataSource` or `MongoClient` bean chooses the backend; without either, the host runs in memory only in a development profile or on a loopback-only internal port ([Host on Spring Boot](../guide/spring-boot#choose-a-backend)). A `CallerResolver` bean authenticates callers ([Serve and Call the HTTP API](../guide/http#who-the-caller-is)).

## Run it

`./gradlew :catalog-server:run` starts one node on this machine. Commands, state reads and queries are served as in the [Quickstart](./quickstart#send-commands). Before production, go through [Running Wow](../operate/).
