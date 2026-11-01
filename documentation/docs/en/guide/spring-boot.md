---
title: Host on Spring Boot
description: "Run a service on the Spring Boot host: what it assembles from the class path, how it chooses a backend, which beans it takes from the application, and how it checks its configuration."
outline: deep
---

# Host on Spring Boot

A service is a Spring Boot application with its domain modules and the Wow host on the class path. The host finds the models the compiler generated, opens the stores, serves the routes, and runs the subscriptions; the application writes no wiring code. This page shows what the host assembles, and where an application plugs into it.

## The application

The eShop's catalog service is the smallest of its services: an empty application class.

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

Its build depends on the catalog's domain module and on the host:

<!-- sample: examples/eshop/catalog/catalog-server/build.gradle.kts#dependencies -->

```kotlin
dependencies {
    implementation(project(":eshop-catalog-domain"))
    implementation("me.ahoo.wow:wow-spring-boot")
    // Swagger UI over the service's routes (/swagger-ui.html): the host merges Wow's document into springdoc's.
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui")
    // A Bean Validation provider: the host checks each command's constraints with it.
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Actuator's health endpoint, which the host gives the node's readiness and liveness: the
    // deployment's probes read /actuator/health/readiness and /actuator/health/liveness.
    implementation("org.springframework.boot:spring-boot-health")
    implementation("org.springframework.boot:spring-boot-actuator-autoconfigure")
    // Traces and metrics on the node's seams, through the OpenTelemetry Java agent when the
    // deployment attaches one (deploy/eshop-*: the OpenTelemetry Operator's annotation), a no-op otherwise.
    implementation("me.ahoo.wow:wow-observability")
    // The `postgres` profile: the host runs on PostgreSQL when the application gives it a DataSource, and serves the
    // snapshot queries there, with the indexes the product's state declares.
    implementation("me.ahoo.wow:wow-postgres")
    // The pool the host makes of `spring.datasource.*` in the postgres profile.
    runtimeOnly("com.zaxxer:HikariCP")
    // The `mongo` profile: the host runs on MongoDB when the Mongo URI is set.
    implementation("me.ahoo.wow:wow-mongo")
    // The `search` profile, MongoDB with the products' search on Elasticsearch, over the client Spring Boot's
    // Elasticsearch support makes of `spring.elasticsearch.uris`.
    implementation("me.ahoo.wow:wow-elasticsearch")
    implementation("org.springframework.boot:spring-boot-elasticsearch")
    // The `cosec` profile, behind the CoSec gateway: CoSec's gateway mode reads the principal the gateway verified
    // from its CoSec-Identity header, and wow-cosec's resolver makes it the caller.
    implementation("me.ahoo.wow:wow-cosec")
    implementation(libs.cosec.spring.boot)
    implementation(libs.cosec.webflux)
    testImplementation("me.ahoo.wow:wow-test-spring-boot")
    // The springdoc bridge's acceptance: the document checked against the OpenAPI 3.1 schema.
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
| `wow-cosec` | CoSec authentication and authorization of the public routes ([Secure a Deployment](../operate/security)); experimental in 10.0, following CoSec 8's pre-release |
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
| `auto`, the default | Takes PostgreSQL when the application has a `DataSource`, MongoDB when it has a `MongoClient`: its own bean, or the one the host makes of `spring.datasource.url` or `spring.mongodb.uri`. With both, it refuses to start until `wow.backend` names one |
| `postgres`, `mongo`, or a community store provider's type | Takes that backend, and refuses to start when the application lacks what it needs |
| `memory` | Keeps everything in memory, on purpose: one node, whose data does not outlive it |

With `auto` and neither bean, the node refuses to start, unless a development profile is active (`wow.dev-profiles`) or its internal port binds the loopback interface alone; it then runs in memory and warns. A production node never falls back to memory by accident.

A service whose aggregates keep their data in different stores names store instances under `wow.stores.<name>` (each with its `type`, such as `postgres`, `mongo`, `elasticsearch` or `memory`, and the bean it runs on) and places each aggregate type's log and snapshots on them: `wow.log.store`, `wow.snapshot.stores`, and per type `wow.aggregates.[<context>.<aggregate>].log.store`. [PostgreSQL](../operate/postgresql) shows a layout. A type whose `query.events.store` names an Elasticsearch store has its events indexed there by a sink the host starts by itself ([Elasticsearch](../operate/elasticsearch#store-combinations)). [Choose the Stores](../operate/choosing-storage) says which store to use for what: MongoDB by default, and a search copy on Elasticsearch beside it.

`wow.ddl` decides whether the stores create their tables, collections and indexes (`auto`, the default), only check them (`validate`), or neither (`none`).

## Beans the application gives

The host takes these from the application when it defines them, and falls back to its own otherwise:

| Bean | Does |
| --- | --- |
| Domain services | The parameters of command functions and processors, by type, or by name with `@Name` |
| `CallerResolver` | Turns the authenticated principal into the caller. Without one, the host reads the development headers in a development profile, serves the anonymous caller on a loopback-only internal port, and does not start anywhere else |
| `CommandAuthorizer` | Says whether it objects to the caller sending a command to an aggregate |
| `CommandTransformer`, `CommandValidator` | Change or check a command before it is decided |
| `GlobalIdGenerator`, `AggregateIdGenerator` | Generate request ids and aggregate ids; per type by `wow.aggregates.[<context>.<aggregate>].id-generator` |
| `FailureClassifier` | Classifies the failures of subscription handlers ([Failures and Retries](./failures)) |
| A Bean Validation provider (`spring-boot-starter-validation`) | Checks the Jakarta constraints of every command body at admission, answering `wow.command.invalid` (400) ([Write an Aggregate](./aggregates#check-its-shape)) |
| `Observer` | Watches the node's work: its stages and what it notices, such as a refusal |

Most of these hooks are lists: the host takes every bean of the type, in the beans' order (`@Order`, `Ordered` or `@Priority`, the one ordering there is). The authorizers, the transformers, the validators, the observers, the failure classifiers, and the query policies, filters and observers are such lists:

- **Authorizers are all asked.** A command is allowed only when every `CommandAuthorizer` allows it; the first that denies it, in their order, is the answer, and the rest are not asked. Allowing means having no objection, not overruling the others, and there is no abstaining: an authorizer of one concern, such as the commands a service identity may call (`caller.service`), allows every command outside it. With `wow.cosec.authorizer.enabled`, CoSec's authorizer is one more of them. Without any, a command is allowed, except the system commands of Wow's built-in aggregates, a scheduled job's and the snapshot rebuild's, which the public port then refuses ([Authorize commands](../operate/security#authorize-commands)).
- **Observers are all told.** Every `Observer` sees each stage and event in their order. One that fails is logged and left out of that call; it never fails the command, nor keeps the others from being told.

A few are single seams the node takes one bean of: the `CallerResolver`, the `GlobalIdGenerator`, the `MaskStrategyResolver`, the `DatasetVisibility`, the `CommandCaller` and the `QueryCountCache`. Two beans of one fail the start, naming the seam and both beans. Mark the one the node should take `@Primary`: that is the one way to pick among several.

## Configuration is checked

Spring binds the keys it knows and ignores the rest, so a misspelled key would silently do nothing. The host compares every `wow.*` key of the environment with the configuration metadata on the class path when it starts:

- An unknown key stops the start, naming it: a mistyped setting would leave a default in place, and some defaults allow everything. A key of an earlier version is refused as any other is, and the message points at the migration guide's configuration page, which maps it to today's; a key of a module that is not on the class path (`wow.cosid.*` without wow-cosid) with that module.
- Under a map of settings, such as `wow.aggregates`, `wow.stores`, `wow.callers` and `wow.peers`, each entry's own keys are checked to their leaves.

An environment variable under a Wow group, such as `WOW_KAFKA_…`, is checked the same way. [Configuration Keys](../reference/configuration) lists every key.

## Run it

`./gradlew bootRun`, or the application's main function, starts a node. Without a `DataSource` or `MongoClient`, it keeps the data in memory, which only a development profile or a loopback-only internal port allows. Without Kafka, it starts anywhere as long as nothing it does needs topics, and says at startup that it publishes no stream; in development it keeps its topics in its JVM instead ([When a service needs Kafka](../operate/kafka#when-a-service-needs-kafka)). [Deployment Model](../operate/topology) describes what a production service needs.
