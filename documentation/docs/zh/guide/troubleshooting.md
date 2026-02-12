# 故障排查指南

本指南提供 Wow 框架常见问题的诊断和解决方案。

## 常见问题 FAQ

### 1. 命令处理相关

#### Q: 命令处理超时

**现象**：命令发送后长时间没有响应，最终超时。

**可能原因**：
1. 消息总线连接问题
2. 命令处理器未正确注册
3. 聚合根加载失败
4. 事件存储连接问题

**排查步骤**：

```kotlin
// 检查命令是否被正确接收
logging.level:
  me.ahoo.wow.command: DEBUG
  me.ahoo.wow.bus: DEBUG
```

**解决方案**：
- 检查 Kafka/Redis 连接配置
- 确认聚合根类使用了 `@AggregateRoot` 注解
- 验证 EventStore 连接状态
- 增加命令超时时间

#### Q: 重复命令执行

**现象**：同一命令被执行多次。

**可能原因**：
1. 客户端重试
2. 幂等性检查未启用
3. 消息总线重复投递

**解决方案**：

```yaml
wow:
  command:
    idempotency:
      enabled: true
      bloom-filter:
        ttl: PT60S
```

#### Q: 命令验证失败

**现象**：`CommandValidationException` 异常。

**排查步骤**：
1. 检查命令字段是否符合验证规则
2. 检查 JSR-303 验证注解配置

```kotlin
data class CreateOrder(
    @field:NotBlank val customerId: String,
    @field:Size(min = 1) val items: List<OrderItem>
)
```

### 2. 事件处理相关

#### Q: 事件投影延迟

**现象**：领域事件发布后，投影更新延迟严重。

**可能原因**：
1. 消费者处理能力不足
2. 事件积压
3. 投影处理器阻塞

**排查步骤**：

```yaml
# 启用投影处理器日志
logging:
  level:
    me.ahoo.wow.projection: DEBUG
```

**解决方案**：
- 增加消费者实例数量
- 优化投影处理器性能
- 使用 `@Blocking` 标记阻塞操作

```kotlin
@ProjectionProcessor
class OrderProjection {
    @OnEvent
    @Blocking
    fun onOrderCreated(event: OrderCreated) {
        // IO 密集型操作
    }
}
```

#### Q: 事件处理失败

**现象**：事件处理抛出异常，事件重复消费。

**解决方案**：
- 配置重试策略
- 使用事件补偿机制

```kotlin
@OnEvent
@Retry(maxRetries = 3)
fun onOrderCreated(event: OrderCreated) {
    // 可能失败的操作
}
```

### 3. 事件溯源相关

#### Q: 版本冲突异常

**现象**：`EventVersionConflictException` 异常。

**原因**：并发修改同一聚合根。

**解决方案**：
- 这是正常的乐观锁行为
- 框架会自动重试
- 如果频繁发生，考虑优化业务流程

#### Q: 聚合根加载缓慢

**现象**：聚合根加载时间过长。

**可能原因**：
1. 事件数量过多
2. 未启用快照
3. EventStore 查询性能问题

**解决方案**：

```yaml
wow:
  eventsourcing:
    snapshot:
      enabled: true
      strategy: VERSION_OFFSET
      version-offset: 10
```

#### Q: 快照不一致

**现象**：快照状态与事件回放结果不一致。

**可能原因**：
1. 事件溯源方法 (`onSourcing`) 实现错误
2. 状态字段未正确更新

**排查步骤**：
1. 检查所有 `onSourcing` 方法的实现
2. 使用测试套件验证事件溯源

```kotlin
class OrderSpec : AggregateSpec<Order, OrderState>({
    on {
        whenCommand(CreateOrder(...)) {
            expectEventType(OrderCreated::class)
            expectState {
                // 验证状态
            }
        }
    }
})
```

### 4. 连接问题

#### Q: Kafka 连接失败

**现象**：`TimeoutException: Failed to update metadata`

**解决方案**：
1. 检查 `bootstrap-servers` 配置
2. 验证网络连通性
3. 确认 Kafka 服务状态

```yaml
wow:
  kafka:
    bootstrap-servers:
      - kafka-0:9092
      - kafka-1:9092
```

#### Q: MongoDB 连接超时

**现象**：`MongoTimeoutException`

**解决方案**：
1. 检查 MongoDB URI 配置
2. 增加连接池大小
3. 验证网络延迟

```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/wow_db?connectTimeoutMS=5000&socketTimeoutMS=5000
```

#### Q: Redis 连接失败

**现象**：`RedisConnectionFailureException`

**解决方案**：
1. 检查 Redis 服务状态
2. 验证密码配置
3. 检查网络防火墙

### 5. 配置问题

#### Q: 元数据未加载

**现象**：`AggregateMetadataException: Aggregate metadata not found`

**可能原因**：
1. 未使用 wow-compiler
2. 元数据文件未生成
3. 限界上下文配置错误

**解决方案**：

```kotlin
// build.gradle.kts
plugins {
    id("com.google.devtools.ksp")
}

dependencies {
    ksp("me.ahoo.wow:wow-compiler")
}
```

检查生成的元数据文件：`build/generated/ksp/main/resources/META-INF/wow/wow-metadata.json`

#### Q: Bean 装配失败

**现象**：`NoSuchBeanDefinitionException`

**排查步骤**：
1. 检查依赖是否正确引入
2. 验证自动配置条件
3. 检查 `@ConditionalOnProperty` 配置

```yaml
# 确保基础配置正确
wow:
  enabled: true
```

## 性能问题诊断

### 响应延迟分析

```yaml
# 启用详细日志
logging:
  level:
    me.ahoo.wow: DEBUG
```

### 监控指标

建议监控以下指标：

| 指标 | 说明 | 告警阈值 |
|------|------|---------|
| 命令处理延迟 | 命令发送到处理完成的时间 | > 1s |
| 事件投影延迟 | 事件发布到投影更新的时间 | > 5s |
| 聚合根加载时间 | 从存储加载聚合根的时间 | > 500ms |
| 消息队列积压 | 待处理消息数量 | > 10000 |

### 性能调优建议

1. **启用 LocalFirst 模式**
```yaml
wow:
  command:
    bus:
      local-first:
        enabled: true
```

2. **优化快照策略**
```yaml
wow:
  eventsourcing:
    snapshot:
      strategy: VERSION_OFFSET
      version-offset: 10
```

3. **调整连接池**
```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/wow_db?minPoolSize=10&maxPoolSize=100
```

## 日志分析

### 启用调试日志

```yaml
logging:
  level:
    me.ahoo.wow: DEBUG
    me.ahoo.wow.command: TRACE
    me.ahoo.wow.eventsourcing: TRACE
    me.ahoo.wow.projection: DEBUG
```

### 关键日志模式

| 日志模式 | 说明 |
|---------|------|
| `Command received` | 命令已接收 |
| `Command processed` | 命令处理完成 |
| `Event appended` | 事件已追加 |
| `Snapshot saved` | 快照已保存 |
| `Projection updated` | 投影已更新 |

### 错误日志分析

```
ERROR - EventVersionConflictException: version conflict for aggregate[order:order-001]
```

**分析**：并发写入导致版本冲突，框架会自动重试。

```
ERROR - DuplicateRequestIdException: duplicate request[req-001]
```

**分析**：幂等性检查阻止了重复请求，这是正常行为。

## 调试技巧

### 本地调试

1. 使用内存实现进行快速测试：

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

2. 使用测试套件验证业务逻辑：

```kotlin
class OrderSpec : AggregateSpec<Order, OrderState>({
    // 测试用例
})
```

### 远程调试

1. 启用 JMX 监控
2. 使用分布式追踪（OpenTelemetry）

```yaml
wow:
  opentelemetry:
    enabled: true
```

## 获取帮助

如果以上解决方案无法解决您的问题，请：

1. 查阅 [GitHub Issues](https://github.com/Ahoo-Wang/Wow/issues)
2. 提交新 Issue 并提供：
   - Wow 框架版本
   - 完整的错误堆栈
   - 相关配置
   - 最小可复现示例
