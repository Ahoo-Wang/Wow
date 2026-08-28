---
title: Compensation Configuration
description: Event-failure capture, retry defaults, scheduling, and webhook configuration boundaries.
---

# Compensation Configuration

Compensation has two owners:

- application-side `wow-spring-boot-starter` captures eligible handler failures and sends compensation commands;
- `wow-compensation-server` stores failure state, schedules retry preparation, and optionally sends notifications.

Changing one side does not operate the other. In particular, `wow.compensation.enabled=false` disables the complete
application-side `CompensationAutoConfiguration`—supporter, capture filters, and `CompensationEventProcessor`—but it
does not stop the standalone server scheduler or delete previously recorded failures.

## Starter Level

| Property | Type | Default | Effect |
|---|---|---|---|
| `wow.compensation.enabled` | Boolean | `true` | Register compensation support, domain/state failure filters, and the compensation event processor |

```yaml
wow:
  compensation:
    enabled: true
```

The filters run on domain-event, stateless-saga, projection, and snapshot dispatchers before the ordinary retry
filter. On an eligible handler error they send `CreateExecutionFailed`; a compensation retry sends
`ApplyExecutionFailed` or `ApplyExecutionSuccess` for the existing execution. On capture, the implementation uses
`commandBus.send(compensationCommand).then(originalError)`: the original handler error propagates only after the
compensation command send succeeds. If that send fails, the send failure terminates the chain instead, so monitoring
must retain both the handler failure and compensation-command delivery result.

`@Retry` is the public per-function contract:

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

The annotation defaults are `maxRetries=10`, `minBackoff=180`, and `executionTimeout=120`, all in seconds except the
retry count. `recoverable` and `unrecoverable` exception lists classify failures. `@Retry(enabled=false)` opts out for
one function even when the global switch is enabled.

Set `wow.compensation.enabled=false` only as a coordinated two-sided cutover. First stop the standalone scheduler,
drain in-flight `PrepareCompensation` commands, and reconcile existing `FAILED`/`PREPARED` executions; then disable the
application-side auto-configuration. Otherwise the scheduler can still produce `CompensationPrepared` while no
application-side `CompensationEventProcessor` invokes the original event function, leaving executions in `PREPARED`
without execution. A later timeout cycle can schedule another preparation, but it cannot replace the missing
application-side processor. The switch also removes automatic capture for new failures; it is not a rollback of
existing compensation state.

## Server Level

The following properties belong to the standalone `wow-compensation-server`. Its `CompensationProperties` also acts
as the default `IRetrySpec` when a `CreateExecutionFailed` command carries no function-specific retry spec.

### Retry Policy

| Property | Type | Default | Effect |
|---|---|---|---|
| `wow.compensation.host` | String | empty | Base host used for compensation navigation links |
| `wow.compensation.max-retries` | Integer | `10` | Default maximum retry count |
| `wow.compensation.min-backoff` | Integer | `180` | Default first backoff in seconds |
| `wow.compensation.execution-timeout` | Integer | `120` | Default execution timeout in seconds |

```yaml
wow:
  compensation:
    host: https://compensation.example.internal
    max-retries: 10
    min-backoff: 180
    execution-timeout: 120
```

For retry number `n`, `NextRetryAtCalculator` computes `minBackoff * 2^n` seconds from the retry timestamp. The domain
rejects negative counts/backoffs/timeouts and arithmetic overflow. A retry spec is materialized into the
`ExecutionFailedCreated` event, so changing server defaults affects new failures without silently rewriting existing
records.

### Scheduler

| Property | Type | Default | Effect |
|---|---|---|---|
| `wow.compensation.scheduler.enabled` | Boolean | `true` | Create the scheduled retry worker |
| `wow.compensation.scheduler.mutex` | String | `compensation_mutex` | Distributed mutex used by the scheduler |
| `wow.compensation.scheduler.batch-size` | Integer | `100` | Maximum failures selected per tick |
| `wow.compensation.scheduler.initial-delay` | Duration | `PT60S` | Delay before the first tick |
| `wow.compensation.scheduler.period` | Duration | `PT60S` | Delay between ticks |

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

The scheduler queries retryable failed snapshots and sends `PrepareCompensation` commands. The mutex prevents normal
concurrent ownership, but it is not recovery evidence by itself. Monitor worker execution, failed-state age, prepared
timeouts, command outcomes, and backlog. Before pausing the scheduler, record who owns manual recovery and how pending
`PREPARED` executions will be reconciled.

### WeChat Webhook (optional)

The WeCom integration is created only when `wow.compensation.webhook.weixin.url` is present.

| Property | Type | Default | Effect |
|---|---|---|---|
| `wow.compensation.webhook.weixin.url` | String | required to enable | WeCom group-bot endpoint |
| `wow.compensation.webhook.weixin.events` | Set&lt;HookEvent&gt; | four events below | Events that generate a message |

Default events are `execution_failed_created`, `execution_failed_applied`, `execution_success_applied`, and
`recoverable_marked`. `compensation_prepared` is also valid but is not enabled by default.

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

Keep the URL in a secret store. A successful application start proves binding only; verify a controlled event reaches
the bot, and keep the compensation dashboard/query path as the authoritative recovery state when notification fails.

<!-- Sources: starter CompensationProperties/AutoConfiguration, server CompensationProperties, SchedulerProperties,
WeiXinWebHookProperties, CompensationFilter, ExecutionFailed, and NextRetryAtCalculator -->
