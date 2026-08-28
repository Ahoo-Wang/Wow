---
title: 发送命令
description: 在应用内 CommandGateway、聚合 HTTP 路由与全局命令门面之间选择，并正确读取 JSON、SSE 与 CommandResult。
outline: deep
---

# 发送命令

同一条命令可以从进程内或 HTTP 边界进入 Wow。选择入口不会改变聚合业务规则，但会改变路由元数据、响应形状以及调用方能否观察中间阶段。

## 选择调用入口

| 场景 | 入口 | 返回 |
| --- | --- | --- |
| 同一应用进程 | `CommandGateway` | `Mono<CommandResult>` 或 `Flux<CommandResult>` |
| 面向聚合的公开 HTTP API | 生成的聚合命令路由 | JSON 最终结果或 SSE 阶段流 |
| 通用 HTTP 门面 | `POST /wow/command/send` | JSON 最终结果 |
| Kotlin 服务间调用 | [API Client](./api-client.md) | 响应式或同步最终结果 |

优先使用保留业务语义且暴露面最小的入口。应用内部不需要先把命令转换成 HTTP；远程调用也不要假装成进程内 Gateway。

## 构造 CommandMessage

命令载荷及其目标元数据见[定义命令](./definition.md)。进程内调用用 `toCommandMessage()` 生成运行时信封：

```kotlin
val message = createOrder.toCommandMessage(
    aggregateId = "order-1",
    requestId = "create-order-1",
)
```

`toCommandMessage()` 会结合命令元数据和显式参数解析 bounded context、aggregate、tenant、owner、space、期望版本以及创建标记。重试同一业务意图时复用稳定的 `requestId`；不要把一次响应丢失改写成新的业务操作。

## 应用内 CommandGateway

`CommandGateway` 在 `CommandBus` 之上执行命令体验证、request ID 预检和阶段等待。接口保持响应式；`sendAndWait` 返回一个最终结果，`sendAndWaitStream` 返回已接受的阶段信号流。

```kotlin
val result: Mono<CommandResult> = commandGateway.sendAndWait(
    message,
    CommandWait.processed(message.commandId),
)
```

便捷方法提供 `SENT`、`PROCESSED` 与 `SNAPSHOT`：

```kotlin
commandGateway.sendAndWaitForSent(message)
commandGateway.sendAndWaitForProcessed(message)
commandGateway.sendAndWaitForSnapshot(message)
```

这些方法选择观察点，不改变命令处理。不要在 Reactor event loop 或 Wow 核心处理链中调用 `block()`。

## 聚合 HTTP 路由

聚合专用路由由命令与聚合 metadata 生成，携带具体请求体 Schema，并可把 tenant、owner、aggregate ID 或命令属性放入路径和请求头。HTTP 方法、路径和作用域必须以目标服务当前生成的 OpenAPI 或 `RouterSpecs` route catalog 为准。

当前 `example-domain` 生成契约中的示例是：

```text
POST /owner/{ownerId}/cart/add_cart_item
PUT  /owner/{ownerId}/cart/change_quantity
```

这些事实不表示其他服务使用相同路径。不要推断 bounded context 前缀；注解、聚合 owner 模式和 route metadata 都可能改变最终合同。生成契约为聚合命令路由声明 `application/json` 与 `text/event-stream`。

## 全局命令门面

全局门面是固定的 `POST /wow/command/send`。请求体是命令载荷，命令类型、目标聚合、等待计划及路由信息由 `Command-*` 请求头补充：

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

当前生成 OpenAPI 对该全局路由只声明 `application/json`。它适合通用客户端和无法绑定聚合专用 Schema 的调用方；安全层仍必须认证调用方并授权目标聚合。

## JSON 与 SSE 响应

聚合命令路由同时支持两种响应：

- `Accept: application/json` 调用 `sendAndWait`，只返回所选等待计划的最终 `CommandResult`；
- `Accept: text/event-stream` 调用 `sendAndWaitStream`，把阶段作为 SSE 事件发送，事件名是 `CommandStage`，数据是该阶段的 `CommandResult`。

阶段流按实际观察顺序到达，调用方不能假定固定顺序。连接断开或超时只结束本次 HTTP 等待，不会撤销已经被命令总线接受的命令。

全局 `/wow/command/send` 的当前路由合同仅接受 JSON；需要 SSE 时使用生成且声明该媒体类型的聚合命令路由。现有 [API Client](./api-client.md) 也不提供 SSE。

## CommandResult 基础字段

| 字段 | 含义 |
| --- | --- |
| `stage` | 当前观察到的 `SENT`、`PROCESSED`、`SNAPSHOT` 等阶段 |
| `commandId` / `waitCommandId` | 当前命令 ID 与等待计划所属命令 ID |
| `contextName` / `aggregateName` / `tenantId` / `aggregateId` | 目标聚合身份 |
| `aggregateVersion` | 当前阶段已知的聚合版本；处理前可能为 `null` |
| `requestId` | 调用方提供的幂等键 |
| `function` | 产生阶段信号的函数信息 |
| `errorCode` / `errorMsg` / `bindingErrors` | 成功状态与失败详情；`succeeded` 由错误码推导 |
| `result` | 已接受信号累积的结果值 |
| `signalTime` | 信号生成时间 |

成功结果只证明 `stage` 对应的观察点。不要从 `PROCESSED` 推断快照、投影、事件处理器或 Saga 已完成。

## 下一步：选择完成语义

根据读后可见性、副作用和延迟目标，阅读“完成语义”（`command/completion`）并选择满足响应合同的最早阶段。超时、重复请求或下游失败的处理见“命令可靠性”（`command/reliability`）。
