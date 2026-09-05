# 查询模块第三阶段：订阅隔离与投影结果合同

日期：2026-09-05

状态：第三阶段范围与方案已确认；本书面设计待审阅。

基线：`ea89fadc3`，Wow `9.0.8`。前置工作见[第二阶段设计与结果](2026-09-05-aggregation-compiler-refactoring-design.md)。

## 目标与选择

修复两个已复现的执行合同缺陷：ES 游标 Publisher 必须为每次订阅发起独立请求；MongoDB 查询结果必须允许投影省略逻辑主键。继续沿用已有 Gateway、Schema 准入、Backend、客户端和 JSON 转换，不新增执行层。

本阶段选择先修复这两处确定性问题。另一方案是同时优化 ES 无分组汇总的 PIT 开销；该方案还需核对原生请求、分片失败和别名语义，留待后续独立评估。本阶段不修改 PIT 或聚合执行器，也不重开前两阶段的解析性能优化。

## 已确认的证据

### ES 游标提前请求并共享响应

[AbstractElasticsearchQueryBackend.executeCursor](../../wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchQueryBackend.kt) 当前在创建返回的 Mono 时直接调用 `elasticsearchClient.search`。当前构建使用 Spring Data Elasticsearch `6.1.1`；其 `search` 通过 `Mono.fromFuture(transport.performRequestAsync(...))` 包装已创建的 Future。

因此，保存并重复订阅同一个 Backend 游标 Publisher 会复用同一 Future、响应及 `Hit.source()` 返回的可变 `ObjectNode`。这违反 [QueryBackend](../../wow-query/src/main/kotlin/me/ahoo/wow/query/QueryBackend.kt) 明确规定的每次订阅、retry、repeat 和并发调用的节点所有权合同。

研究阶段以真实 `6.1.1` 客户端和计数 transport 做了 JShell 内存复现：

```text
未订阅时：请求数 = 1
连续两次订阅后：请求数 = 1
两次结果节点相同：true
修改首次结果后，第二次结果看到该修改：true
```

复用失败的 Future 也使 retry 无法重新查询。Gateway 外层已有 `defer`，每次订阅通常会重新调用 Backend，从而避开这一缺陷；修复仍应位于 Backend，满足其自身的公开 SPI 合同。

### MongoDB 合法投影在映射时失败

`dynamicSingle/dynamicList/dynamicPaged` 经 Schema 准入后，MongoProjectionCompiler 将 Snapshot 的 `aggregateId` 或 EventStream 的 `id` 排除转换为 `{_id:0}`。原生投影返回的 Document 因此没有 `_id`。

两个内建查询 Backend 的 `toObjectNode` 均无条件调用 [Documents.replacePrimaryKeyTo](../../wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/Documents.kt)，其 `checkNotNull(getString("_id"))` 使合法查询转为异常。现有 [MongoProjectionCompilerTest](../../wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/MongoProjectionCompilerTest.kt) 已明确接受这两种逻辑主键排除。

使用当前构建中的 Schema、原生投影编译器和映射函数，最小复现为：

```text
aggregateId compatibility=EXACT projection={"_id": 0}
aggregateId mapping=IllegalStateException: Required value was null.
id compatibility=EXACT projection={"_id": 0}
id mapping=IllegalStateException: Required value was null.
```

这些是内存中的合同复现；真实数据库投影与实际网络查询由实施阶段的集成测试验证，不把内存复现描述为真实后端集成通过。

## 架构与生产范围

| 生产文件 | 变更职责 |
|---|---|
| [AbstractElasticsearchQueryBackend.kt](../../wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchQueryBackend.kt) | 仅延后游标的客户端搜索调用 |
| [AbstractMongoQueryBackend.kt](../../wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryBackend.kt) | 增加查询专用的 internal Document 结果转换函数 |
| [MongoSnapshotQueryBackend.kt](../../wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryBackend.kt) | 使用查询转换函数，目标主键名为 `aggregateId` |
| [MongoEventStreamQueryBackend.kt](../../wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryBackend.kt) | 使用同一查询转换函数，目标主键名为 `id` |

生产变更限定于这四个现有文件。保留 `protected toObjectNode(Document)` 扩展点及所有公开方法、构造器和接口。不新增公开 API、依赖、模块、配置或通用转换框架。

Query JSON、Schema HTTP、生成 OpenAPI/schema、Cursor token、排序、分页大小、存储布局及版本号不变。Gateway 的过滤、ABAC、脱敏、错误处理与 Schema 快照机制保持不变。

## ES 游标设计

保持过滤/排序编译、SearchRequest 构建及 token 校验的既有时机。先按原逻辑得到不可变的 SearchRequest，再以 `Mono.defer` 包装 `elasticsearchClient.search(request, ObjectNode::class.java)`；响应继续调用现有 `toCursorPage`。

这样同一 Publisher 可以复用已构造的请求参数，但每次订阅都会调用客户端并取得新的 Future 和解码结果。不通过复制已共享的结果节点掩盖问题，也不缓存 Mono、Future 或响应。

| 行为 | 修复后的要求 |
|---|---|
| 创建合法游标 Publisher，尚未订阅 | 客户端搜索调用为零 |
| 两次订阅或 repeat | 两次独立客户端调用；返回节点互不共享 |
| 请求失败后 retry | 再次调用客户端，保留 Reactor 的原生错误传播 |
| 下游修改结果后失败并 retry | 新订阅取得干净结果，不继承上次修改 |
| 两个并发订阅 | 各自拥有请求与结果；取消其中一个不终止另一个 |
| 无效 token 或参数 | 保持原校验及异常时机，不先发送请求 |

不改变 `size + 1` 前探、有效 sort、缺失排序值处理、`search_after` 编码或末页 token。游标仍不使用 PIT，也不承诺跨请求快照；取消继续交给 Reactor 和现有客户端处理，不另建资源管理器。

## MongoDB 查询映射设计

在 `AbstractMongoQueryBackend.kt` 内增加一个包内函数 `Document.toQueryObjectNode(idField: String): ObjectNode`，供两个内建查询 Backend 复用：

1. 用 `containsKey(Documents.ID_FIELD)` 判断 `_id` 是否实际存在。
2. 存在时调用现有严格的 `replacePrimaryKeyTo(idField)`，沿用其重命名、删除 `_id` 与错误行为。
3. 缺席时直接进入现有 `Document.toObjectNode()`，保留投影结果中主键缺席的形状。

函数只服务查询结果。通用 `Documents.replacePrimaryKeyTo`、`toSnapshot`、`toMaterializedSnapshot`、`toDomainEventStream` 及其调用者保持不变，存储对象缺失主键时仍失败。

| 输入/路径 | 预期行为 |
|---|---|
| 查询 Document 有字符串 `_id` | 按模型重命名为 `aggregateId` 或 `id`，删除 `_id` |
| 查询 Document 缺少 `_id` | 正常转换，不凭空补出主键 |
| 查询 Document 的 `_id` 为 null | 继续抛出既有 `IllegalStateException` |
| 查询 Document 的 `_id` 类型不是字符串 | 继续抛出既有类型错误，不强制转字符串 |
| 无 `_id`，但已有逻辑主键字段 | 保留已有字段，不覆盖或删除 |
| `_id` 与逻辑主键同时存在 | 沿用严格重命名函数现有的覆盖行为 |
| 存储对象转换缺少 `_id` | 仍失败，不获得投影兼容行为 |

此变更让 single/list/paged 的动态结果遵守投影。typed Gateway 仍按目标类型的必填字段反序列化，不为缺失的目标类型必填字段提供默认值。

Mongo 游标已有内部排序字段补取逻辑：先从 Document 提取续页 token，再转换响应并删除隐藏字段。本阶段保留这一顺序和既有测试。聚合行继续走自己的结果转换流程，不改为调用本次查询映射函数。

JSON 归一化继续使用既有 Jackson/BSON 支持，不增加手写树转换、深复制、字段验证或存储类型推断。

## 测试与验证

以两个已复现问题对应的测试先失败、最小修复后通过为依据。沿用 JUnit、MockK、FluentAssert、Reactor test 与现有 Testcontainers；测试必须同时检查实际请求/结果和合同，不只断言私有实现结构。

### ES

- 新增聚焦的游标订阅测试，使用实际内建 Backend。客户端 stub 在每次调用时分配独立 Future/响应，模拟当前客户端的立即创建行为；不能把 stub 本身写成跨订阅重新创建响应的冷源而掩盖缺陷。
- 覆盖未订阅零请求、重复订阅/下游修改、请求错误后的 retry、并发订阅及独立取消。使用受控 Future 或 Reactor 信号协调，不依赖 sleep 猜测时序。
- 复用现有请求、排序、token 和无效响应测试，确保只改变 I/O 触发时机。
- 在 Snapshot 与 EventStream 的现有真实查询集成夹具中，直接复用同一个 Backend 游标 Publisher，验证两模型的结果隔离。至少包含一次修改后重新订阅，以及一个实际 retry/repeat 路径；避免只经过 Gateway 后让外层 defer 隐藏问题。

### MongoDB

- 用两个实际内建 Backend，覆盖 single/list/paged 的逻辑主键排除，断言数据正常返回、主键缺席；paged 同时断言 total 与列表内容。
- 检查有主键时的原重命名行为、null/错误类型的拒绝、已有逻辑字段的保留；增加最小的严格存储转换对照，锁住查询和存储行为的边界。
- 在现有 Snapshot 与 EventStream MongoDB 集成夹具中使用 Schema 接受的逻辑主键排除投影，验证真实 `{_id:0}` 查询；复用现有 BSON 类型归一化、普通投影和游标隐藏字段测试。
- 保持每次订阅结果节点的隔离；不为重复使用同一可变测试 Document 而添加生产深复制。真实驱动返回的 Document 与测试桩都应遵守各自的订阅所有权。

新增单测使用聚焦测试类，避免继续扩大现有综合测试类。可新增 `ElasticsearchCursorSubscriptionTest.kt` 与 `MongoQueryProjectionResultTest.kt`；具体 fixture 复用和文件安排在实施计划中落实。不通过新增 Detekt suppression 或测试基类规避类大小问题。

先跑对应聚焦测试，再跑两后端相关查询测试、Mongo 严格转换对照和 Detekt，最后运行对应真实查询集成测试。`detekt` 在本仓库会自动修正格式，其产生的改动必须纳入检查与提交。已通过且对应代码未变化的验证不重复执行；若修复引入新改动，重跑覆盖该改动的检查。

本阶段的收益是修复执行合同，不提出吞吐、延迟或分配量改善比例，也不重复前两阶段 JMH。请求次数验证用于证明订阅行为，不作为数据库性能结论。记录新测试数、实际执行命令、失败与通过输出，明确区分既有通过结果、缓存检查与本阶段实际执行。

验证日志与必要的复现产物保存在忽略目录 `build/query-execution/`，不提交生成输出。

## 完成标准

1. 同一 ES Backend 游标 Publisher 每次订阅独立调用客户端；未订阅不发送搜索请求，重复、retry、并发和取消合同通过测试。
2. MongoDB Snapshot/EventStream 的合法主键排除投影在 single/list/paged 正常返回；主键缺席与非法主键值保持区分。
3. MongoDB 通用存储转换的严格主键要求不变；现有游标 token、隐藏字段、JSON 类型归一化与查询结果结构通过回归。
4. 两后端真实查询集成、相关单测及 Detekt 通过；报告实际验证范围与限制。
5. 生产改动不超出四个既有文件，无公开 API、模块、依赖、配置、PIT、聚合执行器或生成协议改动。

书面设计审阅通过后使用 `writing-plans` 制定计划，沿用已选择的子代理逐项实施与独立审查流程。ES 汇总 PIT 降本继续作为后续候选，需单独确认其语义与验收。
