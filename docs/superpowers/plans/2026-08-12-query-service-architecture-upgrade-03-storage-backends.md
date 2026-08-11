# Query Backend、MongoDB 与 Elasticsearch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用同一 Portable Query TCK 实现 MongoDB 与 Elasticsearch `QueryBackend`，完成 mapping/index readiness、storage route resolver 和 Spring 注册；保留 FullText/Native 等显式后端能力，不允许静默降级。

**Architecture:** `QueryBackend` 只编译/执行 Plan 02 生成的 `QueryPlanV1`。Portable vectors 对两个后端使用同一数据集和期望；物理 compiler 各自把 logical field/expression 编译为 BSON 或 Elasticsearch DSL。Mongo page 以单个 `$facet` 命令获得 items + exact total；Elasticsearch page 以一次 `track_total_hits` search 获得 exact total，无限 list 使用 PIT + `search_after` 并用 Reactor resource scope 保证关闭。

**Tech Stack:** MongoDB Reactive Streams Driver、BSON、Spring Data Elasticsearch Reactive Client、Elasticsearch Java API Client、Reactor、Testcontainers、JUnit Jupiter、FluentAssert。

## Global Constraints

- 必须先完成 [Plan 02](2026-08-12-query-service-architecture-upgrade-02-gateway-policy-runtime.md) 全部 gate。
- 本计划的 Mongo `$facet` 是分页一致性实现细节，不是公开聚合分析 API；不新增 `AnalyticsQueryGateway`、group-by、metric 或跨聚合 join。
- 两个后端的 portable 语义必须由共享 TCK 证明；后端不能修改/忽略 canonical expression。
- FullText 不得降级为 CONTAINS；Native 不接受任意原文，只允许注册的 template + typed parameters + declared fields。
- mapping/index 不满足时返回 `BACKEND_NOT_READY`；不自动创建迁移、不覆盖现有 index、不静默换物理字段。
- `limit=0` 是无限流但仍受 deadline/budget/backpressure；禁止 ES 10k 静默截断或一次性 materialize 全量结果。
- 真实客户端资源在 complete/error/cancel/deadline 都必须释放；核心路径禁止 `block()`、内部 `subscribe()`。
- 现有公开 Mongo/ES `*QueryServiceFactory` 本阶段暂不删除或改签名；兼容适配在 Plan 04。

---

## Task 1: 建立跨后端 Portable Query TCK

**Interfaces consumed:** `QueryGatewayFactory`、Backend SPI、Plan 01 portable expression/schema、Plan 02 TestKit。

**Interfaces produced:** 单一 portable dataset/vector、Snapshot/EventStream backend contract spec、lifecycle spec。

**Files:**

- Create: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/backend/PortableQueryDataset.kt`
- Create: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/backend/PortableQueryVector.kt`
- Create: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/backend/QueryBackendTestKit.kt`
- Create: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/backend/SnapshotQueryBackendSpec.kt`
- Create: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/backend/EventStreamQueryBackendSpec.kt`
- Create: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/backend/QueryBackendLifecycleSpec.kt`
- Create: `test/wow-tck/src/test/kotlin/me/ahoo/wow/tck/query/backend/PortableQueryVectorTest.kt`

- [ ] **Step 1: 写 vector completeness 失败测试**

以 `PortableOperator.entries` 和 logical operators/system field cases 为 key，断言每项至少有 positive、negative、type/arity boundary vector；另覆盖 null 与 missing 的差异、empty collection、nested object collection、Unicode、enum/instant、stable sort tie、projection、page/count/list/single。

Run: `./gradlew :wow-tck:test --tests "me.ahoo.wow.tck.query.backend.*"`

Expected: compile failure，因为 vector 尚不存在。

- [ ] **Step 2: 定义后端无关 dataset**

固定 8～12 个 immutable logical documents，同时生成 Snapshot 与 EventStream document wrapper。至少包含：

- field absent 与 field explicit null；
- scalar/string/boolean/decimal/instant/enum；
- scalar array 和 object array；
- 相同主排序值但不同 system id；
- active/deleted Snapshot；
- tenant/owner/space 分布。

期望结果只用 logical id 集合、稳定顺序、exact total 表示，不能引用 BSON `_id` 或 ES `_source` 细节。

- [ ] **Step 3: 实现 Gateway-driven Backend TestKit**

TestKit 用固定 Admission/System policy/schema 和单 backend resolver 创建真实 `QueryGateway`；TCK 不直接构造 `QueryPlanV1`。抽象 hook 只负责 backend、prepare dataset、clear dataset、capability/readiness fixture。

```kotlin
abstract class SnapshotQueryBackendSpec {
    protected abstract fun backend(): QueryBackend
    protected abstract fun prepare(dataset: PortableQueryDataset): Mono<Void>
    protected abstract fun clear(): Mono<Void>
}
```

- [ ] **Step 4: 固定 operation 与生命周期契约**

四 operation 同时测试 typed/dynamic shape；page exact total；list `limit=0` 逐批请求；single 无结果 empty；cancel/deadline 后客户端 publisher 被取消；不支持 capability 精确返回 `UNSUPPORTED_CAPABILITY`。

- [ ] **Step 5: 运行 TCK 自测并提交**

Run: `./gradlew :wow-tck:check`

Expected: vector/TestKit 自测通过；尚无具体后端子类时不要求容器。

```bash
git add test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/backend \
  test/wow-tck/src/test/kotlin/me/ahoo/wow/tck/query/backend
git commit -m "test: define portable query backend tck"
```

## Task 2: 实现 MongoDB QueryBackend

**Interfaces consumed:** `QueryPlanV1`、logical schema binding、Mongo reactive collection。

**Interfaces produced:** Mongo descriptor/compiler/executor/readiness；Mongo Native/FullText capability。

**Files:**

- Create: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/backend/MongoQueryBackend.kt`
- Create: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/backend/MongoQueryBackendDescriptor.kt`
- Create: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/backend/MongoQueryPlanCompiler.kt`
- Create: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/backend/MongoQueryFieldBinding.kt`
- Create: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/backend/MongoQueryResultDecoder.kt`
- Create: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/backend/MongoNativeQueryTemplateRegistry.kt`
- Create: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/backend/MongoQueryReadiness.kt`
- Create: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/backend/MongoQueryPlanCompilerTest.kt`
- Create: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/backend/MongoQueryBackendTest.kt`
- Create: `wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/backend/MongoSnapshotQueryBackendSpec.kt`
- Create: `wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/backend/MongoEventStreamQueryBackendSpec.kt`

- [ ] **Step 1: 写 BSON compiler 失败测试**

逐项消费 TCK portable vectors，另断言 logical→physical Snapshot/EventStream system field；null/missing、`NE`、`NOT_IN`、`EXISTS`、`ELEM_MATCH`、regex escaping；所有集合仅 materialize 一次；unknown field/capability/template 在 collection 调用前失败。

Run: `./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.backend.*"`

Expected: compile failure，因为 Mongo backend 尚不存在。

- [ ] **Step 2: 实现 descriptor 与纯 compiler**

Descriptor 只声明实际可执行的 document kinds、Plan V1、portable operators、`mongo.full-text`/`mongo.native-template` capabilities 和预算上限。Compiler 是纯函数：输入 plan + immutable Mongo field binding，输出 operation-specific BSON command；不得读取 authority、旧 `Condition` 或 Spring context。

Native registry key 是稳定 `templateId`，value 是服务器注册的 typed BSON builder；调用方参数逐项由 Schema/Policy 已验证，builder 仍使用参数绑定而不是拼接 JSON/BSON 字符串。

- [ ] **Step 3: 实现 single/list/count 与 decoder**

single 使用 filter + stable sort + projection + limit(1)；有限 list 使用 limit/batch size；`limit=0` 不设置 driver limit，并按 effective budget 设置 batch size。typed/dynamic decoder 只返回已授权 result shape；decode 错误保留 result stage，不能跳过 document。

- [ ] **Step 4: 用单次 `$facet` 实现 exact page**

生成一个 aggregate pipeline：

```text
$match
$sort
$facet {
  items: [$skip, $limit, $project],
  total: [$count]
}
```

当 page items 为空时仍解析 total；一次 subscription 只能向 collection 发出一个 aggregate command。测试用 recording collection 断言不是 `countDocuments + find` 两次调用。

- [ ] **Step 5: 实现 readiness 与资源终止语义**

basic readiness 验证 collection/codec/system field binding；请求 FullText 时额外验证兼容 text index；Native 验证 template 已注册。driver publisher 在 cancel/deadline/partial decode failure 时收到 cancel；不需要显式关闭 cursor 的驱动路径仍必须通过 publisher lifecycle test 证明终止。

- [ ] **Step 6: 接入共享 TCK integration spec**

两个 concrete spec 使用现有 Mongo Testcontainers fixture，准备同一 logical dataset，覆盖 Snapshot/EventStream。不得复制期望矩阵到 Mongo 模块。

Run: `./gradlew :wow-mongo:check :wow-mongo:integrationTest --stacktrace`

Expected: unit + 两套 TCK 通过，page command count 为 1，`limit=0` 无截断。

- [ ] **Step 7: 提交**

```bash
git add wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/backend \
  wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/backend \
  wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/backend
git commit -m "feat: implement mongodb query backend"
```

## Task 3: 实现 Elasticsearch QueryBackend 与 PIT 生命周期

**Interfaces consumed:** `QueryPlanV1`、logical mapping binding、Reactive Elasticsearch client。

**Interfaces produced:** ES descriptor/compiler/executor/PIT/readiness；ES Native/FullText capability。

**Files:**

- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/backend/ElasticsearchQueryBackend.kt`
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/backend/ElasticsearchQueryBackendDescriptor.kt`
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/backend/ElasticsearchQueryPlanCompiler.kt`
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/backend/ElasticsearchQueryFieldBinding.kt`
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/backend/ElasticsearchQueryResultDecoder.kt`
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/backend/ElasticsearchNativeQueryTemplateRegistry.kt`
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/backend/PitSearchAfterExecutor.kt`
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/backend/ElasticsearchQueryReadiness.kt`
- Create: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/backend/ElasticsearchQueryPlanCompilerTest.kt`
- Create: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/backend/PitSearchAfterExecutorTest.kt`
- Create: `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/backend/ElasticsearchSnapshotQueryBackendSpec.kt`
- Create: `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/backend/ElasticsearchEventStreamQueryBackendSpec.kt`

- [ ] **Step 1: 写 Query DSL 与 mapping 失败测试**

逐项消费 portable vectors；重点覆盖 `keyword` vs analyzed text、null/missing、nested `ELEM_MATCH`、escaped wildcard/prefix、stable sort keyword binding、source filtering。字段需要 nested 但 mapping 不是 nested 时返回 `BACKEND_NOT_READY`，不能编译成 object query 冒充同元素语义。

Run: `./gradlew :wow-elasticsearch:test --tests "me.ahoo.wow.elasticsearch.query.backend.*"`

Expected: compile failure，因为 ES backend 尚不存在。

- [ ] **Step 2: 实现 descriptor、纯 compiler 与受控 capabilities**

FullText 编译为配置的 multi-match/query-string 变体并声明 analyzer 语义；Native 只调用注册 template builder，禁止原始 JSON。Compiler 输入 plan + immutable ES mapping binding，输出 Java client request object，不看旧 `Condition`。

- [ ] **Step 3: 实现 single/page/count**

single 是 size=1 search；page 使用一次 search，`from/size` 在 readiness/budget 允许范围内，`track_total_hits=true` 并要求 exact relation；若 ES 返回 lower-bound/unknown total，返回 `BACKEND_FAILURE`，不能冒充精确值。count 使用 count API。typed/dynamic decode 均服从 authorized source filter。

- [ ] **Step 4: TDD 实现有限 list 与无限 PIT + search_after**

有限 list 小于单次 size 上限时可单 search；超过单批或 `limit=0` 时必须：open PIT → search stable page → emit with backpressure → use last sort values as `search_after` → repeat → close PIT。

```kotlin
Flux.usingWhen(
    openPointInTime(),
    { pit -> searchAfterPages(pit, plan) },
    ::closePointInTime,
    { pit, _ -> closePointInTime(pit) },
    ::closePointInTime
)
```

测试 complete/error/cancel/deadline/decoder failure 五条路径都恰好 close 一次；不预取无限 pages，不用 `collectList()`，不把 0 改成 10,000。

- [ ] **Step 5: 接入共享 TCK integration spec**

两个 concrete spec 使用现有 Elasticsearch Testcontainers fixture；创建符合声明 Schema 的测试 index/mapping。测试完成后删除测试 index/PIT，不修改项目默认 template。

Run: `./gradlew :wow-elasticsearch:check :wow-elasticsearch:integrationTest --stacktrace`

Expected: unit + 两套 TCK 通过；PIT resource assertions 全通过；无 10k 截断。

- [ ] **Step 6: 提交**

```bash
git add wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/backend \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/backend \
  wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/backend
git commit -m "feat: implement elasticsearch query backend"
```

## Task 4: 绑定 Query Schema 与真实 mapping/index readiness

**Interfaces consumed:** `QuerySchemaCustomizer`、Mongo index metadata、Elasticsearch mapping API、现有 initializer/template。

**Interfaces produced:** backend-specific logical→physical binding；启动/调用 readiness report；显式迁移错误。

**Files:**

- Create: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/schema/MongoQuerySchemaCustomizer.kt`
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/schema/ElasticsearchQuerySchemaCustomizer.kt`
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/schema/ElasticsearchMappingInspector.kt`
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/schema/ElasticsearchMappingReadiness.kt`
- Create: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/schema/MongoQuerySchemaCustomizerTest.kt`
- Create: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/schema/ElasticsearchMappingInspectorTest.kt`
- Create: `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/schema/ElasticsearchMappingReadinessTest.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/IndexTemplateInitializer.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/AggregateSchemaInitializer.kt`

- [ ] **Step 1: 写 mapping readiness 矩阵失败测试**

覆盖 missing field、wrong scalar type、text without keyword sort binding、object vs nested、date numeric mismatch、`ignore_above` 风险、system field missing、template only affects new index。错误必须带安全 logical field/code/index alias，不带 mapping 全文。

- [ ] **Step 2: 实现唯一 customizer 的 backend binding**

不要新增第二个 Schema 真相源。Mongo 默认 physical path 等于 Jackson logical path，只覆盖 framework wrapper fields；ES customizer 为 managed index 声明固定 logical→physical/multi-field/nested binding。既有/custom index 仍由 inspector 验证，不从 mapping 反向生成 Query Schema。

- [ ] **Step 3: 实现 readiness cache 与失效边界**

readiness key 包含 backend/index-or-collection/schema version；成功可有界缓存，失败不得永久缓存。index alias 指向新 index 或 initializer 完成后显式 invalidate；query invocation 使用 resolver 冻结的 readiness snapshot。

- [ ] **Step 4: 保证 initializer 不自动迁移**

initializer 可为新资源创建 template/schema/index，但发现已有不兼容 index 时只报告 `BACKEND_NOT_READY` 和迁移文档 key；禁止 delete/recreate、put mapping 强改 incompatible type 或 alias swap。

- [ ] **Step 5: 运行验证并提交**

Run: `./gradlew :wow-mongo:check :wow-elasticsearch:check :wow-elasticsearch:integrationTest --stacktrace`

```bash
git add wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/{query/schema,AggregateSchemaInitializer.kt} \
  wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/schema \
  wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/{query/schema,IndexTemplateInitializer.kt} \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/schema \
  wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/schema
git commit -m "feat: validate query backend readiness"
```

## Task 5: 统一 storage routing 与 Spring Backend 注册

**Interfaces consumed:** 当前 `StorageRoutingProperties`、`StorageType`、Mongo/ES Backends、Plan 02 auto-config。

**Interfaces produced:** 唯一 immutable `QueryBackendResolver` route snapshot；backend bindings；无 NoOp fallback。

**Files:**

- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/eventsourcing/routing/StorageRouteBindings.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/eventsourcing/routing/StorageRouteResolver.kt`
- Create: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/query/StorageRoutingQueryBackendResolver.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoEventSourcingAutoConfiguration.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchEventSourcingAutoConfiguration.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/query/QueryGatewayAutoConfiguration.kt`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/eventsourcing/routing/StorageRouteResolverTest.kt`
- Create: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/query/StorageRoutingQueryBackendResolverTest.kt`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoEventSourcingAutoConfigurationTest.kt`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchEventSourcingAutoConfigurationTest.kt`

- [ ] **Step 1: 写 route compatibility/fail-fast 失败测试**

相同 aggregate storage property 必须让 EventStore/SnapshotStore 与对应 query backend 选择一致；支持默认 storage、per-aggregate storage、named binding。unknown aggregate、missing binding、binding/backend document kind 不匹配在 context startup 失败，不再返回 NoOp。

- [ ] **Step 2: 添加 backend binding，不建立第二套路由表**

```kotlin
data class QueryBackendBinding(
    val name: String,
    val storage: StorageType?,
    val backend: QueryBackend
)
```

`StorageRouteResolver` 在解析现有 event/snapshot channel 时同时解析 backend binding，产生一份 immutable route map；`StorageRoutingQueryBackendResolver.resolve(target)` 只读取该快照。route identity 包含安全的 binding name/document kind，不含连接串。

- [ ] **Step 3: 注册 Mongo/ES backend beans**

复用已有 clients、collection/index name converter、serializer 和 enabled properties；每个 storage auto-config 暴露一个明确 backend binding。custom backend 可通过 named `QueryBackendBinding` 参与现有 `binding` property，但不能替换 System policy 或绕过 resolver。

- [ ] **Step 4: 删除新 Gateway 的 unavailable resolver fallback**

当 query 功能启用却无默认 backend/binding 时 startup fail-fast；只有 query feature 明确未启用时不创建 Gateway。不能退回 `NoOp*QueryServiceFactory` 或 empty result。

- [ ] **Step 5: 运行模块与真实容器验收**

Run:

```bash
./gradlew :wow-spring-boot-starter:check \
  :wow-mongo:check :wow-mongo:integrationTest \
  :wow-elasticsearch:check :wow-elasticsearch:integrationTest \
  :wow-tck:check queryApiCheck --stacktrace
```

Expected: 全部通过；route test 证明同一 storage properties 决定 store 与 backend；错误 binding 不再静默。

- [ ] **Step 6: 提交**

```bash
git add wow-spring-boot-starter/src/main wow-spring-boot-starter/src/test
git commit -m "feat: route query backends by storage binding"
```

## Task 6: 验证资源上界与后端能力不降级

**Interfaces consumed:** 两个真实 Backend、TCK dataset、Micrometer test registry。

**Interfaces produced:** 可重复的背压/lifecycle/command-count 验收测试；不做无证据性能宣传。

**Files:**

- Create: `wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/backend/MongoQueryResourceBoundTest.kt`
- Create: `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/backend/ElasticsearchQueryResourceBoundTest.kt`
- Create: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/backend/QueryCapabilityContract.kt`

- [ ] **Step 1: 写可重复资源测试**

准备超过两个 batch 的 dataset，使用 `StepVerifier` 每次 request 小批量元素；断言 Mongo publisher/ES page fetch 不超出配置 prefetch，取消后无后续 command。记录 command/page count 与最大 buffered items，不以 wall-clock 作为唯一断言。

- [ ] **Step 2: 验证 capability 三方门槛**

对 FullText/Native 分别组合 backend support、config enabled、Policy grant；八种组合只有三者全真执行。显式 Policy `DENY` 永远拒绝；全 `ABSTAIN` 拒绝；不支持时不生成后端 command。

- [ ] **Step 3: fresh run 阶段验收并提交**

Run:

```bash
./gradlew :wow-mongo:integrationTest :wow-elasticsearch:integrationTest \
  :wow-mongo:check :wow-elasticsearch:check :wow-tck:check queryApiCheck --stacktrace
```

```bash
git add wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/backend \
  wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/backend \
  test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/backend
git commit -m "test: verify query backend resource bounds"
```

## Plan 03 完成检查

- [ ] MongoDB 与 Elasticsearch concrete specs 使用同一 vector/dataset，未复制 portable expectations。
- [ ] Mongo page 一条 aggregate `$facet`；ES page 一条 exact-total search；ES unlimited list 使用 PIT + `search_after`。
- [ ] PIT complete/error/cancel/deadline/decode-failure 均 close 恰好一次。
- [ ] mapping/index 不兼容只返回 `BACKEND_NOT_READY`，没有自动迁移或删除资源。
- [ ] FullText/Native 不降级，Native 没有 raw string 拼接入口。
- [ ] storage routing invalid binding startup fail-fast，没有新路径返回 NoOp/empty。
- [ ] fresh run：`./gradlew :wow-mongo:check :wow-mongo:integrationTest :wow-elasticsearch:check :wow-elasticsearch:integrationTest :wow-tck:check :wow-spring-boot-starter:check queryApiCheck --stacktrace`。
- [ ] 执行 `superpowers:verification-before-completion` 后，再开始 Plan 04。
