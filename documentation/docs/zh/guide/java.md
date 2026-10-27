---
title: 用 Java 编写领域
description: 用 Java 编写命令、事件、聚合和拒绝错误码，由同一个编译器构建，用 Kotlin 测试 DSL 测试；以及 Java 目前还做不到的事。
outline: deep
---

# 用 Java 编写领域

Wow 编译器读取 Java 源码就像读取 Kotlin 源码一样：同样的注解、同样的生成模型、同样的 Manifest。领域模块可以用 Java 写、用 Kotlin 写，或者两者混用。本页用 Java 写一个小的账户聚合，并列出 Java 目前还做不到的事。它就是仓库里的 Java 冒烟测试 `example/java-smoke`，每次改动构建都会编译并测试它。

## 准备模块 {#set-up-the-module}

Java 领域模块和 Kotlin 领域模块一样，应用 Kotlin JVM 插件 `kotlin("jvm")` 和 Gradle 插件 `me.ahoo.wow`：KSP 在 Kotlin 编译中处理模块的 Java 源码，它生成的模型是 Kotlin 代码。没有 Kotlin JVM 插件的模块，插件会配置失败，并指出修正方法。冒烟测试作为 Wow 自己构建中的一个项目，则是手动应用 KSP 和编译器。

## 命令与事件 {#commands-and-events}

命令和事件是 Java record。`@Summary` 在 Manifest 中为它们写说明：

<!-- sample: example/java-smoke/src/main/java/me/ahoo/wow/example/java/Withdraw.java -->

```java
package me.ahoo.wow.example.java;

import me.ahoo.wow.api.annotation.Summary;

/** Withdraw money. */
@Summary("Withdraw money")
public record Withdraw(long amount) {
}
```

## 聚合 {#the-aggregate}

根是一个标注了 `@AggregateRoot` 的类，带一个接收其状态的构造函数。它的命令函数是普通的 public 方法，按注解或按名字 `onCommand` 找到：

<!-- sample: example/java-smoke/src/main/java/me/ahoo/wow/example/java/Account.java -->

```java
package me.ahoo.wow.example.java;

import me.ahoo.wow.api.annotation.AggregateRoot;
import me.ahoo.wow.api.annotation.OnCommand;
import me.ahoo.wow.api.decide.Decision;

/**
 * An aggregate root written in Java. Its decide functions are plain Java methods: they return an
 * event, or a {@link Decision} built with the statics {@code Decision.events} and {@code Decision.reject}, whose events
 * {@code @OnCommand(produces)} names. A Java decide function cannot call the {@code suspend} functions of the API,
 * such as {@code DecideScope.reserve}, and the test DSL is Kotlin, so the tests of a Java domain are Kotlin.
 */
@AggregateRoot
public class Account {
    private final AccountState state;

    public Account(AccountState state) {
        this.state = state;
    }

    public AccountOpened onCommand(OpenAccount command) {
        return new AccountOpened(command.owner());
    }

    @OnCommand(produces = Deposited.class)
    public Decision onCommand(Deposit command) {
        if (command.amount() <= 0) {
            return Decision.reject(AccountRejections.INVALID_AMOUNT, command.amount() + " is not positive.");
        }
        return Decision.events(new Deposited(command.amount()));
    }

    @OnCommand(produces = Withdrawn.class)
    public Decision onCommand(Withdraw command) {
        if (command.amount() <= 0) {
            return Decision.reject(AccountRejections.INVALID_AMOUNT, command.amount() + " is not positive.");
        }
        if (command.amount() > state.getBalance()) {
            return Decision.reject(AccountRejections.INSUFFICIENT_BALANCE, "The balance is " + state.getBalance() + ".");
        }
        return Decision.events(new Withdrawn(command.amount()));
    }
}
```

- 方法返回一个事件，或者一个用静态方法 `Decision.events(...)` 和 `Decision.reject(code, message)` 构建的 `Decision`；`Decision.NoOp.INSTANCE` 什么都不改变。
- 返回 `Decision` 的方法要在 `@OnCommand(produces = ...)` 中指明它的事件，与 Kotlin 一样。
- 方法可以在命令之后接收领域服务和 `DecideScope`，也可以接收 `Command<X>` 而不是 `X`。

## 状态 {#the-state}

状态是一个可变类，每个事件对应一个 public 的 `onSourcing` 方法。它的 getter 就是 Jackson 序列化进快照的内容，也是测试 DSL 用来比较、以检查决定没有修改状态的内容：

<!-- sample: example/java-smoke/src/main/java/me/ahoo/wow/example/java/AccountState.java -->

```java
package me.ahoo.wow.example.java;

/**
 * The account's state: mutable, evolved by its public {@code onSourcing} methods, one per event. Its getters are what
 * Jackson serializes (snapshots, the test DSL's purity check).
 */
public class AccountState {
    private String owner = "";
    private long balance;

    public String getOwner() {
        return owner;
    }

    public long getBalance() {
        return balance;
    }

    public void onSourcing(AccountOpened event) {
        owner = event.owner();
    }

    public void onSourcing(Deposited event) {
        balance += event.amount();
    }

    public void onSourcing(Withdrawn event) {
        balance -= event.amount();
    }
}
```

## 拒绝错误码 {#rejection-codes}

`@ErrorCode` 写在一个 `public static final String` 字段上。不带点的错误码是本地名，声明在根类或嵌套在根类中的类的字段上；带点的错误码是完整的，就像这些：

<!-- sample: example/java-smoke/src/main/java/me/ahoo/wow/example/java/AccountRejections.java -->

```java
package me.ahoo.wow.example.java;

import me.ahoo.wow.api.annotation.ErrorCode;

/** The account's rejection codes: {@code @ErrorCode} on Java {@code static final String} fields. */
public final class AccountRejections {
    @ErrorCode(description = "An amount is always positive.")
    public static final String INVALID_AMOUNT = "smoke.account.invalid-amount";

    @ErrorCode(description = "The balance does not cover the withdrawal.", status = 409)
    public static final String INSUFFICIENT_BALANCE = "smoke.account.insufficient-balance";

    private AccountRejections() {
    }
}
```

## 测试 {#test-it}

测试 DSL 是 Kotlin 写的，所以 Java 领域的测试用 Kotlin 写，放在同一个模块里。它们运行编译器从 Java 源码生成的模型：

<!-- sample: example/java-smoke/src/test/kotlin/me/ahoo/wow/example/java/AccountSpec.kt#rejection -->

```kotlin
@Test
fun `a Java rejection should carry the code of a Java field`() = aggregateSpec(model, id = "alice") {
    given(AccountOpened("Alice"), Deposited(100))
    whenCommand(Withdraw(150)) { expectRejected(AccountRejections.INSUFFICIENT_BALANCE) }
    whenCommand(Deposit(0)) { expectRejected(AccountRejections.INVALID_AMOUNT) }
    whenCommand(Withdraw(30)) {
        expectEvents(Withdrawn(30))
        expectState { balance.assert().isEqualTo(70) }
    }
}
```

## Java 目前还做不到的事 {#what-java-cannot-do-yet}

API 中有一部分是 Kotlin 的 `suspend` 函数，Java 无法调用：

| 只在 Kotlin 中 | 在 Java 中改为 |
| --- | --- |
| `DecideScope.reserve` | 用 Kotlin 编写预留唯一键的聚合 |
| `SagaContext.call`，对另一个上下文的同步调用 | 派生命令：返回它，由命令主题投递 |
| 测试 DSL | 用 Kotlin 编写测试 |

Java 写的应用代码通过命令客户端和查询客户端的 Java 门面 `JavaCommandClient` 和 `JavaQueryApi` 调用它们，调用是阻塞的，或者返回 `CompletableFuture`（[调用其他服务](./calling-services#from-java)）。
