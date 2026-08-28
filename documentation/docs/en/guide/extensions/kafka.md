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

The send path is `CommandGateway`/event publisher → Wow bus → Kafka. The receive path is Kafka → Wow exchange → command, event, projection, or Saga processor. Both `send` and `receive` retain Reactor's non-blocking contract.

### Class Hierarchy

`KafkaCommandBus`, `KafkaDomainEventBus`, and `KafkaStateEventBus` share the send, receive, retry, and decode pipeline in `AbstractKafkaBus`. They differ only in message type, topic converter, and exchange type.

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

`wow.kafka.bootstrap-servers` has no default. Defaults are `enabled=true`, `topic-prefix=wow.`, `receiver.prefetch-batches=1`, `receiver.max-deferred-commits=1`, `receiver.retry-attempts=3`, `receiver.retry-backoff=10s`, and `receiver.decode-failure-strategy=FAIL`.

### Bus Type Selection

Each bus independently supports `kafka`, `redis`, `in_memory`, or `no_op`. Do not infer that every bus uses Kafka merely because the capability is present; verify all three `*.bus.type` values and the resulting bean types.

### SenderOptions and ReceiverOptions

`wow.kafka.properties` applies to producer and consumer, while `wow.kafka.producer` and `wow.kafka.consumer` override common entries for one side. Starter fixes string serializers/deserializers. Authentication, TLS, acks, timeouts, and consumption policy remain native Kafka client properties.

### Receiver Retry Policy

The receive stream retries consecutive failures according to `retry-attempts` and `retry-backoff`. `prefetch-batches` and `max-deferred-commits` must be positive; attempts and backoff must not be negative. `KafkaReceiverPolicy` rejects invalid values while the runtime is wired.

### Decode Failure Policy

`FAIL` is the default: a malformed record terminates the current receive stream and enters retry. `ACKNOWLEDGE` acknowledges and skips the record, which can cause unrecoverable data loss. Use it only with a dead-letter, audit, and replay procedure.

## Topic Naming Rules

Default names are `${topic-prefix}${contextAlias}.${aggregateName}.command|event|state`. Applications can provide `CommandTopicConverter`, `EventStreamTopicConverter`, or `StateEventTopicConverter`. These converters do not create topics or manage partition count, replication, or retention.

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

## Key Design Decisions

These constraints come from the current `AbstractKafkaBus` and its tests, not from a general Kafka tutorial.

### 1. String Serialization at the Kafka Layer

Wow writes framework JSON into the record value and uses string serializers at the Kafka client layer. Wow/Jackson model evolution owns wire compatibility; Kafka stores the bytes.

### 2. Read-Only Message Protection

The sender calls `message.withReadOnly()` before asynchronous delivery. This prevents later mutation of the same in-process message object; it is not cross-process tamper protection or signing.

### 3. Manual Offset Acknowledgment

The exchange's `acknowledge()` commits handled offsets, while `max-deferred-commits` retains gaps caused by out-of-order completion. Unacknowledged messages may be delivered again, which is the expected at-least-once recovery boundary.

### 4. Correlation Metadata for Send Feedback

Each send uses correlation metadata to receive `KafkaSender` success or failure. The returned `Mono<Void>` completes after producer feedback, not after a downstream consumer processes the message.

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
      max-deferred-commits: 1
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
