# Query 兼容切换、迁移文档与发布闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把旧 `QueryService`/factory/Spring/WebFlux/DSL 全部改为只委托 `QueryGateway`，迁移 masker 与 CoSec/ABAC 安全职责，删除已批准的 Filter/Handler/Context 扩展面，保持其余 Wow 8.x source/binary/wire 契约，并完成中英文迁移与全量发布 gate。

**Architecture:** 兼容门面执行 `legacy DTO → canonical request → public QueryGateway → legacy result/error`，不能访问 Planner、Backend 或默认 Gateway。WebFlux 保留旧 JSON/OpenAPI/route 并在 rewrite 后进入 Gateway；rewrite 内容仅为 `LEGACY_ENRICHMENT`，可信 authority 来自 Admission。旧 masker 通过 `ResultPolicy` adapter 保留；授权/ABAC 迁移到单一 `QueryPolicy`。Filter 类型及依赖它们的构造签名通过精确 allowlist 产生编译期迁移信号。

**Tech Stack:** Kotlin、Reactor、Spring、WebFlux、CoSec、Jackson/OpenAPI golden、JUnit/Reactor Test、VitePress/pnpm、Gradle。

## Global Constraints

- 必须先完成 [Plan 03](2026-08-12-query-service-architecture-upgrade-03-storage-backends.md) 全部 gate。
- 这是实际 cutover；提交后所有框架托管查询必须进入 `QueryGateway`，不允许保留第二执行器或 NoOp 空结果。
- 兼容门面只依赖 `QueryGateway` 公开接口；通过架构测试禁止 import `DefaultQueryGateway`、`planner`、`backend` 实现包。
- 旧 Query Filter/Handler/Context 直接删除，不提供 hook、adapter、runtime bean scan 或 warning；依赖它们的应用重新编译时由 IDE/编译器报错。
- `RewriteRequestCondition`、`DefaultRewriteRequestCondition`、`CoSecRewriteRequestCondition` 和 masker API 保留原 package/签名并 deprecate；前者不再被当作可信授权。
- 旧 `QueryService` 七方法、factory/routing class、Mongo/ES public class/constructor、Spring bean name、HTTP JSON/OpenAPI/storage property 除精确批准项外不得漂移。
- headers、route variables 和旧 request scope 都是 caller input；只有经服务端认证链构造的 `QueryAuthorityView` 才是 `TRUSTED_AUTHORITY`。
- 文档代码示例必须由编译测试覆盖；只改 migration 页不算完成。

---

## Task 1: 实现只依赖 QueryGateway 的旧 API 兼容门面

**Interfaces consumed:** 旧 `QueryService`/DTO/factory；Plan 01 lowerer；公共 `QueryGateway`。

**Interfaces produced:** Snapshot/EventStream Gateway facades；legacy result/error mapping；NoOp 显式失败。

**Files:**

- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/compat/LegacyQueryRequestMapper.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/compat/LegacyQueryResultMapper.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/compat/LegacyQueryErrorMapper.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/GatewaySnapshotQueryService.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/GatewaySnapshotQueryServiceFactory.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/event/GatewayEventStreamQueryService.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/event/GatewayEventStreamQueryServiceFactory.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/QueryService.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/SnapshotQueryService.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/SnapshotQueryServiceFactory.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/RoutingSnapshotQueryServiceFactory.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/event/EventStreamQueryService.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/event/EventStreamQueryServiceFactory.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/event/RoutingEventStreamQueryServiceFactory.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/compat/LegacyQueryRequestMapperTest.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/compat/GatewayQueryServiceCompatibilityTest.kt`

- [ ] **Step 1: 写七方法与 typed/dynamic 兼容失败测试**

使用 recording `QueryGateway`，分别调用 old `single`、`dynamicSingle`、`list`、`dynamicList`、`paged`、`dynamicPaged`、`count`，断言 target/document kind、condition lowering、projection/sort/page/limit、typed/dynamic result shape 和 `QueryPage → PagedList` 精确映射。facade 每次只调用一次 public Gateway 方法。

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.compat.*"`

Expected: compile failure，因为 facades 尚不存在。

- [ ] **Step 2: 固定 legacy lowering 行为**

Snapshot 使用 `DeleteConditionGuard` 既有默认 active/显式 deleted 规则，EventStream 不套 deletion guard。旧 unknown field 仅在配置允许时转成 `LegacyBackendField` capability，仍需 schema declared field list、backend support、Policy grant；默认 `INVALID_QUERY`。`MATCH`/`RAW` 沿用 FullText/Native 四重门槛，不静默降级。

- [ ] **Step 3: 实现只依赖 public Gateway 的 facade**

```kotlin
class GatewaySnapshotQueryService<S : Any>(
    override val namedAggregate: NamedAggregate,
    private val queryGateway: QueryGateway
) : SnapshotQueryService<S>
```

EventStream 同形。facade 只能 import `me.ahoo.wow.query.QueryGateway` 与公开 request/result types；禁止 `DefaultQueryGateway`、`DefaultQueryPlanner`、`QueryBackend`。七方法只是 mapper + Gateway + result mapper，不复制 admission/policy/error recovery。

- [ ] **Step 4: 保留 factory/routing API 并消除 NoOp 语义**

现有 factory/routing class、公开构造器、cache 行为和 bean-target type 保留并 deprecate；内部返回 Gateway facade。`NoOpSnapshotQueryService*`/`NoOpEventStreamQueryService*` 符号为 ABI 保留，但每个 operation 统一改为 `BACKEND_NOT_READY` error，不再 empty/0/empty page。

- [ ] **Step 5: 添加 facade 架构测试**

使用 `javap -verbose` 的 Gradle test helper 读取 compat/snapshot/event facade constant pool，断言不引用 `.plan.`、`.backend.` 或 `DefaultQueryGateway`；不为该检查新增依赖。

- [ ] **Step 6: 运行 ABI/source gate 并提交**

Run: `./gradlew :wow-query:check queryApiCheck`

Expected: retained APIs 无删除/descriptor 变化；facade tests 通过；NoOp tests 更新为显式错误。

```bash
git add wow-query/src/main wow-query/src/test
git commit -m "feat: delegate legacy query api to gateway"
```

## Task 2: 将 Mongo/ES 公开旧类与 Spring Bean 适配到 Gateway

**Interfaces consumed:** Gateway facades、Plan 03 backend bindings、现有 public constructors/bean names。

**Interfaces produced:** 保留 ABI 的 Mongo/ES factories/services；按聚合 Spring beans 共享唯一 Gateway/resolver。

**Files:**

- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryService.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryService.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryServiceFactory.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryService.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryServiceFactory.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchQueryService.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryService.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryServiceFactory.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/event/ElasticsearchEventStreamQueryService.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/event/ElasticsearchEventStreamQueryServiceFactory.kt`
- Modify: `wow-spring/src/main/kotlin/me/ahoo/wow/spring/query/SnapshotQueryServiceRegistrar.kt`
- Modify: `wow-spring/src/main/kotlin/me/ahoo/wow/spring/query/EventStreamQueryServiceRegistrar.kt`
- Create: `wow-spring/src/test/kotlin/me/ahoo/wow/spring/query/QueryServiceRegistrarGatewayTest.kt`
- Modify: `wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryServiceTest.kt`
- Modify: `wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryServiceTest.kt`
- Modify: `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryServiceTest.kt`
- Modify: `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/event/ElasticsearchEventStreamQueryServiceTest.kt`

- [ ] **Step 1: 写 constructor/bean/gateway identity 失败测试**

反射断言当前 baseline 中每个 public constructor/method 仍存在。Spring 仍按原 bean name 和 generic type 注册 Snapshot/EventStream service；注入这些 bean 后执行查询与直接注入 `QueryGateway` 必须命中同一 resolver/backend instance 和同一 System/custom Policy。

- [ ] **Step 2: 改造 storage public classes，不保留旧执行器**

现有 database/client constructors 保留并 deprecate，内部创建使用对应 Plan 03 Backend 的 non-Spring Gateway；Spring 新增内部构造路径注入应用唯一 `QueryGateway`。`AbstractMongoQueryService`/`AbstractElasticsearchQueryService` 公开 protected surface 由 ABI gate 保留，但七个 method body 统一委托 facade；旧 converter 仅作为 deprecated source compatibility type，不再被框架调用。

- [ ] **Step 3: Registrar 直接从 QueryGateway 创建 facades**

保持 `${namedAggregate.toStringWithAlias()}.SnapshotQueryService` 等 bean name、`ResolvableType` generic 和按聚合注册时机。删除 `getOrNoOp()` 选择；缺 Gateway/backend 在 startup 或调用时显式失败。

- [ ] **Step 4: 真实后端回归旧 TCK**

保留现有 `SnapshotQueryServiceSpec`/`EventStreamQueryServiceSpec`，但实际对象是 Gateway facade；加 recording policy 断言旧服务也执行 mandatory tenant/active 条件。Mongo/ES 新旧入口对同一 dataset 返回相同 portable 结果。

- [ ] **Step 5: 运行验证并提交**

Run:

```bash
./gradlew :wow-spring:check :wow-mongo:check :wow-mongo:integrationTest \
  :wow-elasticsearch:check :wow-elasticsearch:integrationTest queryApiCheck --stacktrace
```

```bash
git add wow-mongo/src wow-elasticsearch/src wow-spring/src
git commit -m "refactor: route storage query services through gateway"
```

## Task 3: 迁移 masker、ABAC 与 CoSec 到 Policy/ResultPolicy

**Interfaces consumed:** 现有 masker registry/API、ABAC semantics、CoSec rewrite、trusted authority admission。

**Interfaces produced:** ResultPolicy masker adapter；ABAC/tenant/space QueryPolicy；deprecated legacy rewrite。

**Files:**

- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/mask/MaskingResultPolicy.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/policy/abac/AbacQueryPolicy.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/policy/abac/PrincipalTagResolver.kt`
- Create: `wow-cosec/src/main/kotlin/me/ahoo/wow/cosec/query/CoSecQueryPolicy.kt`
- Modify: `wow-cosec/src/main/kotlin/me/ahoo/wow/cosec/query/CoSecRewriteRequestCondition.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/cosec/CoSecAutoConfiguration.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/mask/MaskingResultPolicyTest.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/policy/abac/AbacQueryPolicyTest.kt`
- Create: `wow-cosec/src/test/kotlin/me/ahoo/wow/cosec/query/CoSecQueryPolicyTest.kt`

- [ ] **Step 1: 写安全等价与跨入口失败测试**

同一个 tenant/ABAC Policy 分别通过新 Gateway、旧 QueryService 和 WebFlux 执行，断言 mandatory expression 相同。tenant mismatch、缺 trusted tenant、caller header 伪造 tenant/space 均 `POLICY_DENIED`；Backend count 为 0。legacy rewrite 开/关都不能移除 mandatory scope。

- [ ] **Step 2: 将 masker registry 包装为 ResultPolicy**

保留 `AggregateDataMasker`/`DataMasker`/registry public API 和注册语义；`MaskingResultPolicy` 按 target/result shape 调用原 registry。typed/dynamic、single/list/page 均覆盖；masker error 不能返回未脱敏原值，首项后按 partial-result 终止。

- [ ] **Step 3: 将 ABAC 条件注入改为 QueryPolicy**

复刻现有 principal tag 匹配语义并修正 fail-open 风险：需要 ABAC 而 tag resolver empty/error 时拒绝，不返回 `MatchAll`。mandatory expression 只能 portable；字段访问/预算按 Policy constraints 产生。提供 `QueryPolicyTestKit` 示例。

- [ ] **Step 4: 明确 CoSec trust boundary**

`CoSecRewriteRequestCondition` 保留原签名，仅产生 `LEGACY_ENRICHMENT`，标记 deprecation。`CoSecQueryPolicy` 只读取通用 Admission 已经放入 `QueryInvocationScope.trustedAuthority` 的认证 principal view，根据 trusted tenant/space 注入 mandatory expression；它不自行解析 `CoSec-*`/`Wow-*` header，也不新增第二个 authority provider。没有已验证 authority 时 fail-closed。

- [ ] **Step 5: Spring 注册 additive policies**

`CoSecAutoConfiguration` 继续暴露旧 rewrite bean，同时追加 `CoSecQueryPolicy` bean；它作为 custom `QueryPolicy` 加入 System policy，不覆盖 System policy。关闭 CoSec extension 不影响 System active/schema/budget policy。

- [ ] **Step 6: 运行验证并提交**

Run: `./gradlew :wow-query:check :wow-cosec:check :wow-spring-boot-starter:check queryApiCheck`

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/{mask,policy/abac} \
  wow-query/src/test/kotlin/me/ahoo/wow/query/{mask,policy/abac} \
  wow-cosec/src wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/cosec \
  wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/cosec
git commit -m "feat: migrate query security to policies"
```

## Task 4: WebFlux 切换 Gateway 并删除 Filter 扩展面

**Interfaces consumed:** 旧 wire DTO/route/OpenAPI、RewriteRequestCondition、QueryGateway、WebFlux response strategy。

**Interfaces produced:** Gateway-backed handlers；可信 Admission scope；精确 JSON/SSE error semantics；Filter compile-time break。

**Files:**

- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/SingleQueryHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/ListQueryHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/PagedQueryHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/CountQueryHandlerFunction.kt`
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/WebFluxQueryAdmission.kt`
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/WebFluxQueryAuthorityResolver.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/response/StreamingJsonArrayResponse.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/response/WebFluxResponseStrategy.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/CountSnapshotHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/ListQuerySnapshotHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/ListQuerySnapshotStateHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/LoadSnapshotHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/PagedQuerySnapshotHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/PagedQuerySnapshotStateHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/SingleSnapshotHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/SingleSnapshotStateHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/event/CountEventStreamHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/event/ListQueryEventStreamHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/event/LoadEventStreamHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/event/PagedQueryEventStreamHandlerFunction.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/route/QueryRouteModule.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/query/QueryAutoConfiguration.kt`
- Delete: `wow-query/src/main/kotlin/me/ahoo/wow/query/filter/Contexts.kt`
- Delete: `wow-query/src/main/kotlin/me/ahoo/wow/query/filter/MaskingDynamicDocumentQueryFilter.kt`
- Delete: `wow-query/src/main/kotlin/me/ahoo/wow/query/filter/QueryContext.kt`
- Delete: `wow-query/src/main/kotlin/me/ahoo/wow/query/filter/QueryFilter.kt`
- Delete: `wow-query/src/main/kotlin/me/ahoo/wow/query/filter/QueryHandler.kt`
- Delete: `wow-query/src/main/kotlin/me/ahoo/wow/query/filter/QueryType.kt`
- Delete: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/filter/AbacQueryFilter.kt`
- Delete: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/filter/MaskingSnapshotQueryFilter.kt`
- Delete: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/filter/SnapshotQueryFilter.kt`
- Delete: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/filter/SnapshotQueryHandler.kt`
- Delete: `wow-query/src/main/kotlin/me/ahoo/wow/query/event/filter/EventStreamQueryFilter.kt`
- Delete: `wow-query/src/main/kotlin/me/ahoo/wow/query/event/filter/EventStreamQueryHandler.kt`
- Delete: `wow-query/src/main/kotlin/me/ahoo/wow/query/event/filter/MaskingEventStreamQueryFilter.kt`
- Delete: `wow-query/src/test/kotlin/me/ahoo/wow/query/filter/MaskingDynamicDocumentQueryFilterTest.kt`
- Delete: `wow-query/src/test/kotlin/me/ahoo/wow/query/filter/QueryContextTest.kt`
- Delete: `wow-query/src/test/kotlin/me/ahoo/wow/query/filter/QueryTypeTest.kt`
- Delete: `wow-query/src/test/kotlin/me/ahoo/wow/query/snapshot/filter/AbacQueryFilterTest.kt`
- Delete: `wow-query/src/test/kotlin/me/ahoo/wow/query/snapshot/filter/CountSnapshotQueryContextTest.kt`
- Delete: `wow-query/src/test/kotlin/me/ahoo/wow/query/snapshot/filter/DefaultSnapshotQueryHandlerTest.kt`
- Delete: `wow-query/src/test/kotlin/me/ahoo/wow/query/snapshot/filter/MaskingSnapshotQueryFilterTest.kt`
- Delete: `wow-query/src/test/kotlin/me/ahoo/wow/query/event/filter/DefaultEventStreamQueryHandlerTest.kt`
- Delete: `wow-query/src/test/kotlin/me/ahoo/wow/query/event/filter/MaskingEventStreamQueryFilterTest.kt`
- Modify: `config/query-api/approved-removals.txt`
- Create: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/query/QueryGatewayHandlerFunctionTest.kt`
- Create: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/query/WebFluxQueryAdmissionTest.kt`
- Create: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/response/StreamingJsonArrayResponseTest.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/event/ListQueryEventStreamHandlerFunctionTest.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/event/LoadEventStreamHandlerFunctionTest.kt`
- Create: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/event/CountEventStreamHandlerFunctionTest.kt`
- Create: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/event/PagedQueryEventStreamHandlerFunctionTest.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/snapshot/CountSnapshotHandlerFunctionTest.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/snapshot/ListQuerySnapshotHandlerFunctionTest.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/snapshot/ListQuerySnapshotStateHandlerFunctionTest.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/snapshot/LoadSnapshotHandlerFunctionTest.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/snapshot/PagedQuerySnapshotHandlerFunctionFactoryTest.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/snapshot/PagedQuerySnapshotStateHandlerFunctionFactoryTest.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/snapshot/SingleSnapshotHandlerFunctionTest.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/snapshot/SingleSnapshotStateHandlerFunctionTest.kt`

- [ ] **Step 1: 写 HTTP wire/gateway/error 失败测试**

现有 request JSON 和 response JSON/OpenAPI snapshot 不变；四 operation 命中 recording Gateway。JSON list 首项前错误走标准 error response；首项后错误不追加 `]`、中止连接并记录 partial result。SSE 首项后发送最终 error event；取消连接取消 backend。

- [ ] **Step 2: 直接注入 QueryGateway 并保留 rewrite**

handler 先解析原 wire DTO，再调用 deprecated rewrite，mapper 将其标为 `LEGACY_ENRICHMENT`，随后调用 Gateway。`WebFluxQueryAdmission` 将 route/header/request scope 标为 caller input；`WebFluxQueryAuthorityResolver` 只接受已认证 principal adapter 的结果。不得把 header tenant/owner/space 复制到 trusted authority。

- [ ] **Step 3: 删除 Filter/Handler/Context 与 Spring filter chain**

删除规格明确列出的 `QueryFilter`、`SnapshotQueryFilter`、`EventStreamQueryFilter`、`QueryContext`/`Contexts`/`QueryType`、`QueryHandler`/`AbstractQueryHandler`、Snapshot/Event handlers、Tail/Masking/ABAC query filters，以及 `QueryAutoConfiguration` 中对应 chain/error-handler beans。保留 `mask/` 包和 rewrite classes。

- [ ] **Step 4: 精确维护 approved removals**

先运行 `queryApiCheck` 获取缺失 symbols。allowlist 只能加入：上述删除类型本身，以及 WebFlux/Spring 中因构造参数引用已删除 Handler 而必须替换的精确 constructor/method descriptor；不能加入 class/package glob，不能批准其他 storage/API 漂移。新增 Gateway constructors 不抵消旧 descriptor 的 breaking 性，发布说明必须列出。

- [ ] **Step 5: 加编译期迁移证明**

创建临时外部 Kotlin/Java fixture 引用旧 `SnapshotQueryFilter`，编译必须因 unresolved symbol 失败；迁移后的 `QueryPolicy` fixture 必须通过。该检查只验证 compile-time break，不在运行时扫描旧 bean name/classpath。

- [ ] **Step 6: 运行验证并提交**

Run:

```bash
./gradlew :wow-query:check :wow-webflux:check :wow-spring-boot-starter:check \
  :wow-openapi:check :wow-cosec:check queryApiCheck --stacktrace
```

Expected: OpenAPI snapshot unchanged；ABI 只报告精确 allowlist；源码中没有 Filter runtime 类型。

```bash
git add wow-query/src wow-webflux/src wow-spring-boot-starter/src \
  config/query-api/approved-removals.txt
git commit -m "refactor: cut query runtime over to gateway"
```

## Task 5: 更新中英文迁移、使用与 Backend 文档

**Interfaces consumed:** 最终公开 API、43 operator matrix、Policy/Backend test fixtures、readiness/rollback 行为。

**Interfaces produced:** 中英文 Query Filter 迁移指南；全站无失效示例；可编译 Policy/Backend snippets。

**Files:**

- Create: `documentation/docs/zh/guide/migration/query-filter-to-query-policy.md`
- Create: `documentation/docs/en/guide/migration/query-filter-to-query-policy.md`
- Create: `documentation/docs/zh/guide/extensions/query-backend.md`
- Create: `documentation/docs/en/guide/extensions/query-backend.md`
- Modify: `documentation/docs/zh/guide/migration.md`
- Modify: `documentation/docs/en/guide/migration.md`
- Modify: `documentation/docs/zh/guide/query.md`
- Modify: `documentation/docs/en/guide/query.md`
- Modify: `documentation/docs/zh/guide/data-access.md`
- Modify: `documentation/docs/en/guide/data-access.md`
- Modify: `documentation/docs/zh/guide/extensions/cosec.md`
- Modify: `documentation/docs/en/guide/extensions/cosec.md`
- Modify: `documentation/docs/zh/guide/extensions/spring-boot-starter.md`
- Modify: `documentation/docs/en/guide/extensions/spring-boot-starter.md`
- Modify: `documentation/docs/zh/guide/snapshot.md`
- Modify: `documentation/docs/en/guide/snapshot.md`
- Modify: `documentation/docs/zh/guide/best-practices.md`
- Modify: `documentation/docs/en/guide/best-practices.md`
- Modify: `documentation/docs/zh/onboarding/staff-engineer-guide.md`
- Modify: `documentation/docs/en/onboarding/staff-engineer-guide.md`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/docs/QueryPolicyDocumentationTest.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/docs/QueryBackendDocumentationTest.kt`

- [ ] **Step 1: 写 migration 责任决策表与安全清单**

中英文都必须明确：

- 单次条件 → request/DSL；
- 调用方可覆盖默认 → 领域 Query Facade/request builder；
- 所有入口必须执行且不可移除 → `QueryPolicy`；
- 脱敏 → `ResultPolicy`/保留 masker API；
- logical→physical/schema binding → `QuerySchemaCustomizer`/Backend compiler；
- `RewriteRequestCondition` 仅 8.x `LEGACY_ENRICHMENT`，不是授权；
- 没有 `QueryConditionContributor` 或 Filter hook。

授权迁移清单覆盖 tenant mismatch、missing authority、all-ABSTAIN capability、empty/error Policy、旧 header 伪造、跨旧/新/WebFlux 入口一致性。

- [ ] **Step 2: 提供 before/after 可编译示例**

before 仅用于迁移文档展示旧 Filter；after 提供普通 active policy、tenant policy、field/capability/budget constraints、Spring `@Order`、non-Spring factory、`QueryPolicyTestKit`。把 after snippets 同步成 `QueryPolicyDocumentationTest` 真代码，避免文档漂移。

- [ ] **Step 3: 文档化 43 operator 与后端能力**

列出全部 legacy→canonical mapping；强调 `NOR` 非 `NOT`、FullText/Native 不降级。Backend 文档说明 stable descriptor/Plan V1 consumer、TCK、readiness、managed/custom index mapping、PIT/resource cleanup；明确第一阶段无公开聚合分析 API。

- [ ] **Step 4: 文档化行为修正与升级/回滚**

列出：NoOp 不再空结果、非法 page/limit 校验、ES 不再 10k 截断、page exact total、partial result、unknown field、index readiness。升级前检查 mapping/index；不兼容 index 使用 alias-based migration；回滚到上一制品版本，运行时没有 LEGACY/SHADOW 开关。

- [ ] **Step 5: 清理失效文档引用**

Run:

```bash
rg -n "QueryFilter|QueryHandler|AbacQueryFilter|Tail.*QueryFilter|Masking.*QueryFilter" \
  documentation/docs README.md README.zh-CN.md
```

Expected: 仅 migration/history 的 before 示例允许命中；其他 query/data-access/CoSec/onboarding 页面全部改为 Policy/ResultPolicy。`RewriteRequestCondition` 命中必须同时说明 deprecated/legacy/untrusted。

- [ ] **Step 6: 编译示例与构建文档站**

Run:

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.docs.*"
cd documentation
pnpm install --frozen-lockfile
pnpm docs:build
```

Expected: snippets tests 与 VitePress build 通过，无 broken link/unknown sidebar page。

- [ ] **Step 7: 提交**

```bash
git add documentation/docs wow-query/src/test/kotlin/me/ahoo/wow/query/docs
git commit -m "docs: add query gateway migration guide"
```

## Task 6: 全量契约、可观测性与发布回滚闭环

**Interfaces consumed:** 四份计划全部代码/测试/docs；ABI/JSON/OpenAPI/Spring/storage golden。

**Interfaces produced:** 可审计 release evidence；无第二执行路径；制品级回滚说明。

**Files:**

- Create: `docs/superpowers/verification/2026-08-12-query-service-architecture-upgrade.md`
- Modify only if generated by authoritative task: `wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json`

- [ ] **Step 1: 运行静态架构与契约清理**

```bash
./gradlew queryApiCheck
rg -n "NoOp(Snapshot|EventStream)QueryServiceFactory|getOrNoOp\(|DefaultSnapshotQueryHandler|DefaultEventStreamQueryHandler" \
  wow-spring wow-spring-boot-starter wow-webflux wow-mongo wow-elasticsearch
rg -n "QueryConditionContributor|LEGACY.*engine|SHADOW|legacy.*executor" \
  wow-api wow-query wow-spring wow-spring-boot-starter wow-webflux wow-mongo wow-elasticsearch
```

Expected: 第一个成功；后两个无活动 runtime 命中（migration/docs/test fixture 可排除）。任何未批准 ABI 缺失必须修实现，不能扩大 allowlist。

- [ ] **Step 2: 运行模块窄检查**

```bash
./gradlew :wow-query:check :wow-mongo:check :wow-elasticsearch:check \
  :wow-spring:check :wow-spring-boot-starter:check :wow-webflux:check \
  :wow-cosec:check :wow-openapi:check --stacktrace
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: 运行全量契约与集成测试**

```bash
./gradlew allContractTest allIntegrationTest --stacktrace
```

Expected: Mongo/ES containers、Spring/WebFlux wire tests、OpenAPI snapshot、旧/new/WebFlux Policy consistency 全通过。环境临时故障必须保留日志并重试确认，不把未运行写成通过。

- [ ] **Step 4: 运行 detekt 与全量 build**

```bash
./gradlew detekt build --stacktrace
```

Expected: `BUILD SUCCESSFUL`。修复只限本升级引入的问题；发现无关基线失败时在 verification 文档分开记录。

- [ ] **Step 5: fresh 构建文档并记录证据**

```bash
cd documentation
pnpm docs:build
```

在 verification 文档记录每条命令、时间、exit code、关键测试数量/容器版本、已知环境差异；不复制敏感连接信息或长日志。

- [ ] **Step 6: 最终行为审计**

用测试/metrics 证明：四 operation × Snapshot/EventStream × typed/dynamic 全部命中 Gateway；Policy error resolver/backend count=0；Mongo facet command=1；ES page search=1；PIT close all terminal paths；旧 facade metric 可区分；Native payload/authority 未进入 logs/metrics。

- [ ] **Step 7: 提交验证记录**

```bash
git add docs/superpowers/verification/2026-08-12-query-service-architecture-upgrade.md
git commit -m "test: verify planned query architecture"
```

## Plan 04 完成检查

- [ ] `git status --short` 仅保留用户既有/明确排除的 `.superpowers/`，无构建产物。
- [ ] 所有框架托管入口只进入 `QueryGateway`；没有旧 executor、Filter chain 或 NoOp fallback。
- [ ] 旧 Filter 源码引用编译失败，迁移后的 QueryPolicy 示例编译通过；不存在 runtime Filter detection。
- [ ] `RewriteRequestCondition`/masker API 仍可编译并已 deprecate；rewrite scope 永远不是 authority。
- [ ] ABI gate 除精确批准删除项外无差异；OpenAPI snapshot 与现有 wire contract 无意外漂移。
- [ ] Portable/Backend/Policy/Schema/lifecycle/partial-result/readiness/route 测试全部有 fresh evidence。
- [ ] 中英文 migration/query/data-access/CoSec/Spring/backend/Snapshot/best-practices/onboarding 全部更新，docs build 通过。
- [ ] 执行 `superpowers:verification-before-completion`，再按 `superpowers:finishing-a-development-branch` 决定合并、PR 或保留分支；未经用户明确要求不推送、不发布、不合并。
