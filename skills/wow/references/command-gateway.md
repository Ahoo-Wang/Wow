# Command Gateway

The command gateway is the core component for receiving and sending commands. It extends the command bus with idempotency, wait strategies, and command validation.

## API Usage

### Convenience Methods

```kotlin
val command = CreateAccount(balance = 1000, name = "John").toCommandMessage()

// Wait until command is sent to bus
commandGateway.sendAndWaitForSent(command)
    .doOnSuccess { result ->
        println("Command sent: ${result.commandId}")
    }

// Wait until command is processed by aggregate
commandGateway.sendAndWaitForProcessed(command)
    .doOnSuccess { result ->
        if (result.succeeded) {
            println("Command processed! Version: ${result.aggregateVersion}")
        }
    }

// Wait until aggregate snapshot is created
commandGateway.sendAndWaitForSnapshot(command)
    .doOnSuccess { result ->
        println("Snapshot created for aggregate: ${result.aggregateId}")
    }
```

### Base Methods

```kotlin
// Send with specific wait strategy
commandGateway.send(command, waitStrategy)
    .flatMap { exchange ->
        exchange.waitStrategy.waiting()
    }

// Send and wait for final result
commandGateway.sendAndWait(command, waitStrategy)
    .doOnSuccess { result -> ... }

// Streaming updates as command progresses through stages
commandGateway.sendAndWaitStream(command, waitStrategy)
    .doOnNext { result ->
        println("Stage: ${result.stage} - Succeeded: ${result.succeeded}")
    }
```

## Wait Strategies

### WaitingForStage

| Stage | Signal Generated When |
|-------|----------------------|
| `SENT` | Command published to bus/queue |
| `PROCESSED` | Command processed by aggregate root |
| `SNAPSHOT` | Snapshot generated |
| `PROJECTED` | Projection of event completed |
| `EVENT_HANDLED` | Event processed by event processor |
| `SAGA_HANDLED` | Event processed by Saga |

```kotlin
WaitingForStage.sent(commandId)
WaitingForStage.processed(commandId)
WaitingForStage.snapshot(commandId)
WaitingForStage.projected(waitCommandId, contextName, processorName, functionName)
WaitingForStage.eventHandled(...)
WaitingForStage.sagaHandled(...)
```

### WaitingForChain

Wait for the **entire saga chain** to complete. Used for end-to-end request-reply semantics in distributed operations.

For example, a client initiating a bank transfer can wait until both the saga has processed the event and the resulting downstream command has been fully processed.

```http
POST /account/sourceId/prepare
Command-Wait-Stage: SAGA_HANDLED
Command-Wait-Tail-Stage: SNAPSHOT
Command-Wait-Tail-Processor: TransferSaga
```

Programmatic usage:

```kotlin
val waitStrategy = SimpleWaitingForChain.chain(
    tailStage = CommandStage.SNAPSHOT,
    // optional: tailProcessor, tailContextName
)
commandGateway.sendAndWait(message, waitStrategy)
```

This guarantees the entire distributed workflow has completed when the response returns.

## CommandGateway vs CommandBus

| Feature | CommandBus | CommandGateway |
|---------|------------|----------------|
| Send commands | ✓ | ✓ |
| Wait strategies | ✗ | ✓ |
| Command validation | ✗ | ✓ |
| Idempotency checking | ✗ | ✓ |
| Real-time result streaming | ✗ | ✓ |
| Convenience methods | ✗ | ✓ |

## CommandResult Properties

| Property | Type | Description |
|----------|------|-------------|
| `id` | `String` | Unique result identifier |
| `waitCommandId` | `String` | Command ID being waited on |
| `stage` | `CommandStage` | Current processing stage |
| `contextName` | `String` | Bounded context name |
| `aggregateName` | `String` | Aggregate name |
| `tenantId` | `String` | Tenant identifier |
| `aggregateId` | `String` | Aggregate instance ID |
| `aggregateVersion` | `Int?` | Version after processing |
| `requestId` | `String` | Request identifier for idempotency |
| `commandId` | `String` | Command identifier |
| `errorCode` | `String` | Error code ("Ok" on success) |
| `errorMsg` | `String` | Error message |
| `bindingErrors` | `List<BindingError>` | Validation errors |
| `succeeded` | `Boolean` | Whether processing succeeded |

## Error Handling

### CommandResultException

When command processing fails, `sendAndWait` throws `CommandResultException`:

```kotlin
commandGateway.sendAndWaitForProcessed(command)
    .doOnError { error ->
        when (error) {
            is CommandResultException -> {
                val result = error.commandResult
                println("Error: ${result.errorCode} - ${result.errorMsg}")
                result.bindingErrors.forEach {
                    println("Field ${it.name}: ${it.msg}")
                }
            }
            is CommandValidationException -> { /* validation failed */ }
            is DuplicateRequestIdException -> { /* duplicate request */ }
        }
    }
```

### Retry Logic

```kotlin
commandGateway.sendAndWaitForProcessed(command)
    .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
        .filter { error -> isTransientError(error) })
    .subscribe()

fun isTransientError(error: Throwable): Boolean {
    return when (error) {
        is CommandValidationException -> false
        is DuplicateRequestIdException -> false
        is CommandResultException -> false
        else -> true  // Network/infrastructure errors
    }
}
```

## Idempotency

Commands are deduplicated by `RequestId`:

```http
POST /account/create_account
Command-Request-Id: {{$uuid}}
```

Configure idempotency checking:

```yaml
wow:
  command:
    idempotency:
      enabled: true
      bloom-filter:
        expected-insertions: 1000000
        ttl: PT60S
        fpp: 0.00001
```

## LocalFirst Mode

Reduces network IO by processing commands locally when possible:

```yaml
wow:
  command:
    bus:
      type: kafka
      local-first:
        enabled: true  # Default
```

When enabled, if the command gateway determines the command can be processed locally, it sends to both the local and distributed command bus, eliminating network IO for local operations.

## Command Rewriter

Rewrite command metadata (`aggregateId`/`tenantId`) and body before processing:

```kotlin
@Service
class ResetPwdCommandBuilderRewriter(
    private val queryService: SnapshotQueryService<UserState>
) : CommandBuilderRewriter {

    override val supportedCommandType: Class<ResetPwd>
        get() = ResetPwd::class.java

    override fun rewrite(commandBuilder: CommandBuilder): Mono<CommandBuilder> {
        return singleQuery {
            projection { include(Documents.ID_FIELD) }
            condition {
                nestedState()
                PHONE_VERIFIED eq true
                PHONE eq commandBuilder.bodyAs<ResetPwd>().phone
            }
        }.dynamicQuery(queryService)
            .switchIfEmpty { IllegalArgumentException("Phone not bound.").toMono() }
            .map {
                commandBuilder.aggregateId(it.getValue(MessageRecords.AGGREGATE_ID))
            }
    }
}
```

Register via Spring's `@Service` annotation.

## HTTP Headers

| Header | Description |
|--------|-------------|
| `Command-Wait-Stage` | Wait stage: SENT, PROCESSED, SNAPSHOT, PROJECTED, EVENT_HANDLED, SAGA_HANDLED |
| `Command-Aggregate-Id` | Target aggregate ID |
| `Command-Request-Id` | Idempotency key |
| `Command-Wait-Tail-Stage` | Tail wait stage for chain strategy |
| `Command-Wait-Tail-Processor` | Tail processor name for chain strategy |

## Troubleshooting

**Command times out**: Check aggregate dead-letter state, verify wait strategy, increase timeout.

**DuplicateRequestIdException**: Use unique `requestId` per command, wait for TTL expiration.

**Aggregate state not rebuilding**: Verify `@OnSourcing` handlers, check event revision compatibility.

**CommandValidationException**: Verify JSR-303 annotations on command body, check `jakarta.validation` dependency.
