---
title: Compensation Configuration
description: Configuration options for event compensation, including the starter toggle and the compensation server retry, scheduler, and webhook settings.
---

# Compensation Configuration

Compensation configuration is split across two layers:

- **Starter level** (`wow-spring-boot-starter`) — the single `wow.compensation.enabled` toggle.
- **Server level** (`wow-compensation-server`) — retry policy, scheduler, and webhook delivery.

## Starter Level

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.compensation.enabled` | Boolean | `true` | Enable event compensation functionality |

```yaml
wow:
  compensation:
    enabled: true
```

When enabled, the `EventCompensationFilter` automatically registers on every event dispatcher
(`DomainEventDispatcher`, `StatelessSagaDispatcher`, `ProjectionDispatcher`, `SnapshotDispatcher`).
Any handler that throws is caught, and a `CreateExecutionFailed` command is sent to record the
failure. The `@Retry` annotation on each handler controls per-function retry behavior:

```kotlin
@ProjectionProcessor
class OrderProjection {
    // This handler gets the default retry spec (maxRetries=10, minBackoff=180s, executionTimeout=120s)
    @OnEvent
    fun onOrderCreated(event: OrderCreated): Mono<Void> { ... }

    // Custom retry for a handler that calls a flaky external API
    @Retry(maxRetries = 3, minBackoff = 60, executionTimeout = 10)
    @OnEvent
    fun onOrderPaid(event: OrderPaid): Mono<Void> { ... }

    // Opt out of compensation entirely for this handler
    @Retry(enabled = false)
    @OnEvent
    fun onOrderShipped(event: OrderShipped): Mono<Void> { ... }
}
```

Set `wow.compensation.enabled=false` to globally disable the compensation filter on all handlers
in this service. Individual handlers can still opt out via `@Retry(enabled = false)` even when
compensation is globally enabled.

## Server Level

These properties configure the standalone compensation server (`wow-compensation-server`).

### Retry Policy

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.compensation.host` | String | _(empty)_ | Host address of the compensation server |
| `wow.compensation.max-retries` | Integer | `10` | Maximum number of retry attempts for a failed execution |
| `wow.compensation.min-backoff` | Integer | `180` | Minimum backoff (seconds) between retries |
| `wow.compensation.execution-timeout` | Integer | `120` | Per-execution timeout (seconds) |

```yaml
wow:
  compensation:
    host: http://compensation-service
    max-retries: 10
    min-backoff: 180
    execution-timeout: 120
```

### Scheduler

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.compensation.scheduler.enabled` | Boolean | `true` | Enable the scheduled retry execution loop |
| `wow.compensation.scheduler.mutex` | String | `compensation_mutex` | Distributed lock key guarding single-instance scheduling |
| `wow.compensation.scheduler.batch-size` | Integer | `100` | Number of failed executions processed per scheduling tick |
| `wow.compensation.scheduler.initial-delay` | Duration | `PT60S` (60s) | Delay before the first scheduling tick |
| `wow.compensation.scheduler.period` | Duration | `PT60S` (60s) | Interval between scheduling ticks |

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

### WeChat Webhook (optional)

Delivers compensation events to a WeCom (Enterprise WeChat) group bot.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.compensation.webhook.weixin.url` | String | _(required)_ | WeCom group bot webhook URL |
| `wow.compensation.webhook.weixin.events` | Set&lt;HookEvent&gt; | `execution_failed_created`, `execution_failed_applied`, `execution_success_applied`, `recoverable_marked` | Hook events that trigger a notification. Valid values: `execution_failed_created`, `execution_failed_applied`, `execution_success_applied`, `compensation_prepared`, `recoverable_marked` |

```yaml
wow:
  compensation:
    webhook:
      weixin:
        url: https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=YOUR_KEY
        events:
          - execution_failed_created
          - execution_failed_applied
          - execution_success_applied
          - recoverable_marked
```
