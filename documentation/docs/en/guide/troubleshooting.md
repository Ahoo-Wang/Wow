# Troubleshooting Guide

This guide provides diagnostics and solutions for common issues with the Wow framework.

## Frequently Asked Questions

### 1. Command Processing

#### Q: Command Processing Timeout

**Symptom**: Command sent but no response for a long time, eventually timeout.

**Possible Causes**:
1. Message bus connection issues
2. Command processor not properly registered
3. Aggregate root loading failure
4. Event store connection issues

**Troubleshooting Steps**:

```kotlin
// Check if command is received correctly
logging.level:
  me.ahoo.wow.command: DEBUG
  me.ahoo.wow.bus: DEBUG
```

**Solutions**:
- Check Kafka/Redis connection configuration
- Confirm aggregate root class uses `@AggregateRoot` annotation
- Verify EventStore connection status
- Increase command timeout

#### Q: Duplicate Command Execution

**Symptom**: Same command executed multiple times.

**Possible Causes**:
1. Client retry
2. Idempotency check not enabled
3. Message bus duplicate delivery

**Solution**:

```yaml
wow:
  command:
    idempotency:
      enabled: true
      bloom-filter:
        ttl: PT60S
```

#### Q: Command Validation Failure

**Symptom**: `CommandValidationException` exception.

**Troubleshooting Steps**:
1. Check if command fields comply with validation rules
2. Check JSR-303 validation annotation configuration

```kotlin
data class CreateOrder(
    @field:NotBlank val customerId: String,
    @field:Size(min = 1) val items: List<OrderItem>
)
```

### 2. Event Processing

#### Q: Event Projection Delay

**Symptom**: After domain event is published, projection update is severely delayed.

**Possible Causes**:
1. Consumer processing capacity insufficient
2. Event backlog
3. Projection processor blocking

**Troubleshooting Steps**:

```yaml
# Enable projection processor logging
logging:
  level:
    me.ahoo.wow.projection: DEBUG
```

**Solutions**:
- Increase consumer instance count
- Optimize projection processor performance
- Use `@Blocking` annotation for blocking operations

```kotlin
@ProjectionProcessor
class OrderProjection {
    @OnEvent
    @Blocking
    fun onOrderCreated(event: OrderCreated) {
        // IO intensive operation
    }
}
```

#### Q: Event Processing Failure

**Symptom**: Event processing throws exception, event consumed repeatedly.

**Solution**:
- Configure retry strategy
- Use event compensation mechanism

```kotlin
@OnEvent
@Retry(maxRetries = 3)
fun onOrderCreated(event: OrderCreated) {
    // Operation that may fail
}
```

### 3. Event Sourcing

#### Q: Version Conflict Exception

**Symptom**: `EventVersionConflictException` exception.

**Cause**: Concurrent modification of the same aggregate root.

**Solution**:
- This is normal optimistic locking behavior
- Framework will automatically retry
- If frequent, consider optimizing business process

#### Q: Slow Aggregate Root Loading

**Symptom**: Aggregate root loading takes too long.

**Possible Causes**:
1. Too many events
2. Snapshots not enabled
3. EventStore query performance issue

**Solution**:

```yaml
wow:
  eventsourcing:
    snapshot:
      enabled: true
      strategy: VERSION_OFFSET
      version-offset: 10
```

#### Q: Snapshot Inconsistency

**Symptom**: Snapshot state is inconsistent with event replay result.

**Possible Causes**:
1. Event sourcing method (`onSourcing`) implementation error
2. State fields not properly updated

**Troubleshooting Steps**:
1. Check all `onSourcing` method implementations
2. Use test suite to verify event sourcing

```kotlin
class OrderSpec : AggregateSpec<Order, OrderState>({
    on {
        whenCommand(CreateOrder(...)) {
            expectEventType(OrderCreated::class)
            expectState {
                // Verify state
            }
        }
    }
})
```

### 4. Connection Issues

#### Q: Kafka Connection Failure

**Symptom**: `TimeoutException: Failed to update metadata`

**Solutions**:
1. Check `bootstrap-servers` configuration
2. Verify network connectivity
3. Confirm Kafka service status

```yaml
wow:
  kafka:
    bootstrap-servers:
      - kafka-0:9092
      - kafka-1:9092
```

#### Q: MongoDB Connection Timeout

**Symptom**: `MongoTimeoutException`

**Solutions**:
1. Check MongoDB URI configuration
2. Increase connection pool size
3. Verify network latency

```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/wow_db?connectTimeoutMS=5000&socketTimeoutMS=5000
```

#### Q: Redis Connection Failure

**Symptom**: `RedisConnectionFailureException`

**Solutions**:
1. Check Redis service status
2. Verify password configuration
3. Check network firewall

### 5. Configuration Issues

#### Q: Metadata Not Loaded

**Symptom**: `AggregateMetadataException: Aggregate metadata not found`

**Possible Causes**:
1. wow-compiler not used
2. Metadata file not generated
3. Bounded context configuration error

**Solution**:

```kotlin
// build.gradle.kts
plugins {
    id("com.google.devtools.ksp")
}

dependencies {
    ksp("me.ahoo.wow:wow-compiler")
}
```

Check generated metadata file: `build/generated/ksp/main/resources/META-INF/wow/wow-metadata.json`

#### Q: Bean Wiring Failure

**Symptom**: `NoSuchBeanDefinitionException`

**Troubleshooting Steps**:
1. Check if dependencies are correctly imported
2. Verify auto-configuration conditions
3. Check `@ConditionalOnProperty` configuration

```yaml
# Ensure basic configuration is correct
wow:
  enabled: true
```

## Performance Issue Diagnosis

### Response Latency Analysis

```yaml
# Enable detailed logging
logging:
  level:
    me.ahoo.wow: DEBUG
```

### Monitoring Metrics

The following metrics should be monitored:

| Metric | Description | Alert Threshold |
|------|------|---------|
| Command processing latency | Time from command sent to processing complete | > 1s |
| Event projection latency | Time from event published to projection updated | > 5s |
| Aggregate root loading time | Time to load aggregate root from storage | > 500ms |
| Message queue backlog | Number of pending messages | > 10000 |

### Performance Tuning Recommendations

1. **Enable LocalFirst Mode**
```yaml
wow:
  command:
    bus:
      local-first:
        enabled: true
```

2. **Optimize Snapshot Strategy**
```yaml
wow:
  eventsourcing:
    snapshot:
      strategy: VERSION_OFFSET
      version-offset: 10
```

3. **Adjust Connection Pool**
```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/wow_db?minPoolSize=10&maxPoolSize=100
```

## Log Analysis

### Enable Debug Logging

```yaml
logging:
  level:
    me.ahoo.wow: DEBUG
    me.ahoo.wow.command: TRACE
    me.ahoo.wow.eventsourcing: TRACE
    me.ahoo.wow.projection: DEBUG
```

### Key Log Patterns

| Log Pattern | Description |
|---------|------|
| `Command received` | Command has been received |
| `Command processed` | Command processing complete |
| `Event appended` | Event has been appended |
| `Snapshot saved` | Snapshot has been saved |
| `Projection updated` | Projection has been updated |

### Error Log Analysis

```
ERROR - EventVersionConflictException: version conflict for aggregate[order:order-001]
```

**Analysis**: Version conflict due to concurrent writes, framework will automatically retry.

```
ERROR - DuplicateRequestIdException: duplicate request[req-001]
```

**Analysis**: Idempotency check blocked duplicate request, this is normal behavior.

## Debugging Tips

### Local Debugging

1. Use in-memory implementation for quick testing:

```yaml
wow:
  command:
    bus:
      type: in_memory
  eventsourcing:
    store:
      storage: in_memory
    snapshot:
      storage: in_memory
```

2. Use test suite to verify business logic:

```kotlin
class OrderSpec : AggregateSpec<Order, OrderState>({
    // Test cases
})
```

### Remote Debugging

1. Enable JMX monitoring
2. Use distributed tracing (OpenTelemetry)

```yaml
wow:
  opentelemetry:
    enabled: true
```

## Getting Help

If the above solutions cannot resolve your issue, please:

1. Check [GitHub Issues](https://github.com/Ahoo-Wang/Wow/issues)
2. Submit a new Issue with:
   - Wow framework version
   - Complete error stack trace
   - Related configuration
   - Minimal reproducible example
