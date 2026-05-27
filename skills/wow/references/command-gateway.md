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

Wait for a saga handler and for the downstream commands reported by that saga signal to reach a configured tail stage. Use it for request-reply semantics in distributed operations when a saga emits follow-up commands.

For example, a client initiating a bank transfer can wait until the transfer saga has processed the event and the resulting downstream command has reached the configured tail stage.

```http
POST /account/sourceId/prepare
Command-Wait-Stage: SAGA_HANDLED
Command-Wait-Context: transfer
Command-Wait-Processor: TransferSaga
Command-Wait-Tail-Stage: SNAPSHOT
Command-Wait-Tail-Context: transfer
Command-Wait-Tail-Processor: TransferSaga
```

Programmatic usage:

```kotlin
val waitStrategy = SimpleWaitingForChain.chain(
    waitCommandId = command.commandId,
    function = NamedFunctionInfoData(
        contextName = "transfer",
        processorName = "TransferSaga",
        name = "onEvent",
    ),
    tailStage = CommandStage.SNAPSHOT,
    tailFunction = NamedFunctionInfoData(
        contextName = "transfer",
        processorName = "TransferSaga",
        name = "onEvent",
    ),
)
commandGateway.sendAndWait(command, waitStrategy)
```

The completion guarantee is limited to the configured main saga function and the tail commands present in the saga wait signal. It does not prove that unrelated asynchronous work has finished.

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
| `function` | `FunctionInfoData` | Function that produced the wait signal |
| `errorCode` | `String` | Error code ("Ok" on success) |
| `errorMsg` | `String` | Error message |
| `bindingErrors` | `List<BindingError>` | Validation errors |
| `result` | `Map<String, Any>` | Additional result data |
| `signalTime` | `Long` | Signal timestamp in milliseconds |
| `succeeded` | `Boolean` | Derived from `ErrorInfo`; true when the result is successful |

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

When enabled and there are local subscribers, local-first sends the message to the local bus first and then sends a copy to the distributed bus. If local-first is disabled or no local subscriber exists, only the distributed bus is used.

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
| `Command-Wait-Context` | Bounded context for function-level waiting; defaults to the command context when blank |
| `Command-Wait-Processor` | Processor name for function-level waiting |
| `Command-Wait-Function` | Function name for function-level waiting |
| `Command-Wait-Timout` | Current source spelling for wait timeout in milliseconds |
| `Command-Aggregate-Id` | Target aggregate ID |
| `Command-Aggregate-Version` | Expected aggregate version for conflict control |
| `Command-Request-Id` | Idempotency key |
| `Command-Local-First` | Override local-first routing for this command |
| `Command-Wait-Tail-Stage` | Tail wait stage for chain strategy |
| `Command-Wait-Tail-Context` | Tail bounded context for chain strategy |
| `Command-Wait-Tail-Processor` | Tail processor name for chain strategy |
| `Command-Wait-Tail-Function` | Tail function name for chain strategy |
| `Command-Tenant-Id` | Tenant ID, when not provided by route/static tenant |
| `Command-Owner-Id` | Owner ID, when the aggregate route requires ownership |
| `Command-Aggregate-Context` | Aggregate context for generic command routes |
| `Command-Aggregate-Name` | Aggregate name for generic command routes |
| `Command-Type` | Fully qualified command body type for generic command routes |
| `Command-Header-*` | Prefix for custom command headers |

Note: public documentation may spell the timeout header with `Timeout`, but the current source constant is `Command-Wait-Timout`. Use the source spelling when validating the current checkout.

## Troubleshooting

**Command times out**: Check aggregate dead-letter state, verify wait strategy, increase timeout.

**DuplicateRequestIdException**: Use unique `requestId` per command, wait for TTL expiration.

**Aggregate state not rebuilding**: Verify `@OnSourcing` handlers, check event revision compatibility.

**CommandValidationException**: Verify JSR-303 annotations on command body, check `jakarta.validation` dependency.
