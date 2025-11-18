# 指标

Wow 框架集成了 Micrometer 指标收集功能，为所有核心组件提供全面的性能监控和可观测性。

## 安装

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

## 自动指标收集

Wow 框架自动为以下组件收集指标：

### 命令总线指标

- `wow.command.bus.send`: 命令发送计数和延迟
- `wow.command.bus.receive`: 命令接收计数和延迟
- 按聚合类型和命令类型分类的指标

### 事件总线指标

- `wow.event.bus.send`: 事件发布计数和延迟
- `wow.event.bus.receive`: 事件接收计数和延迟
- 按聚合类型和事件类型分类的指标

### 事件存储指标

- `wow.eventstore.append`: 事件追加计数和延迟
- `wow.eventstore.load`: 事件加载计数和延迟
- 按聚合类型分类的指标

### 快照指标

- `wow.snapshot.save`: 快照保存计数和延迟
- `wow.snapshot.load`: 快照加载计数和延迟
- 按聚合类型分类的指标

### 处理器指标

- `wow.command.handler`: 命令处理计数和延迟
- `wow.event.handler`: 事件处理计数和延迟
- `wow.projection.handler`: 投影处理计数和延迟
- `wow.saga.handler`: Saga 处理计数和延迟

## 指标标签

所有指标都包含以下标签：

- `aggregate`: 聚合名称
- `context`: 限界上下文名称
- `type`: 组件类型 (command, event, projection, saga)
- `name`: 处理器或总线名称

## 自定义指标

### 手动指标收集

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
                    // 业务成功指标
                    meterRegistry.counter("wow.business.order.created").increment()
                }
                .doOnError { error ->
                    sample.stop(orderCreationTimer)
                    // 业务失败指标
                    meterRegistry.counter("wow.business.order.failed").increment()
                }
        }
    }
}
```

### 反应式流指标

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

## 配置

### Micrometer 配置

```yaml
management:
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: ${spring.application.name}
```

### Wow 指标配置

Wow 框架的指标收集默认启用，可以通过以下方式控制：

```yaml
wow:
  metrics:
    enabled: true  # 默认启用
```

## 监控仪表板

### Prometheus + Grafana

使用 Prometheus 收集指标，Grafana 创建仪表板：

```yaml
# Prometheus 配置
scrape_configs:
  - job_name: 'wow-application'
    static_configs:
      - targets: ['localhost:8080']
    metrics_path: '/actuator/prometheus'
```

### 常用查询

```promql
# 命令处理延迟
histogram_quantile(0.95, rate(wow_command_handler_duration_seconds_bucket[5m]))

# 事件发布速率
rate(wow_event_bus_send_total[5m])

# 错误率
rate(wow_command_handler_errors_total[5m]) / rate(wow_command_handler_total[5m])
```

## 性能影响

- **轻量级**: 指标收集使用高效的计数器和直方图
- **异步**: 不会阻塞业务逻辑执行
- **可配置**: 可以根据需要启用或禁用特定指标

## 最佳实践

1. **选择合适的指标**: 只收集真正需要的指标
2. **设置合理的标签**: 避免标签数量过多导致的性能问题
3. **监控告警**: 为关键业务指标设置告警阈值
4. **定期审查**: 定期审查和清理不再需要的指标

## 故障排除

### 指标不显示

检查：
1. Micrometer 依赖是否正确添加
2. MeterRegistry Bean 是否正确配置
3. `/actuator/metrics` 端点是否可访问

### 性能问题

如果指标收集影响性能：
1. 减少收集的指标数量
2. 使用采样率而不是全量收集
3. 考虑异步指标收集