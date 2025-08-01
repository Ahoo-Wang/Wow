# 命令网关

命令网关是系统中接收和发送命令的核心组件，作为命令的入口点发挥关键作用。
它是命令总线的扩展，不仅负责命令的传递，还增加了一系列重要的职责，包括命令的幂等性、等待策略以及命令验证。

## 发送命令

![发送命令 - 命令网关](../public/images/command-gateway/send-command.svg)

## 幂等性

命令幂等性是确保相同命令在系统中最多执行一次的原则。

命令网关通过使用 `IdempotencyChecker` 对命令的 `RequestId` 进行幂等性检查。
如果命令已经执行过，则会抛出 `DuplicateRequestIdException` 异常，防止对同一命令的重复执行。

以下是一个示例的 HTTP 请求，展示了如何在请求中使用 `Command-Request-Id` 来确保命令的幂等性：

:::tip
开发者也可以通过`CommandMessage`的`requestId`属性自定义`RequestId`。
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

![WaitingForStage](../public/images/wait/WaitingForStage.gif)

`WaitingForStage` 支持的等待信号如下：

- `SENT` : 当命令发布到命令总线/队列后，生成完成信号
- `PROCESSED` : 当命令被聚合根处理完成后，生成完成信号
- `SNAPSHOT` : 当快照被生成后，生成完成信号
- `PROJECTED` : 当命令产生的事件*投影*完成后，生成完成信号
- `EVENT_HANDLED` : 当命令产生的事件被*事件处理器*处理完成后，生成完成信号
- `SAGA_HANDLED` : 当命令产生的事件被*Saga*处理完成后，生成完成信号

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
commamdGateway.sendAndWaitForProcessed(message)
```
:::

### WaitingForChain

![WaitingForChain](../public/images/wait/WaitingForChain.gif)

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