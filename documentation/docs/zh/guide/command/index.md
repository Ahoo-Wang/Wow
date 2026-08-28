---
title: 命令
description: 从定义、应用内发送、HTTP 调用、服务间客户端、完成语义与故障定位进入 Wow 命令体系。
outline: deep
---

# 命令

命令表达一次改变聚合状态的业务意图。应用先定义载荷和目标聚合，再选择应用内 `CommandGateway`、聚合 HTTP 路由或全局命令门面发送，并只等待调用方真正需要的完成阶段。

命令从业务意图开始，以持久事实和可观察的完成信号连接下游协作。

```mermaid
flowchart LR
    Intent["业务意图"] --> Definition["定义命令"]
    Definition --> Send["发送命令"]
    Send --> Process["聚合处理"]
    Process --> Append["追加领域事件"]
    Append --> Completion["观察完成阶段"]
    Completion --> Collaboration["事件与协作"]
```

## 快速入口

| 目标 | 入口 |
| --- | --- |
| 定义并发送命令 | 先读[定义命令](./definition.md)，再读[发送命令](./sending.md) |
| 服务间调用 | 使用 [API Client](./api-client.md) 调用全局命令门面 |
| 选择完成阶段 | 阅读[完成语义](./completion.md) |
| 定位超时、重复请求与下游失败 | 阅读[失败与幂等](./reliability.md) |

## 应用使用

1. 用[定义命令](./definition.md)确定载荷、目标聚合和处理函数。
2. 用[发送命令](./sending.md)在本地 Gateway、聚合路由与全局门面之间选择。
3. 跨服务调用时使用 [API Client](./api-client.md)，并接受它当前只返回最终结果的能力边界。
4. 用[完成语义](./completion.md)选择满足响应合同的最早阶段。

这条阅读轨回答“应用怎样调用”，不展开 Dispatcher、Filter、WaitState 或 notifier 的实现。

## 工作原理

需要解释运行时时，先读[命令处理管线](./internals/pipeline.md)，再读[命令等待运行时](./internals/wait-runtime.md)与[命令传输和路由](./internals/transport.md)。它们负责 Gateway 到聚合处理、事件追加和阶段信号的时序；应用页面只引用这些结果，不复制内部状态机。

故障处理回到[失败与幂等](./reliability.md)，按 `commandId`、`requestId`、聚合身份、`stage` 与错误码收集证据。
