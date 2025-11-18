# Metrics

The Wow framework integrates Micrometer metrics collection functionality, providing comprehensive performance monitoring and observability for all core components.

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

### Command Bus Metrics

- `wow.command.bus.send`: Command send count and latency
- `wow.command.bus.receive`: Command receive count and latency
- Metrics categorized by aggregate type and command type

### Event Bus Metrics

- `wow.event.bus.send`: Event publish count and latency
- `wow.event.bus.receive`: Event receive count and latency
- Metrics categorized by aggregate type and event type

### Event Store Metrics

- `wow.eventstore.append`: Event append count and latency
- `wow.eventstore.load`: Event load count and latency
- Metrics categorized by aggregate type

### Snapshot Metrics

- `wow.snapshot.save`: Snapshot save count and latency
- `wow.snapshot.load`: Snapshot load count and latency
- Metrics categorized by aggregate type

### Handler Metrics

- `wow.command.handler`: Command processing count and latency
- `wow.event.handler`: Event processing count and latency
- `wow.projection.handler`: Projection processing count and latency
- `wow.saga.handler`: Saga processing count and latency

## Metrics Tags

All metrics include the following tags:

- `aggregate`: Aggregate name
- `context`: Bounded context name
- `type`: Component type (command, event, projection, saga)
- `name`: Handler or bus name

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
            commandGateway.sendAndWait(createOrderCommand, WaitStrategy.PROCESSED)
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
    return this.tag(operation)
        .metrics()
}

fun <T> Mono<T>.tagMetrics(operation: String): Mono<T> {
    return this.tag(operation)
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

```promql
# Command processing latency
histogram_quantile(0.95, rate(wow_command_handler_duration_seconds_bucket[5m]))

# Event publishing rate
rate(wow_event_bus_send_total[5m])

# Error rate
rate(wow_command_handler_errors_total[5m]) / rate(wow_command_handler_total[5m])
```

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
2. Whether MeterRegistry Bean is correctly configured
3. Whether `/actuator/metrics` endpoint is accessible

### Performance Issues

If metrics collection affects performance:
1. Reduce the number of metrics collected
2. Use sampling rates instead of full collection
3. Consider asynchronous metrics collection