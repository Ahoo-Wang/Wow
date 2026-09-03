# Task 3 Report: Mongo Filter Compiler schema binding

## 完成内容

- 将 Mongo Filter Converter 重命名为 Compiler，并移除 `FieldConverter`、`convertField`、旧 `convert` 兼容入口。
- `compile(filter, schema)` 和 `compileWithoutDefaultDeletion(filter, schema, logicalParent, physicalParent)` 通过 `QueryModelSchema.resolvePhysicalField` 选择物理字段。
- `IdFilter`/`IdsFilter` 按模型解析 `id` 或 `aggregateId`；`AggregateIdFilter`/`AggregateIdsFilter` 始终按 `aggregateId` binding 解析。
- `$elemMatch` 保存绝对 logical/physical parent；内部 predicate 只输出相对物理路径。已接受的缺失或动态字段保留原路径。
- 同步更新 Mongo collection helper、查询后端、聚合编译器、具体后端、单元/集成测试调用点；`ResolvedQuery(query, schema)` 未变，未引入 `physicalQuery`。

## 改动文件

- `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoFilterCompiler.kt`（由 `AbstractMongoFilterConverter.kt` 重命名）
- `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/SnapshotFilterCompiler.kt`（重命名）
- `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/EventStreamFilterCompiler.kt`（重命名）
- `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/MongoCollections.kt`
- `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryBackend.kt`
- `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/aggregation/MongoAggregationCompiler.kt`
- `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryBackend.kt`
- `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryBackend.kt`
- `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/SnapshotFilterCompilerTest.kt`（重命名）
- `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/event/EventStreamFilterCompilerTest.kt`（重命名）
- `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryBackendTest.kt`
- `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt`
- `wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryBackendTest.kt`

## 测试命令与完整结果

1. RED：

   ```bash
   ./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.SnapshotFilterCompilerTest" --tests "me.ahoo.wow.mongo.query.event.EventStreamFilterCompilerTest"
   ```

   结果：预期失败；生产端尚未提供新 Compiler 类，`compileTestKotlin` 报告 `SnapshotFilterCompiler` 与 `EventStreamFilterCompiler` 未解析。

2. Filter GREEN：

   ```bash
   ./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.SnapshotFilterCompilerTest" --tests "me.ahoo.wow.mongo.query.event.EventStreamFilterCompilerTest"
   ```

   结果：`BUILD SUCCESSFUL`；46 个测试全部通过。

3. 聚合与 Filter 回归：

   ```bash
   ./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.snapshot.MongoAggregationCompilerTest" --tests "me.ahoo.wow.mongo.query.SnapshotFilterCompilerTest" --tests "me.ahoo.wow.mongo.query.event.EventStreamFilterCompilerTest"
   ```

   结果：`BUILD SUCCESSFUL`；66 个测试全部通过。

4. 模块完整检查：

   ```bash
   ./gradlew :wow-mongo:check
   ```

   结果：`BUILD SUCCESSFUL`；Detekt、编译与 `wow-mongo` 全部 253 个测试通过。

## Self-review

- 已用 `rg` 确认 Mongo query 生产、单元与集成范围内不存在旧 FilterConverter 类名、旧 filter `convert` 入口或 `convertWithoutDefaultDeletion` 调用。
- 物理 `_id`、事件流 `id`、`aggregateId`、普通 `EXACT_MATCH` binding、兼容缺失字段透传及嵌套 `$elemMatch` 相对子路径均有测试覆盖。
- 聚合 unwind 后的 `$match` 使用绝对物理路径；仅 `$elemMatch` 内部 predicate 使用相对物理路径，避免重复拼接。
- `git diff --check` 无空白错误。

## 未解决顾虑

无。Projection/Sort 的现有 `FieldConverter` 仍是 Task 4/5 的明确范围，未在本任务改动。
