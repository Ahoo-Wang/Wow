---
title: Quickstart
description: Build a small service in one Gradle module, from the build to a running node that takes commands and answers queries, in about thirty minutes.
outline: deep
---

# Quickstart

In Wow you write the domain as plain Kotlin: commands, events, a state, and an aggregate that decides. The compiler turns them into models and a contract, and the Spring Boot host serves them over HTTP, with no controller or repository to write. This page builds a small bank service in one Gradle module: an `account` aggregate, its test, and a server that keeps its data in memory.

::: warning Pre-release
Wow 10.0.0-beta.1 is a pre-release. Its API, configuration and stored formats may still change before 10.0.0.
:::

## What you need

- JDK 21 or later; JDK 25 to run in production ([The JDK](../operate/kubernetes#the-jdk)).
- Gradle 9 with the Kotlin JVM plugin. The Wow Gradle plugin applies KSP and the Wow compiler.

## Set up the build

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "bank"
```

`build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.4.21"
    // The service in one module: KSP with wow-compiler, the BOM and the kernel of the same version, and the
    // service's Manifest.
    id("me.ahoo.wow.service") version "10.0.0-beta.1"
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("me.ahoo.wow.example.quickstart.BankServerKt")
}

dependencies {
    // The Spring Boot host: auto-configuration, the HTTP routes, the memory backend.
    implementation("me.ahoo.wow:wow-spring-boot")
    // Jakarta Bean Validation: the host checks the commands' constraints (@field:Positive) at admission.
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // The aggregate test DSL, package me.ahoo.wow.test.
    testImplementation("me.ahoo.wow:wow-test")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("me.ahoo.test:fluent-assert-core:1.3.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
```

The plugin is published to Maven Central with its marker, so `pluginManagement` needs `mavenCentral()`. A pre-release has only exact versions there: write `10.0.0-beta.1`, not a range. A real service splits into an api, a domain and a server module ([Add Wow to an Existing Service](./existing-project)); one module is enough to try it.

The plugin also registers an `integrationTest` test suite for the tests that run on real stores in containers: their sources go under `src/integrationTest/kotlin`, and `./gradlew integrationTest` runs them, apart from `check` ([Run it on real stores](../guide/testing-services#run-it-on-real-stores)). The bank needs none: its tests run in memory.

`./gradlew build` also checks the service's Manifest, the contract its clients see, against a baseline committed beside the build file, `manifest/baseline.json`, and fails while there is none. Once the code below is written, record it once and commit it with the code:

```bash
./gradlew updateWowManifestBaseline
```

From then on `check` fails a change the baseline does not record, so the review of each change shows what its clients will see ([The Manifest](../guide/manifest#the-compatibility-check)).

## Write the commands and events

Name the context once, with `@BoundedContext`. Commands and events are data classes. A command carries no aggregate id: the id comes from the route. A deposit and a withdrawal are of a positive amount: that is the command's shape, a Jakarta constraint the host checks before the account decides.

<!-- sample: examples/quickstart/src/main/kotlin/me/ahoo/wow/example/quickstart/Bank.kt -->

```kotlin
package me.ahoo.wow.example.quickstart

import jakarta.validation.constraints.Positive
import me.ahoo.wow.api.annotation.BoundedContext
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.Summary

@BoundedContext(name = "bank", description = "Accounts and their balances")
object BankBoundedContext

@CreateAggregate
@Summary("Open an account")
data class OpenAccount(val owner: String)

@Summary("Deposit money")
data class Deposit(@field:Positive val amount: Long)

@Summary("Withdraw money")
data class Withdraw(@field:Positive val amount: Long)

@Summary("An account was opened")
data class AccountOpened(val owner: String)

@Summary("Money was deposited")
data class Deposited(val amount: Long)

@Summary("Money was withdrawn")
data class Withdrawn(val amount: Long)
```

## Write the state and the aggregate

The state applies each event in an `onSourcing` function. The aggregate decides each command in an `onCommand` function: it returns events, or a `Decision` that rejects the command with an error code, in which case nothing is written. It rejects only what needs its state, a withdrawal the balance does not cover.

<!-- sample: examples/quickstart/src/main/kotlin/me/ahoo/wow/example/quickstart/Account.kt -->

```kotlin
package me.ahoo.wow.example.quickstart

import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.ErrorCode
import me.ahoo.wow.api.decide.reject

class AccountState {
    var owner: String = ""
        private set
    var balance: Long = 0
        private set

    fun onSourcing(event: AccountOpened) {
        owner = event.owner
    }

    fun onSourcing(event: Deposited) {
        balance += event.amount
    }

    fun onSourcing(event: Withdrawn) {
        balance -= event.amount
    }
}

@AggregateRoot
class Account(private val state: AccountState) {
    fun onCommand(command: OpenAccount): AccountOpened = AccountOpened(command.owner)

    fun onCommand(command: Deposit): Deposited = Deposited(command.amount)

    fun onCommand(command: Withdraw): Withdrawn {
        if (command.amount > state.balance) {
            reject(Rejections.INSUFFICIENT_BALANCE, "The balance is ${state.balance}.")
        }
        return Withdrawn(command.amount)
    }

    /** The codes it rejects with, by local name: the Manifest and its answers say `bank.account.insufficient-balance`. */
    object Rejections {
        @ErrorCode(description = "The balance does not cover the withdrawal.", status = 409)
        const val INSUFFICIENT_BALANCE = "insufficient-balance"
    }
}
```

Each function returns the event it produces, and `Withdraw` rejects by throwing a `RejectedException`; the contract reads the events from the return types. [Write an Aggregate](../guide/aggregates) covers every rule.

## Test it

The compiler generated `accountModel` beside `Account`. `aggregateSpec` runs it on the real kernel with an in-memory log: given these events, when this command, expect these events and this state.

<!-- sample: examples/quickstart/src/test/kotlin/me/ahoo/wow/example/quickstart/AccountSpec.kt -->

```kotlin
package me.ahoo.wow.example.quickstart

import me.ahoo.test.asserts.assert
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test

class AccountSpec {
    private val model = accountModel()

    @Test
    fun `opening an account names its owner`() = aggregateSpec(model, id = "alice") {
        whenCommand(OpenAccount("Alice")) {
            expectEvents(AccountOpened("Alice"))
            expectState { owner.assert().isEqualTo("Alice") }
        }
    }

    @Test
    fun `a deposit adds to the balance`() = aggregateSpec(model, id = "alice") {
        given(AccountOpened("Alice"))
        whenCommand(Deposit(100)) {
            expectEvents(Deposited(100))
            expectState { balance.assert().isEqualTo(100) }
        }
    }

    @Test
    fun `a withdrawal the balance does not cover is rejected`() = aggregateSpec(model, id = "alice") {
        given(AccountOpened("Alice"), Deposited(100))
        whenCommand(Withdraw(150)) { expectRejected(Account.Rejections.INSUFFICIENT_BALANCE) }
        whenCommand(Withdraw(30)) {
            expectEvents(Withdrawn(30))
            expectState { balance.assert().isEqualTo(70) }
        }
    }
}
```

```bash
./gradlew test
```

[Test Domain Code](../guide/testing) lists every expectation.

## Run the service

The server is a Spring Boot application. The host finds the generated models on the class path and serves them:

<!-- sample: examples/quickstart/src/main/kotlin/me/ahoo/wow/example/quickstart/BankServer.kt -->

```kotlin
package me.ahoo.wow.example.quickstart

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(proxyBeanMethods = false)
class BankServer

fun main(args: Array<String>) {
    runApplication<BankServer>(*args)
}
```

`src/main/resources/application.yaml` runs one node with its data in memory, and serves the snapshot queries from memory too:

<!-- sample: examples/quickstart/src/main/resources/application.yaml -->

```yaml
spring:
  application:
    name: bank-service
server:
  port: 8080
wow:
  # One node, its data in memory: for trying the service out, not for production. The `mongo` profile keeps it on
  # MongoDB, the `postgres` profile on PostgreSQL (application-mongo.yaml, application-postgres.yaml).
  internal:
    port: 9090
---
# On this machine alone, where Spring Boot detects no cloud platform: the internal port on the loopback interface, and
# the node serves the anonymous caller. A deployment binds the address its nodes reach.
spring:
  config:
    activate:
      on-cloud-platform: none
wow:
  internal:
    host: 127.0.0.1
```

```bash
./gradlew run
```

With `wow-mongo` on the class path, or `wow-postgres` and a connection pool (`com.zaxxer:HikariCP`, from which the host makes the `DataSource`), `--args='--spring.profiles.active=mongo'` (or `postgres`) keeps the data on MongoDB or PostgreSQL instead, as the module's `application-mongo.yaml` and `application-postgres.yaml` set it ([MongoDB](../operate/mongodb), [PostgreSQL](../operate/postgresql)).

The node serves its routes on port 8080 and its internal port on 9090. It warns that its data is in memory, and that every request is the anonymous caller until the application authenticates callers ([Serve and Call the HTTP API](../guide/http#who-the-caller-is)): only a node in development, here one whose internal port is on the loopback interface, starts without a caller resolver.

## Send commands

Each command has a route, `POST /<context>/<aggregate>/<id>/<command>`, the command's name in kebab case:

```bash
curl -X POST localhost:8080/bank/account/alice/open-account \
  -H 'Content-Type: application/json' -d '{"owner":"Alice"}'
curl -X POST localhost:8080/bank/account/alice/deposit \
  -H 'Content-Type: application/json' -d '{"amount":100}'
curl -X POST localhost:8080/bank/account/alice/withdraw \
  -H 'Content-Type: application/json' -d '{"amount":150}'
```

A command answers its result, `"status":"succeeded"` with the account's new `version`, once the snapshot holds its commit, so a read right after it sees the change. The withdrawal is rejected: the answer is an RFC 9457 problem, status 409, with `"code":"bank.account.insufficient-balance"`.

A deposit of nothing never reaches the account:

```bash
curl -X POST localhost:8080/bank/account/alice/deposit \
  -H 'Content-Type: application/json' -d '{"amount":0}'
```

It answers status 400 with `"code":"wow.command.invalid"` and its `violations`, `[{"path":"amount","code":"positive","message":"must be greater than 0"}]`.

## Read and query

Read one account's state, then query all of them by balance:

```bash
curl localhost:8080/bank/account/alice/state
curl -X POST localhost:8080/bank/account/snapshot/query \
  -H 'Content-Type: application/json' \
  -d '{"kind":"list","filter":{"op":"gte","field":"state.balance","value":100}}'
```

The first answers the version and the state, `{"balance":100,"owner":"Alice"}`; the second a list of snapshots, `alice`'s among them. [Query Snapshots](../guide/queries) describes filters, sorts, pages and the other kinds of query. On the memory backend the queries are served from memory by default, and `wow.query.memory.enabled: false` turns that off; PostgreSQL and MongoDB serve them themselves.

## Send commands from code

Application code sends command objects through the host's `CommandClient`, with the same admission as the HTTP routes:

<!-- sample: examples/quickstart/src/main/kotlin/me/ahoo/wow/example/quickstart/Teller.kt -->

```kotlin
package me.ahoo.wow.example.quickstart

import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.wait
import me.ahoo.wow.api.result.CommandResult
import org.springframework.stereotype.Service

/** Deposits from application code, through the host's [CommandClient]: the same admission as the HTTP routes. */
@Service
class Teller(private val commands: CommandClient) {
    suspend fun deposit(accountId: String, amount: Long): CommandResult = commands.wait(Deposit(amount), accountId)
}
```

`wait` answers once the command's wait ends, the snapshot by default; `send` answers once it is accepted. [Call Other Services](../guide/calling-services) shows the rest.

## Run several services locally

A system of several services runs in one JVM, for development, a front end against its back ends, or a demo. `runWowLocal` from `wow-test-spring-boot` starts each service serving its own contexts, all on the memory backend and sharing one set of topics in memory. A saga's commands to another service and the streams the services publish then flow between them as they do on Kafka. Write its `main` in a source set that has every service on its class path, such as the `integrationTest` suite:

<!-- sample: fragment -->

```kotlin
fun main() = runWowLocal {
    service<InventoryServer>("eshop-inventory-service", listOf("eshop-inventory"), 8084, 9094)
    service<OrderingServer>("eshop-ordering-service", listOf("eshop-ordering"), 8083, 9093)
    service<BasketServer>("eshop-basket-service", listOf("eshop-basket"), 8082, 9092)
}
```

- Each service runs in the `dev` profile, so a request names its caller in the `Wow-*` headers. It is named by `spring.application.name` and serves its own contexts alone (`wow.contexts.include`); `properties` add its own settings.
- The services start in the order listed, and each calls the ones listed before it: its `wow.peers.<context>.url` names their internal ports. List a called service before its callers, here the ordering before the basket, whose checkout calls it.
- The ports are each service's own; `-Dwow-local.ports=random` lets the system choose every one, and a service may then name none.
- Once every service listens, it prints one line with their public ports, `wow-local ready {"eshop-inventory-service":8084,…}`. With `-Dwow-local.exit-on-stdin-eof=true` the JVM exits when its standard input closes, so a process that started it, a front end's end-to-end test say, never leaves it running.
- `wowLocal { … }` starts the same services from a test and gives them back (`servers`, `publicPorts`) to close.

A task of the build runs it:

<!-- sample: fragment -->

```kotlin
tasks.register<JavaExec>("runLocal") {
    classpath = sourceSets["integrationTest"].runtimeClasspath
    mainClass.set("com.example.shop.LocalKt")
}
```

## Where to go next

- **The concepts.** [Architecture Overview](../concepts/) explains what the host did for you.
- **A real service.** [Add Wow to an Existing Service](./existing-project) lays a service out in modules.
- **The guide.** [Developer Guide](../guide/) shows each task: sagas, projections, queries, tests.
- **The full example.** The [eShop](https://github.com/Ahoo-Wang/Wow/tree/main/examples/eshop) is five services on PostgreSQL, Kafka and ClickHouse, with sagas between contexts, scheduled commands, datasets and a web UI.
- **Deploy it.** [Kubernetes](../operate/kubernetes) installs a service with Wow's Helm chart, the values its build generates and your own.
- **This sample** is [`examples/quickstart`](https://github.com/Ahoo-Wang/Wow/tree/main/examples/quickstart), built and tested with every change.
