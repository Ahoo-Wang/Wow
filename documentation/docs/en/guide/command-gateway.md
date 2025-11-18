# Command Gateway

The command gateway is the core component in the system for receiving and sending commands, serving as the entry point for commands.
It is an extension of the command bus, not only responsible for command transmission, but also adds a series of important responsibilities, including command idempotency, waiting strategies, and command validation.

## Send Command

![Send Command - Command Gateway](/images/command-gateway/send-command.svg)

## Idempotency

Command idempotency is the principle of ensuring that the same command is executed at most once in the system.

The command gateway uses `IdempotencyChecker` to perform idempotency checks on the command's `RequestId`.
If the command has already been executed, a `DuplicateRequestIdException` exception will be thrown, preventing duplicate execution of the same command.

Below is an example HTTP request showing how to use `Command-Request-Id` in the request to ensure command idempotency:

:::tip
Developers can also customize the `RequestId` through the `requestId` property of `CommandMessage`.
:::

```http request
POST http://localhost:8080/account/create_account
Content-Type: application/json
Command-Wait-Stage: PROCESSED
Command-Wait-Timeout: 30000
Command-Request-Id: {{$uuid}} // [!code focus]
Command-Aggregate-Id: sourceId

{
  "name": "source",
  "balance": 100
}
```

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

*Command waiting strategy* refers to a strategy where the command gateway waits for command execution results after sending commands.

*Command waiting strategy* is an important feature in the _Wow_ framework, aiming to solve the data synchronization delay problem in _CQRS_ and read-write separation patterns.

Currently supported command waiting strategies include:

### WaitingForStage

<p align="center" style="text-align:center;">
  <img  width="95%" src="/images/wait/WaitingForStage.svg" alt="WaitingForStage"/>
</p>

`WaitingForStage` supports the following waiting signals:

- `SENT`: Generate completion signal when command is published to command bus/queue
- `PROCESSED`: Generate completion signal when command is processed by aggregate root
- `SNAPSHOT`: Generate completion signal when snapshot is created
- `PROJECTED`: Generate completion signal when command-generated events are *projected*
- `EVENT_HANDLED`: Generate completion signal when command-generated events are processed by *event handlers*
- `SAGA_HANDLED`: Generate completion signal when command-generated events are processed by *Saga*

::: code-group
```http request
POST http://localhost:8080/account/create_account
Content-Type: application/json
Command-Wait-Stage: PROCESSED // [!code focus]
Command-Wait-Timeout: 30000
Command-Request-Id: {{$uuid}}
Command-Aggregate-Id: sourceId

{
  "name": "source",
  "balance": 100
}
```

```kotlin {1}
commandGateway.sendAndWaitForProcessed(message)
```
:::

### WaitingForChain

<p align="center" style="text-align:center;">
  <img  width="95%" src="/images/wait/WaitingForChain.svg" alt="WaitingForChain"/>
</p>

## Validation

The command gateway uses `jakarta.validation.Validator` to validate commands before sending them. If validation fails, a `CommandValidationException` exception will be thrown.

By utilizing `jakarta.validation.Validator`, developers can use various validation annotations provided by `jakarta.validation` to ensure commands meet specified norms and conditions.

## LocalFirst Mode: Reducing Network IO Impact

Normally, the flow from sending a command to the aggregate root completing command processing is as follows:

1. Aggregate root processor subscribes to distributed command bus messages.
2. Client sends command to distributed command bus through command gateway.
3. Aggregate root processor receives and processes command.
4. Aggregate root processor sends completion signal to client.

In the above flow, steps 2 and 3 involve network IO. The goal of LocalFirst mode is to minimize the impact of this network IO. The specific flow is as follows:

1. Aggregate root processor subscribes to local command bus and distributed command bus messages.
2. Client sends command through command gateway.
   1. If the command gateway determines that the command cannot be processed by the local service instance, it sends the command to the distributed command bus.
   2. If it can be processed locally, it sends the command to both the local command bus and distributed command bus simultaneously.
3. Aggregate root processor receives and processes the command.
4. Aggregate root processor sends completion signal to client.

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

The command rewriter (`CommandBuilderRewriter`) is used to rewrite command message metadata (`aggregateId`/`tenantId`, etc.) and command body (`body`).

Below is an example of a password reset command rewriter:

::: tip
Before a user resets their password (password recovery), they cannot obtain the aggregate root ID, so this rewriter is needed to obtain the `User` aggregate root ID
:::

```kotlin
/**
 * Password recovery (`ResetPwd`) command rewriter.
 *
 * This command needs to query the user aggregate root ID based on the phone number in the command body
 * to meet the requirement that command messages must have an aggregate root ID.
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
            IllegalArgumentException("Phone number not yet bound.").toMono()
         }.map {
            commandBuilder.aggregateId(it.getValue(MessageRecords.AGGREGATE_ID))
         }
   }
}
```

Developers can register the rewriter by using Spring's `@Service` annotation to register it in the Spring container.