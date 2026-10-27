---
title: Write the Domain in Java
description: Write commands, events, aggregates and rejection codes in Java, built by the same compiler, tested with the Kotlin test DSL; what Java cannot do yet.
outline: deep
---

# Write the Domain in Java

The Wow compiler reads Java sources as it reads Kotlin: the same annotations, the same generated models, the same Manifest. A domain module may be written in Java, in Kotlin, or in both. This page writes a small account aggregate in Java and lists what Java cannot do yet. It is the repository's Java smoke test, `example/java-smoke`, which the build compiles and tests on every change.

## Set up the module

A Java domain module applies the Kotlin JVM plugin, `kotlin("jvm")`, and the Gradle plugin `me.ahoo.wow`, as a Kotlin one does: KSP processes the module's Java sources within the Kotlin compilation, and the models it generates are Kotlin. The plugin fails to configure a module without the Kotlin JVM plugin, naming the fix. The smoke test, a project of Wow's own build, applies KSP and the compiler by hand instead.

## Commands and events

Commands and events are Java records. `@Summary` documents them in the Manifest:

<!-- sample: example/java-smoke/src/main/java/me/ahoo/wow/example/java/Withdraw.java -->

```java
package me.ahoo.wow.example.java;

import me.ahoo.wow.api.annotation.Summary;

/** Withdraw money. */
@Summary("Withdraw money")
public record Withdraw(long amount) {
}
```

## The aggregate

The root is a class annotated `@AggregateRoot`, with a constructor that takes its state. Its command functions are plain public methods, found by the annotation or by the name `onCommand`:

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

- A method returns an event, or a `Decision` built with the statics `Decision.events(...)` and `Decision.reject(code, message)`; `Decision.NoOp.INSTANCE` changes nothing.
- A method that returns `Decision` names its events in `@OnCommand(produces = ...)`, as in Kotlin.
- A method may take the domain services and the `DecideScope` after the command, and `Command<X>` instead of `X`.

## The state

The state is a mutable class with one public `onSourcing` method per event. Its getters are what Jackson serializes into snapshots, and what the test DSL compares to check that a decision did not change the state:

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

## Rejection codes

`@ErrorCode` goes on a `public static final String` field. A code without a dot is a local name, declared on a field of the root class or of a class nested in it; a code with dots is full, as these are:

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

## Test it

The test DSL is Kotlin, so a Java domain's tests are Kotlin, in the same module. They run the model the compiler generated from the Java sources:

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

## What Java cannot do yet

Some of the API is made of Kotlin `suspend` functions, which Java cannot call:

| Kotlin only | In Java instead |
| --- | --- |
| `DecideScope.reserve` | Write the aggregate that reserves unique keys in Kotlin |
| `SagaContext.call`, a synchronous call to another context | Derive the command: return it, and the command topic delivers it |
| The test DSL | Write the tests in Kotlin |

Application code in Java calls the command and query clients through their Java facades, `JavaCommandClient` and `JavaQueryApi`, whose calls are blocking or return a `CompletableFuture` ([Call Other Services](./calling-services#from-java)).
