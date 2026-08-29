# 通用游标分页 API 设计

## 状态

- 设计已确认并按本文实施。
- 本文只定义通用游标分页，不改变现有 `single`、`list`、`paged`、`count` 和 `aggregate` 契约。
- 现有查询的行为和 HTTP wire contract 保持不变；新的 Cursor API Client 通过显式 opt-in 接口提供。
- `QueryType` 新增 `CURSOR` 与 `DYNAMIC_CURSOR`，外部对该 enum 使用穷尽 `when` 的源码需要补充分支，因此不声明完整源码兼容；按确认结果也不验证或声明 JVM 二进制兼容。

## 背景与目标

当前 `PagedQuery` 具有两个结构性成本：

- MongoDB 每次分页同时执行 `countDocuments` 和 `skip/limit`；精确计数与深 offset 都会增加扫描成本。
- Elasticsearch 使用 `from/size` 并启用 `track_total_hits`；深分页受结果窗口限制，精确总数也会增加成本。

新增通用游标分页 API，用无状态 keyset/`search_after` 代替 offset，并且不计算 `total`。该 API 同时覆盖 Snapshot 与 EventStream、typed 与 dynamic 查询，以及 JVM、HTTP、OpenAPI 和 API Client 契约。

## 非目标

- 不替代或修改 `PagedQuery`。
- 不提供精确或近似 `total`。
- 不提供上一页或双向翻页。
- 不保证并发写入期间的跨页快照一致性。
- 不创建服务端游标状态，不引入缓存、游标存储、key ring、历史密钥、过期时间或新依赖。
- Elasticsearch 不为该 API 创建 PIT。
- 不通过 JMH 或耗时阈值测试宣称吞吐、延迟提升。
- 不解决缺少索引、昂贵过滤、超大 page size 或过宽投影导致的性能问题。

## 公共 API

### 查询契约

```kotlin
interface ICursorQuery : Queryable<ICursorQuery> {
    val size: Int
    val cursor: String?
}

data class CursorQuery(
    override val filter: FilterExpression,
    override val projection: Projection = Projection.ALL,
    override val sort: List<Sort> = emptyList(),
    override val size: Int = 10,
    override val cursor: String? = null,
) : ICursorQuery
```

规则：

- `size` 必须在 `1..Int.MAX_VALUE - 1`，为 `size + 1` 的 lookahead 保留一个整数位置。
- JVM API 不设置容量型最大 page size；HTTP 使用现有 `wow.webflux.query.max-page-size`。
- `cursor == null` 表示第一页。
- `CursorQuery` 与其他 Queryable 一样支持 `withFilter`、`withProjection` 和请求级过滤重写。

### 响应契约

```kotlin
data class CursorPage<out T>(
    val list: List<T>,
    val nextCursor: String?,
)
```

- `nextCursor == null` 表示没有下一页。
- 不增加与 `nextCursor` 重复的 `hasNext` 字段。
- 不返回 `total`。

### Service 与 Gateway

`QueryService` 和 `QueryGateway` 分别新增：

```kotlin
fun cursor(query: ICursorQuery): Mono<CursorPage<R>>
fun dynamicCursor(query: ICursorQuery): Mono<CursorPage<DynamicDocument>>
```

新增方法提供与现有 `aggregate` 相同的默认“不支持”实现：

```kotlin
fun cursor(query: ICursorQuery): Mono<CursorPage<R>> =
    Mono.error(UnsupportedOperationException("Cursor query is not supported."))
```

这使现有第三方实现不必立即实现新方法。Wow 内置 MongoDB 和 Elasticsearch QueryService 必须覆盖 typed 与 dynamic 游标查询；实际调用未实现的第三方服务时才返回“不支持”。

## 排序契约

游标分页必须形成严格全序：

- 未指定 `sort` 时，框架默认按模型唯一键升序。
- 指定 `sort` 时，框架在末尾自动追加模型唯一键升序；调用方已经显式排序该唯一键时不重复追加，并保留调用方的方向。
- Snapshot 的唯一逻辑字段是 `aggregateId`。
- EventStream 的唯一逻辑字段是 `id`。
- 用户排序字段必须由 Query Schema 声明 `SORT` 能力。
- 用户 sort 与追加唯一键后的 effective sort 都最多包含 `AggregationQuery.MAX_SORT_FIELDS`（32）个字段；32 个用户字段还需追加唯一键时拒绝请求。
- 拒绝重复排序字段以及 `_score`、`_doc`、`_shard_doc` 等不适合无状态跨请求游标的后端元数据排序。
- 支持升序、降序、多字段排序以及 `null`/缺失值；唯一键负责打破相同业务排序值之间的平局。
- 排序字段的实际存储类型必须符合 Query Schema；不承诺为混合物理类型数据提供跨后端一致顺序。

## 游标格式与信任边界

游标是 AES-256-GCM 加密的版本化 Base64URL token。解码后的 envelope 只包含格式版本、每次生成的 96-bit 随机 nonce，以及带 128-bit GCM authentication tag 的 ciphertext；格式版本作为 AAD 参与认证。实现只使用 JDK JCA。

- 单个 Base64URL 编码的 32-byte key 配置在 `wow.query.cursor.encryption-key`。不生成、持久化或记录 fallback key。
- 未配置 key 时应用仍可启动，现有查询（包括 `PageQuery`）不变；任何内置 CursorQuery（包括第一页）都以 `UnsupportedOperationException` 明确失败。
- 轮换单 key 会立即使所有既有 cursor 失效；没有 key ID、key ring 或历史 key 解密。
- 共享加密组件只处理 opaque bytes。MongoDB 在后端边界使用 BSON payload 保留 `Date`、`BsonTimestamp`、`Decimal128` 等物理值；Elasticsearch 在后端边界保留 `FieldValue` 的 null、boolean、string、long、double 标量。
- payload values 的顺序与 effective sort 完全一致，包括框架追加的唯一键。
- 游标不包含授权信息，也不包含 filter、projection、sort 或查询指纹。服务端不会把 cursor 绑定到这些请求字段；调用方必须自行保持后续请求的 filter 与 sort 不变。
- projection 与 size 可以在后续请求中调整，不改变游标位置含义。
- 每次请求重新执行请求作用域重写、租户/Owner/Space 约束、授权过滤和数据脱敏；token 只表示经过认证的后端排序位置。
- malformed Base64URL、短 token、未知版本、错误 key、tag 校验失败、backend payload 结构/类型/数量错误都抛出不含敏感细节的 `IllegalArgumentException`；HTTP 映射为 400。
- 游标无服务端状态、无过期时间。格式版本不支持时明确失败，不静默回退。

## 并发一致性

该 API 是无状态、非快照一致的游标分页：

- 对排序字段不变的数据，keyset 能稳定向后推进并避免 offset 扫描。
- 并发新增、删除或修改排序字段可能使后续页面出现遗漏或重复；这是已确认的契约，不通过服务端状态修复。
- 调用方需要稳定遍历时，应选择不可变排序字段，并建立与 filter、sort、唯一键匹配的索引。

## 通用执行流程

1. 请求进入 QueryGateway，执行现有请求作用域和安全过滤重写。
2. 检查 CursorTokenCodec；未配置时在首次后端查询前失败。
3. Query Schema 只解析 filter、projection 和用户 sort 的能力与物理路径。
4. 计算 effective sort，并追加模型唯一键，同时校验 32 字段上限。
5. 若存在 cursor，由选中的后端解密并校验 envelope、payload、值数量与物理值类型。
6. 后端请求 `size + 1` 条数据，不执行 count。
7. 返回前 `size` 条。只有存在额外一条数据时，才用最后一条已返回记录的 effective sort 值生成加密 `nextCursor`。
8. typed、dynamic 和 state-only 响应只改写 `list`，原样保留 `nextCursor`。

## MongoDB 实现

MongoDB 把游标位置编译为词典序 keyset 条件，并与原 filter 使用 `AND` 合并。示例：

```text
sort = [createdAt DESC, aggregateId ASC]

after =
  createdAt < cursor.createdAt
  OR (createdAt == cursor.createdAt AND aggregateId > cursor.aggregateId)
```

多字段排序按相同规则继续展开。比较方向跟随每个排序字段，并显式覆盖 MongoDB 对 `null`/缺失值的原生排序位置。

执行约束：

- 使用 `find + sort + limit(size + 1)`。
- 不调用 `countDocuments`，不设置 `skip`。
- filter、sort 和唯一键应由业务按真实查询模式建立复合索引；框架不自动为任意业务字段创建索引。
- 生成游标需要读取全部 effective sort 字段。若原 projection 未返回这些字段，MongoDB 查询临时补充读取；提取游标值后删除仅内部补充的字段，再执行 typed/dynamic 映射，不能改变响应或数据脱敏语义。
- cursor payload 使用 BSON-native 编解码，比较值保持 `Date`、`BsonTimestamp`、`Decimal128` 与普通 BSON 标量的原类型，不经逻辑 JSON schema 强制转换。

## Elasticsearch 实现

Elasticsearch 使用 effective sort 和命中结果的 sort values：

- 第一页不设置 `search_after`。
- 后续页把解码后的 values 设置为 `search_after`。
- 请求 `size + 1`。
- 设置 `track_total_hits=false`。
- 不设置 `from`，不打开 PIT。
- `_source` projection 与 sort values 分离，因此不需要把内部排序字段加入响应 `_source`。
- backend payload 只接受 `search_after` 支持的 null、boolean、string、long、double 标量；date/date_nanos 返回的数值 sort value 不受逻辑 schema 的 temporal/string 声明阻止。

由于不使用 PIT，并发索引变更可能影响后续页面，行为遵循已确认的非快照一致性契约。

相关后端语义以官方文档为准：Elasticsearch `search_after` 接受上一页返回的排序值（包括 `null`），MongoDB 将缺失字段按 `null` 参与排序，但比较谓词仍受 BSON 类型约束。

## Query Pipeline 与 HTTP

新增 `QueryType.CURSOR` 和 `QueryType.DYNAMIC_CURSOR`，并接入现有：

- Snapshot/EventStream QueryFilter 尾节点；
- 请求作用域 filter 重写；
- typed/dynamic 数据脱敏；
- idle timeout；
- filter 节点和值数量限制；
- 昂贵操作符限制；
- `max-page-size`。

Cursor 不属于 counting query；match-all cursor 不因查询类型本身被当作 match-all count 拒绝。

`QueryType` 是公开 enum；外部穷尽 `when` 必须迁移并处理这两个新成员。现有 `ReactiveSnapshotQueryApi` 与 `SynchronousSnapshotQueryApi` 不继承 cursor 接口；调用方显式声明 `ReactiveSnapshotCursorQueryApi` 或 `SynchronousSnapshotCursorQueryApi` 后才能使用新方法。

新增仅支持 JSON 的 POST 路由：

```text
.../snapshot/cursor
.../snapshot/cursor/state
.../event/cursor
```

请求体是 `CursorQuery`，响应体是 `CursorPage`。不支持 SSE，因为响应必须同时携带页面列表和下一页游标。

同步更新：

- Built-in handler keys 与 QueryRouteModule；
- OpenAPI route contributor、request/response components 和快照测试；
- 查询 JSON Schema；
- Query DSL；
- 手写 API Client 接口及其契约测试；
- 中英文查询、OpenAPI、API Client 和 WebFlux 配置文档。

不手工修改生成客户端或生成构建产物。

## 错误处理

| 场景 | 行为 |
| --- | --- |
| `size < 1` 或 `size == Int.MAX_VALUE` | `IllegalArgumentException` / HTTP 400 |
| cursor 不是合法 Base64URL/GCM envelope，认证失败或 key 错误 | `IllegalArgumentException` / HTTP 400 |
| cursor 版本不支持 | `IllegalArgumentException` / HTTP 400 |
| values 数量与 effective sort 不一致 | `IllegalArgumentException` / HTTP 400 |
| cursor value 不是所选后端支持的物理标量 | `IllegalArgumentException` / HTTP 400 |
| sort 字段重复、无 SORT 能力或属于禁止的元数据字段 | Query Schema validation error / HTTP 400 |
| sort 超过 32，或追加唯一键后超过 32 | `IllegalArgumentException` / HTTP 400 |
| 内置后端未配置 cursor encryption key | `UnsupportedOperationException`；应用启动和其他查询不受影响 |
| 后端或第三方 QueryService 未实现 cursor | `UnsupportedOperationException`，沿用现有错误策略 |

不新增游标专用异常层级或错误码；现有参数与 Schema 错误策略已经覆盖这些失败。

## 测试策略

### API 与通用行为

- `CursorQuery` 默认值、size 边界、withFilter/withProjection。
- AES-GCM opaque bytes round-trip、随机 nonce、机密性、篡改/错误 key/短 token/版本拒绝，以及 `CursorPage` 序列化。
- TCK 覆盖首、中、末页、空结果和不足一页。
- 默认排序、升序、降序、多字段相同值和唯一键 tie-breaker。
- `null`/缺失排序值。
- projection 排除排序字段时不泄漏内部字段。
- 非法 Base64URL、未知版本、认证失败、backend payload values 数量及物理类型错误。
- sort 32 个字段已形成全序时通过、33 个拒绝、追加唯一键溢出拒绝。

### Pipeline 与兼容

- QueryGateway typed/dynamic 分发。
- Snapshot/EventStream Filter、租户约束、数据脱敏与 state-only 改写。
- HTTP `max-page-size`、filter guard、昂贵操作符和 idle timeout。
- 旧 QueryService 实现继承默认“不支持”行为。
- 缺少 encryption key 时首次 CursorQuery 也“不支持”，而现有 query 保持可用。
- legacy composite API Client 实现不需要 cursor 方法；opt-in cursor 接口单独验证。
- 现有 list/paged/count 行为与 wire contract 不变。
- 不运行 `javap`，不作 JVM 二进制兼容声明。

### 后端结构性验证

MongoDB：

- 真实集成测试验证单字段、多字段、混合方向、相同值和 `null`/缺失值分页结果。
- 请求级测试确认不调用 `countDocuments`、不使用 `skip`、limit 为 `size + 1`。
- 验证内部补充 projection 字段不会出现在 typed/dynamic 响应中。
- 真实两页覆盖 BSON `Date`、`BsonTimestamp` 与 `Decimal128`。

Elasticsearch：

- 验证第一页不含 `search_after`，后续页携带解码后的 values。
- 验证 `track_total_hits=false`、没有 `from`、没有 PIT、size 为请求值加一。
- 验证 projection、相同排序值和末页 `nextCursor == null`。
- 真实两页覆盖 date mapping 返回的 numeric `search_after` 值。

### OpenAPI 与文档

- OpenAPI 路由、schema component 和 API Client 契约快照。
- 查询 JSON Schema 验证。
- 中英文文档构建。

## 验证命令

实现后至少运行涉及模块的检查：

```bash
./gradlew \
  :wow-api:check \
  :wow-query:check \
  :wow-mongo:check \
  :wow-elasticsearch:check \
  :wow-webflux:check \
  :wow-openapi:check \
  :wow-schema:check \
  :wow-apiclient:check \
  :wow-spring-boot-starter:check \
  --stacktrace
```

另运行 `git diff --check`。若真实后端集成测试属于独立 task，则同时运行对应 integration test task。

## 验收标准

- MongoDB 与 Elasticsearch 在 Snapshot、EventStream 上返回一致的 API shape，并能通过 opaque cursor 连续向后翻页。
- 所有返回项严格遵循 filter、sort、projection、租户约束与数据脱敏。
- 游标分页不执行精确 count，不使用 offset/from。
- effective sort 始终包含模型唯一键，相同业务排序值不会因缺少 tie-breaker 被跳过。
- 最后一页 `nextCursor == null`；非法游标稳定返回客户端错误。
- token 不暴露被脱敏前的 raw sort value，且任何篡改都会认证失败。
- 现有查询 API、HTTP 路由和 wire shape 不变。
- 本地验证只证明行为和结构性优化；生产延迟、吞吐收益必须通过真实数据、索引和负载另行验证。

## 参考资料

- [Elasticsearch：Paginate search results](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/paginate-search-results)
- [Elasticsearch：Search API](https://www.elastic.co/guide/en/elasticsearch/reference/current/search-search.html)
- [MongoDB：Comparison/Sort Order](https://www.mongodb.com/docs/v8.0/reference/bson-type-comparison-order/)
- [MongoDB：ESR Guideline](https://www.mongodb.com/docs/manual/tutorial/equality-sort-range-guideline/)
