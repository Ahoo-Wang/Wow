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

## 等待策略

*命令等待策略*指的是命令网关在发送命令后，等待命令执行结果的一种策略。

*命令等待策略*是 _Wow_ 框架中的重要特性，其目标是解决 _CQRS_ 、读写分离模式下数据同步延迟的问题。

命令等待策略（`WaitStrategy`）支持的等待信号如下：

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

## 验证

命令网关在发送命令之前会使用 `jakarta.validation.Validator` 对命令进行验证，如果验证失败，将会抛出 CommandValidationException 异常。

通过利用 `jakarta.validation.Validator`，开发者可以使用 `jakarta.validation` 提供的各种验证注解，确保命令符合指定的规范和条件。


