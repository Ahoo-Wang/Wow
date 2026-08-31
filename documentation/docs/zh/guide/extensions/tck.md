---
title: 兼容性测试套件
description: 用共享规格验证 Wow adapter 是否满足公共运行时合同。
---

# 兼容性测试套件

`wow-tck` 发布可继承的 JUnit 规格，用同一组断言验证自定义或内置 bus、store、query、prepare 等实现。开发 adapter 或升级后端驱动时使用；普通业务领域测试使用 `wow-test`，无需直接依赖 TCK。

TCK 拥有公共合同断言，adapter test 拥有 fixture、连接、实现构造和清理，真实 backend 仍拥有原子性、唯一性、排序和故障语义。TCK 通过不等于生产拓扑、容量或迁移通过。

## 安装

```kotlin
testImplementation("me.ahoo.wow:wow-tck")
```

容器型 adapter 通常把规格放在 `integrationTest` source set，并使用 TCK 的 Kafka、Mongo、Redis 或 Elasticsearch Testcontainers fixture。Docker 不可用、镜像拉取失败或 backend readiness 失败会使集成测试失败，不应被跳过后报告为兼容。

当前公开规格包括 `CommandBusSpec`、`DomainEventBusSpec`、`StateEventBusSpec`、`EventStoreSpec`、`SnapshotStoreSpec`、`PrepareKeySpec`、`SnapshotQueryBackendSpec` 与 `EventStreamQueryBackendSpec`，以及 dispatcher/repository/modeling 规格。

## Redis 扩展案例

Redis adapter 的仓库测试位于 `wow-redis/src/integrationTest`，由真实 `RedisTestFixture` 创建 `ReactiveStringRedisTemplate`。以下片段只展示最小覆盖点；复用共享 fixture，不要复制规格内部断言。

### CommandBus

```kotlin
class RedisCommandBusTest : CommandBusSpec() {
    override fun createMessageBus(): CommandBus = RedisCommandBus(redis.redisTemplate)
}
```

规格验证发送、订阅、exchange 与确认等公共 bus 行为；Redis Streams group/claim 仍需 adapter 专有集成测试。

### DomainEventBus

```kotlin
class RedisDomainEventBusTest : DomainEventBusSpec() {
    override fun createMessageBus(): DomainEventBus = RedisDomainEventBus(redis.redisTemplate)
}
```

### StateEventBus

```kotlin
class RedisStateEventBusTest : StateEventBusSpec() {
    override fun createMessageBus(): StateEventBus = RedisStateEventBus(redis.redisTemplate)
}
```

### EventStore

```kotlin
class RedisEventStoreTest : EventStoreSpec() {
    override fun createEventStore(): EventStore = RedisEventStore(redis.redisTemplate)
}
```

EventStore spec 覆盖追加、版本冲突、请求幂等、加载与扫描的公共合同；Redis canonical key layout 和旧布局 fail-closed 另由模块测试验证。

### SnapshotStore

```kotlin
class RedisSnapshotStoreTest : SnapshotStoreSpec() {
    override fun createSnapshotStore(): SnapshotStore = RedisSnapshotStore(redis.redisTemplate)
}
```

共享规格验证较旧版本 no-op、同版本替换和并发后保留最高版本，adapter 必须依赖 backend 原子机制实现，不能用进程内预检查冒充。

### RedisPrepareKey

```kotlin
class StringRedisPrepareKeyTest : RedisPrepareKeySpec<String>() {
    override val name: String = "string"
    override val valueType: Class<String> = String::class.java
    override fun generateValue(): String = GlobalIdGenerator.generateAsString()
    override fun createPrepareKey(name: String): PrepareKey<String> =
        prepareKeyFactory.create(name, valueType)
}
```

已验证失败边界包括公共断言失败、fixture/readiness 失败、实现抛出错误或 publisher 未按合同结束。为 adapter 增加专有测试时只覆盖 TCK 未表达的 backend-native 行为。

聚焦检查 TCK 自身：

```bash
./gradlew :wow-tck:check
```

运行 Redis 的真实合同测试：

```bash
./gradlew :wow-redis:integrationTest
```

下一步阅读[应用测试](../application-testing.md)和[测试运行时](../test-runtime.md)，区分领域、合同、集成与生产证据。
