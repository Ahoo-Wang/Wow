---
title: 预分配 Key
description: PrepareKey 的条件预留、TTL、回滚和换 Key 语义及其事务边界。
outline: deep
---

# 预分配 Key

`PrepareKey<V>` 为用户名、SKU 等跨聚合唯一键提供应用级预留。它把“某个 key 当前归哪个 value”交给 MongoDB、Redis 等 Adapter 原子实现；它不是 EventStore 的一部分，也不是跨 EventStore 与外部存储的数据库事务。

## 声明与装配

```kotlin
@PreparableKey(name = "username")
interface UsernamePrepareKey : PrepareKey<UsernameIndex>
```

Starter 在应用 base package 与 `wow.prepare.base-packages` 中扫描带 `@PreparableKey` 的接口。接口必须直接暴露 `PrepareKey<V>` 的具体 value 类型。注解 name 为空时使用接口简单名；Spring Bean 名也使用接口简单名。

只有存在 `PrepareKeyFactory` 时，默认 proxy factory 才能创建后端 delegate。后端与开关配置见[核心配置参考](../../reference/config/core.md)及 [MongoDB](../extensions/mongo.md)/[Redis](../extensions/redis.md)扩展页。

## 操作合同

| 操作 | 成功条件 | `false` 表示 |
| --- | --- | --- |
| `prepare(key, value)` | key 可被当前 value 预留 | key 已被占用 |
| `get(key)` | 存在且 `PreparedValue` 未过期 | 返回 empty 表示不存在或已过期 |
| `getValue(key)` | 存在记录 | 返回完整 value 与 `ttlAt`，即使已过期 |
| `rollback(key)` | 无条件删除当前记录 | 没有可删除记录 |
| `rollback(key, value)` | 当前 value 匹配后删除 | key 不存在或 value 不匹配 |
| `reprepare(key, old, new)` | 当前 value 等于 old，并替换为 new | key 不存在或 old 不匹配 |

原子性由具体 `PrepareKeyFactory` 实现。应用不能从接口本身推断锁范围、隔离级别或跨区域一致性。

## TTL

`PreparedValue` 保存 value 和绝对过期时间 `ttlAt`（Unix epoch 毫秒）：

```kotlin
val forever = value.toForever()
val temporary = value.toTtlAt(System.currentTimeMillis() + 5 * 60_000)
```

`get` 在客户端接口层过滤已过期值；具体后端负责让过期 key 可以再次 prepare。TTL 依赖调用方/后端时钟，不能作为精确业务定时器。永久值使用框架常量 `TTL_FOREVER`，不要自行复制数值。

## `usingPrepare` 的精确边界

```kotlin
return usernamePrepareKey.usingPrepare(command.username, index) { prepared ->
    require(prepared) { "username is already reserved" }
    Registered(command.username).toMono()
}
```

流程是：

1. 调用 `prepare`；
2. 把 Boolean 结果交给 `then`，无论 true/false；
3. 只有 `prepared == true` 且 `then` 以 error 终止时，调用条件 rollback；
4. rollback 完成后继续传播原始 error；若 rollback 自身失败，响应式链会传播该 rollback error。

当前实现使用 `onErrorResume`，没有为 cancellation 注册自动 rollback。成功结果也不会“commit”另一份记录：预留本身继续存在，直到显式 rollback、reprepare 或 TTL 过期。因此“事务-like”只指错误路径的条件释放，不能扩展成跨存储事务保证。

## 改变 Key

```kotlin
prepareKey.reprepare(
    oldKey = state.username,
    oldValue = currentIndex,
    newKey = command.newUsername,
    newValue = currentIndex,
)
```

默认组合实现先 `prepare(newKey)`，成功后再条件 `rollback(oldKey, oldValue)`：

- 新 key 已占用：返回 `false`，旧 key 保持不变；
- old key/value 不匹配：抛出 `IllegalStateException`，错误路径尝试释放刚预留的新 key；
- oldKey 与 newKey 相同：立即拒绝，调用方应使用同 key 的 `reprepare` overload。

这是两个后端操作的补偿式组合，不应描述成不可分割的跨 key 事务。进程崩溃、超时或取消发生在两步之间时，恢复策略仍需结合后端与 TTL 验证。

## 与聚合命令配合

PrepareKey 适合在命令决策需要独占外部唯一 key 时调用，但要明确 EventStore append 可能随后失败。选择永久预留时，应设计命令失败后的释放/对账；选择 TTL 时，应证明业务可接受预留过期及再次申请。

不要把 `requestId` 幂等、EventStore 版本并发和 PrepareKey 唯一性混成一个机制：

- request ID 识别重复命令请求；
- aggregate version 保护一条聚合事件流；
- PrepareKey 协调多个聚合争用同一应用 key。

## 验证

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.infra.prepare.PrepareKeyTest"
./gradlew :wow-core:test --tests "me.ahoo.wow.infra.prepare.proxy.PrepareKeyProxyAndMetadataTest"
./gradlew :wow-mongo:integrationTest --tests "*PrepareKey*"
./gradlew :wow-redis:integrationTest --tests "*PrepareKey*"
```

后两项需要对应基础设施。应用还应覆盖崩溃窗口、TTL/时钟和失败后的对账。

## 源码

- [`PrepareKey`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/infra/prepare/PrepareKey.kt)
- [`PreparedValue`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/infra/prepare/PreparedValue.kt)
- [`PrepareKeyAutoRegistrar`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/prepare/PrepareKeyAutoRegistrar.kt)
