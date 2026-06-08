# Wow Serialization Subsystem Design (2026-06-08)

## 1. 目标

为 `wow-core/src/main/kotlin/me/ahoo/wow/serialization/` 目录中的序列化链路建立一个**无破坏兼容**的改造方案，重点覆盖：

- 保持现有 JSON 兼容性，不引入新的 wire 格式。
- 提升反序列化健壮性，避免由于脏数据导致全链路失败。
- 增强未知事件类型的回退可观测性与稳定性。
- 在高并发场景下控制对象分配与反序列化路径开销。

本次不做协议破坏性变更，不重构现有 API。

## 2. 现状与问题识别

### 2.1 当前模块边界

- `JsonSerializer` 定义统一 `ObjectMapper` 配置（字段可见性、BIG_DECIMAL、module 自动发现）。
- `MessageSerializer` 提供命令/事件/状态三类消息公共字段序列化骨架。
- `JsonMessageRecord` 提供反序列化 record 视图（`MessageRecord`, `CommandRecord`, `DomainEventRecord`, `EventStreamRecord`, `StateRecord`）。
- `WowModule` 注册自定义序列化器到 Jackson。

### 2.2 关键风险点

1. 反序列化层对输入字段缺失依赖较多，存在异常抛出风险。
2. `EventStreamRecord.toDomainEventStream()` 与 `DomainEventRecord.toDomainEvent()` 的失败路径和对象构造路径在高吞吐下有优化空间。
3. `TypeNameMapper.toType()` 命中失败时异常驱动回退（`ClassNotFoundException`）是可接受的兼容策略，但应明确标为慢路径。
4. `toMessageHeader()` 对 Header 反序列化是固定字符串映射，当前语义稳定但在异常输入下缺少明确保护。

## 3. 设计范围

### In-Scope

- `wow-core/src/main/kotlin/me/ahoo/wow/serialization/` 下的核心序列化与反序列化实现。
- `wow-core/src/main/kotlin/me/ahoo/wow/event/upgrader` 与 `me/ahoo/wow/infra/TypeNameMapper` 中的兼容链路。
- 配套测试主要聚焦 `wow-core/src/test/kotlin/me/ahoo/wow/serialization/*`。

### Out-of-Scope

- JSON schema 的重构、字段名变更、版本字段新增。
- 更换 Jackson 生态或 `ObjectMapper` 核心配置。
- 跨模块序列化接入改造（非 `serialization` 子树）。

## 4. 设计目标与约束

### 4.1 功能约束（Must）

- 不改变现有序列化输出（JSON 字段名与默认行为）。
- 历史事件的旧 JSON 必须可再次反序列化。
- 未知事件类型继续回退为 `JsonDomainEvent`。
- 兼容 `StateJsonRecord` 的现有缺元数据行为。

### 4.2 非功能约束（Must/Should）

- **性能优先**：保持主路径的吞吐和延迟不下降。
- **内存优先**：尽量降低热路径短生命周期对象的产生。
- **可观测优先**：慢路径（回退/修复）应可识别、可统计。

## 5. 建议实现（分层）

### 5.1 Serialization Robustness Layer（最小改造）

1. 在 `JsonMessageRecord.toMessageHeader()` 及各个 Record 的字段访问处，新增防御式读取策略（如默认值、空安全）而不破坏现有正向行为。
2. 保持现有 `MessageSerializer` 写入字段不变。
3. 对事件反序列化加入结构化降级分支：
   - 能解析字段优先走“强类型”路径。
   - 缺失字段或类型转换失败走可审计的安全回退路径。

### 5.2 Event/Stream Deserialization Path（兼容与性能）

1. `EventStreamRecord.toDomainEventStream()`：
   - 继续按 body 顺序重建 `sequence/isLast`。
   - 避免在回退或成功路径新增无意义中间分配。
2. `DomainEventRecord.toDomainEvent()`：
   - 保留 `Class.forName` 的 cache 命中优化；
   - 把 `ClassNotFoundException` 视为兼容慢路径，返回 `JsonDomainEvent`。
3. `StateEventJsonDeserializer`：
   - 在 `aggregate metadata` 缺失时维持现有 `StateJsonRecord` 行为。

### 5.3 性能与内存改造点

1. 统一减少“每次创建后被丢弃”的临时包装对象。
2. 对头部与事件体解析路径保持“按需转换”，避免在非必需场景复制。
3. 对异常回退路径避免额外 `stack trace` 重建成本（仅在错误日志级别需要时记录）。
4. 在 JMH 与微基准层补充反序列化序列长度分层测试，验证分配与延迟。

## 6. 数据流（目标状态）

```text
JSON String / ObjectNode
  -> MessageRecord / DomainEventRecord / EventStreamRecord / StateRecord
    -> 可选升级：EventUpgraderFactory (按需)
      -> JsonDomainEvent(回退) 或 Strongly Typed DomainEvent
        -> 上层业务使用对象
```

### 6.1 关键数据保真规则

- `commandId`、`requestId`、`aggregateId`、`version`、`sequence`、`revision` 在可用时保持原值。
- `event stream` 的 `sequence/isLast` 仍以流内顺序为准。
- header 字段保持字符串 map 语义。

## 7. 错误处理与降级策略

- 未知 bodyType：返回 `JsonDomainEvent` 并保留 `bodyType/body/id/name` 及 header。
- 缺失可选字段：回退到安全默认值（或保留原异常行为仅在关键字段缺失时），并仅在兼容风险较低时触发。
- 结构损坏或严重缺失：抛出异常，交由上层治理（与现有失败策略一致）。

## 8. 测试与验证计划

### 8.1 单元测试（新增/更新）

1. `JsonSerializerMapperTest`：补充不完整 JSON 容错/缺省字段场景。
2. `JsonSerializerPolymorphicTest`：补充未知 bodyType 的异常触发条件与回退元数据保持。
3. `JsonSerializerEventTest`：补充大流事件序列 `sequence/isLast` 重建稳定性。
4. `StateJsonRecordTest`：补充头部缺失时的默认化行为。

### 8.2 性能/内存验证

- 增加反序列化基准或场景测试（按事件流长度分组：1/16/128/1024）。
- 关注以下指标：
  - P50/P95/P99 延迟
  - 每次反序列化对象分配数量（或 JVM 分配率）
  - 大流场景吞吐（events/sec）
- 目标：成功路径分配不增加，未知类型慢路径保持可接受。

## 9. 风险与兼容性

- 边界风险：过度放宽字段校验可能掩盖上游问题。
- 缓解：只对非关键字段使用防御式读取，关键字段保持现有异常语义。
- 回退风险：更多回退日志可能放大 IO；缓解为按级别和抽样输出。

## 10. 实施步骤

1. 修改 `JsonMessageRecord` 关键读取逻辑，补充缺失字段容错。
2. 在 `DomainEventRecord`/`EventStreamRecord` 保持语义前提下修复性能阻断点。
3. 增加/更新 `wow-core/src/test/kotlin/me/ahoo/wow/serialization/*` 用例。
4. 若有 benchmark 框架：增加序列化/反序列化性能场景。
5. 完成一次性回归确认并提交。

## 11. 交付定义

- 功能不变（wire compatible）。
- 反序列化在已知兼容异常场景下不崩溃到系统级故障。
- 未知事件类型仍可完整回退为 `JsonDomainEvent`。
- 性能/内存指标不劣化（尤其是成功路径）。
