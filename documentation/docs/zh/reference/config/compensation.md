---
title: 事件补偿配置
description: 事件失败捕获、函数级重试、服务端调度与企业微信通知的完整属性参考。
outline: deep
---

# 事件补偿配置

补偿配置分属两个运行时：应用侧 starter 捕获并重放处理失败，独立 `wow-compensation-server` 保存失败状态、调度准备命令并发送可选通知。相同的 `wow.compensation` 前缀不代表一个进程中的开关会操作另一个进程。

## 属性总览

| 所有者 | 属性 | 类型 | 默认值 | 作用 |
| --- | --- | --- | --- | --- |
| 应用 starter | `wow.compensation.enabled` | Boolean | `true` | 注册补偿 supporter、domain/state 失败 Filter 与 `CompensationEventProcessor` |
| 补偿服务 | `wow.compensation.host` | String | 空 | 通知中快速导航链接的基础 host |
| 补偿服务 | `wow.compensation.max-retries` | Integer | `10` | 无函数级规格时的最大重试次数 |
| 补偿服务 | `wow.compensation.min-backoff` | Integer | `180` | 无函数级规格时的基础退避秒数 |
| 补偿服务 | `wow.compensation.execution-timeout` | Integer | `120` | 无函数级规格时的单次执行超时秒数 |
| 补偿服务 | `wow.compensation.scheduler.enabled` | Boolean | `true` | 创建自动准备 worker |
| 补偿服务 | `wow.compensation.scheduler.mutex` | String | `compensation_mutex` | scheduler 的分布式互斥 Key |
| 补偿服务 | `wow.compensation.scheduler.batch-size` | Integer | `100` | 每个 tick 最多处理的失败记录数 |
| 补偿服务 | `wow.compensation.scheduler.initial-delay` | Duration | `PT60S` | 首个 tick 前的延迟 |
| 补偿服务 | `wow.compensation.scheduler.period` | Duration | `PT60S` | tick 间隔 |
| 补偿服务 | `wow.compensation.webhook.weixin.url` | String | 未配置 | 企业微信群机器人 endpoint；存在时启用集成 |
| 补偿服务 | `wow.compensation.webhook.weixin.events` | Set | 见下文 | 触发机器人消息的事件集合 |

## 完整 YAML

下面展示全部补偿专属属性。应用进程通常只使用 `enabled`；其余属性属于独立补偿服务。若不需要企业微信，删除整个 `webhook.weixin` 段，不要配置虚假 URL。

```yaml
wow:
  compensation:
    # Application starter
    enabled: true

    # Standalone compensation server
    host: https://compensation.example.internal
    max-retries: 10
    min-backoff: 180
    execution-timeout: 120
    scheduler:
      enabled: true
      mutex: compensation_mutex
      batch-size: 100
      initial-delay: PT60S
      period: PT60S
    webhook:
      weixin:
        url: ${COMPENSATION_WEIXIN_WEBHOOK_URL}
        events:
          - execution_failed_created
          - execution_failed_applied
          - execution_success_applied
          - recoverable_marked
```

企业微信 URL 是凭据，应通过 Secret 注入。属性绑定成功只证明配置可读，不证明机器人可达。

## 应用侧开关

`wow.compensation.enabled=false` 会移除完整的应用侧 `CompensationAutoConfiguration`：

- `EventCompensateSupporter`；
- `DomainEventCompensationFilter` 与 `StateEventCompensationFilter`；
- `CompensationEventProcessor`。

它不会停用 Saga、改变 `RetryableFilter` 的即时重试、停止独立服务的 scheduler 或删除已有 `ExecutionFailed`。关闭必须作为两端协调切换：先停止 scheduler，排空在途 `PrepareCompensation`，对账 `FAILED` / `PREPARED`，再关闭应用侧补偿。否则 scheduler 仍可产生 `CompensationPrepared`，但应用已没有 processor 重放原事件。

## 函数级 @Retry

`@Retry` 是每个处理函数的持久补偿策略：

```kotlin
@Retry(
    maxRetries = 5,
    minBackoff = 60,
    executionTimeout = 10,
    recoverable = [TimeoutException::class],
    unrecoverable = [IllegalArgumentException::class],
)
@OnEvent
fun onOrderPaid(event: OrderPaid): Mono<Void> = project(event)
```

| 参数 | 类型 | 默认值 | 作用 |
| --- | --- | --- | --- |
| `enabled` | Boolean | `true` | `false` 时不创建或更新该函数的补偿记录 |
| `maxRetries` | Int | `10` | 普通准备允许的最大补偿次数 |
| `minBackoff` | Int | `180` | 指数退避基础秒数 |
| `executionTimeout` | Int | `120` | 一次 `PREPARED` 执行的超时秒数 |
| `recoverable` | 异常类型数组 | 空 | 匹配异常分类为 `RECOVERABLE` |
| `unrecoverable` | 异常类型数组 | 空 | 匹配异常分类为 `UNRECOVERABLE` |

函数级规格在创建失败记录时优先；没有函数级规格时，补偿服务的 `max-retries`、`min-backoff` 与 `execution-timeout` 作为默认 `IRetrySpec`。最终规格会写入 `ExecutionFailedCreated`，所以修改默认值只影响之后的新记录，不会改写已有失败。

## 退避与异常分类

`NextRetryAtCalculator` 从本次 retry timestamp 计算：

```text
nextRetryAt = retryAt + minBackoff * 2^retries
timeoutAt   = retryAt + executionTimeout
```

次数、退避和超时必须非负，且时间计算不能溢出。首次失败的 `retries` 为 `0`；每次 prepare 后加一。

`recoverable` / `unrecoverable` 按可赋值类型和最近继承距离匹配；距离相同则 `recoverable` 优先。没有函数级匹配时使用运行时全局分类：`RecoverableException` 与 `TimeoutException` 默认为 `RECOVERABLE`，其他未注册异常为 `UNKNOWN`。

## Scheduler

启用的 scheduler 按 `initial-delay` / `period` 运行，每次最多提交 `batch-size` 个 `PrepareCompensation`。`mutex` 避免正常情况下多个实例同时取得 worker 所有权，但它不是恢复成功证据。运行环境仍应监控 worker 执行、失败年龄、`PREPARED` 超时、命令结果与 backlog。

调整周期与批量前应以积压和处理耗时为依据。暂停 scheduler 时，必须明确人工恢复负责人以及在途 `PREPARED` 的对账方式。

## 企业微信 Webhook

只有存在 `wow.compensation.webhook.weixin.url` 时才注册企业微信集成。默认事件为：

- `execution_failed_created`
- `execution_failed_applied`
- `execution_success_applied`
- `recoverable_marked`

`compensation_prepared` 也是合法值，但默认不启用。通知失败时，补偿查询结果仍是恢复状态的权威入口；运营验证见[事件补偿示例](../example/compensation.md)。

<!-- Sources: starter/server CompensationProperties, CompensationAutoConfiguration, Retry,
NextRetryAtCalculator, SchedulerProperties, CompensationScheduler, and WeiXinWebHookProperties -->
