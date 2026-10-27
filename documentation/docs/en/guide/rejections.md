---
title: Rejections and Error Codes
description: Refuse a command with a declared error code, give it an HTTP status and structured data, and read it as a caller, a peer, a saga or a test.
outline: deep
---

# Rejections and Error Codes

A command the aggregate refuses is a business outcome, not an error of the program. The aggregate returns a rejection with a stable code, nothing is written, and the caller is told why. This page declares the codes and shows what each kind of caller receives.

## Reject, or fail

A decide function refuses a command in one of three ways, and each is answered the same: nothing is written, a compensation registered with `onAppendFailure` runs, the caller gets the code with its message, and sending the command again with the same request id is rejected again. A rejection is never retried and never parks anything, whatever sent the command. The message is shown to the caller, so write it for them.

| The decide function | The caller gets |
| --- | --- |
| returns `Decision.reject(code, message)` | the code, with its status (422 by default) |
| calls `reject(code, message)` (`me.ahoo.wow.api.decide`, or its scope's), or throws `RejectedException(Rejection.of(code, message))` (Java) | the same as returning it |
| calls `require`, `requireNotNull` (an `IllegalArgumentException`) | `wow.command.invalid` (400) |
| calls `check`, `checkNotNull` or `error` (an `IllegalStateException`) | `wow.command.refused` (409): the aggregate's current state refuses the command |
| throws anything else | the command **fails** with `wow.command.failed` (500): nothing is written, and a retry fails alike |

Only those two classes themselves are refusals: a subclass, such as `NumberFormatException` or a library's own exception, is a failure, and so is a cancellation. A failure is a defect of the program or its services, never an answer to the caller's request. Do not catch exceptions around `reject`, `require` or `check` with a `catch (e: Exception)`: that swallows the refusal, and the command goes on.

Reject when the request cannot be granted in the aggregate's current state, or by its caller: a basket checked out empty, an unpaid order that is shipped, a short stock, a buyer who does not own the basket. Prefer a declared code (`Decision.reject` or `reject`) when a caller acts on the reason; `require` and `check` suit the guards whose message is enough.

A rule about the command's own shape, such as a quantity that is not positive, a blank name or an order of no items, is no rejection: it is a Jakarta constraint on the command class, and the gateway refuses the command with `wow.command.invalid` (400) before the aggregate decides ([Write an Aggregate](./aggregates#check-its-shape)). The aggregate then declares codes only for what needs its state.

| The rule needs | Where it goes | The caller gets |
| --- | --- | --- |
| The command alone | A Jakarta constraint on the command (`@field:Positive`, `@field:NotBlank`, `@field:NotEmpty`, `@field:Valid`) | `wow.command.invalid` (400) with its `violations` |
| The aggregate's state or the caller | `Decision.reject` with an `@ErrorCode` | The code, with its status (422 by default) |

## Declare the codes

A code is a `const val` of type `String` annotated `@ErrorCode`, in an `object` nested in the root class:

<!-- sample: example/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/Order.kt#rejections -->

```kotlin
/**
 * The codes the aggregate rejects a command with, by their local names: the compiler and the kernel name them
 * `<context>.order.<code>`, as its peers read them from the api's generated contract.
 */
object Rejections {
    @ErrorCode(description = "The order's status does not allow the command.", status = 409)
    const val WRONG_STATUS = "wrong-status"

    @ErrorCode(description = "A payment pays the order's total amount.")
    const val WRONG_AMOUNT = "wrong-amount"
}
```

- **The name.** A code without a dot is a local name. The compiler and the kernel qualify it with the context and the aggregate, so callers, receipts, HTTP answers and the Manifest only ever see the full code: `eshop-ordering.order.wrong-status`. A code with a dot is full as written, for a code the context's aggregates share, such as `eshop-ordering.closed`.
- **The status.** `status` is the HTTP status a rejection with the code is answered with, from 400 to 599; 422 by default. Use 409 when the request conflicts with the aggregate's state, 404 when it names something the aggregate does not hold, and 403 when the caller may not ask it.
- **The description.** `description` says what the code means. The Manifest publishes it with the code.
- **The rules.** The initializer is a string literal. A code may not start with `wow.`, which belongs to the framework, and a module declares each code once. `Decision.reject` and `Rejection.of` refuse a `wow.` code too, and a command rejected with one anyway, through a model that builds its rejection by other means, fails with `wow.command.failed` (500): its message stays, and the code it chose is the `rejectedCode` extension.

## Reject with a code

A command function returns the rejection with the code's local name and a message for people:

<!-- sample: example/eshop/inventory/inventory-domain/src/main/kotlin/me/ahoo/wow/example/eshop/inventory/domain/stock/Stock.kt#reserve -->

```kotlin
fun onCommand(command: ReserveStock, scope: DecideScope): StockReserved? = when {
    command.orderId in state.reservations -> null
    command.quantity > state.available -> reject(
        Rejections.INSUFFICIENT,
        "Only ${state.available} of ${scope.command.ref.id} are available, not ${command.quantity}.",
    )

    else -> StockReserved(command.orderId, command.quantity)
}
```

The message may say anything that helps a person: amounts, ids, the state the aggregate is in. Callers decide on the code, never on the message.

`Decision.reject(code, message, extensions)` adds structured data a client reads instead of parsing the message: a map of names to JSON values, such as the ids of what blocks the command. The command's result carries them in its error, and an HTTP answer writes each one as a member of its problem.

## What the caller receives

**Over HTTP**, a rejection is answered with the code's status and an `application/problem+json` body whose `type` is `urn:wow:error:<code>` and whose `code` member is the full code. [Wire Protocols](../reference/protocols) describes the whole shape.

**In Kotlin**, the command client throws a `CommandRejectedException` whose `code` is the full code, for a rejection by the aggregate as for one at admission:

<!-- sample: example/eshop/e2e/src/integrationTest/kotlin/me/ahoo/wow/example/eshop/e2e/EshopClientTest.kt#rejected -->

```kotlin
// An order of no items breaks SubmitOrder's constraints: the ordering refuses it at admission, typed.
val rejected = runCatching {
    basket.bean<CommandClient>().of(alice).wait(SubmitOrder(emptyList()), "client-alice-2")
}.exceptionOrNull() as CommandRejectedException
rejected.code.assert().isEqualTo("wow.command.invalid")
rejected.result.error!!.violations.single().path.assert().isEqualTo("items")
```

**A peer** compares the code with a constant instead of a string. The api module's generated contract holds every code of its context by its full name, `EshopOrderingContract.Order.Errors.WRONG_STATUS`, so a peer that depends on the ordering's api reads the same value the ordering rejects with.

**A saga** that sent the command gets the rejection in its `@OnRejected` handler and decides how to compensate: [Write a Saga](./sagas#handle-a-rejection).

**A test** expects it with `expectRejected(code)`, by the local or the full name: [Test Domain Code](./testing#expect). `aggregateSpec` runs the kernel without admission, so a command's constraints are tested against the server: a request that breaks them answers 400 `wow.command.invalid` with the violation's path and constraint.

## Where a command's failure is recorded

A rejection is an answer, never a failure: it is not retried or parked, whichever way the command came. A failure is recorded by what sent the command:

| The command came from | A failure |
| --- | --- |
| A saga (a derived command) | parks the saga's subscription on its aggregate, until it is resumed or skipped |
| The Kafka command intake | parks the target aggregate under the intake, at the command's offset |
| A timer | parks the aggregate under `wow.timers` |
| An HTTP request, or a synchronous call (`Wow-Call`) | goes back to the caller as the problem; metrics, logs and traces record it, the failure ledger does not |

## Remote calls in a decide function

A decide function runs again after a version conflict, so a remote call in it must be idempotent. Wow's clients derive the request id of a command they send from the deciding command's id and the call's order, so a repeated call is the same request as long as the calls keep their order (`CommandRequest.requestId` overrides it). Commands sent concurrently from one decision take their indices in no stable order, so send them one after another, or pass `CommandRequest.requestId` when a resend must be deduplicated; a `JavaCommandClient` future derives nothing, so pass the id explicitly there too; a third-party call needs a key of its own, and undoing it goes through `onAppendFailure`. Bringing the remote facts in before the decision is usually better: enrich the command with a `CommandTransformer`, or let a saga call and send the result. A `CommandRejectedException` of a Wow client call is answered as a rejection of this command with the target's code; the target failing, timing out or answering an unknown outcome is a failure, as are I/O errors and timeouts of any client. Wrap a third-party call whose library throws a plain `IllegalStateException` for a transient error, so that it is not taken for a refusal.

## Codes the framework rejects with

Some commands never reach the aggregate's function: the command is malformed, the caller may not send it, the aggregate does not exist, or the expected version does not match. Those are rejected with the framework's own codes, all of them under `wow.`. A command whose body breaks a Bean Validation constraint is rejected with `wow.command.invalid` (400), and the problem lists each violation with its property path, its constraint and its message; the host checks the constraints when the application has a Bean Validation provider on its class path.

[Error Codes](../reference/error-codes) lists every framework code with its status.
