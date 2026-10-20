---
title: V10 快速开始
description: 在一个模块里试用 Wow 10.0.0-alpha.3：从 Gradle 构建到一个接收命令、回答查询的运行服务。
outline: deep
---

# V10 快速开始

Wow V10 是 Wow 的下一个大版本：领域用普通的 Kotlin 类编写（命令、事件、状态，以及做决定的聚合），编译器把它们变成模型与 Manifest，由 Spring Boot 主机通过 HTTP 提供服务。它在新的内核、日志与集群上重写，API、配置与存储都与 9.x 不同。

::: danger 10.0.0-alpha.3 只用于评估

- **不做兼容性承诺。** 一个 alpha 可以改变上一个 alpha 的 API、配置、HTTP 路由，以及存储与线上格式。
- **还不能从 9.x 迁移。** 导入 9.x 事件流的工作尚未完成（里程碑 M8）：不要把生产数据迁移到 alpha。
- **试用并反馈。** 用它评估 V10，把不能用的地方报到 [issues](https://github.com/Ahoo-Wang/Wow/issues)。本站 V10 栏目之外的内容描述的是 9.x。

:::

本页在一个 Gradle 模块里写一个小小的银行服务：一个 `account` 聚合、它的测试，以及运行在内存后端上的服务端。完整示例是 [eShop](#下一步)。

## 前提

- JDK 21。
- Gradle 9 与 Kotlin JVM 插件。Wow Gradle 插件会替你应用 KSP 与 Wow 编译器。

## 配置 Gradle

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
    // 应用 KSP 与同版本的 wow-compiler，并汇总服务的 Manifest。
    id("me.ahoo.wow") version "10.0.0-alpha.3"
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("me.ahoo.wow.example.quickstart.BankServerKt")
}

dependencies {
    // V10 BOM：所有 Wow 模块用同一个版本。
    implementation(platform("me.ahoo.wow:wow-bom:10.0.0-alpha.3"))
    // 生成的模型所依赖的内核。
    implementation("me.ahoo.wow:wow-kernel")
    // Spring Boot 主机：自动配置、HTTP 路由、内存后端。
    implementation("me.ahoo.wow:wow-spring-boot")
    // 聚合测试 DSL，包名 me.ahoo.wow.test。
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

插件连同插件标记发布在 Maven Central，所以 `pluginManagement` 需要 `mavenCentral()`。Maven Central 上的 alpha 只有明确的版本号：写 `10.0.0-alpha.3`，不要写版本范围。真实的服务分成 `-api`、`-domain` 与 `-server` 三个模块（[编写 V10 服务](./index.md#划分模块)）；试用 V10，一个模块就够了。

## 编写命令与事件

上下文用 `@BoundedContext` 命名一次。命令与事件是普通的类；命令不带聚合 ID，ID 来自路由。

<!-- sample: example/quickstart/src/main/kotlin/me/ahoo/wow/example/quickstart/Bank.kt -->

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

## 编写状态与聚合

状态在 `onSourcing` 函数里应用事件。聚合在 `onCommand` 函数里对每条命令做决定：返回事件，或者返回一个 `Decision`，它可以用错误码拒绝命令。被拒绝的命令什么也不写。

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

    @OnCommand(produces = [Deposited::class])
    fun onCommand(command: Deposit): Decision =
        if (command.amount <= 0) {
            Decision.reject(Rejections.INVALID_AMOUNT, "${command.amount} is not positive.")
        } else {
            Decision.events(Deposited(command.amount))
        }

    @OnCommand(produces = [Withdrawn::class])
    fun onCommand(command: Withdraw): Decision = when {
        command.amount <= 0 ->
            Decision.reject(Rejections.INVALID_AMOUNT, "${command.amount} is not positive.")

        command.amount > state.balance ->
            Decision.reject(Rejections.INSUFFICIENT_BALANCE, "The balance is ${state.balance}.")

        else -> Decision.events(Withdrawn(command.amount))
    }

    /** The codes it rejects with, by local name: the Manifest and its answers say `bank.account.invalid-amount`. */
    object Rejections {
        @ErrorCode(description = "An amount is always positive.")
        const val INVALID_AMOUNT = "invalid-amount"

        @ErrorCode(description = "The balance does not cover the withdrawal.", status = 409)
        const val INSUFFICIENT_BALANCE = "insufficient-balance"
    }
}
```

`@OnCommand(produces)` 告诉 Manifest 一个返回 `Decision` 的函数可能产生哪些事件。完整规则见[编程模型](./programming-model.md)。

## 测试

编译器在 `Account` 旁边生成了 `accountModel`。`aggregateSpec` 用内存日志在真实的内核上运行它：

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

全部断言见[测试](./testing.md)。

## 运行服务

服务端是一个 Spring Boot 应用。主机在类路径上找到生成的模型并提供服务：

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

`src/main/resources/application.yaml` 运行一个节点，数据放在内存里，快照查询也由内存提供（`wow.query.memory.enabled`）：

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

节点在 8080 端口提供公开路由，在 9090 提供内部端口。它会警告数据在内存里，并且在应用声明 `CallerResolver` Bean 之前，每个请求都按匿名调用方处理（见 [HTTP API](./http-api.md#调用方)）。

## 通过 HTTP 发送命令

每条命令有一个路由，`POST /<context>/<aggregate>/<id>/<command>`，命令名用 kebab-case：

```bash
curl -X POST localhost:8080/bank/account/alice/open-account \
  -H 'Content-Type: application/json' -d '{"owner":"Alice"}'
curl -X POST localhost:8080/bank/account/alice/deposit \
  -H 'Content-Type: application/json' -d '{"amount":100}'
curl -X POST localhost:8080/bank/account/alice/withdraw \
  -H 'Content-Type: application/json' -d '{"amount":150}'
```

命令在快照保存了它的提交之后返回 `CommandResult`（`"status":"SUCCEEDED"` 与新的 `version`），所以紧接着的读取能看到变化。取款被拒绝：返回的是 RFC 9457 问题，状态 409，`"type":"urn:wow:error:bank.account.insufficient-balance"`。

## 查询快照

读取一个聚合的快照，再按条件查询全部快照：

```bash
curl localhost:8080/bank/account/alice/state
curl -X POST localhost:8080/bank/account/snapshot/query \
  -H 'Content-Type: application/json' \
  -d '{"kind":"list","filter":{"op":"GTE","field":"state.balance","value":100}}'
```

第一个返回版本与状态 `{"balance":100,"owner":"Alice"}`；第二个返回快照列表，其中有 `alice`。过滤、排序、分页与其他查询种类见[查询语言](./query.md)。内存后端只有在 `wow.query.memory.enabled` 打开时才提供查询；PostgreSQL 与 MongoDB 自己提供查询（[部署](./deployment.md)）。

## 在进程内发送命令

应用代码通过节点的 `CommandGateway`（主机提供的 Bean）发送命令，它经过与 HTTP 路由相同的准入：

<!-- sample: example/quickstart/src/main/kotlin/me/ahoo/wow/example/quickstart/Teller.kt -->

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

`execute` 像 HTTP 命令一样等待，返回最后一个结果；`send` 以 `Flow` 返回每一个结果。

## 下一步

- **完整示例。** [eShop](https://github.com/Ahoo-Wang/Wow/tree/v10/example/eshop) 是运行在 PostgreSQL、Kafka 与 ClickHouse 上的五个服务：跨上下文的 Saga、定时命令、查询索引、数据集与 Web 界面。
- **TypeScript 客户端。** `npm install @ahoo-wang/wow-client@alpha` 安装同一个 alpha 的客户端；[wow-generator](https://github.com/Ahoo-Wang/Wow/tree/v10/typescript/wow-generator) 根据服务的 Manifest 生成带类型的命令与查询客户端。
- **V10 指南。** [编写 V10 服务](./index.md) 按模块组织一个服务，然后是[编程模型](./programming-model.md)、[测试](./testing.md)、[HTTP API](./http-api.md)、[配置项](./configuration.md)与[部署](./deployment.md)。
- **本页示例。** 它是 Wow 仓库里的 [`example/quickstart`](https://github.com/Ahoo-Wang/Wow/tree/v10/example/quickstart)，每次改动都会构建与测试。
