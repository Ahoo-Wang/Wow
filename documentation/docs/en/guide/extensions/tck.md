---
title: Technology Compatibility Kit
description: Verify Wow adapters against shared public runtime contracts.
---

# Technology Compatibility Kit

`wow-tck` publishes inheritable JUnit specifications that verify custom and built-in buses, stores, queries, and prepare implementations with the same assertions. Use it when developing an adapter or upgrading its backend driver. Business domain tests should use `wow-test` instead.

TCK owns public contract assertions. Adapter tests own fixtures, connections, implementation construction, and cleanup. The real backend still owns atomicity, uniqueness, ordering, and failure semantics. TCK passing is not proof of production topology, capacity, or migration.

## Installation

```kotlin
testImplementation("me.ahoo.wow:wow-tck")
```

Container-backed adapters normally place specifications in an `integrationTest` source set and use TCK Kafka, Mongo, Redis, or Elasticsearch Testcontainers fixtures. Missing Docker, image-pull failure, or backend-readiness failure must fail integration testing rather than being reported as compatibility.

Published specifications include `CommandBusSpec`, `DomainEventBusSpec`, `StateEventBusSpec`, `EventStoreSpec`, `SnapshotStoreSpec`, `PrepareKeySpec`, `SnapshotQueryBackendSpec` and `EventStreamQueryBackendSpec`, and dispatcher/repository/modeling specs.

## Redis Extension Example

Repository Redis adapter tests live in `wow-redis/src/integrationTest` and create a real `ReactiveStringRedisTemplate` through `RedisTestFixture`. These snippets show only the minimum override; reuse shared fixtures instead of copying specification assertions.

### CommandBus

```kotlin
class RedisCommandBusTest : CommandBusSpec() {
    override fun createMessageBus(): CommandBus = RedisCommandBus(redis.redisTemplate)
}
```

The specification verifies common send, subscription, exchange, and acknowledgment behavior. Redis Streams group/claim behavior still needs adapter-specific integration tests.

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

EventStore spec covers common append, version conflict, request idempotency, loading, and scanning. Redis canonical key layout and legacy-layout fail-closed behavior remain module-specific tests.

### SnapshotStore

```kotlin
class RedisSnapshotStoreTest : SnapshotStoreSpec() {
    override fun createSnapshotStore(): SnapshotStore = RedisSnapshotStore(redis.redisTemplate)
}
```

The shared spec verifies older-version no-op, same-version replacement, and highest-version retention under concurrency. An adapter must use backend atomicity rather than an in-process precheck.

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

Verified failure boundaries include shared assertion failure, fixture/readiness failure, implementation errors, or publishers that do not terminate as contracted. Add adapter-specific tests only for backend-native behavior outside the TCK.

Focused check for TCK itself:

```bash
./gradlew :wow-tck:check
```

Run Redis real contract tests with:

```bash
./gradlew :wow-redis:integrationTest
```

Next, read [Application testing](../application-testing.md) and [Test runtime](../test-runtime.md) to separate domain, contract, integration, and production evidence.
