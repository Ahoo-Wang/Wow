---
title: Failures and Retries
description: What happens when a handler fails, how to give it its own retry budget and timeout, how failures are classified, how a command is retried at each layer, and how parked aggregates are retried or skipped.
outline: deep
---

# Failures and Retries

A saga, a projection or a reactor will sometimes fail: the mail service is down, a row violates a constraint, a bug meets a value nobody expected. Wow neither skips the event nor stops the subscription. It retries the handler, and when that does not help, it parks the one aggregate the handler failed on, while the subscription goes on with every other aggregate. This page shows how to tune that for a handler. [Subscriptions, Failures and Replay](../concepts/subscriptions#failures-park-one-aggregate) explains the model.

## What happens when a handler throws

1. **Retry.** The handler is called again within its retry budget: 5 attempts in all, waiting 100 ms after the first failure and twice as long after each next one (the `multiplier`, 2), up to 5 s. An attempt runs as long as it takes unless the handler sets a `timeout`.
2. **Park.** When the budget is spent, the subscription records in the failure ledger the subscription, the aggregate, the version it stopped at and the error. The aggregate's later events wait; every other aggregate goes on.
3. **Retry later.** The parked aggregate is retried over a longer horizon: 10 times, the first 3 minutes after it parked, each next one twice as long after the previous one started, about two days in all. Each retry runs the handler again with its whole budget. A failure that no retry can change, an unrecoverable one, is not retried this way.
4. **Retry or skip.** When those retries are spent too, the aggregate waits for an operator, who retries it, reading its stream again from the version it stopped at, or skips it, moving past the events it held back without handling them.

A rejection of a command a saga derived is not a failure: it is never retried and goes to the saga's `@OnRejected` handler ([Write a Saga](./sagas#handle-a-rejection)). Without a handler for it, the rejection parks the saga's source aggregate, whatever context the command went to, where 9.x logged a failed derived command and went on: a command to another context always asks for its rejection's receipt, which parks the aggregate when it comes back. The same goes for a `CommandRejectedException` a saga's handler lets through from `context.call`: it goes to the `@OnRejected` handler of the command it called. Without one, or when it comes from a `CommandClient` the handler uses, it parks the aggregate at once, without a retry. In a projection or a reactor, a `CommandRejectedException` is a failure like any other: retried, then parked.

## Give a handler its own budget

A handler that calls a slow or flaky system needs a budget of its own. `@Retry` on a processor class applies to all its handlers; on a handler, it overrides its processor's, setting by setting:

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/ShipmentNotifier.kt#notifier -->

```kotlin
/**
 * Tells the buyer of a shipped order that it is on its way. The notification service is a remote call that may be
 * slow or briefly down, so the reactor gives it its own budget: five attempts, from one second apart up to thirty, and
 * ten seconds for each, before the order is parked for the handler in the failure ledger. A buyer the service does not
 * know is a failure no retry can fix, so it parks the order at once.
 *
 * A reactor starts at the end of the log: deploying it does not notify the buyers of the orders shipped before.
 */
@Reactor
@Retry(
    maxAttempts = 5,
    initialBackoff = "1s",
    maxBackoff = "30s",
    timeout = "10s",
    unrecoverable = [UnknownBuyerException::class],
)
class ShipmentNotifier(private val notifications: BuyerNotifications) {
    @OnEvent
    suspend fun onOrderShipped(event: StateTypedEvent<OrderState, OrderShipped>, context: SubscriptionContext) {
        val buyer = event.scope.ownerId ?: return
        notifications.orderShipped(buyer, event.ref.id, key = context.invocationKey)
    }
}
```

| Setting | Means | Default |
| --- | --- | --- |
| `maxAttempts` | The calls in all, at least 1 | 5 |
| `initialBackoff` | The wait after the first failure | 100 ms |
| `multiplier` | How many times longer each next wait is than the one before, at least 1 | 2 |
| `maxBackoff` | The longest wait between attempts | 5 s |
| `timeout` | How long one attempt may run: a longer one is cancelled and counts as failed | None: an attempt runs as long as it takes |
| `unrecoverable` | Exception classes that park the aggregate at once, without a retry | |
| `recoverable` | Exception classes that are retried within the budget, even where the subscription would take them for an outage | |
| `systemic` | Exception classes that are an outage, not the aggregate's: a downstream the handler calls that is down. They pause the partition, as [below](#how-a-failure-is-classified) | |

Durations are written as Kotlin durations (`500ms`, `2s`, `1m 30s`) or ISO-8601 (`PT2S`). A setting left out comes from the processor's `@Retry`, else from the host's `wow.subscription.retry.*`, whose defaults the table gives. A handler's own lists of exception classes replace its processor's.

A `@Blocking` handler runs on a virtual thread that a timeout cannot interrupt: its attempt counts as failed when the call returns.

The host overrides the annotation per subscription, setting by setting, without a new build. A subscription's key is its context's name and its own, `<context>.<processor.function>`:

```yaml
wow:
  subscription:
    retry:
      max-attempts: 5
  subscriptions:
    "[eshop-ordering.ShipmentNotifier.onOrderShipped]":
      retry:
        max-attempts: 8
        multiplier: 3
        timeout: 20s
```

A key that names no subscription of the node stops it at startup, so a misspelled one does not go unnoticed. The host classifies no exception: the lists are the handler's. A job's subscription is configured under its job instead, `wow.schedules.<job>.retry.*` ([Scheduled Commands](./scheduling#run-a-recurring-job)).

## How a failure is classified

Not every failure is the aggregate's own, so each one is classified before it is counted:

| Class | Examples | What happens |
| --- | --- | --- |
| Unrecoverable | A class in the handler's `unrecoverable`; an `UnrecoverableHandlingException` the handler throws; a value a store or a broker refuses for good (a record too large, say) | The aggregate is parked at once, and its parked retries skip it: only an operator retries it |
| Recoverable | Most exceptions | Retried within the budget, then the aggregate is parked |
| Systemic | A class in the handler's `systemic`; a store, a broker or a connection that is down; a broker that refuses the node's credentials; 3 aggregates of a partition failing within 10 seconds with no success between (`wow.subscription.systemic.*`) | The whole partition pauses: one attempt at a time probes it, from 1 s apart up to 30 s, until a handling goes through. Meanwhile no aggregate spends its budget or is parked |
| Backpressure | A command refused as busy (`wow.command.busy`), a wait that ran out, a query store's bulkhead, a 429 with `Retry-After` | The entry is retried from 100 ms up to 10 s apart, with jitter and at least the `Retry-After`, spending none of the handler's attempts. Each refusal halves the partition's concurrency and each success raises it again. After 20 refusals of one entry (`wow.subscription.backpressure.max-attempts`) the aggregate is parked |

Wow's own failures come first, whatever the lists name: a saga that sends a command its `@Sends` does not declare, or derives what is no command request, and a Wow API used in a way it cannot serve, park at once; an attempt past its `timeout` is retried. Several of them are `IllegalStateException`s, so a list that names that class does not turn the code's mistake into retries. Then the handler's own `recoverable`, `unrecoverable` and `systemic` lists, which match the exception the handler threw. When a class is in several, the nearer one in its class hierarchy decides; on a tie, `recoverable`, then `systemic`. Then the classifiers read the exception and each of its causes in turn: the application's `FailureClassifier` beans, then the modules' (the PostgreSQL module's for its driver, the client's for the network), then Wow's own rules, which know its stores, brokers and queries. An application's classifier returns a class for the failures it knows and null for the rest: the exceptions of a driver or a client its handlers call directly, say. A failure nothing classifies is recoverable.

The failures counted towards a pause did spend their attempts; once the partition is paused, nothing does. A pause is not a retry budget: a partition stays paused as long as the outage lasts.

The handler above marks `UnknownBuyerException` unrecoverable: the notification service does not know the buyer, and asking again will not change that, so the order is parked at once for someone to look at.

## Parked aggregates

The failure ledger lives in the service's own store, beside the checkpoints. The long-horizon retries are set by `wow.subscription.parked.*`, or per subscription by `wow.subscriptions.[<context>.<processor.function>].parked.*`:

| Setting | Means | Default |
| --- | --- | --- |
| `max-retries` | The automatic retries of a parked aggregate; 0 turns them off | 10 |
| `initial-backoff` | The wait before the first one; each next one waits twice as long after the previous one started | 3 min |
| `claim-ttl` | How long one retry holds the entry before another node may retry it, such as the partition's next owner. It cancels nothing | 2 min |

An unrecoverable failure is parked but not retried automatically: no retry would change it.

Operators see the parked aggregates of every service in the ops console, with the error ([what it records](#what-a-parked-aggregate-records)) and the version each stopped at, and retry or skip them there: [The Ops Service and Console](../operate/ops-console). A webhook can tell a team when an aggregate parks or spends its retries.

Parked timers are listed there too, under the subscription `wow.timers.sends`, one entry per target aggregate. Nothing retries them automatically. Retrying the entry sends its timers again with fresh attempts; skipping it cancels them with the outcome `SKIPPED`. The entry is recorded before its timer is parked, so a parked timer always has one; when the ledger cannot be written, the timer is not parked but sent again after its backoff. A node that stops between the two leaves an entry whose timer is sent again and parked once more; an entry with no timer parked behind it is resolved by a retry or a skip, which find nothing to send.

So are the aggregates the background work on a type's histories could not read: those the access walk skipped, under `wow.access.walk`, and the snapshots a rebuild could not rewrite, under `wow.snapshot.rewrite` ([Rebuild and Replay](../operate/replay#access-attributes)). Nothing retries them automatically. Retrying an entry runs the work on that aggregate again; skipping it resolves the entry and leaves undone what the work could not do.

Before retrying, fix the cause: deploy the corrected handler, or bring the outside system back. A retried aggregate reads its stream again from the version it stopped at, so its handler sees every event it missed, in order (on a state topic, an operator's retry goes on past a version the topic did not deliver: [A version a state topic did not deliver](#version-gap)); an operator's retry spends none of its automatic retries. Skipping marks the entry resolved: the events it held back are never handled, and the aggregate's next events are. What a skip discards ends when the skip has settled in the subscription, not when its entry left the ledger: an event of the aggregate the subscription takes between the two is skipped with the ones held back, and every event it takes after the skip answers is handled.

### A version a state topic did not deliver {#version-gap}

A subscription that reads another context's state topic expects each aggregate's records at every version: the transport sink publishes every commit ([State event](../reference/protocols#state-event)). A record above the version after the last one the subscription saw of its aggregate parks the aggregate at the version missing, before the record is handled, so its handler sees nothing past the gap. The records after it wait behind the park. It parks at once, without spending the handler's attempts: retrying the record in place cannot bring the missing one about. Its reason names the code and what each action does:

```text
me.ahoo.wow.runtime.subscription.VersionGapException: 'wow.subscription.version-gap' an earlier version of the aggregate
  was not delivered wait and a late record resolves it in order or retry by hand to accept the gap and go on from the next
  version or skip to drop what it holds
```

- **Wait.** The automatic retries read the topic again from the record that showed the gap, in version order: a late record resolves the park, handled before the records after it. A record that never comes leaves the aggregate parked for an operator once the retries are spent.
- **Retry by hand.** An operator's retry accepts the gap: it goes on from the next version present, and the node's log names the versions it passed. Use it when the version is known to be gone, such as a commit the publishing service's operator skipped, or a record its broker refused for good.
- **Skip.** The entry is resolved and the records held behind it are never handled, as for any parked aggregate.

A subscription remembers the last version of the 4,096 aggregates it saw most recently in each partition, in memory. An aggregate it does not remember, first seen since its node started or since its topic was created again ([Kafka](../operate/kafka#reading-other-contexts-streams)), or forgotten since, is not checked: its record's version is where the next one is checked from. The context's own log is never checked; it has no gap to show.

### What a parked aggregate records {#what-a-parked-aggregate-records}

A parked aggregate's reason tells an operator what failed, never a value it failed on. It leaves the service: the ops routes, the console, the park webhook and the ops context all carry it. A reason is the failure's class name, then, after `: `, what is known of it, then each of at most three of its causes, `caused by` its class name and what is known of it:

```text
me.ahoo.wow.kernel.UnreadableEventException: event 'shop.order.Placed' revision 1 not readable;
  caused by tools.jackson.databind.exc.MismatchedInputException: cannot read 'quantity' as int
```

What is known of a failure is never its message, which may quote the value that failed (a card number in a field that takes a number, the key of a row a unique index refused). It is one of:

- **A description** a `FailureDescriber` gives from the failure's structured fields: where in a document it failed and the type it read as (Jackson), a write's error code and category (MongoDB), the SQL state and the constraint, table and column the server names (JDBC), the protocol's error and the topics it names (Kafka), the server's error code (ClickHouse). A describer returns a `FailureDescription`, built only from enum constants, names (letters, digits, `_`, `$`, `.` and `-`), paths, types and numbers: no part of it takes free text, so it cannot carry a message on. The modules on the classpath bring theirs; the application declares its own as `FailureDescriber` beans, for the exceptions of its own code and clients, and they are asked first.
- **The message of a failure written for a caller**: a `WowProblemException`, a command's rejection, a decide function's rejection, which the caller reads anyway.
- **Nothing**: a failure none describes, the application's own exceptions among them, is recorded by its class name alone.

Since the application's own exceptions are recorded by their class name alone, give a failure an operator should tell apart a class of its own, named for what failed, rather than an `IllegalStateException` with a message. The eShop's payment gateway throws one when it cannot be reached, and a parked payment's reason reads `…PaymentGatewayUnreachableException`, without the payment its message names:

<!-- sample: examples/eshop/payment/payment-domain/src/main/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/PaymentGateway.kt#unreachable -->

```kotlin
/**
 * The payment gateway could not be reached: a technical failure, which a retry may get past. A parked payment's reason
 * records a failure of the application's own by its class name alone, never its message, which names the payment; so
 * the class says what failed, and the message stays in the node's log.
 */
class PaymentGatewayUnreachableException(message: String) : IllegalStateException(message)
```

When the class is not enough, declare a `FailureDescriber` for it, which describes it from its fields.

The full failure, its message and its stack trace, is in the node's log, logged when the aggregate parks with the subscription, the aggregate and the version; search for those to read it. The class name comes first, so the console's error type, and the type the ops context groups the entries by, is the text before the first `: `.

## How a command is retried

This page so far is about handlers. A command is retried at several layers too, and every layer keeps its request id: a retry is the same request, so the target decides it once.

**In the kernel**, before the caller is answered:

- **Request ids.** A command whose request id its aggregate type already committed, in the same tenant, is not decided again. It is answered with that commit: `duplicate: true`, its version, but not the first answer's results. A request id another command type committed is refused, `wow.command.request-id-reused` (409). A command sent without a request id takes its command id.
- **Version conflicts.** Another writer appended first: the attempt is compensated and the command decided again on the newer state, at most 3 attempts in all, a limit no setting changes. Then it fails with `wow.command.contended` (409, `Retry-After: 1`), which may be sent again with the same request id. A conflict with an append of the same request is that request's duplicate instead.
- **Cached state.** A command refused on a cached state, or one that changed nothing, is checked against the log. When the log has moved past the cache, the command is decided again on the newer state, without spending an attempt.
- **Unknown outcomes.** When an append's answer is lost, the kernel reads the version the append would have taken. Its own commit is a success, another commit of the same request a duplicate, another request's commit a conflict. When it still cannot tell, it answers `wow.command.unknown` (503, `Retry-After: 1`): the command may have committed. A command forwarded to its partition's owner that gets no reply within `wow.cluster.forward-timeout` and the 2 seconds after it is answered the same, and is not run where it arrived, which could decide it twice. One the owner surely never received is run where it arrived.

**By source**, once the kernel has answered:

| Source | Who retries | How |
| --- | --- | --- |
| An HTTP caller | The caller | The server never retries a request. A 409 `wow.command.contended`, a 503 or a 429 is sent again with the same `Wow-Request-Id` ([Retry safely](./http#retry-safely)), and so is a gateway's bare 502 or 504, whose command's outcome is unknown. Wow's Kotlin client sends the first three again itself, and leaves a gateway's 502 or 504 to its caller: 5 attempts, 200 ms apart and doubling, waiting a `Retry-After` up to 5 s (`wow.peer.retry.*`); one that asks for longer is not waited for, and the call fails. It moves to another node of the peer, spending no attempt, when it cannot connect to one or the node answers that it is closing. |
| A saga | Its subscription | A derived command's request id comes from the commit, its target, its command type and its count among those, or the request's own `requestId`, so handling the commit again sends the same requests. Each derived command is settled on its own: a rejection goes to `@OnRejected` and stops none of the others. Any other failure fails the handling, which is retried within its budget and then parks the commit's aggregate, as [above](#what-happens-when-a-handler-throws). A busy target is backpressure. |
| A timer | The timer | A fired command's request id is the timer's and its slot's. A rejection settles the timer: it is not retried, and no `@OnRejected` sees it. A failure leaves the timer pending, tried again from 1 s, doubling up to 5 min, 10 times in all (`wow.timers.max-attempts`), then parked under `wow.timers.sends`. A busy target is tried again a second later, spending no attempt, up to 20 times in a row; the next refusal spends one, so a target that stays busy parks the timer in the end. |
| The Kafka command intake | Its subscription | A command delivered again is a duplicate. A rejection is neither retried nor parked: it goes back to the sender as a receipt when the sender asked for one. A failure is retried within the subscription's budget and then parks the target aggregate at the command's offset, holding its later commands. A busy target is backpressure. |

**When a store is down**, a saga's failure is systemic, and so is the Kafka command intake's: the subscription pauses the partition and probes until a command or an event goes through again, and no aggregate spends its budget or parks ([How a failure is classified](#how-a-failure-is-classified)). The node's health reports the `subscriptions` component degraded while a partition is paused, and, with `wow-observability`, `wow.subscription.paused.partitions` counts them.

**An aggregate that parks** is retried later and then waits for an operator, as [Parked aggregates](#parked-aggregates) describes.

## Test it

A test fails a handler through its fake port. `expectRetried()` checks that the runtime retries the handling after its first attempt, and `expectParked(attempt)` that it parks it after that attempt; both return what the handler threw. They classify it as the subscription does: by the handler's `@Retry`, then the modules' classifiers and Wow's own rules. An application's own `FailureClassifier` beans are the runtime's, so a host test checks them. Handling the same commit again, `whenRedelivered()`, is how a test shows that a retry is safe:

<!-- sample: examples/eshop/ordering/ordering-domain/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/ShipmentNotifierTest.kt#test -->

```kotlin
@Test
fun `a failed notification should be asked for again with the same key`() = reactorSpec(handlers) {
    val order = paid()
    failures += IllegalStateException("The mail service is down.")
    whenEvent(OrderShipped, by = ShipOrder::class) {
        // Within the notifier's own budget, the runtime asks again.
        expectRetried().assert().isInstanceOf(IllegalStateException::class.java)
    }
    // The retry hands the same commit again: the same key, so the mail service sends it once.
    whenRedelivered()
    sent.single().third.assert()
        .isEqualTo("${order.ref.type.context}.ShipmentNotifier.onOrderShipped:${order.ref}@3:0")
}
```

The waits and the parked retries are the runtime's: the test DSL calls the handler once per `whenEvent` or `whenRedelivered`, and tells what the runtime would do next without doing it.
