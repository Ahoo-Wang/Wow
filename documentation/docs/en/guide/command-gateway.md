# Command Gateway

The command gateway is the core component in the system for receiving and sending commands, serving as the entry point for commands.
It is an extension of the command bus, not only responsible for command transmission, but also adds a series of important responsibilities, including command idempotency, waiting strategies, and command validation.

## Send Command

![Send Command - Command Gateway](../../public/images/command-gateway/send-command.svg)

## API Usage

The `CommandGateway` interface provides several methods for sending commands and waiting for their results. Below are the main methods and their usage patterns.

### Basic Methods

:::tip
The `toCommandMessage()` extension function converts a command body into a `CommandMessage`. This is provided by the Wow framework and handles setting up the command ID, aggregate ID, and other metadata.
:::

#### send(command, waitStrategy)

The base method that sends a command with a specified wait strategy and returns a `ClientCommandExchange`.

```kotlin
val command = CreateAccount(balance = 1000, name = "John").toCommandMessage()
val waitStrategy = WaitingForStage.processed(command.commandId)

commandGateway.send(command, waitStrategy)
    .flatMap { exchange ->
        // Access the ClientCommandExchange
        // Use the waitStrategy to get the command result
        exchange.waitStrategy.waiting()
    }
    .subscribe { signal ->
        println("Stage: ${signal.stage} - Succeeded: ${signal.succeeded}")
    }
```

#### sendAndWait(command, waitStrategy)

Sends a command and waits for the final result. If the command fails, it throws a `CommandResultException`.

```kotlin
val command = CreateAccount(balance = 1000, name = "John").toCommandMessage()
val waitStrategy = WaitingForStage.processed(command.commandId)

commandGateway.sendAndWait(command, waitStrategy)
    .doOnSuccess { result ->
        println("Command processed: ${result.commandId}")
        println("Aggregate Version: ${result.aggregateVersion}")
    }
    .subscribe()
```

#### sendAndWaitStream(command, waitStrategy)

Returns a `Flux<CommandResult>` for real-time streaming updates as the command progresses through different stages.

```kotlin
val command = CreateAccount(balance = 1000, name = "John").toCommandMessage()
val waitStrategy = WaitingForStage.snapshot(command.commandId)

commandGateway.sendAndWaitStream(command, waitStrategy)
    .doOnNext { result ->
        println("Stage: ${result.stage} - Succeeded: ${result.succeeded}")
        println("Aggregate Version: ${result.aggregateVersion}")
    }
    .subscribe()
```

### Convenience Methods

The `CommandGateway` provides convenience methods that pre-configure common wait strategies:

```kotlin
val command = CreateAccount(balance = 1000, name = "John").toCommandMessage()

// Wait until command is sent to the bus
commandGateway.sendAndWaitForSent(command)
    .doOnSuccess { result ->
        println("Command sent: ${result.commandId}")
    }
    .subscribe()

// Wait until command is processed by the aggregate
commandGateway.sendAndWaitForProcessed(command)
    .doOnSuccess { result ->
        if (result.succeeded) {
            println("Command processed successfully: ${result.commandId}")
            println("New aggregate version: ${result.aggregateVersion}")
        }
    }
    .subscribe()

// Wait until aggregate snapshot is created
commandGateway.sendAndWaitForSnapshot(command)
    .doOnSuccess { result ->
        println("Snapshot created for aggregate: ${result.aggregateId}")
    }
    .subscribe()
```

## Core Concepts

### ClientCommandExchange

`ClientCommandExchange` is the client-side exchange context returned when sending commands via `CommandGateway.send()`. It provides access to:

- **message**: The original `CommandMessage` that was sent
- **waitStrategy**: The `WaitStrategy` used to wait for command processing results
- **attributes**: A mutable map for storing additional exchange-related data

```kotlin
interface ClientCommandExchange<C : Any> {
    val message: CommandMessage<C>
    val waitStrategy: WaitStrategy
    val attributes: MutableMap<String, Any>
}
```

Use `ClientCommandExchange` when you need low-level access to the wait strategy or want to implement custom waiting logic:

```kotlin
commandGateway.send(command, waitStrategy)
    .flatMap { exchange ->
        // Access the command message
        val commandId = exchange.message.commandId
        
        // Use the wait strategy directly
        exchange.waitStrategy.waiting()
            .filter { signal -> signal.stage == CommandStage.PROCESSED }
            .next()
    }
    .subscribe()
```

### CommandResult

`CommandResult` represents the result of a command execution at a specific processing stage. It contains comprehensive information about the command processing outcome.

| Property | Type | Description |
|----------|------|-------------|
| `id` | `String` | Unique identifier for this result |
| `waitCommandId` | `String` | The command ID being waited on |
| `stage` | `CommandStage` | Current processing stage (SENT, PROCESSED, SNAPSHOT, etc.) |
| `contextName` | `String` | Bounded context name |
| `aggregateName` | `String` | Aggregate name |
| `tenantId` | `String` | Tenant identifier |
| `aggregateId` | `String` | Aggregate instance identifier |
| `aggregateVersion` | `Int?` | Aggregate version after processing (null on gateway validation failure or before processing) |
| `requestId` | `String` | Request identifier for idempotency |
| `commandId` | `String` | Command identifier |
| `function` | `FunctionInfoData` | Information about the processing function |
| `errorCode` | `String` | Error code ("Ok" on success) |
| `errorMsg` | `String` | Error message (empty on success) |
| `bindingErrors` | `List<BindingError>` | List of validation errors |
| `result` | `Map<String, Any>` | Additional result data |
| `signalTime` | `Long` | Timestamp when this result was generated |
| `succeeded` | `Boolean` | Whether the command processing succeeded |

### WaitSignal vs CommandResult

- **WaitSignal**: Internal interface used within the wait strategy infrastructure. Contains processing stage information and is used for signaling between components.
- **CommandResult**: Public API for command results. Created from `WaitSignal` and includes additional context like `requestId` and formatted aggregate information.

### CommandGateway vs CommandBus

`CommandGateway` extends `CommandBus` with additional high-level features:

| Feature | CommandBus | CommandGateway |
|---------|------------|----------------|
| Send commands | ✓ | ✓ |
| Wait strategies | ✗ | ✓ |
| Command validation | ✗ | ✓ |
| Idempotency checking | ✗ | ✓ |
| Real-time result streaming | ✗ | ✓ |
| Convenience methods | ✗ | ✓ |

Use `CommandBus` when you only need basic command routing. Use `CommandGateway` for full-featured command handling with wait strategies and validation.

```kotlin
// CommandBus - basic routing only
interface CommandBus : MessageBus<CommandMessage<*>, ServerCommandExchange<*>>

// CommandGateway - extends CommandBus with additional features
interface CommandGateway : CommandBus {
    fun <C : Any> send(command: CommandMessage<C>, waitStrategy: WaitStrategy): Mono<out ClientCommandExchange<C>>
    fun <C : Any> sendAndWait(command: CommandMessage<C>, waitStrategy: WaitStrategy): Mono<CommandResult>
    fun <C : Any> sendAndWaitStream(command: CommandMessage<C>, waitStrategy: WaitStrategy): Flux<CommandResult>
    // ... convenience methods
}
```

## Error Handling

### CommandResultException

When command processing fails, `sendAndWait` throws a `CommandResultException` containing the full `CommandResult` with error details.

```kotlin
commandGateway.sendAndWait(command, waitStrategy)
    .doOnError { error ->
        if (error is CommandResultException) {
            val result = error.commandResult
            println("Command failed at stage: ${result.stage}")
            println("Error code: ${result.errorCode}")
            println("Error message: ${result.errorMsg}")
            
            // Check for validation errors
            if (result.bindingErrors.isNotEmpty()) {
                result.bindingErrors.forEach { bindingError ->
                    println("Field '${bindingError.name}': ${bindingError.msg}")
                }
            }
        }
    }
    .onErrorResume { error ->
        // Handle the error gracefully
        when (error) {
            is CommandResultException -> {
                // Log and return a fallback value
                Mono.empty()
            }
            else -> Mono.error(error)
        }
    }
    .subscribe()
```

### CommandValidationException

Thrown when command validation fails before sending. Contains validation constraint violations.

```kotlin
// Command with validation annotations
data class CreateAccount(
    @field:NotBlank(message = "Name is required")
    val name: String,
    @field:Min(value = 0, message = "Balance must be non-negative")
    val balance: Int
)

commandGateway.sendAndWaitForProcessed(command)
    .doOnError { error ->
        if (error is CommandValidationException) {
            println("Validation failed for command: ${error.command}")
            error.bindingErrors.forEach { bindingError ->
                println("Field '${bindingError.name}': ${bindingError.msg}")
            }
        }
    }
    .subscribe()
```

### DuplicateRequestIdException

Thrown when attempting to process a command with a request ID that has already been processed.

```kotlin
commandGateway.sendAndWaitForProcessed(command)
    .doOnError { error ->
        if (error is DuplicateRequestIdException) {
            println("Duplicate request: ${error.requestId}")
            println("Aggregate: ${error.aggregateId}")
        }
    }
    .onErrorResume(DuplicateRequestIdException::class.java) { error ->
        // Return cached result or ignore duplicate
        Mono.empty()
    }
    .subscribe()
```

### Error Handling Best Practices

1. **Use specific exception handlers**: Handle `CommandResultException`, `CommandValidationException`, and `DuplicateRequestIdException` separately for appropriate responses.

2. **Log error details**: Always log the `errorCode`, `errorMsg`, and `bindingErrors` for debugging.

3. **Implement retry logic for transient failures**:

```kotlin
commandGateway.sendAndWaitForProcessed(command)
    .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
        .filter { error -> isTransientError(error) })
    .subscribe()

// Transient errors are typically network or temporary infrastructure issues
// Do NOT retry validation errors or duplicate request errors
fun isTransientError(error: Throwable): Boolean {
    return when (error) {
        is CommandValidationException -> false  // Validation errors won't succeed on retry
        is DuplicateRequestIdException -> false // Duplicate requests should not be retried
        is CommandResultException -> false      // Business logic errors from aggregate
        else -> true                            // Network/infrastructure errors may be transient
    }
}
```

4. **Handle timeout scenarios**: Configure appropriate timeouts for wait strategies.

```kotlin
commandGateway.sendAndWaitForProcessed(command)
    .timeout(Duration.ofSeconds(30))
    .doOnError(TimeoutException::class.java) { error ->
        println("Command processing timed out")
    }
    .subscribe()
```

## Idempotency

Command idempotency is the principle of ensuring that the same command is executed at most once in the system.

The command gateway uses `IdempotencyChecker` to check the `RequestId` of the command for idempotency.
If the command has already been executed, it will throw a `DuplicateRequestIdException` exception to prevent duplicate execution of the same command.

The following is an example HTTP request showing how to use `Command-Request-Id` in the request to ensure command idempotency:

:::tip
Developers can also customize the `RequestId` through the `requestId` property of `CommandMessage`.
:::

::: code-group
```shell {6} [Http Request]
curl -X 'POST' \
  'http://localhost:8080/account/create_account' \
  -H 'accept: application/json' \
  -H 'Command-Wait-Stage: SNAPSHOT' \
  -H 'Command-Aggregate-Id: sourceId' \
  -H 'Command-Request-Id: {{$uuid}}' \
  -H 'Content-Type: application/json' \
  -d '{
  "balance": 1000,
  "name": "source"
}'
```

```json [Response]
{
  "id": "0V3oAWI60001003",
  "waitCommandId": "0V3oAWGt0001001",
  "stage": "SNAPSHOT",
  "contextName": "transfer-service",
  "aggregateName": "account",
  "tenantId": "(0)",
  "aggregateId": "sourceId",
  "aggregateVersion": 1,
  "requestId": "0V3oAWGt0001001",
  "commandId": "0V3oAWGt0001001",
  "function": {
    "functionKind": "STATE_EVENT",
    "contextName": "wow",
    "processorName": "SnapshotDispatcher",
    "name": "save"
  },
  "errorCode": "Ok",
  "errorMsg": "",
  "result": {},
  "signalTime": 1764297025846,
  "succeeded": true
}
```
:::

### Configuration

```yaml {5-10}
wow:
  command:
    bus:
      type: kafka
    idempotency:
      enabled: true
      bloom-filter:
        expected-insertions: 1000000
        ttl: PT60S
        fpp: 0.00001
```


## Waiting Strategies

*Command waiting strategy* refers to a strategy where the command gateway waits for the command execution result after sending the command.

*Command waiting strategy* is an important feature in the _Wow_ framework, aiming to solve the data synchronization delay problem in _CQRS_ and read-write separation modes.

Currently supported command waiting strategies include:

### WaitingForStage

<p align="center" style="text-align:center;">
  <img  width="95%" src="../../public/images/wait/WaitingForStage.svg" alt="WaitingForStage"/>
</p>

The waiting signals supported by `WaitingForStage` are as follows:

- `SENT`: Generates a completion signal when the command is published to the command bus/queue
- `PROCESSED`: Generates a completion signal when the command is processed by the aggregate root
- `SNAPSHOT`: Generates a completion signal when the snapshot is generated
- `PROJECTED`: Generates a completion signal when the *projection* of the event produced by the command is completed
- `EVENT_HANDLED`: Generates a completion signal when the event produced by the command is processed by the *event processor*
- `SAGA_HANDLED`: Generates a completion signal when the event produced by the command is processed by *Saga*

::: code-group
```shell {4} [Http Request]
curl -X 'POST' \
  'http://localhost:8080/account/create_account' \
  -H 'accept: application/json' \
  -H 'Command-Wait-Stage: SNAPSHOT' \
  -H 'Command-Aggregate-Id: targetId' \
  -H 'Content-Type: application/json' \
  -d '{
  "balance": 1000,
  "name": "target"
}'
```

```json [Response]
{
  "id": "0V3oAdHd0001007",
  "waitCommandId": "0V3oAdHV0001005",
  "stage": "SNAPSHOT",
  "contextName": "transfer-service",
  "aggregateName": "account",
  "tenantId": "(0)",
  "aggregateId": "targetId",
  "aggregateVersion": 1,
  "requestId": "0V3oAdHV0001005",
  "commandId": "0V3oAdHV0001005",
  "function": {
    "functionKind": "STATE_EVENT",
    "contextName": "wow",
    "processorName": "SnapshotDispatcher",
    "name": "save"
  },
  "errorCode": "Ok",
  "errorMsg": "",
  "result": {},
  "signalTime": 1764297052692,
  "succeeded": true
}
```
```text [SSE Response]
id:0V3oCwcv0001002
event:SENT
data:{"id":"0V3oCwcv0001002","waitCommandId":"0V3oCwbn0001001","stage":"SENT","contextName":"transfer-service","aggregateName":"account","tenantId":"(0)","aggregateId":"targetId","aggregateVersion":0,"requestId":"0V3oCwbn0001001","commandId":"0V3oCwbn0001001","function":{"functionKind":"COMMAND","contextName":"wow","processorName":"CommandGateway","name":"send"},"errorCode":"Ok","errorMsg":"","result":{},"signalTime":1764297603701,"succeeded":true}

id:0V3oCwbn0001001
event:PROCESSED
data:{"id":"0V3oCwbn0001001","waitCommandId":"0V3oCwbn0001001","stage":"PROCESSED","contextName":"transfer-service","aggregateName":"account","tenantId":"(0)","aggregateId":"targetId","aggregateVersion":1,"requestId":"0V3oCwbn0001001","commandId":"0V3oCwbn0001001","function":{"functionKind":"COMMAND","contextName":"transfer-service","processorName":"Account","name":"onCommand"},"errorCode":"Ok","errorMsg":"","result":{},"signalTime":1764297603737,"succeeded":true}

id:0V3oCwdB0001003
event:SNAPSHOT
data:{"id":"0V3oCwdB0001003","waitCommandId":"0V3oCwbn0001001","stage":"SNAPSHOT","contextName":"transfer-service","aggregateName":"account","tenantId":"(0)","aggregateId":"targetId","aggregateVersion":1,"requestId":"0V3oCwbn0001001","commandId":"0V3oCwbn0001001","function":{"functionKind":"STATE_EVENT","contextName":"wow","processorName":"SnapshotDispatcher","name":"save"},"errorCode":"Ok","errorMsg":"","result":{},"signalTime":1764297603754,"succeeded":true}
```
```kotlin {1}
commamdGateway.sendAndWaitForProcessed(message)
```
:::

### WaitingForChain

<p align="center" style="text-align:center;">
  <img  width="95%" src="../../public/images/wait/WaitingForChain.svg" alt="WaitingForChain"/>
</p>


::: code-group
```shell {4-6} [Http Request]
curl -X 'POST' \
  'http://localhost:8080/account/sourceId/prepare' \
  -H 'accept: application/json' \
  -H 'Command-Wait-Stage: SAGA_HANDLED' \
  -H 'Command-Wait-Tail-Stage: SNAPSHOT' \
  -H 'Command-Wait-Tail-Processor: TransferSaga' \
  -H 'Content-Type: application/json' \
  -d '{
  "amount": 100,
  "to": "targetId"
}'
```
```json [Response]
{
  "id": "0V3oAkw6000100G",
  "waitCommandId": "0V3oAkvW0001009",
  "stage": "SNAPSHOT",
  "contextName": "transfer-service",
  "aggregateName": "account",
  "tenantId": "(0)",
  "aggregateId": "targetId",
  "aggregateVersion": 2,
  "requestId": "0V3oAkvW0001009",
  "commandId": "0V3oAkw2000100E",
  "function": {
    "functionKind": "STATE_EVENT",
    "contextName": "wow",
    "processorName": "SnapshotDispatcher",
    "name": "save"
  },
  "errorCode": "Ok",
  "errorMsg": "",
  "result": {},
  "signalTime": 1764297082107,
  "succeeded": true
}
```
```text [SSE Response]
id:0V3oCVz9000100M
event:SENT
data:{"id":"0V3oCVz9000100M","waitCommandId":"0V3oCVyv000100L","stage":"SENT","contextName":"transfer-service","aggregateName":"account","tenantId":"(0)","aggregateId":"sourceId","aggregateVersion":null,"requestId":"0V3oCVyv000100L","commandId":"0V3oCVyv000100L","function":{"functionKind":"COMMAND","contextName":"wow","processorName":"CommandGateway","name":"send"},"errorCode":"Ok","errorMsg":"","result":{},"signalTime":1764297501291,"succeeded":true}

id:0V3oCVyv000100L
event:PROCESSED
data:{"id":"0V3oCVyv000100L","waitCommandId":"0V3oCVyv000100L","stage":"PROCESSED","contextName":"transfer-service","aggregateName":"account","tenantId":"(0)","aggregateId":"sourceId","aggregateVersion":4,"requestId":"0V3oCVyv000100L","commandId":"0V3oCVyv000100L","function":{"functionKind":"COMMAND","contextName":"transfer-service","processorName":"Account","name":"onCommand"},"errorCode":"Ok","errorMsg":"","result":{},"signalTime":1764297501299,"succeeded":true}

id:0V3oCVzW000100R
event:SENT
data:{"id":"0V3oCVzW000100R","waitCommandId":"0V3oCVyv000100L","stage":"SENT","contextName":"transfer-service","aggregateName":"account","tenantId":"(0)","aggregateId":"targetId","aggregateVersion":null,"requestId":"0V3oCVyv000100L","commandId":"0V3oCVzI000100Q","function":{"functionKind":"COMMAND","contextName":"wow","processorName":"CommandGateway","name":"send"},"errorCode":"Ok","errorMsg":"","result":{},"signalTime":1764297501314,"succeeded":true}

id:0V3oCVzG000100P
event:SAGA_HANDLED
data:{"id":"0V3oCVzG000100P","waitCommandId":"0V3oCVyv000100L","stage":"SAGA_HANDLED","contextName":"transfer-service","aggregateName":"account","tenantId":"(0)","aggregateId":"sourceId","aggregateVersion":4,"requestId":"0V3oCVyv000100L","commandId":"0V3oCVyv000100L","function":{"functionKind":"EVENT","contextName":"transfer-service","processorName":"TransferSaga","name":"onEvent"},"errorCode":"Ok","errorMsg":"","result":{},"signalTime":1764297501314,"succeeded":true}

id:0V3oCVzI000100Q
event:PROCESSED
data:{"id":"0V3oCVzI000100Q","waitCommandId":"0V3oCVyv000100L","stage":"PROCESSED","contextName":"transfer-service","aggregateName":"account","tenantId":"(0)","aggregateId":"targetId","aggregateVersion":3,"requestId":"0V3oCVyv000100L","commandId":"0V3oCVzI000100Q","function":{"functionKind":"COMMAND","contextName":"transfer-service","processorName":"Account","name":"onCommand"},"errorCode":"Ok","errorMsg":"","result":{},"signalTime":1764297501316,"succeeded":true}

id:0V3oCVzX000100S
event:SNAPSHOT
data:{"id":"0V3oCVzX000100S","waitCommandId":"0V3oCVyv000100L","stage":"SNAPSHOT","contextName":"transfer-service","aggregateName":"account","tenantId":"(0)","aggregateId":"targetId","aggregateVersion":3,"requestId":"0V3oCVyv000100L","commandId":"0V3oCVzI000100Q","function":{"functionKind":"STATE_EVENT","contextName":"wow","processorName":"SnapshotDispatcher","name":"save"},"errorCode":"Ok","errorMsg":"","result":{},"signalTime":1764297501317,"succeeded":true}

```
```kotlin {1}
val waitStrategy = SimpleWaitingForChain.chain(
    tailStage = CommandStage.SNAPSHOT,
    //...
)
commamdGateway.sendAndWait(message,waitStrategy)
```
:::

## Validation

The command gateway uses `jakarta.validation.Validator` to validate the command before sending it. If validation fails, it will throw a CommandValidationException exception.

By utilizing `jakarta.validation.Validator`, developers can use various validation annotations provided by `jakarta.validation` to ensure that commands meet the specified specifications and conditions.

## LocalFirst Mode: Reducing the Impact of Network IO

Normally, the process from sending a command to the aggregate root completing command processing is as follows:

1. The aggregate root processor subscribes to distributed command bus messages.
2. The client sends the command to the distributed command bus through the command gateway.
3. The aggregate root processor receives and processes the command.
4. The aggregate root processor sends a completion signal to the client.

In the above process, steps 2 and 3 involve network IO. The goal of LocalFirst mode is to minimize the impact of this network IO. The specific process is as follows:

1. The aggregate root processor subscribes to local command bus and distributed command bus messages.
2. The client sends the command through the command gateway.
   1. If the command gateway determines that the command cannot be processed on the local service instance, it sends the command to the distributed command bus.
   2. If it can be processed locally, it sends the command to both the local command bus and the distributed command bus.
3. The aggregate root processor receives the command and processes it.
4. The aggregate root processor sends a completion signal to the client.

Through _LocalFirst mode_, sending commands to the local bus and completion signal notifications do not require network IO.

### Configuration

```yaml {5-6}
wow:
  command:
    bus:
      type: kafka
      local-first:
        enabled: true # Enabled by default
```

## Command Rewriter

The command rewriter (`CommandBuilderRewriter`) is used to rewrite the command's message metadata (`aggregateId`/`tenantId`, etc.) and command body (`body`).

The following is an example of a password reset command rewriter:

::: tip
Before a user resets their password (recovers password), they cannot obtain the aggregate root ID, so this rewriter is needed to obtain the `User` aggregate root ID
:::

```kotlin
/**
 * Password recovery (`ResetPwd`) command rewriter.
 *
 * This command needs to query the user aggregate root ID based on the phone number in the command body to meet the requirement that the command message aggregate root ID is mandatory.
 *
 */
@Service
class ResetPwdCommandBuilderRewriter(private val queryService: SnapshotQueryService<UserState>) :
   CommandBuilderRewriter {
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
         .switchIfEmpty {
            IllegalArgumentException("Phone number not bound.").toMono()
         }.map {
            commandBuilder.aggregateId(it.getValue(MessageRecords.AGGREGATE_ID))
         }
   }
}
```

Developers can register the rewriter by using Spring's `@Service` annotation to register it in the Spring container.