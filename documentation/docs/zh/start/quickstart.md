---
title: 快速开始
description: 在一个 Gradle 模块中构建一个小服务，从构建到一个能接收命令、回答查询的运行节点，大约三十分钟。
outline: deep
---

# 快速开始

在 Wow 中，你用普通的 Kotlin 编写领域：命令、事件、一个状态，以及一个做出决定的聚合。编译器把它们变成模型和契约，Spring Boot 宿主经 HTTP 提供它们，不需要写控制器或仓储。本页在一个 Gradle 模块中构建一个小的银行服务：一个 `account` 聚合、它的测试，以及一个把数据放在内存中的服务器。

::: warning 预发布版本
Wow 10.0.0-alpha.5 是预发布版本。在 10.0.0 之前，它的 API、配置和存储格式仍可能改变。
:::

## 你需要什么 {#what-you-need}

- JDK 21。
- 带 Kotlin JVM 插件的 Gradle 9。Wow Gradle 插件会应用 KSP 和 Wow 编译器。

## 准备构建 {#set-up-the-build}

`settings.gradle.kts`：

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

`build.gradle.kts`：

```kotlin
plugins {
    kotlin("jvm") version "2.4.21"
    // Applies KSP with wow-compiler of the same version, and assembles the service's Manifest.
    id("me.ahoo.wow") version "10.0.0-alpha.5"
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
    implementation(platform("me.ahoo.wow:wow-bom:10.0.0-alpha.5"))
    // The kernel the generated models build on.
    implementation("me.ahoo.wow:wow-kernel")
    // The Spring Boot host: auto-configuration, the HTTP routes, the memory backend.
    implementation("me.ahoo.wow:wow-spring-boot")
    // Jakarta Bean Validation: the host checks the commands' constraints (@field:Positive) at admission.
    implementation("org.springframework.boot:spring-boot-starter-validation")
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

插件连同它的 marker 一起发布到 Maven Central，所以 `pluginManagement` 需要 `mavenCentral()`。预发布版本在那里只有确切的版本号：要写 `10.0.0-alpha.5`，不能写范围。真实的服务会拆成 api、domain 和 server 三个模块（[在已有服务中引入 Wow](./existing-project)）；试用时一个模块就够了。

## 编写命令和事件 {#write-the-commands-and-events}

用 `@BoundedContext` 命名一次上下文。命令和事件都是数据类。命令不带聚合 ID：ID 来自路由。存款和取款的金额为正：这是命令的形状，一个由宿主在账户决定之前检查的 Jakarta 约束。

<!-- sample: example/quickstart/src/main/kotlin/me/ahoo/wow/example/quickstart/Bank.kt -->

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

## 编写状态和聚合 {#write-the-state-and-the-aggregate}

状态在 `onSourcing` 函数中应用每个事件。聚合在 `onCommand` 函数中决定每条命令：它返回事件，或者返回一个以错误码拒绝命令的 `Decision`，这时什么都不写。它只拒绝需要它状态的命令：余额不足以支付的取款。

<!-- sample: example/quickstart/src/main/kotlin/me/ahoo/wow/example/quickstart/Account.kt -->

```kotlin
package me.ahoo.wow.example.quickstart

import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.ErrorCode
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.decide.Decision

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

    @OnCommand(produces = [Withdrawn::class])
    fun onCommand(command: Withdraw): Decision =
        if (command.amount > state.balance) {
            Decision.reject(Rejections.INSUFFICIENT_BALANCE, "The balance is ${state.balance}.")
        } else {
            Decision.events(Withdrawn(command.amount))
        }

    /** The codes it rejects with, by local name: the Manifest and its answers say `bank.account.insufficient-balance`. */
    object Rejections {
        @ErrorCode(description = "The balance does not cover the withdrawal.", status = 409)
        const val INSUFFICIENT_BALANCE = "insufficient-balance"
    }
}
```

`@OnCommand(produces = ...)` 告诉契约，返回 `Decision` 的函数能产生哪些事件。所有规则见[编写聚合](../guide/aggregates)。

## 测试 {#test-it}

编译器在 `Account` 旁边生成了 `accountModel`。`aggregateSpec` 在真实的内核和内存日志上运行它：给定这些事件，执行这条命令，期望得到这些事件和这个状态。

<!-- sample: example/quickstart/src/test/kotlin/me/ahoo/wow/example/quickstart/AccountSpec.kt -->

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

全部断言见[测试领域代码](../guide/testing)。

## 运行服务 {#run-the-service}

服务器是一个 Spring Boot 应用。宿主在类路径上找到生成的模型并提供它们：

<!-- sample: example/quickstart/src/main/kotlin/me/ahoo/wow/example/quickstart/BankServer.kt -->

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

`src/main/resources/application.yaml` 运行一个把数据放在内存中的节点，并且也从内存提供快照查询：

<!-- sample: example/quickstart/src/main/resources/application.yaml -->

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

节点在 8080 端口上提供路由，在 9090 端口上提供内部端口。它会警告数据在内存中，并且在应用认证调用方之前，每个请求都是匿名调用方（[提供和调用 HTTP API](../guide/http#who-the-caller-is)）。

## 发送命令 {#send-commands}

每条命令都有一个路由 `POST /<context>/<aggregate>/<id>/<command>`，命令名采用 kebab case：

```bash
curl -X POST localhost:8080/bank/account/alice/open-account \
  -H 'Content-Type: application/json' -d '{"owner":"Alice"}'
curl -X POST localhost:8080/bank/account/alice/deposit \
  -H 'Content-Type: application/json' -d '{"amount":100}'
curl -X POST localhost:8080/bank/account/alice/withdraw \
  -H 'Content-Type: application/json' -d '{"amount":150}'
```

命令在快照包含它的提交后返回结果：`"status":"SUCCEEDED"`，以及账户新的 `version`，所以紧接着的读取能看到这次改变。取款被拒绝：回答是一个 RFC 9457 problem，状态 409，带 `"code":"bank.account.insufficient-balance"`。

金额为零的存款根本到不了账户：

```bash
curl -X POST localhost:8080/bank/account/alice/deposit \
  -H 'Content-Type: application/json' -d '{"amount":0}'
```

它回答状态 400，带 `"code":"wow.command.invalid"` 及其 `violations`：`[{"path":"amount","code":"Positive","message":"must be greater than 0"}]`。

## 读取与查询 {#read-and-query}

读取一个账户的状态，再按余额查询所有账户：

```bash
curl localhost:8080/bank/account/alice/state
curl -X POST localhost:8080/bank/account/snapshot/query \
  -H 'Content-Type: application/json' \
  -d '{"kind":"list","filter":{"op":"GTE","field":"state.balance","value":100}}'
```

第一个请求返回版本和状态 `{"balance":100,"owner":"Alice"}`；第二个返回快照列表，其中有 `alice` 的。过滤、排序、分页和其他种类的查询见[查询快照](../guide/queries)。在内存后端上，只有设置了 `wow.query.memory.enabled` 才提供查询；PostgreSQL 和 MongoDB 自己就能提供。

## 从代码发送命令 {#send-commands-from-code}

应用代码通过宿主的 `CommandClient` 发送命令对象，经过与 HTTP 路由相同的准入：

<!-- sample: example/quickstart/src/main/kotlin/me/ahoo/wow/example/quickstart/Teller.kt -->

```kotlin
package me.ahoo.wow.example.quickstart

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.result.CommandResult
import org.springframework.stereotype.Service

/** Deposits from application code, through the host's [CommandClient]: the same admission as the HTTP routes. */
@Service
class Teller(private val commands: CommandClient) {
    suspend fun deposit(accountId: String, amount: Long): CommandResult =
        commands.wait(Deposit(amount), ACCOUNT.ref(Caller.DEFAULT_TENANT, accountId))

    private companion object {
        val ACCOUNT = AggregateType("bank", "account")
    }
}
```

`wait` 在命令的等待结束时回答，默认是快照；`send` 在命令被接受后就回答。其余用法见[调用其他服务](../guide/calling-services)。

## 接下来读什么 {#where-to-go-next}

- **概念。** [架构总览](../concepts/)说明宿主为你做了什么。
- **真实的服务。** [在已有服务中引入 Wow](./existing-project) 把服务按模块组织起来。
- **指南。** [开发指南](../guide/)说明每项任务：Saga、投影、查询、测试。
- **完整的示例。** [eShop](https://github.com/Ahoo-Wang/Wow/tree/main/example/eshop) 是基于 PostgreSQL、Kafka 和 ClickHouse 的五个服务，带有上下文之间的 Saga、定时命令、数据集和一个 Web 界面。
- **这个示例**就是 [`example/quickstart`](https://github.com/Ahoo-Wang/Wow/tree/main/example/quickstart)，每次改动都会构建和测试。
