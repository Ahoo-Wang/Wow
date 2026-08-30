# Task 5: MongoDB/Elasticsearch Cursor Backend TCK

## 修改

- `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryBackendSpec.kt`
  - 同版本 snapshot 的两页遍历：断言 3 个 aggregateId 无重复/无遗漏，第二页终止。
  - DESC + 多字段排序；include projection 不泄漏 `version`/`aggregateId`；空页。
- `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/EventStreamQueryBackendSpec.kt`
  - 同版本 event stream 的两页遍历：断言 3 个 id 无重复/无遗漏，第二页终止。
  - DESC + 多字段排序；include projection 不泄漏 `version`/`id`；坏 cursor 为 `Invalid cursor.`；不存在 tenant 的空页。

未修改 MongoDB 或 Elasticsearch 生产代码。

## 场景与证据

- Snapshot 与 EventStream 均覆盖 size=2 的多页、tie business sort + 后端唯一键、无重复/无遗漏、终止页。
- Snapshot 与 EventStream 均覆盖 DESC 与显式多字段排序。
- Snapshot 与 EventStream 均覆盖 include projection 时 cursor 专用字段不回传。
- EventStream 覆盖坏 token；两种模型均覆盖空 terminal page。
- Controller 裁决：不扩展共享 `MockStateAggregate`/event-stream 夹具，因此 null/missing 不进入通用 TCK。现有后端聚焦单测仍覆盖该边界：`MongoCursorDocumentsTest` 的 null cursor value，以及 `AbstractElasticsearchQueryBackendTest` 的 cursor missing sort 值；已在本次 `:wow-mongo:test` / `:wow-elasticsearch:test` 定向运行中通过。

## 命令和输出

1. `./gradlew :wow-mongo:integrationTest --tests 'me.ahoo.wow.mongo.query.snapshot.MongoSnapshotQueryBackendTest.cursor*' --tests 'me.ahoo.wow.mongo.query.event.MongoEventStreamQueryBackendTest.cursor*' :wow-elasticsearch:integrationTest --tests 'me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryBackendTest.cursor*' --tests 'me.ahoo.wow.elasticsearch.query.event.ElasticsearchEventStreamQueryBackendTest.cursor*' --stacktrace`
   - `BUILD SUCCESSFUL in 24s`。
2. `./gradlew :wow-mongo:test --tests 'me.ahoo.wow.mongo.query.MongoCursorDocumentsTest' --tests 'me.ahoo.wow.mongo.query.AbstractMongoQueryBackendTest' :wow-elasticsearch:test --tests 'me.ahoo.wow.elasticsearch.query.AbstractElasticsearchQueryBackendTest' --stacktrace`
   - `BUILD SUCCESSFUL in 4s`。
3. `./gradlew :wow-mongo:integrationTest :wow-elasticsearch:integrationTest --stacktrace`
   - Elasticsearch：127 个测试、0 失败；Snapshot 60 个测试、0 失败；EventStream 23 个测试、0 失败。
   - Mongo：159 个测试中仅 1 个失败，`MongoEventStreamQueryBackendTest.strict aggregation should use explicit event payload schema`，报错为 `Query compatibility [COMPATIBLE] is rejected by mode [STRICT]`。该测试不在本任务变更的 cursor TCK 中，且每个测试独立 setup；未扩大范围修复 aggregation/schema 行为。

首次全量运行暴露的 TCK 夹具假设已收紧：event stream 的 `aggregateVersion` 会生成从 `aggregateVersion + 1` 开始的 event `version`；无数据 Elasticsearch 索引无法解析 schema，所以 empty/malformed 场景先插入不匹配的 event。未发现后端 cursor 缺陷，无生产修复，亦无第二个 RED/GREEN cycle。

## 自审

- `git diff --check` 通过。
- 仅改动两个 Task 5 TCK 源文件；新增 helper 只保存本测试所需的三个 canonical snapshot。
- 断言均为后端可观察结果，不依赖 codec、请求构造或 mock 调用次数。

## 关注项

- Mongo 完整 integration suite 不能全绿，原因如上；新增 cursor TCK 定向验证均通过。
- null/missing 的跨后端集成覆盖受共享夹具能力限制，按 controller 裁决保留在后端聚焦单测。
