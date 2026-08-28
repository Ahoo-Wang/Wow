---
title: 事件补偿配置
description: 事件失败捕获、重试默认值、调度与 Webhook 的配置边界。
---

# 事件补偿配置

补偿由两端分别负责：

- 应用侧 `wow-spring-boot-starter` 捕获符合条件的 handler 失败并发送补偿命令；
- `wow-compensation-server` 保存失败状态、调度重试准备，并可选发送通知。

修改一端不会操作另一端。尤其是，`wow.compensation.enabled=false` 会禁用完整的应用侧
`CompensationAutoConfiguration`——supporter、capture filter 与 `CompensationEventProcessor`——但不会停止
独立 server 的 scheduler，也不会删除之前记录的失败。

## Starter 级

| 属性 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `wow.compensation.enabled` | Boolean | `true` | 注册补偿支持、domain/state 失败 filter 与补偿 event processor |

```yaml
wow:
  compensation:
    enabled: true
```

这些 filter 位于 domain-event、stateless-saga、projection 与 snapshot dispatcher，并在普通 retry filter
之前执行。符合条件的 handler 失败时会发送 `CreateExecutionFailed`；补偿重试则为已有 execution 发送
`ApplyExecutionFailed` 或 `ApplyExecutionSuccess`。捕获路径实际执行
`commandBus.send(compensationCommand).then(originalError)`：只有补偿命令发送成功后，原 handler error 才
继续传播；发送失败时，响应式链改由 send failure 终止，因此监控必须同时保留 handler failure 与补偿命令
投递结果。

`@Retry` 是公开的函数级契约：

```kotlin
@ProjectionProcessor
class OrderProjection {
    @Retry(maxRetries = 3, minBackoff = 60, executionTimeout = 10)
    @OnEvent
    fun onOrderPaid(event: OrderPaid): Mono<Void> = project(event)

    @Retry(enabled = false)
    @OnEvent
    fun onOrderDeleted(event: OrderDeleted): Mono<Void> = delete(event)
}
```

注解默认值是 `maxRetries=10`、`minBackoff=180`、`executionTimeout=120`；除重试次数外，单位均为秒。
`recoverable` 与 `unrecoverable` 异常列表用于分类失败。全局开关启用时，函数仍可通过
`@Retry(enabled=false)` 单独退出。

设置 `wow.compensation.enabled=false` 必须作为两端协调切换：先停止独立 scheduler，排空在途
`PrepareCompensation` command，并对账已有 `FAILED`/`PREPARED` execution；之后才禁用应用侧自动配置。
否则 scheduler 仍可能产生 `CompensationPrepared`，但应用侧已没有 `CompensationEventProcessor` 调用原事件
函数，使 execution 留在 `PREPARED` 而没有实际执行。后续 timeout cycle 可以再次调度 preparation，却不能
替代缺失的应用侧 processor。该开关还会移除新失败的自动捕获，但不会回滚已有补偿状态。

## 服务端级

以下属性属于独立 `wow-compensation-server`。当 `CreateExecutionFailed` 没有携带函数级 retry spec 时，
server `CompensationProperties` 还会作为默认 `IRetrySpec`。

### 重试策略

| 属性 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `wow.compensation.host` | String | 空 | 补偿导航链接使用的基础 host |
| `wow.compensation.max-retries` | Integer | `10` | 默认最大重试次数 |
| `wow.compensation.min-backoff` | Integer | `180` | 第一次退避的默认秒数 |
| `wow.compensation.execution-timeout` | Integer | `120` | 单次执行的默认超时秒数 |

```yaml
wow:
  compensation:
    host: https://compensation.example.internal
    max-retries: 10
    min-backoff: 180
    execution-timeout: 120
```

第 `n` 次重试时，`NextRetryAtCalculator` 从本次 retry timestamp 计算 `minBackoff * 2^n` 秒。领域层拒绝
负的次数/退避/超时和算术溢出。Retry spec 会实体化到 `ExecutionFailedCreated` 事件，因此修改 server
默认值只影响新失败，不会静默改写已有记录。

### 调度器

| 属性 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `wow.compensation.scheduler.enabled` | Boolean | `true` | 创建定时重试 worker |
| `wow.compensation.scheduler.mutex` | String | `compensation_mutex` | scheduler 使用的分布式互斥 Key |
| `wow.compensation.scheduler.batch-size` | Integer | `100` | 每个 tick 最多选择的失败数 |
| `wow.compensation.scheduler.initial-delay` | Duration | `PT60S` | 首次 tick 前延迟 |
| `wow.compensation.scheduler.period` | Duration | `PT60S` | tick 间隔 |

```yaml
wow:
  compensation:
    scheduler:
      enabled: true
      mutex: compensation_mutex
      batch-size: 100
      initial-delay: PT60S
      period: PT60S
```

scheduler 查询可重试的 failed snapshot，并发送 `PrepareCompensation` 命令。mutex 可避免正常情况下的并发
所有权，但本身不是恢复证据；还需监控 worker 执行、失败状态年龄、prepared timeout、命令结果与 backlog。
暂停 scheduler 前必须记录人工恢复负责人，以及 pending `PREPARED` execution 的对账方法。

### 企业微信 Webhook（可选）

只有存在 `wow.compensation.webhook.weixin.url` 时才会创建企业微信集成。

| 属性 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `wow.compensation.webhook.weixin.url` | String | 启用时必填 | 企业微信群机器人 endpoint |
| `wow.compensation.webhook.weixin.events` | Set&lt;HookEvent&gt; | 下列四项 | 生成消息的事件 |

默认事件为 `execution_failed_created`、`execution_failed_applied`、`execution_success_applied` 与
`recoverable_marked`。`compensation_prepared` 也是合法值，但默认不启用。

```yaml
wow:
  compensation:
    webhook:
      weixin:
        url: ${COMPENSATION_WEIXIN_WEBHOOK_URL}
        events:
          - execution_failed_created
          - execution_failed_applied
          - execution_success_applied
          - recoverable_marked
```

URL 应存入 secret store。应用启动成功只证明属性绑定；应触发受控事件验证机器人实际收到，并在通知失败
时继续以 compensation dashboard/query 路径作为权威恢复状态。

<!-- Sources: starter CompensationProperties/AutoConfiguration, server CompensationProperties, SchedulerProperties,
WeiXinWebHookProperties, CompensationFilter, ExecutionFailed, and NextRetryAtCalculator -->
