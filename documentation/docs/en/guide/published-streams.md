---
title: Publish and Consume Streams
description: Choose what an aggregate publishes to other contexts, and read another context's published stream in a saga, a projection or a reactor, with the peer's own event classes.
outline: deep
---

# Publish and Consume Streams

A context shares what happened in it by publishing its aggregates' event streams. Other contexts read them as they read their own: a handler names the aggregate, takes the peer's event classes, and keeps its position in its own checkpoints. This page publishes the ordering's order and reads it in the payment context. [Between Contexts](../concepts/cross-context) compares this with the other ways contexts talk.

## What a context publishes

Every aggregate publishes its state events by default: one record per commit, with the commit's events and the state after it. The Manifest lists them as the context's `published` streams, and the context's Kafka sink writes them to the topic `wow.<context>.<aggregate>.state`. `@AggregateRoot` changes that per aggregate:

| Setting | Effect |
| --- | --- |
| `published = false` | The aggregate is not published |
| `publishState = false` | Its events are published without the state, for a state too large to send |
| `topic = "…"` | Another topic name |

The ordering publishes its order as it is, on `wow.eshop-ordering.order.state`. A published stream is part of the context's contract: its events and its state's fields are what peers read, and the Manifest's compatibility check treats a removed or retyped field as breaking.

Publishing needs Kafka: [Kafka](../operate/kafka) describes the topics and the envelope.

## Read another context's stream

A handler takes that context's event class, from its api module, and declares its own view of the state. The service's Manifest finds the aggregate from the context's contract; an event two of its aggregates share is named with `@OnEvent(aggregates = [<Context>Contract.<Aggregate>.QUALIFIED_NAME])`:

<!-- sample: example/eshop/payment/payment-domain/src/main/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/OrderPaymentSaga.kt#peer-stream -->

```kotlin
@OnEvent
fun onOrderStockConfirmed(
    event: StateTypedEvent<OrderView, OrderStockConfirmed>,
    context: SagaContext,
): Command<RequestPayment> = context.command(event.ref.id, RequestPayment(event.ref.id, event.state.totalAmount))
```

The view is a class of the subscriber, holding only the fields it reads:

<!-- sample: example/eshop/payment/payment-domain/src/main/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/OrderPaymentSaga.kt#order-view -->

```kotlin
/**
 * The payment context's view of the ordering's order, decoded from the state the ordering context publishes: what
 * the order costs. Reading ignores the rest.
 */
data class OrderView(val totalAmount: BigDecimal = BigDecimal.ZERO)
```

- **The event classes.** `OrderStockConfirmed` is the ordering api's class. The payment's domain module depends on `eshop-ordering-api`, whose jar carries the ordering's contract (`META-INF/wow/contract.json`); nothing else is configured.
- **The state.** The published state is decoded into the handler's view, and fields the view does not have are ignored. When the upstream publishes without the state, declare it nullable, `StateEvent<OrderView?>`: a non-nullable view of a stream without state fails when the node starts.
- **The aggregates.** All the aggregates in `aggregates` belong to one context, written `context.aggregate`.
- **The kinds.** A saga, a projection and a reactor read a published stream the same way.

## Events the subscriber has no class for

A handler that takes a `StateTypedEvent<S, E>` is called only for the events that are an `E`. A handler that takes the whole `StateEvent<S>` gets each event as the api's class when the event's revision is the one the api's contract declares, and as a `PublishedEvent` otherwise: its `name` (`eshop-ordering.order.OrderShipped`), its `revision` and its JSON `payload`. So a subscriber built against an older api keeps working when the upstream adds a revision, and decides for itself what to do with the payload.

The node does not start when a handler takes an event class the contract on its class path does not declare.

## Where it starts

A subscription to another context's stream starts at the beginning of the topic when it has no checkpoint yet, so a service that joins later still gets what was published before. `@OnEvent(start = OnEvent.Start.END)`, or the host's `wow.subscriptions.[<processor.function>].start`, makes it start at the end instead. After its first checkpoint it goes on from there.

The service reads the topic without a Kafka consumer group: its offsets are in its own checkpoint store, beside its other subscriptions' positions.

## Answer the upstream

A saga that reacts to a peer's event often answers with a command to that peer: the payment pays the order once its gateway approves the payment.

<!-- sample: example/eshop/payment/payment-domain/src/main/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/OrderPaymentSaga.kt#peer-command -->

```kotlin
@OnEvent
fun onPaymentSucceeded(
    event: StateTypedEvent<PaymentState, PaymentSucceeded>,
    context: SagaContext,
): Command<PayOrder> = context.command(event.state.orderId, PayOrder(event.ref.id, event.state.amount))
```

`context.command(id, body)` finds the ordering's order from the contract in the ordering api's jar, and the command goes to the ordering's command topic, `wow.eshop-ordering.commands`. A rejection comes back as a receipt, to the saga's `@OnRejected` handler:

<!-- sample: example/eshop/payment/payment-domain/src/main/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/OrderPaymentSaga.kt#peer-rejected -->

```kotlin
/** An order that refuses its payment has the payment refunded. */
@OnRejected
fun onPayRejected(rejected: CommandRejected<PayOrder>, context: ReceiptContext): Command<RefundPayment> =
    context.command(
        rejected.source.id,
        RefundPayment("The order ${rejected.ref.id} refused the payment: ${rejected.error.message}"),
    )
```

[Write a Saga](./sagas) covers derived commands, receipts and rejections in full.

## External consumers

A system that is not a Wow service reads the same topics. Each record is an envelope keyed by the aggregate, so one aggregate's records stay in order: [Wire Protocols](../reference/protocols#kafka-envelope) describes it.

## Test it

A test hands the handler a commit of the upstream's aggregate with the test DSL's `stateEvent`, built from the api's event class and the subscriber's view: [Test Domain Code](./testing#sagas).
