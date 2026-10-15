---
title: V10 Quick Start
description: Try Wow 10.0.0-alpha.2 in one module, from the Gradle build to a running service that takes commands and answers queries.
outline: deep
---

# V10 Quick Start

Wow V10 is the next major version of Wow: you write the domain as plain Kotlin classes (commands, events, a state and an aggregate that decides), and the compiler turns them into models and a Manifest that a Spring Boot host serves over HTTP. It is a rewrite on a new kernel, log and cluster, so its API, configuration and storage differ from 9.x.

::: danger 10.0.0-alpha.2 is for evaluation only

- **No compatibility promise.** An alpha may change the API, the configuration, the HTTP routes and the storage and wire formats of the alpha before it.
- **No migration from 9.x yet.** Importing 9.x event streams is unfinished (milestone M8): do not migrate production data to an alpha.
- **Try it, and tell us.** Use it to evaluate V10 and report what does not work in the [issues](https://github.com/Ahoo-Wang/Wow/issues). The rest of this site, outside the V10 section, describes 9.x.

:::

This page builds a small bank service in one Gradle module: an `account` aggregate, its test, and a server on the memory backend. The [eShop](#where-to-go-next) is the full example.

## What you need

- JDK 21.
- Gradle 9 with the Kotlin JVM plugin. The Wow Gradle plugin applies KSP and the Wow compiler for you.

## Set up Gradle

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
    // Applies KSP with wow-compiler of the same version, and assembles the service's Manifest.
    id("me.ahoo.wow") version "10.0.0-alpha.2"
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("me.ahoo.wow.example.quickstart.BankServerKt")
}

dependencies {
    // The V10 BOM: one version for every Wow module.
    implementation(platform("me.ahoo.wow:wow-bom:10.0.0-alpha.2"))
    // The kernel the generated models build on.
    implementation("me.ahoo.wow:wow-kernel")
    // The Spring Boot host: auto-configuration, the HTTP routes, the memory backend.
    implementation("me.ahoo.wow:wow-spring-boot")
    // The aggregate test DSL, package me.ahoo.wow.test.
    testImplementation("me.ahoo.wow:wow-test")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("me.ahoo.test:fluent-assert-core:1.2.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
```

The plugin is published to Maven Central with its marker, so `pluginManagement` needs `mavenCentral()`. Maven Central has only exact versions of an alpha: write `10.0.0-alpha.2`, not a range. A real service splits into an `-api`, a `-domain` and a `-server` module ([Write a V10 Service](./index.md#lay-out-the-service)); one module is enough to try V10.

## Write the commands and events

A context is named once, with `@BoundedContext`. Commands and events are plain classes; a command carries no aggregate id, which comes from the route.

<!-- sample: src/main/kotlin/me/ahoo/wow/example/quickstart/Bank.kt -->

```kotlin
package me.ahoo.wow.example.quickstart

import me.ahoo.wow.api.annotation.BoundedContext
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.Summary

@BoundedContext(name = "bank", description = "Accounts and their balances")
object BankBoundedContext

@CreateAggregate
@Summary("Open an account")
data class OpenAccount(val owner: String)

@Summary("Deposit money")
data class Deposit(val amount: Long)

@Summary("Withdraw money")
data class Withdraw(val amount: Long)

@Summary("An account was opened")
data class AccountOpened(val owner: String)

@Summary("Money was deposited")
data class Deposited(val amount: Long)

@Summary("Money was withdrawn")
data class Withdrawn(val amount: Long)
```

## Write the state and the aggregate

The state applies events in its `onSourcing` functions. The aggregate decides each command in an `onCommand` function: it returns the events, or a `Decision` that may reject the command with an error code. A rejection writes nothing.

<!-- sample: src/main/kotlin/me/ahoo/wow/example/quickstart/Account.kt -->

```kotlin
package me.ahoo.wow.example.quickstart

import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.ErrorCode
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.decide.Decision

object AccountRejections {
    @ErrorCode(description = "An amount is always positive.")
    const val INVALID_AMOUNT = "bank.account.invalid-amount"

    @ErrorCode(description = "The balance does not cover the withdrawal.", status = 409)
    const val INSUFFICIENT_BALANCE = "bank.account.insufficient-balance"
}

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

    @OnCommand(produces = [Deposited::class])
    fun onCommand(command: Deposit): Decision =
        if (command.amount <= 0) {
            Decision.reject(AccountRejections.INVALID_AMOUNT, "${command.amount} is not positive.")
        } else {
            Decision.events(Deposited(command.amount))
        }

    @OnCommand(produces = [Withdrawn::class])
    fun onCommand(command: Withdraw): Decision = when {
        command.amount <= 0 ->
            Decision.reject(AccountRejections.INVALID_AMOUNT, "${command.amount} is not positive.")

        command.amount > state.balance ->
            Decision.reject(AccountRejections.INSUFFICIENT_BALANCE, "The balance is ${state.balance}.")

        else -> Decision.events(Withdrawn(command.amount))
    }
}
```

`@OnCommand(produces)` tells the Manifest which events a function returning `Decision` can produce. [Programming Model](./programming-model.md) has the full rules.

## Test it

The compiler generated `accountModel` beside `Account`. `aggregateSpec` runs it on the real kernel with an in-memory log:

<!-- sample: src/test/kotlin/me/ahoo/wow/example/quickstart/AccountSpec.kt -->

```kotlin
package me.ahoo.wow.example.quickstart

import me.ahoo.test.asserts.assert
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test

class AccountSpec {
    private val model = accountModel("bank")

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
        whenCommand(Withdraw(150)) { expectRejected(AccountRejections.INSUFFICIENT_BALANCE) }
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

[Testing](./testing.md) lists every expectation.

## Run the service

The server is a Spring Boot application. The host finds the generated models on the class path and serves them:

<!-- sample: src/main/kotlin/me/ahoo/wow/example/quickstart/BankServer.kt -->

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

`src/main/resources/application.yaml` runs one node with its data in memory, and serves the snapshot queries from memory too (`wow.query.memory.enabled`):

<!-- sample: src/main/resources/application.yaml -->

```yaml
spring:
  application:
    name: bank-service
server:
  port: 8080
wow:
  # One node, its data in memory: for trying the service out, not for production.
  backend: memory
  # Serve the snapshot queries from memory too.
  query:
    memory:
      enabled: true
  internal:
    port: 9090
```

```bash
./gradlew run
```

The node serves the public routes on port 8080 and its internal port on 9090. It warns that its data is in memory, and that every request is the anonymous caller until the application declares a `CallerResolver` bean (see [HTTP API](./http-api.md#callers)).

## Send commands over HTTP

Each command has a route, `POST /<context>/<aggregate>/<id>/<command>`, its name in kebab case:

```bash
curl -X POST localhost:8080/bank/account/alice/open-account \
  -H 'Content-Type: application/json' -d '{"owner":"Alice"}'
curl -X POST localhost:8080/bank/account/alice/deposit \
  -H 'Content-Type: application/json' -d '{"amount":100}'
curl -X POST localhost:8080/bank/account/alice/withdraw \
  -H 'Content-Type: application/json' -d '{"amount":150}'
```

A command answers its `CommandResult` (`"status":"SUCCEEDED"`, the new `version`) once the snapshot holds its commit, so a read right after it sees the change. The withdrawal is rejected: the answer is an RFC 9457 problem, status 409, `"type":"urn:wow:error:bank.account.insufficient-balance"`.

## Query the snapshots

Read one aggregate's snapshot, then query them all with a filter:

```bash
curl localhost:8080/bank/account/alice/state
curl -X POST localhost:8080/bank/account/snapshot/query \
  -H 'Content-Type: application/json' \
  -d '{"kind":"list","filter":{"op":"GTE","field":"state.balance","value":100}}'
```

The first answers the version and the state, `{"balance":100,"owner":"Alice"}`; the second a list of snapshots, `alice`'s among them. [Query Language](./query.md) describes the filters, sorts, paging and the other kinds of query. On the memory backend the queries are served only with `wow.query.memory.enabled`; PostgreSQL and MongoDB serve them themselves ([Deployment](./deployment.md)).

## Send commands in process

Application code sends commands through the node's `CommandGateway`, a bean of the host. It runs the same admission as the HTTP routes:

<!-- sample: src/main/kotlin/me/ahoo/wow/example/quickstart/Teller.kt -->

```kotlin
package me.ahoo.wow.example.quickstart

import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.admission.CommandRequest
import org.springframework.stereotype.Service

@Service
class Teller(private val commands: CommandGateway) {
    suspend fun deposit(accountId: String, amount: Long): CommandResult {
        val request = CommandRequest.builder(
            context = "bank",
            aggregate = "account",
            command = "Deposit",
            body = CommandBody.of("""{"amount":$amount}"""),
        ).aggregateId(accountId).build()
        return commands.execute(request)
    }
}
```

`execute` waits as an HTTP command does and returns the last result; `send` returns each one as a `Flow`.

## Where to go next

- **The full example.** The [eShop](https://github.com/Ahoo-Wang/Wow/tree/v10/example/eshop) is five services on PostgreSQL, Kafka and ClickHouse: sagas between contexts, scheduled commands, query indexes, datasets and a web UI.
- **The TypeScript client.** `npm install @ahoo-wang/wow-client@alpha` installs the client of the same alpha; [wow-generator](https://github.com/Ahoo-Wang/Wow/tree/v10/typescript/wow-generator) generates typed command and query clients from a service's Manifest.
- **The V10 guide.** [Write a V10 Service](./index.md) lays out a service in modules, then [Programming Model](./programming-model.md), [Testing](./testing.md), [HTTP API](./http-api.md), [Configuration Keys](./configuration.md) and [Deployment](./deployment.md).
- **This sample.** It is [`example/quickstart`](https://github.com/Ahoo-Wang/Wow/tree/v10/example/quickstart) in the Wow repository, built and tested with every change.
