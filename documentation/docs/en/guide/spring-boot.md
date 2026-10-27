---
title: Host on Spring Boot
description: "Run a service on the Spring Boot host: what it assembles from the class path, how it chooses a backend, which beans it takes from the application, and how it checks its configuration."
outline: deep
---

# Host on Spring Boot

A service is a Spring Boot application with its domain modules and the Wow host on the class path. The host finds the models the compiler generated, opens the stores, serves the routes, and runs the subscriptions; the application writes no wiring code. This page shows what the host assembles, and where an application plugs into it.

## The application

The eShop's catalog service is the smallest of its services: an empty application class.

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

Its build depends on the catalog's domain module and on the host:

<!-- sample: example/eshop/catalog/catalog-server/build.gradle.kts#dependencies -->

```kotlin
dependencies {
    implementation(project(":eshop-catalog-domain"))
    implementation(project(":spring-boot"))
    // A Bean Validation provider: the host checks each command's constraints with it (decision D106).
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Actuator's health endpoint, which the host gives the node's readiness and liveness (review R3.5-3): the
    // deployment's probes read /actuator/health/readiness and /actuator/health/liveness.
    implementation("org.springframework.boot:spring-boot-health")
    implementation("org.springframework.boot:spring-boot-actuator-autoconfigure")
    // Traces and metrics on the node's seams (decision D174), through the OpenTelemetry Java agent when the
    // deployment attaches one (deploy/eshop-*: the OpenTelemetry Operator's annotation), a no-op otherwise.
    implementation(project(":observability"))
    // The `postgres` profile: the host runs on PostgreSQL when the application gives it a DataSource, and serves the
    // snapshot queries there, with the indexes the product's state declares.
    implementation(project(":postgres"))
    // The pool the host makes of `spring.datasource.*` in the postgres profile (decision D310).
    runtimeOnly("com.zaxxer:HikariCP")
    // The `mongo` profile: the host runs on MongoDB when the Mongo URI is set (decision D318).
    implementation(project(":mongo"))
    // The `search` profile, beside `mongo`: the products' search on Elasticsearch, over the client Spring Boot's
    // Elasticsearch support makes of `spring.elasticsearch.uris`.
    implementation(project(":elasticsearch"))
    implementation("org.springframework.boot:spring-boot-elasticsearch")
    // The `cosec` profile, behind the CoSec gateway (decisions D295, D296): CoSec's inject mode reads the principal
    // from the token the gateway verified, and wow-cosec's resolver makes it the caller (decision D301).
    implementation(project(":cosec"))
    implementation(libs.cosec.spring.boot.starter)
    implementation(libs.cosec.webflux)
    implementation(libs.cosec.jwt)
    testImplementation("org.springframework:spring-test")
    // The springdoc bridge's acceptance (decision D233): the catalog's routes in springdoc's documents and Swagger UI,
    // the document checked against the OpenAPI 3.1 schema.
    testImplementation("org.springdoc:springdoc-openapi-starter-webflux-ui")
    testImplementation(libs.json.schema.validator)
}
```

The host is the artifact `me.ahoo.wow:wow-spring-boot`, a WebFlux application. Its version, and every other Wow module's, comes from the BOM `me.ahoo.wow:wow-bom`.

## What the class path decides

The host wires each part when its module is on the class path, and the part's own setting when it needs one:

| Module | Adds |
| --- | --- |
| `wow-postgres` | The PostgreSQL backend, over the application's `DataSource` |
| `wow-mongo` | The MongoDB backend, over the application's `MongoClient` |
| `wow-kafka` | The Kafka transport, when `wow.kafka.bootstrap-servers` is set: published streams, commands and receipts between contexts ([Kafka](../operate/kafka)) |
| `wow-clickhouse` | The warehouse sink and the datasets' queries |
| `wow-elasticsearch` | The store type `elasticsearch`, for snapshots and their queries, and the sink that indexes event streams for search |
| `wow-observability` | Traces and metrics of the node's work |
| `wow-cosec` | CoSec authentication and authorization of the public routes ([Secure a Deployment](../operate/security)) |
| `wow-cosid` | CosId's id generators |
| `wow-reactor` | Handlers that return Reactor's `Mono` and `Flux` |

Spring Boot Actuator on the class path gives the node's health to the readiness and liveness groups ([Deployment Model](../operate/topology#starting-and-stopping)).

## What the host assembles

- **Models.** Every domain module on the class path carries a binding index. The host loads each module's models, builds them with their domain services from the application's beans, and merges the modules' Manifest fragments into the service's Manifest. `wow.contexts.include` and `wow.contexts.exclude` select the contexts a node serves; by default it serves all of them.
- **Stores.** Each context's log, snapshots and timers, on the backend the host chooses (below).
- **Ports.** The public routes on the application's WebFlux server, and the internal port, `wow.internal.port`, on a server of its own: the cluster channel, wait signals, synchronous calls and reads between services ([Deployment Model](../operate/topology#two-ports)).
- **Subscriptions.** Every processor handler the binding indexes list, and the sinks of the modules on the class path.
- **Lifecycle.** The node starts once its stores are up, its internal port is bound and it has joined its peers, and stops in the reverse order.

## Choose a backend

`wow.backend` chooses where the data lives when the configuration names no store instances:

| `wow.backend` | The node |
| --- | --- |
| `auto`, the default | Takes PostgreSQL when the application has a `DataSource`, MongoDB when it has a `MongoClient`: its own bean, or the one the host makes of `spring.datasource.url` or `spring.mongodb.uri` |
| `memory` | Keeps everything in memory, on purpose: one node, whose data does not outlive it |

With `auto` and neither bean, the node refuses to start, unless a development profile is active (`wow.dev-profiles`) or its internal port binds the loopback interface alone; it then runs in memory and warns. A production node never falls back to memory by accident.

A service whose aggregates keep their data in different stores names store instances under `wow.stores.<name>` (each with its `type`, such as `postgres`, `mongo`, `elasticsearch` or `memory`, and the bean it runs on) and places each aggregate type's log and snapshots on them: `wow.log.store`, `wow.snapshot.stores`, and per type `wow.aggregates.[<context>.<aggregate>].log.store`. [PostgreSQL](../operate/postgresql) shows a layout. A type whose `query.events.store` names an Elasticsearch store has its events indexed there by a sink the host starts by itself ([Elasticsearch](../operate/elasticsearch#store-combinations)).

`wow.ddl` decides whether the stores create their tables, collections and indexes (`auto`, the default), only check them (`validate`), or neither (`none`).

## Beans the application gives

The host takes these from the application when it defines them, and falls back to its own otherwise:

| Bean | Does |
| --- | --- |
| Domain services | The parameters of command functions and processors, by type, or by name with `@Name` |
| `CallerResolver` | Turns the authenticated principal into the caller; anonymous otherwise |
| `CommandAuthorizer` | Decides whether the caller may send a command to an aggregate |
| `CommandTransformer`, `CommandValidator` | Change or check a command before it is decided |
| `GlobalIdGenerator`, `AggregateIdGenerator` | Generate request ids and aggregate ids; per type by `wow.aggregates.[<context>.<aggregate>].id-generator` |
| `FailureClassifier` | Classifies the failures of subscription handlers ([Failures and Retries](./failures)) |
| A Bean Validation provider (`spring-boot-starter-validation`) | Checks the Jakarta constraints of every command body at admission, answering `wow.command.invalid` (400) ([Write an Aggregate](./aggregates#check-its-shape)) |

## Configuration is checked

Spring binds the keys it knows and ignores the rest, so a misspelled key would silently do nothing. The host compares every `wow.*` key of the environment with the configuration metadata on the class path when it starts:

- An unknown key stops the start, naming it: a mistyped setting would leave a default in place, and some defaults allow everything. A key of an earlier version is named with the key that took its place, and a key of a module that is not on the class path (`wow.cosid.*` without wow-cosid) with that module.
- Under a map of settings, such as `wow.aggregates`, `wow.stores`, `wow.callers` and `wow.peers`, each entry's own keys are checked to their leaves.

An environment variable under a Wow group, such as `WOW_KAFKA_…`, is checked the same way. [Configuration Keys](../reference/configuration) lists every key.

## Run it

`./gradlew bootRun`, or the application's main function, starts a node. Without a `DataSource` or `MongoClient` and without Kafka, it runs on one machine with the data in memory, in a development profile or on a loopback-only internal port. [Deployment Model](../operate/topology) describes what a production service needs.
