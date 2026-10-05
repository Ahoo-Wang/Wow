---
title: Command Processing Pipeline
description: Follow a command from CommandGateway through event append, message acknowledgement, event publication, and the PROCESSED boundary.
outline: deep
---

# Command Processing Pipeline

This page explains how a non-`Void` command crosses the Wow runtime. See [Send Commands](../sending.md) for application APIs and [Completion Semantics](../completion.md) for choosing a wait stage; this page covers implementation order and failure boundaries only.

## Component map

```mermaid
flowchart TB
    Caller --> Gateway[DefaultCommandGateway]
    Gateway --> Bus[CommandBus]
    Bus --> Dispatcher[CommandDispatcher]
    Dispatcher --> Handler[Command Filter chain]
    Handler --> Processor[AggregateProcessorFilter]
    Processor --> Aggregate[SimpleCommandAggregate]
    Aggregate --> Store[EventStore.append]
    Processor --> Ack[exchange.acknowledge]
    Ack --> EventBus[DomainEventBus.send]
    EventBus --> StateBus[StateEventBus.send attempt]
    StateBus --> Processed[PROCESSED notifier]
```

`CommandBus` transports envelopes; `CommandDispatcher` creates processors by named aggregate and maps the same aggregate ID to a stable scheduling group; the `CommandFilter` chain defines processing boundaries. Aggregate execution, event persistence, transport acknowledgement, domain-event publication, and state-event publication are separate operations.

## Pre-send pipeline

Every `DefaultCommandGateway` send path first runs the same `check`:

1. A body implementing `CommandValidator` validates itself before the Jakarta `Validator` runs.
2. `RequestIdChecker.check(aggregateId, requestId)` performs the request-ID precheck; `false` terminates with `DuplicateRequestIdException`. Validation runs first, so a command that fails it does not consume its request ID (since 9.2.3).
3. `CommandBus.send` is invoked only after both checks complete; when it fails, `RequestIdChecker.release` gives the reservation back.

`sendAndWait` and `sendAndWaitStream` also verify that the wait plan supports a `Void` command, register a wait handle, propagate the wait plan into the Header, and then send. `sendAndWaitForSent` is a separate fast path: it allocates no handle and propagates no wait Header, but synthesizes a `SENT` result after `CommandBus.send` succeeds.

The precheck is not the durable concurrency decision. Atomic request-ID and version conflicts remain the responsibility of `EventStore.append`; see [Failures and Idempotency](../reliability.md).

## Bus to Dispatcher

`CommandBus.receiver`, with a runtime-owned subscription for the runtime's `CommandDispatcher`, produces `ServerCommandExchange` instances. `CommandDispatcher` first filters `isVoid` messages: it acknowledges them without entering the aggregate command chain. Ordinary commands are dispatched by `NamedAggregate`.

Each `AggregateCommandDispatcher` resolves aggregate metadata and calculates a group key from the aggregate ID. Commands for one ID retain scheduler affinity while multiple IDs can share a worker. This prevents concurrent execution for one aggregate inside this process; it does not replace the EventStore's durable version constraint.

`DefaultCommandHandler` executes the Filter chain sorted by `@Order`. Its core order is:

```text
ProcessedNotifierFilter
  -> AggregateProcessorFilter
    -> SendDomainEventStreamFilter
      -> SendStateEventFilter
```

The first Filter is the outermost wrapper, so it observes completion or failure of the entire inner pipeline, not just the aggregate function return.

## Aggregate recovery and invocation

`AggregateProcessorFilter` puts the `ServiceProvider` and aggregate metadata into the exchange, then creates an `AggregateProcessor` for the aggregate identity. The default `RetryableAggregateProcessor`:

- constructs an empty StateAggregate for a create command;
- restores other commands through `StateAggregateRepository`;
- creates a `SimpleCommandAggregate` from that state; the aggregation pattern constructs a command root with the state, while the non-aggregation pattern reuses the state object;
- rebuilds state and retries only failures marked recoverable, using the built-in backoff policy;
- starts every attempt from the exchange as it was before the first one: the error, event stream, aggregate version, command-invoke result, command results and command aggregate of a failed attempt are not carried over, so a wait signal never reports a version that was not stored (since 9.2.3);
- runs the `@OnError` function once, after the final failure (unwrapped from a retry exhaustion), on the most recently loaded aggregate: the last attempt's, or an earlier attempt's when the last one failed before loading; not when no attempt loaded one, and not for a custom `CommandAggregate` that is not a `SimpleCommandAggregate`, whose own `process` handles its errors (since 9.2.3).

`SimpleCommandAggregate.process` then checks expected version, create permission, owner, space, deleted/recovery state, and command-function availability. It looks the command up in the aggregate's `AggregateModel`, which is compiled once when the aggregate metadata is parsed: the command entries (with the matching after-command functions and the built-in delete, recover and resource-tag handlers), the error functions and the sourcing table that every state aggregate of the type shares. Handlers take the command root or state root as an argument, so nothing is bound per aggregate instance or per command (since 9.3.0). The entry invokes the matching function and ordered after-command functions, flattens their returns into one `DomainEventStream`, and stores it on the exchange.

## In-memory sourcing and append

After the function produces an event stream, `SimpleCommandAggregate` first calls `state.onSourcing(eventStream)` on the current working instance and then calls `EventStore.append(eventStream)`. The order makes the new state available during the same processing attempt, but append success remains the authoritative commit point:

```text
invoke command
  -> build DomainEventStream
  -> source events into in-memory state
  -> EventStore.append
  -> mark command state STORED
```

Before append, the exchange aggregate version is set to the event-stream version; the command state returns to `STORED` only after append succeeds. An append failure moves this command aggregate to `EXPIRED`, so the working instance cannot continue. See [Event Sourcing](../../domain/event-sourcing.md) for the history and recovery contract.

## Ack/event-send order

`AggregateProcessorFilter` applies `finallyAck` to aggregate processing. The exchange transport acknowledgement therefore runs whether aggregate processing completes or fails; only the successful path enters the next Filter. `SendDomainEventStreamFilter` obtains the stream from the exchange and waits for `DomainEventBus.send` before continuing. The following `SendStateEventFilter`, when state is initialized, copies the event stream and current state into a `StateEvent` and attempts `StateEventBus.send`.

The effective order is:

```text
EventStore.append
  -> command exchange ack
  -> DomainEventBus.send
  -> StateEventBus.send attempt
  -> PROCESSED signal
```

When the aggregate fails before producing a stream, the exchange is still acknowledged but the event-send Filter is not entered. If events were appended and `DomainEventBus.send` then fails, the transport acknowledgement has already happened, the error propagates outward, `StateEventBus.send` is not attempted, and `PROCESSED` observes failure. Domain-event publication failure cannot be read as “events were not stored,” and the command transport cannot be assumed to redeliver it.

`StateEventBus.send` has a different failure boundary: `SendStateEventFilter` uses `logErrorResume()` to log the error and resume with empty completion before continuing the Filter chain. A successful `PROCESSED` therefore proves only that state-event publication was attempted and returned, not that the StateEvent was published; snapshots and projections that depend on that input may not receive it. See [Event Dispatch Pipeline](../../event/dispatch.md) for event-side consumption.

## `PROCESSED` error boundary

`ProcessedNotifierFilter` wraps the inner chain with `MonoCommandWaitNotifier`:

- normal inner completion creates a `PROCESSED` signal from exchange function, version, result, and any business error;
- an inner error creates a failed signal and then propagates the original error to the outer error handler; a retry-exhausted wrapper is reduced to its cause first;
- no signal is produced when there is no wait Header or the target does not require `PROCESSED`;
- notification is fire-and-forget, so notification failure is logged without replacing the command result.

Successful `PROCESSED` therefore means aggregate execution, event append, command acknowledgement, and `DomainEventBus.send` completed, and `SendStateEventFilter` also completed; when state is initialized, the `StateEventBus.send` attempt returned. It does not guarantee successful StateEvent publication or mean snapshot, projection, event handler, or Saga completion. A failed signal alone also cannot prove that no event was appended; inspect authoritative history as described in [Failures and Idempotency](../reliability.md).

## API tiers

The types on this page are implementation, not application API. Since 9.3.0 wow-core says so in code:

- `CommandAggregate`, its supertype `AggregateProcessor`, `CommandAggregateFactory` and `SimpleCommandAggregateFactory` are marked `@WowSpi`. Code that supplies its own command aggregate opts in with `@OptIn(WowSpi::class)`; without it the compiler warns. They keep their binary signatures within a minor line, and a minor release may change them in its release notes.
- `AggregateProcessorFactory`, `RetryableAggregateProcessorFactory`, `AggregateProcessorFilter`, `SendDomainEventStreamFilter`, `SimpleStateAggregate`, the function-metadata types (`FunctionAccessorMetadata`, `InjectParameter`, `FirstParameterKind`, `AfterCommandFunctionMetadata`, `MessageFunctionRegistrar`, `SimpleMessageFunctionRegistrar`), the event-dispatcher bases (`CompositeEventDispatcher`, `AbstractEventFunctionRegistrar`, `EventHandler`), `COMMAND_GATEWAY_FUNCTION`, and the exchange accessors for the processor, the metadata, the invoke result, the event stream setter and the version setter are `@InternalWowApi`: Wow's own modules wire them and they may change in any release.
- `RetryableAggregateProcessor`, `SimpleCommandAggregate`, `CommandState`, the compiled aggregate model (`AggregateModel`, its command entries and compiled functions), the exchange attribute keys, the function accessors and the aggregate and state event dispatchers are `internal`.

Applications send commands through `CommandGateway`, handle them with `@OnCommand` functions and read `ServerCommandExchange.getEventStream()`; none of that needs an opt-in. A command function that needs the current state takes a `ReadOnlyStateAggregate<S>` parameter (for example to read `initialized`), not a `CommandAggregate`.

## Source entry points

- [`DefaultCommandGateway`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/command/DefaultCommandGateway.kt)
- [`CommandDispatcher`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/dispatcher/CommandDispatcher.kt) and [`AggregateCommandDispatcher`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/dispatcher/AggregateCommandDispatcher.kt)
- [`AggregateProcessorFilter`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/dispatcher/AggregateProcessorFilter.kt) and [`SendDomainEventStreamFilter`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/dispatcher/SendDomainEventStreamFilter.kt)
- [`RetryableAggregateProcessor`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/RetryableAggregateProcessor.kt) and [`SimpleCommandAggregate`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/SimpleCommandAggregate.kt)
- [`EventStore`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/eventsourcing/EventStore.kt), [`SendStateEventFilter`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/eventsourcing/state/SendStateEventFilter.kt), and [`NotifierFilters`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/command/wait/NotifierFilters.kt)
