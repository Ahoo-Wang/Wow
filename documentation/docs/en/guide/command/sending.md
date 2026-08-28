---
title: Send Commands
description: Choose among the in-process CommandGateway, aggregate HTTP routes, and the global command facade, then interpret JSON, SSE, and CommandResult correctly.
outline: deep
---

# Send Commands

The same command can enter Wow through an in-process or HTTP boundary. The entry point does not change aggregate business rules, but it does change route metadata, response shape, and whether the caller can observe intermediate stages.

## Choose an Invocation Entry Point

| Scenario | Entry point | Return |
| --- | --- | --- |
| Same application process | `CommandGateway` | `Mono<CommandResult>` or `Flux<CommandResult>` |
| Aggregate-facing public HTTP API | Generated aggregate command route | Final JSON result or SSE stage stream |
| Generic HTTP facade | `POST /wow/command/send` | Final JSON result |
| Kotlin service-to-service call | [API Client](./api-client.md) | Reactive or synchronous final result |

Prefer the entry point that preserves the required semantics with the smallest exposed surface. An in-process application does not need to turn a command into HTTP first, and a remote call should not pretend to be an in-process Gateway.

## Construct a CommandMessage

See [Define Commands](./definition.md) for command payloads and target metadata. For an in-process call, use `toCommandMessage()` to create the runtime envelope:

```kotlin
val message = createOrder.toCommandMessage(
    aggregateId = "order-1",
    requestId = "create-order-1",
)
```

`toCommandMessage()` combines command metadata and explicit arguments to resolve the bounded context, aggregate, tenant, owner, space, expected version, and creation flag. Reuse a stable `requestId` when retrying the same business intent; do not turn a lost response into a new business operation.

## In-Process CommandGateway

`CommandGateway` adds command-body validation, a request-ID precheck, and stage waiting to `CommandBus`. The API remains reactive: `sendAndWait` returns one final result, while `sendAndWaitStream` returns a stream of accepted stage signals.

```kotlin
val result: Mono<CommandResult> = commandGateway.sendAndWait(
    message,
    CommandWait.processed(message.commandId),
)
```

Convenience methods cover `SENT`, `PROCESSED`, and `SNAPSHOT`:

```kotlin
commandGateway.sendAndWaitForSent(message)
commandGateway.sendAndWaitForProcessed(message)
commandGateway.sendAndWaitForSnapshot(message)
```

These methods select an observation point; they do not change command processing. Do not call `block()` on a Reactor event loop or inside Wow's core processing chain.

## Aggregate HTTP Routes

Aggregate-specific routes are generated from command and aggregate metadata. They carry a concrete request-body schema and may place tenant, owner, aggregate ID, or command properties in paths and headers. Treat the target service's current generated OpenAPI or `RouterSpecs` route catalog as the authority for HTTP methods, paths, and scope.

Examples in the current generated `example-domain` contract are:

```text
POST /owner/{ownerId}/cart/add_cart_item
PUT  /owner/{ownerId}/cart/change_quantity
```

These facts do not mean another service has the same paths. Do not infer a bounded-context prefix: annotations, the aggregate owner mode, and route metadata can all change the final contract. The generated contract declares both `application/json` and `text/event-stream` for aggregate command routes.

## Global Command Facade

The global facade is the fixed `POST /wow/command/send` route. Its body is the command payload, while `Command-*` request headers supply command type, aggregate target, wait plan, and routing data:

```bash
curl -X POST http://order-service:8080/wow/command/send \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -H 'Command-Type: me.example.CreateOrder' \
  -H 'Command-Aggregate-Id: order-1' \
  -H 'Command-Request-Id: create-order-1' \
  -H 'Command-Wait-Stage: PROCESSED' \
  -d '{"items":[],"address":{},"fromCart":false}'
```

The current generated OpenAPI declares only `application/json` for this global route. It serves generic clients and callers that cannot bind an aggregate-specific schema. The security layer must still authenticate the caller and authorize the target aggregate.

## JSON and SSE Responses

Aggregate command routes support two response modes:

- `Accept: application/json` uses `sendAndWait` and returns only the final `CommandResult` for the selected wait plan;
- `Accept: text/event-stream` uses `sendAndWaitStream` and emits each stage as an SSE event whose event name is the `CommandStage` and whose data is that stage's `CommandResult`.

Stage signals arrive in observed order; callers must not assume a fixed sequence. A disconnect or timeout ends only this HTTP wait. It does not cancel a command already accepted by the command bus.

The current route contract for global `/wow/command/send` accepts JSON only. Use a generated aggregate command route that declares the media type when SSE is required. The current [API Client](./api-client.md) does not provide SSE either.

## CommandResult Core Fields

| Field | Meaning |
| --- | --- |
| `stage` | Observed stage such as `SENT`, `PROCESSED`, or `SNAPSHOT` |
| `commandId` / `waitCommandId` | Current command ID and the command ID that owns the wait plan |
| `contextName` / `aggregateName` / `tenantId` / `aggregateId` | Target aggregate identity |
| `aggregateVersion` | Aggregate version known at this stage; it can be `null` before processing |
| `requestId` | Caller-provided idempotency key |
| `function` | Function information for the stage signal |
| `errorCode` / `errorMsg` / `bindingErrors` | Success state and failure details; `succeeded` is derived from the error code |
| `result` | Result values accumulated from accepted signals |
| `signalTime` | Time when the signal was generated |

A successful result proves only the observation point named by `stage`. Do not infer snapshot, projection, event-processor, or Saga completion from `PROCESSED`.

## Next: Choose Completion Semantics

Based on read-after-write visibility, side effects, and latency goals, read “Completion semantics” (`command/completion`) and choose the earliest stage that satisfies the response contract. See “Command reliability” (`command/reliability`) for timeouts, duplicate requests, and downstream failures.
