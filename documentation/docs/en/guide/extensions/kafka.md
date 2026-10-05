---
title: Kafka
description: Use Kafka for distributed command, domain-event, and state-event buses.
---

# Kafka

`wow-kafka` implements distributed `CommandBus`, `DomainEventBus`, and `StateEventBus` contracts. Use it when one bounded context runs across processes and Kafka consumer groups must distribute messages. Prefer `in_memory` for a single-process development or test runtime; do not add a broker only for speculative scale.

Having the module on the classpath only makes the implementation available. Starter creates a bus only when the classpath, `wow.kafka.enabled`, the corresponding `*.bus.type`, and Kafka connection properties all match.

## Architecture Overview

Wow converts framework messages to Kafka records and wraps received records in acknowledgeable exchanges. Kafka owns topics, partitions, replication, retention, consumer groups, and offset persistence. The application still owns broker operations, topic policy, and business idempotency.

### High-Level Flow

The send path is `CommandGateway`/event publisher → Wow bus → Kafka. The receive path is Kafka → Wow exchange → command, event, projection, or Saga processor. Both `send` and `receiver` retain Reactor's non-blocking contract.

### Class Hierarchy

Since 9.3.0 `KafkaCommandBus`, `KafkaDomainEventBus`, and `KafkaStateEventBus` are the core `TransportCommandBus`, `TransportDomainEventBus` and `TransportStateEventBus` over a `KafkaTransport` (see [Transport SPI](../command/internals/transport.md#transport-spi)). `KafkaTransport` owns the producer, the consumer, readiness anchoring and receive retry; the core bus owns encoding, decoding, the key and topic checks and the decode-failure policy. Each bus differs only in its topic converter. A bus can also be built over an existing transport: `KafkaCommandBus(transport, topicConverter, decodeFailureHandler)`.

### Three Buses, Three Topic Kinds

| Bus | Selection property | Default suffix |
|---|---|---|
| Command | `wow.command.bus.type=kafka` | `.command` |
| Domain event | `wow.event.bus.type=kafka` | `.event` |
| State event | `wow.eventsourcing.state.bus.type=kafka` | `.state` |

## End-to-End Message Flow

The sender marks a message read-only, uses its aggregate ID as the record key, and waits for the `KafkaSender` result. The receiver derives topics from the subscription, joins a consumer group, decodes each record, and creates an exchange. The processor acknowledges the offset through that exchange after handling completes.

This is not an exactly-once business guarantee. Broker redelivery, handler failure, and process termination still require idempotent command and event handling.

## Installation

Use the module directly:

```kotlin
implementation("me.ahoo.wow:wow-kafka")
```

With Starter, request the actual Gradle capability:

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities {
        requireCapability("me.ahoo.wow:kafka-support")
    }
}
```

Do not declare both the capability and `wow-kafka` unless dependency resolution requires it.

## Configuration

This is the minimum explicit configuration when Kafka carries all three buses:

```yaml
spring:
  application:
    name: order-service

wow:
  command:
    bus:
      type: kafka
  event:
    bus:
      type: kafka
  eventsourcing:
    state:
      bus:
        type: kafka
  kafka:
    bootstrap-servers:
      - localhost:9092
```

`wow.kafka.bootstrap-servers` has no default. Defaults are `enabled=true`, `topic-prefix=wow.`, `receiver.prefetch-batches=1`, `receiver.max-deferred-commits=500`, `receiver.retry-attempts=3`, `receiver.retry-backoff=10s`, and `receiver.decode-failure-strategy=FAIL`. `close-timeout` (since 9.3.0) bounds how long closing a producer waits to flush its buffered records; unset, it is `wow.shutdown-timeout`, so a close never outlasts the runtime's shutdown deadline (the Kafka client alone waits without bound). A `KafkaTransport` built by hand takes the `closeTimeout` of the `SenderOptions` it is given.

### Bus Type Selection

Each bus independently supports `kafka`, `redis`, `in_memory`, or `no_op`. Do not infer that every bus uses Kafka merely because the capability is present; verify all three `*.bus.type` values and the resulting bean types.

### SenderOptions and ReceiverOptions

`wow.kafka.properties` applies to producer and consumer, while `wow.kafka.producer` and `wow.kafka.consumer` override common entries for one side. Starter fixes string serializers/deserializers. Authentication, TLS, acks, timeouts, and consumption policy remain native Kafka client properties.

### Receiver Retry Policy

The receive stream retries consecutive failures according to `retry-attempts` and `retry-backoff`; only once they are exhausted does the error reach the dispatcher, which stops the runtime. Since 9.3.0 the retry is the core `TransportFailurePolicy`, the same for Kafka and Redis (bean `kafkaTransportFailurePolicy`; `KafkaReceiverPolicy` no longer has a `retrySpec`). `prefetch-batches` and `max-deferred-commits` must be positive; attempts and backoff must not be negative. Invalid values are rejected while the runtime is wired.

Kafka's `RetriableException` and its subclasses (timeouts, leader changes, unavailable brokers) are registered as `RECOVERABLE` since 9.3.0 (`KafkaRecoverableExceptionProvider`), so `RetryableFilter` and the event-store append resolution retry a send that failed on them.

### Decode Failure Policy

The strategy selects the `TransportDecodeFailureHandler` bean (`TransportDecodeFailureHandler.FAIL` or `.ACKNOWLEDGE`; until 9.2 the bean type was `KafkaRecordDecodeFailureHandler`). A record fails to decode when its value is not the bus's message JSON, or its key or topic does not match the decoded message. `FAIL` is the default: a malformed record is not retried (decoding happens after the receive retry); it stays uncommitted and the receive stream fails, which stops the runtime until the record is dealt with. `ACKNOWLEDGE` acknowledges and skips the record, which can cause unrecoverable data loss. Use it only with a dead-letter, audit, and replay procedure.

## Topic Naming Rules

Default names are `${topic-prefix}${contextAlias}.${aggregateName}.command|event|state`. Applications can provide `CommandTopicConverter`, `EventStreamTopicConverter`, or `StateEventTopicConverter`. These converters do not create topics or manage partition count, replication, or retention. Since 9.3.0 a bus calls its converter once per bounded context and aggregate name and reuses the name, so a converter must return the same topic for the same aggregate.

## Partition Strategy

The record key is `aggregateId.id`, so Kafka's partitioner normally routes records for one aggregate to one partition and preserves partition order. Changing partition count or the partitioner changes that mapping and must be evaluated under Kafka's native migration and ordering semantics.

## Auto-Configuration

`KafkaAutoConfiguration` requires Wow to be enabled, `wow-kafka` classes to exist, and `wow.kafka.enabled=true`. It then creates each implementation only when that bus selects Kafka.

### Bean Wiring

Auto-configuration provides topic converters, a `ReceiverOptionsCustomizer`, `KafkaReceiverPolicy`, a decode-failure handler, and the selected distributed buses. `@ConditionalOnMissingBean` applies only to extension points marked in source; it does not make every Kafka bean freely replaceable.

### ConditionalOnKafkaEnabled

`wow.kafka.enabled=false` disables Kafka auto-configuration but does not rewrite `*.bus.type=kafka`. Select another available bus at the same time, or the runtime will be missing the required distributed bean.

### ReceiverOptionsCustomizer

Provide the existing `ReceiverOptionsCustomizer` only for receiver changes that native `wow.kafka.consumer` properties cannot express. Avoid a customizer for ordinary Kafka client options.

## Producer Optimization

Batching, compression, acks, and retries are Kafka producer settings. Use broker and producer evidence first, then tune through `wow.kafka.producer`; Wow does not duplicate Kafka's validation or compatibility rules.

## Consumer Optimization

Throughput depends on partitions, consumer instances, handler latency, and poll/commit settings. Prefer native consumer tuning and handler concurrency evidence; increasing `prefetch-batches` alone can hide a slow processor.

## Consumer Groups

`MessageSubscription.receiverGroup` becomes Kafka `group.id`. Kafka owns assignment and rebalance. Before deployment, verify that each runtime uses the intended group and that unrelated logical processors do not accidentally compete in one group.

Since 9.3.0 a dispatcher (command, domain event, state event, projection, saga, snapshot) opens **one consumer per bounded context**, subscribed to all of that context's aggregate topics; up to 9.2 it opened one consumer per aggregate topic. The group ID is unchanged: it is still the dispatcher name (`<context>.CommandDispatcher`, …). Instances per dispatcher drop from "aggregate types" to "bounded contexts", and so do rebalance participants.

**Rolling upgrade from 9.2.** 9.2 and 9.3 instances can share the same groups. Kafka's group protocol carries each member's own subscription, and the assignors Wow uses (Kafka's defaults, `RangeAssignor` then `CooperativeStickyAssignor`) assign each topic's partitions only among the members subscribed to that topic. During the upgrade a 9.2 instance's per-topic consumers and a 9.3 instance's per-context consumer therefore split each topic's partitions between them, and every rebalance (an instance leaving or joining) hands partitions over with their committed offsets. Delivery stays at-least-once: records handled but not yet committed when a partition moves are delivered again to the new owner. Commands are deduplicated by the event store's request ID check; event processors must be idempotent as before. The `Mixed-Version` CI workflow verifies this with the released 9.2.3 image and the build under test in the same groups, including a restart of the 9.3 member while commands flow (no command lost or applied twice). Keep `partition.assignment.strategy` identical on all members, as Kafka requires.

One consumer now serves all of a context's topics, so a dispatcher that is slow on one aggregate backpressures (pauses) the whole context's consumer rather than one topic's.

## Key Design Decisions

These constraints come from the current `KafkaTransport`, `TransportMessageBus` and their tests, not from a general Kafka tutorial.

### 1. String Serialization at the Kafka Layer

Wow writes framework JSON into the record value and uses string serializers at the Kafka client layer. Wow/Jackson model evolution owns wire compatibility; Kafka stores the bytes.

### 2. Read-Only Message Protection

The sender calls `message.withReadOnly()` before asynchronous delivery. This prevents later mutation of the same in-process message object; it is not cross-process tamper protection or signing.

### 3. Manual Offset Acknowledgment

The exchange's `acknowledge()` commits handled offsets, while `max-deferred-commits` retains gaps caused by out-of-order completion: a commit never moves past the earliest unacknowledged record. Unacknowledged messages may be delivered again, which is the expected at-least-once recovery boundary.

Reactor Kafka stops polling while `max-deferred-commits` acknowledged offsets wait for a commit, so the buses also start a commit as soon as that many are waiting (Reactor Kafka's `commitBatchSize`, capped at `max-deferred-commits`; a smaller value set through a `ReceiverOptionsCustomizer` is kept). Otherwise offsets are committed every `commitInterval` (5 s by default). Polling pauses only while an earlier record is still in flight or a commit is running. The limit is per consumer: one Wow receiver is one consumer, and its `max-deferred-commits` counts acknowledged offsets across all the topics and partitions it subscribes to. The commit trigger is applied after `ReceiverOptionsCustomizer`s, so it always matches the final `maxDeferredCommits`; a larger `commitBatchSize` is capped, logged once at INFO.

::: info Changed in 9.2.3
Up to 9.2.2 the default was `max-deferred-commits=1` without the commit trigger: after each acknowledged record the consumer paused until the next periodic commit, so a receiver handled about one poll per `commitInterval` (5 s). The default is now 500, Kafka's default `max.poll.records`. A deployment that set `max-deferred-commits` explicitly keeps its value and now commits after that many acknowledgements instead of pausing.
:::

### 4. Send Feedback

Each send waits for the `KafkaSender` result of its record and reports the producer's exception as a Reactor error. The returned `Mono<Void>` completes after producer feedback, not after a downstream consumer processes the message.

## Monitoring and Observability

Observe broker availability, producer errors, consumer lag, rebalances, decode failures, and handler errors. Add `opentelemetry-support` when Wow spans are required; the Kafka capability does not configure an exporter or Collector.

## Troubleshooting

The current properties, implementation, or tests verify these failures:

- missing `wow.kafka.bootstrap-servers`: required `KafkaProperties` binding cannot complete;
- unsafe receiver bounds: runtime wiring throws `IllegalArgumentException`;
- failure to anchor the initial assigned offset: receiver readiness fails and must not be reported as ready;
- malformed JSON: retry under `FAIL`, or skip only under explicit `ACKNOWLEDGE`.

### Common Issues

Separate connection, topic, consumer-group, and message-content failures before inspecting the corresponding client logs and broker state.

#### 1. Connection Timeout

Check `bootstrap-servers`, DNS, TLS/SASL, and network policy. Wow does not pre-validate Kafka addresses or credentials.

#### 2. Unknown Topic or Partition

Check the fully converted topic name and the broker's topic-creation policy. A Kafka module on the classpath is not evidence that a topic exists.

#### 3. Frequent Consumer Rebalancing

Inspect instance churn, processing time, `max.poll.interval.ms`, and group settings. Rebalance belongs to Kafka; Wow reacts to assignment and revoke events.

#### 4. Message Decoding Failures

Retain the raw record, topic/partition/offset, and exception. The default `FAIL` prevents silent loss. Prepare isolation and replay before selecting `ACKNOWLEDGE`.

### Monitoring Metrics

Start with Kafka client and broker producer-error, request-latency, consumer-lag, rebalance, and commit metrics. Wow metrics and traces add framework-processing context.

## Complete Configuration Example

```yaml
wow:
  command:
    bus:
      type: kafka
  event:
    bus:
      type: kafka
  eventsourcing:
    state:
      bus:
        type: kafka
  kafka:
    bootstrap-servers: [kafka-0:9092, kafka-1:9092]
    topic-prefix: 'wow.'
    producer:
      acks: all
    consumer:
      auto.offset.reset: earliest
    receiver:
      prefetch-batches: 1
      max-deferred-commits: 500
      retry-attempts: 3
      retry-backoff: 10s
      decode-failure-strategy: FAIL
```

The producer and consumer values are examples, not a universal cluster recommendation. Choose them from the Kafka version, durability target, and capacity tests.

## Best Practices

- Select every bus explicitly instead of using defaults as production architecture.
- Operate topics, consumer groups, retention, and replay through an explicit runbook.
- Keep handlers idempotent and verify redelivery with fault injection.
- Rehearse compatibility and recovery before changing partitions, topic converters, or decode policy.

Focused check:

```bash
./gradlew :wow-kafka:check
```

This checks module unit and contract tests. It does not prove that your Kafka cluster, ACLs, topic policy, or target-environment wiring works.

## Related Topics

Next, read [Infrastructure configuration](../../reference/config/infrastructure.md) to establish broker, configuration, recovery, and admission evidence. Read [OpenTelemetry](./opentelemetry.md) when tracing is required.
