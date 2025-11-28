# 命令网关

命令网关是系统中接收和发送命令的核心组件，作为命令的入口点发挥关键作用。
它是命令总线的扩展，不仅负责命令的传递，还增加了一系列重要的职责，包括命令的幂等性、等待策略以及命令验证。

## 发送命令

![发送命令 - 命令网关](../../public/images/command-gateway/send-command.svg)

## API 使用

`CommandGateway` 接口提供了多种发送命令并等待结果的方法。以下是主要方法及其使用模式。

### 基础方法

:::tip
`toCommandMessage()` 扩展函数将命令体转换为 `CommandMessage`。该函数由 Wow 框架提供，负责设置命令 ID、聚合根 ID 以及其他元数据。
:::

#### send(command, waitStrategy)

基础方法，使用指定的等待策略发送命令并返回 `ClientCommandExchange`。

```kotlin
val command = CreateAccount(balance = 1000, name = "张三").toCommandMessage()
val waitStrategy = WaitingForStage.processed(command.commandId)

commandGateway.send(command, waitStrategy)
    .flatMap { exchange ->
        // 访问 ClientCommandExchange
        // 使用 waitStrategy 获取命令结果
        exchange.waitStrategy.waiting()
    }
    .subscribe { signal ->
        println("阶段: ${signal.stage} - 成功: ${signal.succeeded}")
    }
```

#### sendAndWait(command, waitStrategy)

发送命令并等待最终结果。如果命令失败，将抛出 `CommandResultException`。

```kotlin
val command = CreateAccount(balance = 1000, name = "张三").toCommandMessage()
val waitStrategy = WaitingForStage.processed(command.commandId)

commandGateway.sendAndWait(command, waitStrategy)
    .doOnSuccess { result ->
        println("命令处理完成: ${result.commandId}")
        println("聚合根版本: ${result.aggregateVersion}")
    }
    .subscribe()
```

#### sendAndWaitStream(command, waitStrategy)

返回 `Flux<CommandResult>` 用于实时流式更新，随着命令在不同阶段的处理进度发出更新。

```kotlin
val command = CreateAccount(balance = 1000, name = "张三").toCommandMessage()
val waitStrategy = WaitingForStage.snapshot(command.commandId)

commandGateway.sendAndWaitStream(command, waitStrategy)
    .doOnNext { result ->
        println("阶段: ${result.stage} - 成功: ${result.succeeded}")
        println("聚合根版本: ${result.aggregateVersion}")
    }
    .subscribe()
```

### 便捷方法

`CommandGateway` 提供了预配置常见等待策略的便捷方法：

```kotlin
val command = CreateAccount(balance = 1000, name = "张三").toCommandMessage()

// 等待命令发送到总线
commandGateway.sendAndWaitForSent(command)
    .doOnSuccess { result ->
        println("命令已发送: ${result.commandId}")
    }
    .subscribe()

// 等待命令被聚合根处理
commandGateway.sendAndWaitForProcessed(command)
    .doOnSuccess { result ->
        if (result.succeeded) {
            println("命令处理成功: ${result.commandId}")
            println("新聚合根版本: ${result.aggregateVersion}")
        }
    }
    .subscribe()

// 等待聚合根快照创建
commandGateway.sendAndWaitForSnapshot(command)
    .doOnSuccess { result ->
        println("已为聚合根创建快照: ${result.aggregateId}")
    }
    .subscribe()
```

## 核心概念

### ClientCommandExchange（客户端命令交换）

`ClientCommandExchange` 是通过 `CommandGateway.send()` 发送命令时返回的客户端侧交换上下文。它提供以下访问：

- **message**：发送的原始 `CommandMessage`
- **waitStrategy**：用于等待命令处理结果的 `WaitStrategy`
- **attributes**：用于存储额外交换相关数据的可变 Map

```kotlin
interface ClientCommandExchange<C : Any> {
    val message: CommandMessage<C>
    val waitStrategy: WaitStrategy
    val attributes: MutableMap<String, Any>
}
```

当需要低级别访问等待策略或想要实现自定义等待逻辑时，使用 `ClientCommandExchange`：

```kotlin
commandGateway.send(command, waitStrategy)
    .flatMap { exchange ->
        // 访问命令消息
        val commandId = exchange.message.commandId
        
        // 直接使用等待策略
        exchange.waitStrategy.waiting()
            .filter { signal -> signal.stage == CommandStage.PROCESSED }
            .next()
    }
    .subscribe()
```

### CommandResult（命令结果）

`CommandResult` 表示命令在特定处理阶段的执行结果。它包含关于命令处理结果的完整信息。

| 属性 | 类型 | 描述 |
|------|------|------|
| `id` | `String` | 该结果的唯一标识符 |
| `waitCommandId` | `String` | 正在等待的命令 ID |
| `stage` | `CommandStage` | 当前处理阶段（SENT、PROCESSED、SNAPSHOT 等） |
| `contextName` | `String` | 限界上下文名称 |
| `aggregateName` | `String` | 聚合根名称 |
| `tenantId` | `String` | 租户标识符 |
| `aggregateId` | `String` | 聚合根实例标识符 |
| `aggregateVersion` | `Int?` | 处理后的聚合根版本（网关验证失败或处理前为 null） |
| `requestId` | `String` | 幂等性请求标识符 |
| `commandId` | `String` | 命令标识符 |
| `function` | `FunctionInfoData` | 处理函数的相关信息 |
| `errorCode` | `String` | 错误码（成功时为 "Ok"） |
| `errorMsg` | `String` | 错误消息（成功时为空） |
| `bindingErrors` | `List<BindingError>` | 验证错误列表 |
| `result` | `Map<String, Any>` | 额外的结果数据 |
| `signalTime` | `Long` | 生成该结果的时间戳 |
| `succeeded` | `Boolean` | 命令处理是否成功 |

### WaitSignal 与 CommandResult 的区别

- **WaitSignal**：等待策略基础设施内部使用的接口。包含处理阶段信息，用于组件间的信号传递。
- **CommandResult**：命令结果的公共 API。由 `WaitSignal` 创建，包含额外的上下文信息如 `requestId` 和格式化的聚合根信息。

### CommandGateway 与 CommandBus 的关系

`CommandGateway` 扩展了 `CommandBus`，提供额外的高级特性：

| 特性 | CommandBus | CommandGateway |
|------|------------|----------------|
| 发送命令 | ✓ | ✓ |
| 等待策略 | ✗ | ✓ |
| 命令验证 | ✗ | ✓ |
| 幂等性检查 | ✗ | ✓ |
| 实时结果流 | ✗ | ✓ |
| 便捷方法 | ✗ | ✓ |

当只需要基本的命令路由时使用 `CommandBus`。当需要完整的命令处理功能（包括等待策略和验证）时使用 `CommandGateway`。

```kotlin
// CommandBus - 仅基本路由
interface CommandBus : MessageBus<CommandMessage<*>, ServerCommandExchange<*>>

// CommandGateway - 扩展 CommandBus 并增加额外功能
interface CommandGateway : CommandBus {
    fun <C : Any> send(command: CommandMessage<C>, waitStrategy: WaitStrategy): Mono<out ClientCommandExchange<C>>
    fun <C : Any> sendAndWait(command: CommandMessage<C>, waitStrategy: WaitStrategy): Mono<CommandResult>
    fun <C : Any> sendAndWaitStream(command: CommandMessage<C>, waitStrategy: WaitStrategy): Flux<CommandResult>
    // ... 便捷方法
}
```

## 错误处理

### CommandResultException（命令结果异常）

当命令处理失败时，`sendAndWait` 会抛出包含完整 `CommandResult` 和错误详情的 `CommandResultException`。

```kotlin
commandGateway.sendAndWait(command, waitStrategy)
    .doOnError { error ->
        if (error is CommandResultException) {
            val result = error.commandResult
            println("命令在阶段失败: ${result.stage}")
            println("错误码: ${result.errorCode}")
            println("错误消息: ${result.errorMsg}")
            
            // 检查验证错误
            if (result.bindingErrors.isNotEmpty()) {
                result.bindingErrors.forEach { bindingError ->
                    println("字段 '${bindingError.name}': ${bindingError.msg}")
                }
            }
        }
    }
    .onErrorResume { error ->
        // 优雅地处理错误
        when (error) {
            is CommandResultException -> {
                // 记录日志并返回降级值
                Mono.empty()
            }
            else -> Mono.error(error)
        }
    }
    .subscribe()
```

### CommandValidationException（命令验证异常）

当命令验证在发送前失败时抛出。包含验证约束违规信息。

```kotlin
// 带有验证注解的命令
data class CreateAccount(
    @field:NotBlank(message = "名称不能为空")
    val name: String,
    @field:Min(value = 0, message = "余额不能为负数")
    val balance: Int
)

commandGateway.sendAndWaitForProcessed(command)
    .doOnError { error ->
        if (error is CommandValidationException) {
            println("命令验证失败: ${error.command}")
            error.bindingErrors.forEach { bindingError ->
                println("字段 '${bindingError.name}': ${bindingError.msg}")
            }
        }
    }
    .subscribe()
```

### DuplicateRequestIdException（重复请求 ID 异常）

当尝试处理一个已处理过的请求 ID 的命令时抛出。

```kotlin
commandGateway.sendAndWaitForProcessed(command)
    .doOnError { error ->
        if (error is DuplicateRequestIdException) {
            println("重复请求: ${error.requestId}")
            println("聚合根: ${error.aggregateId}")
        }
    }
    .onErrorResume(DuplicateRequestIdException::class.java) { error ->
        // 返回缓存结果或忽略重复
        Mono.empty()
    }
    .subscribe()
```

### 错误处理最佳实践

1. **使用特定的异常处理器**：分别处理 `CommandResultException`、`CommandValidationException` 和 `DuplicateRequestIdException` 以提供适当的响应。

2. **记录错误详情**：始终记录 `errorCode`、`errorMsg` 和 `bindingErrors` 以便调试。

3. **为瞬时故障实现重试逻辑**：

```kotlin
commandGateway.sendAndWaitForProcessed(command)
    .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
        .filter { error -> isTransientError(error) })
    .subscribe()

// 瞬时错误通常是网络或临时基础设施问题
// 不要重试验证错误或重复请求错误
fun isTransientError(error: Throwable): Boolean {
    return when (error) {
        is CommandValidationException -> false  // 验证错误重试不会成功
        is DuplicateRequestIdException -> false // 重复请求不应重试
        is CommandResultException -> false      // 聚合根的业务逻辑错误
        else -> true                            // 网络/基础设施错误可能是瞬时的
    }
}
```

4. **处理超时场景**：为等待策略配置适当的超时时间。

```kotlin
commandGateway.sendAndWaitForProcessed(command)
    .timeout(Duration.ofSeconds(30))
    .doOnError(TimeoutException::class.java) { error ->
        println("命令处理超时")
    }
    .subscribe()
```

## 幂等性

命令幂等性是确保相同命令在系统中最多执行一次的原则。

命令网关通过使用 `IdempotencyChecker` 对命令的 `RequestId` 进行幂等性检查。
如果命令已经执行过，则会抛出 `DuplicateRequestIdException` 异常，防止对同一命令的重复执行。

以下是一个示例的 HTTP 请求，展示了如何在请求中使用 `Command-Request-Id` 来确保命令的幂等性：

:::tip
开发者也可以通过`CommandMessage`的`requestId`属性自定义`RequestId`。
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


### 配置

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


## 等待策略

*命令等待策略*指的是命令网关在发送命令后，等待命令执行结果的一种策略。

*命令等待策略*是 _Wow_ 框架中的重要特性，其目标是解决 _CQRS_ 、读写分离模式下数据同步延迟的问题。

目前支持的命令等待策略有：

### WaitingForStage

<p align="center" style="text-align:center;">
  <img  width="95%" src="../../public/images/wait/WaitingForStage.svg" alt="WaitingForStage"/>
</p>

`WaitingForStage` 支持的等待信号如下：

- `SENT` : 当命令发布到命令总线/队列后，生成完成信号
- `PROCESSED` : 当命令被聚合根处理完成后，生成完成信号
- `SNAPSHOT` : 当快照被生成后，生成完成信号
- `PROJECTED` : 当命令产生的事件*投影*完成后，生成完成信号
- `EVENT_HANDLED` : 当命令产生的事件被*事件处理器*处理完成后，生成完成信号
- `SAGA_HANDLED` : 当命令产生的事件被*Saga*处理完成后，生成完成信号

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

## 验证

命令网关在发送命令之前会使用 `jakarta.validation.Validator` 对命令进行验证，如果验证失败，将会抛出 CommandValidationException 异常。

通过利用 `jakarta.validation.Validator`，开发者可以使用 `jakarta.validation` 提供的各种验证注解，确保命令符合指定的规范和条件。

## LocalFirst 模式：减少网络IO的影响

通常情况下，从发送命令到聚合根完成命令处理的流程如下：

1. 聚合根处理器订阅分布式命令总线消息。
2. 客户端通过命令网关将命令发送至分布式命令总线。
3. 聚合根处理器接收并处理命令。
4. 聚合根处理器发送处理完成信号给客户端。

在上述流程中，步骤 2 和 3 中涉及网络IO。而 LocalFirst 模式的目标是尽量消除这些网络IO的影响。具体流程如下：

1. 聚合根处理器订阅本地命令总线以及分布式命令总线消息。
2. 客户端通过命令网关发送命令。
   1. 如果命令网关判断该命令不能在本地服务实例处理，则将命令发送至分布式命令总线。
   2. 如果可以在本地处理，则将命令同时发送至本地命令总线和分布式命令总线。
3. 聚合根处理器接收到命令并处理命令.
4. 聚合根处理器发送处理完成信号给客户端.

通过 _LocalFirst 模式_，命令发送至本地总线以及完成信号通知均不需要网络IO。

### 配置

```yaml {5-6}
wow:
  command:
    bus:
      type: kafka
      local-first:
        enabled: true # 默认已开启
```

## 命令改写器

命令改写器(`CommandBuilderRewriter`)是用于改写命令的消息元数据(`aggregateId`/`tenantId` 等)以及命令体(`body`)。

以下是一个重置密码命令重写器的示例：

::: tip
用户重置密码（找回密码）前是无法获得聚合根ID的，所以需要通过该改写器获得 `User` 聚合根的ID
:::

```kotlin
/**
 * 找回密码(`ResetPwd`)命令重写器。
 *
 * 该命令需要根据命令体中的手机号码查询用户聚合根ID，以便满足命令消息聚合根ID必填的要求。
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
            IllegalArgumentException("手机号码尚未绑定。").toMono()
         }.map {
            commandBuilder.aggregateId(it.getValue(MessageRecords.AGGREGATE_ID))
         }
   }
}
```

开发者通过 _Spring_ 的 `@Service` 注解，将该提取器注册到 _Spring_ 容器中即可完成提取器的注册。