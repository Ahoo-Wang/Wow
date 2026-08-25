# Task 2：DateHistogram 从 LogicalField 读取时间类型

## 实现

- `AggregationGroup.DateHistogram` 的默认时区改为 `ZoneId.systemDefault().id`；构造时通过 `LogicalField.temporalTypeOrDefault()` 接受未标注、`DATE` 和 `NUMBER`，拒绝 `STRING`。未来若增加非时间 `FieldType`，该方法也会在同一公共构造路径抛出异常。
- `AggregationQueryDsl.dateHistogram` 新增以 `LogicalField` 为主的重载，并保留 `String` 简写委托；两者参数顺序均为 `field, unit, alias, timeZone`，默认 `ZoneId.systemDefault()`。
- Mongo、Elasticsearch 与 TCK 的原生 `createdAt` 直方图调用改为 `LogicalField(..., FieldType.Temporal.Date)`。原先测试依赖 UTC 桶边界的 TCK 调用显式传入 `ZoneId.of("Z")`，因此默认时区契约变化不会改变其既有断言。

修改文件：

- `wow-api/src/main/kotlin/me/ahoo/wow/api/query/AggregationQuery.kt`
- `wow-api/src/test/kotlin/me/ahoo/wow/api/query/AggregationQueryTest.kt`
- `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDsl.kt`
- `wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDslTest.kt`
- `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt`
- `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompilerTest.kt`
- `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryServiceSpec.kt`

## RED

命令：

```bash
./gradlew :wow-api:test --tests "me.ahoo.wow.api.query.AggregationQueryTest" --stacktrace
```

实际关键输出：

```text
AggregationQueryTest > date histogram should reject STRING() FAILED
Expected java.lang.IllegalArgumentException to be thrown, but nothing was thrown.

AggregationQueryTest > date histogram should use logical field temporal type() FAILED
expected: "Asia/Shanghai"
 but was: "UTC"

14 tests completed, 2 failed
BUILD FAILED in 3s
```

失败符合预期：原实现没有针对 `STRING` 的校验，且默认时区仍为 UTC。

DSL 另外执行：

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.dsl.AggregationQueryDslTest" --stacktrace
```

实际关键输出：

```text
AggregationQueryDslTest.kt:74:27 Argument type mismatch: actual type is 'LogicalField', but 'String' was expected.
BUILD FAILED in 3s
```

失败符合预期：主 `LogicalField` DSL 重载尚未存在。

## GREEN 与模块验证

聚焦 GREEN 命令：

```bash
./gradlew :wow-api:test --tests "me.ahoo.wow.api.query.AggregationQueryTest" :wow-query:test --tests "me.ahoo.wow.query.dsl.AggregationQueryDslTest" --stacktrace
```

实际末尾输出：

```text
> Task :wow-api:test
> Task :wow-query:test
BUILD SUCCESSFUL in 5s
29 actionable tasks: 12 executed, 17 up-to-date
```

模块及迁移调用验证命令：

```bash
./gradlew :wow-api:test :wow-query:test :wow-mongo:test --tests "me.ahoo.wow.mongo.query.snapshot.MongoAggregationCompilerTest" :wow-elasticsearch:test --tests "me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchAggregationCompilerTest" --stacktrace
```

实际末尾输出：

```text
> Task :wow-mongo:test
> Task :wow-api:test
> Task :wow-query:test
> Task :wow-elasticsearch:test
BUILD SUCCESSFUL in 5s
39 actionable tasks: 10 executed, 29 up-to-date
```

还执行了 `git diff --check`，退出成功且无输出。

## 自检与关注点

- 自检了全部 Kotlin `dateHistogram(` 调用：原生日期字段均已用 `FieldType.Temporal.Date` 声明；没有实现后端时间类型编译，避免越过任务边界。
- API 测试覆盖默认毫秒数、系统默认时区、DATE 接受以及 STRING 拒绝；DSL 测试覆盖 `LogicalField` 主重载。
- Gradle 输出中有项目既有的弃用及 Kotlin 编译器提示；本改动未新增失败或错误。
- 未发现阻塞性关注点。
