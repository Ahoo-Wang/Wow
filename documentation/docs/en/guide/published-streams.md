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

A handler takes that context's event class and the view of its state, both from its api module. The service's Manifest finds the aggregate from the context's contract; an event two of its aggregates share is named with `@OnEvent(aggregates = [<Context>Contract.<Aggregate>.QUALIFIED_NAME])`:

<!-- sample: examples/eshop/payment/payment-domain/src/main/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/OrderPaymentSaga.kt#peer-stream -->

```kotlin
@OnEvent
fun onOrderStockConfirmed(
    event: StateTypedEvent<OrderStateView, OrderStockConfirmed>
): CommandRequest<RequestPayment> = CommandRequest.of(
    RequestPayment(event.ref.id, event.state.totalAmount),
    event.ref.id
)
```

- **The event classes.** `OrderStockConfirmed` is the ordering api's class. The payment's domain module depends on `eshop-ordering-api`, whose jar carries the ordering's contract (`META-INF/wow/contract.json`); nothing else is configured.
- **The state.** `OrderStateView` is generated into the ordering api from its contract, one view per aggregate whose stream carries its state, named after the state class with `View` appended. It holds the state's fields: those the ordering requires are non-null, the others nullable. A class of the api (`OrderItem`, `OrderStatus`) is referenced; a type of the ordering's domain is copied into the view as a nested class; free-shape content is an `ObjectNode` (a free value a `JsonNode`). Fields the view does not have are ignored, so the ordering adding one breaks no reader. When the upstream publishes without the state (`publishState = false`), its api generates no view of it, and the handler declares its state nullable: a non-nullable state of a stream without state fails when the node starts.
- **The aggregates.** All the aggregates in `aggregates` belong to one context, written `context.aggregate`.
- **The kinds.** A saga, a projection and a reactor read a published stream the same way.

## Events the subscriber has no class for

A handler that takes a `StateTypedEvent<S, E>` is called only for the events that are an `E`. A handler that takes the whole `StateEvent<S>` gets each event as the api's class when the event's revision is the one the api's contract declares, and as a `PublishedEvent` otherwise: its `name` (`eshop-ordering.order.OrderShipped`), its `revision` and its JSON `payload`. So a subscriber built against an older api keeps working when the upstream adds a revision, and decides for itself what to do with the payload.

The node does not start when a handler takes an event class the contract on its class path does not declare.

## Where it starts

A subscription to another context's stream that has no checkpoint yet starts by its kind:

- **A projection** starts at the beginning of the topic, so it fills its read model with what was published before.
- **A saga's or a reactor's handler** starts where its service joins the stream. When no other subscription of the service has read the topic yet, that is the beginning, so a service that joins later still gets what was published before. When one has, it is the end, so a handler added later to a service that already reads the stream does not act on its history again: a new saga handler does not send commands for events its service read long ago. The Manifest writes this start as `JOIN`.

`@OnEvent(start = OnEvent.Start.BEGINNING)` or `END` fixes it, and the host's `wow.subscriptions.[<context>.<processor.function>].start` overrides both, with `beginning`, `end` or an instant. After its first checkpoint it goes on from there.

The service reads the topic without a Kafka consumer group: its offsets are in its own checkpoint store, beside its other subscriptions' positions.

## History imported from 9.x {#history-imported-from-9x}

A service migrated from 9.x publishes the history `migrate-data` imported into its log too, so its peers' projections can build their read models from it. Each record up to the cut-over the import recorded is marked imported (`imported` in the [state event](../reference/protocols#state-event)). A saga or a reactor passes over such a record as it passes over an event it does not handle, since 9.x already acted on it; a projection, and any system reading the topic, takes it. A service that was never migrated publishes no record so marked.

## Answer the upstream

A saga that reacts to a peer's event often answers with a command to that peer: the payment pays the order once its gateway approves the payment.

<!-- sample: examples/eshop/payment/payment-domain/src/main/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/OrderPaymentSaga.kt#peer-command -->

```kotlin
@OnEvent
fun onPaymentSucceeded(
    event: StateTypedEvent<PaymentState, PaymentSucceeded>
): CommandRequest<PayOrder> = CommandRequest.of(PayOrder(event.ref.id, event.state.amount), event.state.orderId)
```

`CommandRequest.of(body, id)` finds the ordering's order from the contract in the ordering api's jar, and the command goes to the ordering's command topic, `wow.eshop-ordering.commands`. A rejection comes back as a receipt, to the saga's `@OnRejected` handler:

<!-- sample: examples/eshop/payment/payment-domain/src/main/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/OrderPaymentSaga.kt#peer-rejected -->

```kotlin
/** An order that refuses its payment has the payment refunded. */
@OnRejected
fun onPayRejected(rejected: CommandReceipt.Rejected<PayOrder>): CommandRequest<RefundPayment> =
    CommandRequest.of(
        RefundPayment("The order ${rejected.ref.id} refused the payment: ${rejected.error.message}"),
        rejected.source.id,
    )
```

[Write a Saga](./sagas) covers derived commands, receipts and rejections in full.

## External consumers

A system that is not a Wow service reads the same topics. Each record is an envelope keyed by the aggregate, so one aggregate's records stay in order: [Wire Protocols](../reference/protocols#kafka-envelope) describes it.

## Test it

A test hands the handler a commit of the upstream's aggregate with the test DSL's `stateEvent`, built from the api's event class and its state view: [Test Domain Code](./testing#sagas).
