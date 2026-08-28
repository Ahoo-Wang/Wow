---
title: Compensation Configuration
description: Complete property reference for event-failure capture, per-function retry, server scheduling, and WeCom notifications.
outline: deep
---

# Compensation Configuration

Compensation configuration belongs to two runtimes. The application-side starter captures and replays processing failures. The standalone `wow-compensation-server` stores failure state, schedules preparation commands, and sends optional notifications. Sharing the `wow.compensation` prefix does not make a switch in one process operate the other process.

## Property Summary

| Owner | Property | Type | Default | Effect |
| --- | --- | --- | --- | --- |
| Application starter | `wow.compensation.enabled` | Boolean | `true` | Register compensation supporter, domain/state failure filters, and `CompensationEventProcessor` |
| Compensation server | `wow.compensation.host` | String | empty | Base host for quick-navigation links in notifications |
| Compensation server | `wow.compensation.max-retries` | Integer | `10` | Maximum retry count when no function specification exists |
| Compensation server | `wow.compensation.min-backoff` | Integer | `180` | Base backoff in seconds when no function specification exists |
| Compensation server | `wow.compensation.execution-timeout` | Integer | `120` | Per-attempt timeout in seconds when no function specification exists |
| Compensation server | `wow.compensation.scheduler.enabled` | Boolean | `true` | Create the automatic preparation worker |
| Compensation server | `wow.compensation.scheduler.mutex` | String | `compensation_mutex` | Distributed mutex key for the scheduler |
| Compensation server | `wow.compensation.scheduler.batch-size` | Integer | `100` | Maximum failure records processed per tick |
| Compensation server | `wow.compensation.scheduler.initial-delay` | Duration | `PT60S` | Delay before the first tick |
| Compensation server | `wow.compensation.scheduler.period` | Duration | `PT60S` | Delay between ticks |
| Compensation server | `wow.compensation.webhook.weixin.url` | String | absent | WeCom group-bot endpoint; enabled when configured and its value is not `false` |
| Compensation server | `wow.compensation.webhook.weixin.events` | Set | below | Events that produce bot messages |

## Complete YAML

This block shows every compensation-specific property. An application process normally uses only `enabled`; the remaining properties belong to the standalone compensation server. Remove the entire `webhook.weixin` block when WeCom is not required instead of configuring a fake URL.

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

The WeCom URL is a credential and should be injected through a secret. Successful property binding proves only that configuration was read, not that the bot is reachable.

## Application-side Switch

`wow.compensation.enabled=false` removes the complete application-side `CompensationAutoConfiguration`:

- `EventCompensateSupporter`;
- `DomainEventCompensationFilter` and `StateEventCompensationFilter`;
- `CompensationEventProcessor`.

It does not disable Saga, change immediate retry in `RetryableFilter`, stop the standalone scheduler, or delete existing `ExecutionFailed` records. Treat shutdown as a coordinated two-sided cutover: stop the scheduler, drain in-flight `PrepareCompensation`, reconcile `FAILED` / `PREPARED`, and only then disable application-side compensation. Otherwise the scheduler can still produce `CompensationPrepared` while the application no longer has a processor to replay the source event.

## Per-function @Retry

`@Retry` is the durable-compensation policy for one processing function:

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

| Parameter | Type | Default | Effect |
| --- | --- | --- | --- |
| `enabled` | Boolean | `true` | `false` prevents failure-record creation and `ApplyExecutionFailed`; an existing compensation success still sends `ApplyExecutionSuccess` |
| `maxRetries` | Int | `10` | Maximum ordinary preparation count |
| `minBackoff` | Int | `180` | Exponential-backoff base in seconds |
| `executionTimeout` | Int | `120` | Timeout in seconds for one `PREPARED` execution |
| `recoverable` | exception-type array | empty | Classify matching errors as `RECOVERABLE` |
| `unrecoverable` | exception-type array | empty | Classify matching errors as `UNRECOVERABLE` |

A function specification wins when the failure record is created. Without one, the server's `max-retries`, `min-backoff`, and `execution-timeout` supply the default `IRetrySpec`. The final specification is stored in `ExecutionFailedCreated`, so changing server defaults affects only later records and does not rewrite existing failures.

## Backoff and Exception Classification

`NextRetryAtCalculator` calculates from the current retry timestamp:

```text
nextRetryAt = retryAt + minBackoff * 2^retries
timeoutAt   = retryAt + executionTimeout
```

Count, backoff, and timeout must be non-negative, and time calculation must not overflow. The first failure starts with `retries = 0`; every preparation increments it.

`recoverable` / `unrecoverable` use assignable-type matching and the nearest inheritance distance; `recoverable` wins a tie. Without a function-level match, runtime global classification applies: `RecoverableException` and `TimeoutException` are `RECOVERABLE` by default, while other unregistered exceptions are `UNKNOWN`.

## Scheduler

An enabled scheduler runs with `initial-delay` / `period` and submits at most `batch-size` `PrepareCompensation` commands per tick. `mutex` prevents normal concurrent worker ownership across instances, but it is not recovery proof. Monitor worker execution, failure age, `PREPARED` timeout, command outcomes, and backlog.

Base period and batch changes on measured backlog and processing time. Before pausing the scheduler, assign an operator owner and define reconciliation for in-flight `PREPARED` records.

## WeCom Webhook

The WeCom integration is registered only when `wow.compensation.webhook.weixin.url` is configured and its value is not `false`. Its default events are:

- `execution_failed_created`
- `execution_failed_applied`
- `execution_success_applied`
- `recoverable_marked`

`compensation_prepared` is also valid but is disabled by default. When notification fails, the compensation query result remains the authoritative recovery-state entry point; see the [Event Compensation Example](../example/compensation.md) for operator verification.

<!-- Sources: starter/server CompensationProperties, CompensationAutoConfiguration, Retry,
NextRetryAtCalculator, SchedulerProperties, CompensationScheduler, and WeiXinWebHookProperties -->
