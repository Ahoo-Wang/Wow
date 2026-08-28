---
title: Command Transport and Routing
description: Understand CommandBus contracts, local and distributed implementations, LocalFirst dual-copy admission, Void routing, and the SENT boundary.
outline: deep
---

# Command Transport and Routing

Command transport routes a `CommandMessage` into a `ServerCommandExchange`; it does not execute aggregate business rules. Use each extension's documentation and the [Core Configuration Reference](../../../reference/config/core.md) to select and install a transport. This page does not duplicate dependency or configuration tables.

## CommandBus contract

`CommandBus` is a `MessageBus<CommandMessage<*>, ServerCommandExchange<*>>` with `TopicKind.COMMAND`. Its three core operations expose different boundaries:

- `send`: its `Mono<Void>` completes when the concrete transport accepts the send;
- `receive`: returns exchanges for a `MessageSubscription`;
- `receiver`: exposes transport readiness as well as messages; `runtimeReceiver` also lets WowRuntime control processing admission and quiescence.

`LocalCommandBus` additionally exposes subscriber count and `sendIfSubscribed`. The latter may return `true` only when target local receivers have obtained processing admission and this delivery remains valid; sink acceptance or subscriber count alone is insufficient. `DistributedCommandBus` keeps the same send/receive contract, with persistence, consumer groups, and acknowledgement supplied by its backend.

## InMemory

`InMemoryCommandBus` creates an MPSC unicast sink per `NamedAggregate`: concurrent senders can write while one consuming chain owns commands for each named aggregate. A message becomes read-only before emission and is converted to `SimpleServerCommandExchange`.

Ordinary `send` logs at debug and completes when there is no subscriber, so it proves only that the in-process sink send ended, not that a processor exists. The runtime-owned receiver tracks connection and processing-open state. `sendIfSubscribed` allocates a receipt per delivery and reports success only after all target receivers accept runtime admission.

This implementation is suitable for single-process execution and tests; it provides no cross-process durability.

## Kafka

`KafkaCommandBus` reuses `AbstractKafkaBus`:

- the command's named aggregate is converted to a topic;
- record key is aggregate ID and value is read-only command JSON;
- `send` waits for the Reactor Kafka sender result and reports producer failure as a Reactor error;
- `receive` assigns the subscribed topics to a consumer group and converts records into exchanges holding a `ReceiverOffset`;
- exchange acknowledgement calls `ReceiverOffset.acknowledge()`.

`receiver.readiness` completes only after partition assignment and a conservative initial offset boundary are anchored, avoiding a startup window that could miss messages. Decode failure follows an explicit failure handler; acknowledgement of successfully processed records remains the exchange ack boundary.

## Redis

`RedisCommandBus` uses Redis Streams. `send` writes read-only command JSON to the topic stream under the `msg` field. `receive` creates or reuses a consumer group for each topic, reads from `lastConsumed`, and puts the `XACK` publisher into the exchange.

`receiver.readiness` fires after consumer groups are prepared, while actual reads remain blocked by processing admission. Optional recovery scans and claims eligible pending records. Undecodable records are reported through `RedisMessageBusObserver` and remain pending instead of being presented as successful consumption.

Redis and Kafka have different send-completion conditions; neither means the aggregate was processed. Backend operations, retention, retry, and recovery parameters belong to extension configuration and are not repeated here.

## LocalFirst dual-copy admission

`LocalFirstCommandBus` combines one local and one distributed bus. For a local aggregate whose Header does not explicitly disable local-first, it does not merely choose one route; it creates a marked dual-copy flow:

1. Copy the command, mark it `local_first=true`, and call `localBus.sendIfSubscribed`.
2. The receipt returns `true` only after runtime-owned local receivers are processing-open and confirm that delivery remains valid.
3. Copy the original command again and send it through the distributed bus; the distributed copy's `local_first` value equals the local delivery result.
4. The merged receiver filters and acknowledges a distributed copy marked “handled locally.” If local admission closes or fails, the distributed copy remains eligible for processing.

The distributed copy therefore provides fallback and an observable record. `local_first=true` is an admission-confirmed suppression marker, not a guess based on subscriber count. The original and both copies have independent mutable Headers so the routes cannot rewrite one another.

## Void

`LocalFirstCommandBus.send` forces `local_first=false` on an `isVoid` command, skips the local-first attempt, and uses distributed send only. `CommandDispatcher` then acknowledges and filters `Void` commands with `filterThenAck`, so they never enter the aggregate Filter chain and cannot produce `PROCESSED` or later stages.

The Gateway accordingly accepts only a wait plan with `supportVoidCommand=true`. The built-in `CommandWait.sent` provides that contract; later-stage plans fail before send. The observable boundary for a Void route is transport acceptance, not aggregate execution.

## `SENT` comparison

`SENT` means that the current `CommandBus.send` publisher completed successfully. The concrete fact depends on the implementation:

| Implementation | Already happened before `SENT` | Still not proven by `SENT` |
| --- | --- | --- |
| InMemory | Sink emission ended; it may also complete without subscribers | Processor presence, aggregate execution, persistence |
| Kafka | Producer send result succeeded | Consumer receive or ack, aggregate execution |
| Redis | Stream add completed | Consumer-group processing or XACK |
| LocalFirst | Local delivery attempt ended and distributed send completed | Aggregate processing by either copy |
| Void + LocalFirst | Distributed send completed | Aggregate processing; Dispatcher filters this route |

`sendAndWaitForSent` synthesizes its result directly from this publisher and does not depend on callback Headers. For a stronger guarantee, choose a stage through [Completion Semantics](../completion.md) instead of redefining `SENT`.

## Metrics and tracing entry points

`MetricCommandBus` records `command_bus` `send`, `send_if_subscribed`, and receive-stream operations at the decorator layer while preserving receiver readiness and runtime admission. Tags come from context, aggregate, message, and receiver group; multiple aggregates collapse to a bounded value rather than exposing business IDs as metric dimensions.

OpenTelemetry's `TracingLocalCommandBus` / `TracingDistributedCommandBus` create a producer span at the send boundary and inject trace context into the message Header. `TracingCommandGateway` additionally wraps `sendAndWait` and streaming waits with an end-to-end waiting span. The processing path also has separate decorators for `CommandHandler`, `EventStore`, and `DomainEventBus`; one bus span alone is not proof of end-to-end completion.

See [Observability](../../advanced/observability.md) for runtime activation and exporter configuration. Source entry points: [`CommandBus`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/command/CommandBus.kt), [`InMemoryCommandBus`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/command/InMemoryCommandBus.kt), [`LocalFirstCommandBus`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/command/LocalFirstCommandBus.kt), [`KafkaCommandBus`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-kafka/src/main/kotlin/me/ahoo/wow/kafka/KafkaCommandBus.kt), and [`RedisCommandBus`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-redis/src/main/kotlin/me/ahoo/wow/redis/bus/RedisCommandBus.kt).
