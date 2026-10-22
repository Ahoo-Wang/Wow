---
title: Rejections and Error Codes
description: Refuse a command with a declared error code, give it an HTTP status and structured data, and read it as a caller, a peer, a saga or a test.
outline: deep
---

# Rejections and Error Codes

A command the aggregate refuses is a business outcome, not an error of the program. The aggregate returns a rejection with a stable code, nothing is written, and the caller is told why. This page declares the codes and shows what each kind of caller receives.

## Reject, or fail

| The decision | What happens |
| --- | --- |
| `Decision.reject(code, message)` | The command is **rejected**: nothing is written, and the caller gets the code and the message. Sending it again with the same request id is rejected again. |
| The function throws | The command **fails** with `wow.command.failed` (500): nothing is written, and a retry fails alike. A failure is a defect, never an answer to the caller's request. |

Reject when the request cannot be granted in the aggregate's current state: an empty order, an unpaid order that is shipped, a short stock. Let a function throw only when something is wrong with the program or its services.

## Declare the codes

A code is a `const val` of type `String` annotated `@ErrorCode`, in an `object` nested in the root class:

<!-- sample: example/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/Order.kt#rejections -->

```kotlin
/**
 * The codes the aggregate rejects a command with, by their local names: the compiler and the kernel name them
 * `<context>.order.<code>` (decision D259), as its peers read them from the api's generated contract.
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
- **The rules.** The initializer is a string literal. A code may not start with `wow.`, which belongs to the framework, and a module declares each code once.

## Reject with a code

A command function returns the rejection with the code's local name and a message for people:

<!-- sample: example/eshop/inventory/inventory-domain/src/main/kotlin/me/ahoo/wow/example/eshop/inventory/domain/stock/Stock.kt#reserve -->

```kotlin
@OnCommand(produces = [StockReserved::class])
fun onCommand(command: ReserveStock, scope: DecideScope): Decision = when {
    command.orderId in state.reservations -> Decision.NoOp
    command.quantity > state.available -> Decision.reject(
        Rejections.INSUFFICIENT,
        "Only ${state.available} of ${scope.command.ref.id} are available, not ${command.quantity}.",
    )

    else -> Decision.events(StockReserved(command.orderId, command.quantity))
}
```

The message may say anything that helps a person: amounts, ids, the state the aggregate is in. Callers decide on the code, never on the message.

`Decision.reject(code, message, extensions)` adds structured data a client reads instead of parsing the message: a map of names to JSON values, such as the ids of what blocks the command. The command's result carries them in its error, and an HTTP answer writes each one as a member of its problem.

## What the caller receives

**Over HTTP**, a rejection is answered with the code's status and an `application/problem+json` body whose `type` is `urn:wow:error:<code>` and whose `code` member is the full code. [Wire Protocols](../reference/protocols) describes the whole shape.

**In Kotlin**, the command client throws a `CommandRejectedException` whose `code` is the full code:

<!-- sample: example/eshop/e2e/src/integrationTest/kotlin/me/ahoo/wow/example/eshop/e2e/EshopClientTest.kt#rejected -->

```kotlin
// An order of no items breaks SubmitOrder's constraints: the ordering refuses it at admission, typed.
val rejected = runCatching {
    basket.bean<CommandClient>().of(alice)
        .wait(SubmitOrder(emptyList()), EshopOrderingContract.Order.ref(Caller.DEFAULT_TENANT, "client-alice-2"))
}.exceptionOrNull() as CommandRejectedException
rejected.code.assert().isEqualTo("wow.command.invalid")
rejected.result.error!!.violations.single().path.assert().isEqualTo("items")
```

**A peer** compares the code with a constant instead of a string. The api module's generated contract holds every code of its context by its full name, `EshopOrderingContract.Order.Errors.WRONG_STATUS`, so a peer that depends on the ordering's api reads the same value the ordering rejects with.

**A saga** that sent the command gets the rejection in its `@OnRejected` handler and decides how to compensate: [Write a Saga](./sagas#handle-a-rejection).

**A test** expects it with `expectRejected(code)`, by the local or the full name: [Test Domain Code](./testing#expect).

## Codes the framework rejects with

Some commands never reach the aggregate's function: the command is malformed, the caller may not send it, the aggregate does not exist, or the expected version does not match. Those are rejected with the framework's own codes, all of them under `wow.`. A command whose body breaks a Bean Validation constraint is rejected with `wow.command.invalid` (400), and the problem lists each violation with its property path, its constraint and its message; the host checks the constraints when the application has a Bean Validation provider on its class path.

[Error Codes](../reference/error-codes) lists every framework code with its status.
