---
title: Metrics
description: Micrometer metrics collection for comprehensive performance monitoring and observability.
---

# Metrics

The Wow framework integrates Reactor and Micrometer metrics for its core reactive components.

## Installation

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("io.micrometer:micrometer-core")
```
```groovy [Gradle(Groovy)]
implementation 'io.micrometer:micrometer-core'
```
```xml [Maven]
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-core</artifactId>
</dependency>
```
:::

## Automatic Metrics Collection

The Wow framework automatically collects metrics for the following components:

The names below are Reactor publisher base names. Reactor creates meters such as `<base>.subscribed`,
`<base>.requested`, `<base>.onNext.delay`, and `<base>.flow.duration` according to the publisher type.

### Command Metrics

- `wow.command.send`
- `wow.command.receive`
- `wow.command.handle`

### Event Bus Metrics

- `wow.event.send`
- `wow.event.receive`
- `wow.event.handle`
- `wow.state.send`
- `wow.state.receive`

### Event Store Metrics

- `wow.eventstore.append`: Event append count and latency
- `wow.eventstore.load`: Event load count and latency
- `wow.eventstore.last`
- `wow.eventstore.exists.request.id`
- `wow.eventstore.scanAggregateId`

### Snapshot Metrics

- `wow.snapshot.save`: Snapshot save count and latency
- `wow.snapshot.load`: Snapshot load count and latency
- `wow.snapshot.getVersion`
- `wow.snapshot.checkpoint.save`
- `wow.snapshot.checkpoint.load`
- `wow.snapshot.event`
- `wow.snapshot.handle`

### Handler Metrics

- `wow.projection.handle`
- `wow.saga.handle`
- `wow.dispatcher`

## Metrics Tags

Tags depend on the operation. Wow-defined tags are:

- `source`: Original decorated component type
- `aggregate`: Aggregate name, or a canonical sorted aggregate list for receive publishers
- `command`: Command name on command send/handle publishers
- `event`: Event name on event handlers
- `processor`: Processor name on handler publishers
- `subscriber`: Subscriber identity on receive publishers; the Reactor context value overrides the subscription receiver group
- `dispatcher`: Dispatcher name on dispatcher publishers

Reactor adds tags such as `type`, `status`, and `exception` depending on the generated meter. Internal dispatcher routing keys are intentionally not exported as tags because they multiply time-series cardinality. A bounded-context tag is not currently emitted.

## Custom Metrics

### Manual Metrics Collection

```kotlin
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer

@Service
class OrderService(
    private val meterRegistry: MeterRegistry,
    private val commandGateway: CommandGateway
) {

    private val orderCreationTimer = Timer.builder("wow.business.order.creation")
        .description("Order creation duration")
        .register(meterRegistry)

    fun createOrder(request: CreateOrderRequest): Mono<OrderSummary> {
        return Mono.fromCallable {
            Timer.Sample.start(meterRegistry)
        }.flatMap { sample ->
            commandGateway.sendAndWait(createOrderCommand, CommandWait.processed(createOrderCommand.commandId))
                .doOnSuccess { result ->
                    sample.stop(orderCreationTimer)
                    // Business success metrics
                    meterRegistry.counter("wow.business.order.created").increment()
                }
                .doOnError { error ->
                    sample.stop(orderCreationTimer)
                    // Business failure metrics
                    meterRegistry.counter("wow.business.order.failed").increment()
                }
        }
    }
}
```

### Reactive Stream Metrics

```kotlin
fun <T> Flux<T>.tagMetrics(operation: String): Flux<T> {
    return this.name(operation)
        .metrics()
}

fun <T> Mono<T>.tagMetrics(operation: String): Mono<T> {
    return this.name(operation)
        .metrics()
}
```

## Configuration

### Micrometer Configuration

```yaml
management:
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: ${spring.application.name}
```

### Wow Metrics Configuration

Wow framework metrics collection is enabled by default and can be controlled as follows:

```yaml
wow:
  metrics:
    enabled: true  # Enabled by default
```

Wow's current Reactor decorators write to Micrometer's global registry. Keep Spring Boot's global-registry bridge enabled so application registries receive these meters. Explicit application-registry injection is planned for a future metrics integration revision.

## Monitoring Dashboard

### Prometheus + Grafana

Use Prometheus to collect metrics and Grafana to create dashboards:

```yaml
# Prometheus configuration
scrape_configs:
  - job_name: 'wow-application'
    static_configs:
      - targets: ['localhost:8080']
    metrics_path: '/actuator/prometheus'
```

### Common Queries

Exporter naming depends on the registry. Inspect `/actuator/metrics` or the target registry first, then build queries from the generated Reactor suffixes such as `flow.duration` and `onNext.delay`.

## Performance Impact

- **Lightweight**: Metrics collection uses efficient counters and histograms
- **Asynchronous**: Does not block business logic execution
- **Configurable**: Can enable or disable specific metrics as needed

## Best Practices

1. **Choose appropriate metrics**: Only collect metrics that are truly needed
2. **Set reasonable tags**: Avoid performance issues caused by too many tags
3. **Monitoring alerts**: Set alert thresholds for critical business metrics
4. **Regular review**: Regularly review and clean up metrics that are no longer needed

## Troubleshooting

### Metrics Not Showing

Check:
1. Whether Micrometer dependencies are correctly added
2. Whether the MeterRegistry Bean is correctly configured and connected to Micrometer's global registry
3. Whether `/actuator/metrics` endpoint is accessible

### Performance Issues

If metrics collection affects performance:
1. Reduce the number of metrics collected
2. Use sampling rates instead of full collection
3. Consider asynchronous metrics collection
