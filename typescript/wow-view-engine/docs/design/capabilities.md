# 采用服务端的能力描述（N5）

**状态**：Q1～Q3 已定（第 9 节，2026-09-25 按推荐）；C1～C6 已落地（C6：补偿控制台 #3541），裁定写进 [D47](decisions.md#d47-采用服务端的能力描述n5修订-d422026-09-25)，修订 [D42](decisions.md#d42-引擎的缺省预算不超过缺省配置的-wow-服务端2026-09-25)。C1 已合并（#3482）；C2 的第一部分已落地（第 12 节），余下的收窄行见第 12 节「C2 余项」。批次见第 10 节。
**依据**：

- 查询模块的目标架构 `documentation/designs/2026-09-24-query-target-architecture-design.md` §7（能力描述）与 §8.2（定义的构成与校验）；
- 服务端 #3467（`GET …/schema` 答能力描述）、#3473（schema 定期重新校验，refresh 路由移除）、#3477（`dynamic[].excludedKeys`）；
- wow-client 的 `QueryDescriptorClient`（#3482）；
- 补偿控制台重构方案 `compensation/dashboard/docs/design/view-engine-rebuild.md` §3 的 G15。

## 1. 问题

引擎现在只认定义。一个字段能用哪些算子、能不能排序、能怎样分组，全由定义与 `FieldKind` 的默认集决定；一次请求多大，由 `RuntimeLimits` 写死的缺省决定（D42：页 100、窗口 10,000、分析行 1,000）。服务端实际收什么，引擎不知道，只能等 400。已经出过三类错：

1. **上限不一致**：D42 的来由。缺省配置的服务端拒绝了引擎按自己的缺省发出的查询。D42 把缺省压到缺省守卫，但宿主调高守卫时仍要同步改引擎，两边还是两个真相源。
2. **同一份定义，换个存储就不成立**：G15。补偿控制台的「错误信息」按短语检索，在 Elasticsearch 上可用，在 MongoDB 上没有全文能力（除非建了文本索引）。引擎照样画出检索框，用户输入后收到服务端的拒绝。定义是代码，写的时候不知道部署在哪种存储上。
3. **准入的口径不是服务端的口径**：过滤节点数、`IN` 的值个数（[todo.md](todo.md)「守卫数的过滤节点与值」）。

服务端从 9.2 起用能力描述回答「在这个入口上，这个模型能被怎样查询」。契约是：**列出的每一项单独使用一定被准入，没列出的一定被拒绝**；组合规则写在 `constraints` 里；取值、作用域和策略仍可能在运行时拒绝，拒绝带违规码（#3461、#3471）。描述与调用者无关，`version` 是内容哈希，同时也是 ETag。

## 2. 原则

- **定义只收窄，不放宽**（目标架构 §8.2）。生效的能力 = 定义声明的 ∩ 当前描述。定义写了、描述没有的，运行时去掉；描述有、定义没写的，不自动加。定义是对业务受众的取舍：显示名、分组、默认列都由研发（或 Agent）决定。
- **上限以描述为准**。服务端的上限只由描述说；`RuntimeLimits` 里与服务端重合的几项，从「缺省值」降为「宿主可选的更小上限」。
- **交集的结果仍是一份定义**。收窄的产物是一份普通的 `DataViewDefinition`，所以四个内核、`operatorsOf`、`maxSortFields`、React 控制器和 `/ui` 都不用知道描述的存在，照旧读定义。描述只在一处被读：收窄函数。
- **描述是提示，4xx 仍是必须处理的路径**。副本之间、两次重新校验之间，描述都可能落后；被拒时按违规码处理（第 7 节）。
- **没有描述时照旧工作**：Wow 9.2 之前的服务端、非 Wow 的数据源、测试替身。这时按定义与 D42 的缺省运行，与今天相同。

## 3. 宿主怎样给描述：数据源端口多一个可选方法

**裁定**（技术细节，自定）：`ViewSource` 多一个可选方法，wow-client 的 `QueryDescriptorApi.describeSnapshot` 可以直接充当它，由引擎经数据源去取。宿主不直接传描述对象。

```ts
export interface ViewSource {
  paged(…): Promise<PagedList<RecordData>>;
  cursor(…): Promise<CursorPage<RecordData>>;
  aggregate(…): Promise<RecordData[]>;
  /** 这个源的能力描述；带上已持有的版本，未变时答 notModified。不提供即按定义运行。 */
  describe?(
    previous?: string,
    attributes?: Record<string, unknown>,
    abortController?: AbortController,
  ): Promise<QueryDescriptorResult>;
}
```

- **为什么经数据源，而不是宿主传描述对象**：
  - 描述随部署变化：服务端每 5 分钟（`wow.query.schema.revalidate-interval`）重新校验 schema，建了文本索引就会多出全文能力。宿主传进来的对象是某一时刻的快照，引擎无法重新验证。
  - 描述与数据同属一个源：同一个键、同一套认证与 `Wow-Space-Id`。放在数据源上，一个源键就说清了「数据从哪来、能怎样查」。
  - 与目标架构 §8.2 一致：「视图引擎的数据源端口增加获取描述的能力」。
- **为什么是可选方法**：`test/architecture.test.ts` 断言 `QueryApi` 不经适配就是一个 `ViewSource`，这条继续成立。可选也覆盖了没有描述的源。
- **Wow 宿主的接法**：快照查询客户端与描述客户端是两个对象，因为 schema 路由没有租户、所有者段（见 wow-client 的 `architecture.md` §7.3）。宿主拼一个对象，方法都已自绑定：

  ```ts
  const snapshots = factory.createSnapshotQueryClient();
  const descriptors = factory.createQueryDescriptorClient();
  resolveSource: () => ({
    paged: snapshots.paged,
    cursor: snapshots.cursor,
    aggregate: snapshots.aggregate,
    describe: descriptors.describeSnapshot,
  });
  ```

  事件流的源传 `describeEventStream`。故事和测试可以传一个返回固定描述的函数，这样也能在 Storybook 里演示 G15（同一份定义配上「有全文」「无全文」两份描述）。

- **不加的东西**：不加 `ViewEngineOptions.descriptors`，不加单独的 `ViewEngineOptions.resolveDescriptor`。多一个入口，就多一个「两处都给了以谁为准」的问题。

## 4. 准入：定义 × 描述 → 生效的定义

收窄是一个纯函数：`narrowDefinition(definition, descriptor, kinds): { definition; findings: Issue[] }`。它放在新的 `src/capabilities/` 里，只引 `model/` 和 wow-client 的描述类型，与四个内核平级、互不引用。按字段和能力逐项对应如下。

### 4.1 字段

| 定义                              | 对照描述                                                                                                          | 不成立时                                                                                                                   |
| --------------------------------- | ----------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| 根字段 `name`                     | `fields[].path` 精确匹配；没有时按 `dynamic[].pattern` 的 `{key}` 匹配，键在 `excludedKeys` 里的不算匹配（#3477） | 查询能力全部去掉（算子、排序、分组、指标）；列仍可显示（显示读的是行，不是描述）。报 `capability.field.unknown`（warning） |
| 元素字段 `parent.element`         | 描述里同一路径，且 `scope === parent`；父字段在 `elements[]` 里，`filter`／`aggregate` 分别为真                   | 同上；父字段的 `elements[].filter` 为假时，`elementMatch` 种类整个不可用                                                   |
| 显示字段（列、卡片、`rowFields`） | `project`                                                                                                         | 该字段不进投影，列显示为空，同时报 `capability.field.not-projectable`（warning）                                           |
| `temporal`                        | `semantic`（`TEMPORAL_EPOCH` 的 `timeUnit`、`TEMPORAL_DATE`）                                                     | 两者不一致时报 **error** `capability.field.temporal-mismatch`：日期条件会按错误的单位发出，这是定义写错了，不是能力少了    |
| `enum` 的 `options`               | `enum[].value`                                                                                                    | 描述有值而定义没有的选项不补（显示名归定义）；定义有而描述没有的值报 warning，候选里仍保留（历史数据可能有）               |

- **每个 `{key}` 模式在 `dynamic` 里只有一条**（#3489）：服务端按解析具体键的方式描述模式，值为数组的模式（如 `tags.{key}`）就是一条 `ARRAY`，数组项隐含其中。一个键因此至多匹配一条，直接取它的算子，不做并集。
- **`sensitivity`**：受保护字段的描述不列 `enum`，也没有 `aggregate`，这两处自然去掉；算子为空时它不出现在筛选里。显示照旧，值本来就由服务端遮盖。

### 4.2 筛选

- **算子**：生效的算子 = `operatorsOf(field, kind)` ∩ `filter.operators`。`operatorsOf` 不变，收窄后的定义把交集写进 `field.operators`。
- **不带字段的算子**（`documentId`、`aggregateId`、`tenantId`、`ownerId`、`spaceId`、`deletion` 这几种种类）对照 `record.rootOperators`：`ID`／`IDS`、`AGGREGATE_ID(S)`、`TENANT_ID`、`OWNER_ID`、`SPACE_ID`、`DELETION`。不在其中的种类整个不可用。
- **检索**（G15）：`search` 种类的字段对照 `record.search`。
  - 没有 `record.search` 时，检索字段不可用。
  - `searchMode` 不在 `search.modes` 里时，换成描述里有的另一种。PHRASE 换 TERMS 仍然是在检索，只是更宽；反过来 TERMS 换 PHRASE 更窄。所以只做 PHRASE→TERMS 这一个方向；定义写 TERMS 而描述只有 PHRASE 时，按不可用处理。换了模式就报一条 note，说明检索按词进行。
  - `searchFields` 与 `search.fields` 取交集；交集为空时，检索不可用。
- **组合规则**：
  - `STARTS_WITH_REQUIRES_PREFIX`：条件编辑器对 `STARTS_WITH` 要求非空，并提示区分大小写；
  - `COUNT_REQUIRES_FILTER`：见第 9 节 Q3。

### 4.3 排序与分页

- `sortable` 对照 `sort.paged`（分页源）或 `sort.cursor`（游标源）。
- `RecordCapability.paging` 必须在 `record.paging` 里，否则报 error：记录视图整个不可用，这是部署与定义不匹配。
- 游标源：`CURSOR_UNIQUE_SORT.appended` 必须等于 `rowKey`，否则报 error。服务端会追加它的身份字段，引擎也追加自己的行键，两者不同就会多占一个排序位，顺序也不再是引擎以为的顺序。
- 行键必须在描述里可排序，不可排序时报 error（与今天的 `definition.record.row-key-unsortable` 同理）。
- `maxSortFields`：分页源取 `limits.maxSortFields`；游标源仍是 `MAX_CURSOR_SORT_FIELDS - 1`，两者取小。

### 4.4 分析

| 定义（`AggregationFieldCapability`／`AnalysisCapability`） | 对照描述                                                                                                                                                     |
| ---------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `groups`                                                   | `aggregate.groups`（TERMS／HISTOGRAM／DATE_HISTOGRAM／DATE_PART）                                                                                            |
| TERMS 的 `missingKey`（默认维度会带）                      | `aggregate.missingKey`；为假时默认维度不带哨兵桶                                                                                                             |
| `functions`                                                | `aggregate.functions`                                                                                                                                        |
| `any`／`distinctCount`／`percentile`                       | 同名布尔值，并且 `analysis.metrics` 列了对应的指标类型                                                                                                       |
| `firstLast`                                                | `aggregate.firstLast`，并且 `analysis.metrics` 同时列了 `FIRST` 与 `LAST`；描述的 `analysis.firstLastOrderBy` 原样写进 `AnalysisCapability.firstLastOrderBy` |
| `count`                                                    | `analysis.metrics` 含 `COUNT`                                                                                                                                |
| `expressions`                                              | `analysis.expressions`；参与运算的字段要 `expressionInput`                                                                                                   |
| `dateDiffUnits`（不写即四种）                              | 与 `analysis.dateDiffUnits` 取交集；入口关掉昂贵查询时描述给空，两个时刻之差随之不可用                                                                       |
| `having`                                                   | `analysis.having.metrics` 非空；可测的指标类型按它收窄                                                                                                       |
| 按指标排序                                                 | `analysis.sort.metrics`                                                                                                                                      |
| 日期直方图的 `dense`                                       | `analysis.dense`                                                                                                                                             |
| `dateUnits`                                                | 与 `analysis.dateUnits` 取交集                                                                                                                               |
| `dateParts`（不写即四种）                                  | 与 `analysis.dateParts` 取交集；交集为空时去掉 `DATE_PART`                                                                                                   |
| 指标上的条件引用的字段                                     | `aggregate.inMetricFilter`                                                                                                                                   |
| `elements` 链的每一层                                      | `elements[].aggregate`                                                                                                                                       |

- 收窄后一个指标都构造不出来时，分析能力整个去掉，报 warning。这与今天 `hasConstructibleMetric` 报 error 不同：那是定义本身写错了，这里是部署的能力少了。
- **`dateUnits`**：定义给出要提供的单位，生效的单位是它与 `analysis.dateUnits` 的交集（#3489）。与别的收窄一样只减不加：交集为空时，该字段的日期直方图不可用。

### 4.5 上限

`RuntimeLimits` 分成两类：

| 类                   | 成员                                                                                                                                                                                                                                        | 采用后                                                                                                                                                                                                                                     |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 服务端收多大的请求   | `maxPageSize`、`maxPageWindow`、`maxAnalysisRows`、新增的 `maxQueryFilterNodes`（C3 起与配置树的 `maxFilterNodes` 分开）与 `maxFilterValues`；分析的组数、指标数、元素层数、表达式深度与节点数（今天读 wow-client 的 `AGGREGATION_LIMITS`） | 以描述为准：`limits.maxPageSize`、`maxPageWindow`、`aggregation.maxLimit`（已经是 `maxListSize` 与协议上限的较小者）、`maxFilterNodes`、`maxFilterValues`、`aggregation.*`。`null` 表示不限。宿主在 `RuntimeLimits` 里写了的，按较小者生效 |
| 引擎自己的界面与节奏 | `maxConcurrentQueries`、`maxQueuedQueries`、`exportMax`、刷新间隔、`maxFilterDepth`、`maxDashboardPanels`、`pageSizes`、`defaultPageSize` 等                                                                                                | 不变                                                                                                                                                                                                                                       |

- **`DEFAULT_RUNTIME_LIMITS` 不再给第一类赋值**，这几项改为可选。缺省值挪到一个内部常量 `FALLBACK_SOURCE_LIMITS`（D42 的那几个数），只在没有描述时用。否则服务端调高守卫后，宿主照抄的 `DEFAULT_RUNTIME_LIMITS` 仍会把引擎压在 100，D42 的问题换个样子回来。补偿控制台今天写的 `{ ...DEFAULT_RUNTIME_LIMITS, maxPageSize: 100 }` 在批 C6 删掉。
- **分页条的档位**：`pageSizes` 照旧，超过生效 `maxPageSize` 的档位去掉。
- **数的口径**：`maxFilterNodes`、`maxFilterValues` 与服务端同口径，都在编译后的整条查询上数，即 [todo.md](todo.md) 里那一条。描述给出了数字，那条的判据从「缺省对齐守卫」改为「读描述」。
- **定义里的 `maxLimit`、`maxWindow`** 仍然只能再压低，含义不变。
- **`limits.defaultListSize`**：引擎不发不带 `limit` 的列表查询，用不到。

### 4.6 严重度

| 情况                                                                   | 严重度  | 理由                                                            |
| ---------------------------------------------------------------------- | ------- | --------------------------------------------------------------- |
| 能力少了：算子、排序、分组、函数、检索、整个字段的查询能力             | warning | 同一份定义换一种存储本就如此（G15）；视图照常可用，只是少了选项 |
| 部署与定义冲突：分页方式、游标的追加字段、行键不可排序、时间编码不一致 | error   | 视图会发出注定被拒或含义错误的查询；与今天定义准入的 error 同级 |

两类都经 `onIssue` 报给研发，按 `(定义, 描述版本)` 只报一次。发现码统一以 `capability.` 开头，文案进 `ui/messages`。那几个文件正由违规码批次修改，所以文案随 C2 一起加。

## 5. 界面：只提供服务端做得到的

收窄的产物是一份定义，下面各处因此不需要新的分支。它们本来就读定义，现在读到的就是生效的定义：

- **条件编辑器**：
  - `useFilterEditor.addableFields` 只列至少还有一个算子的字段；
  - 算子下拉（`ConditionPill` 读 `operatorsFor`）只列交集；
  - `addLeaf` 的缺省算子不在交集里时，取交集的第一个。今天的规则就是这样，不用改。
- **筛选托盘**（`ConditionBlock` 与简单模式的字段清单 `FieldChecklist`）：读同一份字段，所以同样只列可用的字段。
- **排序**：`SortSettings` 与表头排序只列 `sortable` 的字段，并停在收窄后的 `maxSortFields`。
- **检索框**：`searchFieldOf` 找不到可用的检索字段时不画检索框（G15 在 MongoDB 上就是这样）。检索换成按词时，占位文字随模式改写（「按词检索…」），不另起提示条。
- **分析托盘**：`groupableFields`、`summaryChoices`、`groupOfType` 读收窄后的 `AggregationFieldCapability`。
- **「近似值」字样**：来自 `analysis.approximate`（#3489），它列出该后端估算的指标类型（MongoDB 上是 `PERCENTILE`，Elasticsearch 上是 `DISTINCT_COUNT` 与 `PERCENTILE`）。指标类型在其中时标「近似值」，不在时不标；今天写死在 `analysis/boxplot.ts` 和 `ui/display.ts` 里的判断改读它。

**隐藏还是置灰**，按目标架构 §8.2「被去掉的能力不出现，而不是置灰」处理，即隐藏。已保存视图里残留的条件怎样处置是产品问题，见第 9 节 Q1、Q2。

## 6. 缓存与重新获取

**裁定**（技术细节，自定）：

- **一个源一份**：`ViewEngine` 持有一份描述缓存（`src/capabilities/cache.ts`），按源键缓存 `{ descriptor, version, checkedAt }`。一个看板上十个面板读同一个源，只取一次。同一时刻的多次请求合并为一个在途的 Promise。
- **首次**：打开第一个用到该源的视图时，先取描述，再准入并发出第一条查询。多一次很小的 GET（同一主机，一个源一次），换来的是不发注定被拒的查询，分页条也从一开始就停在对的窗口里。取描述失败时不挡视图，见下文「取不到时」。
- **重新验证**：带着持有的版本发 `If-None-Match`，没变就是 304、没有正文，所以重新验证很便宜。时机：
  1. 打开视图、切回标签页（`visibilitychange`）、用户点刷新时，距上次检查超过 **5 分钟**（与服务端的缺省重新校验间隔相同）就在后台重新验证，不挡当前的查询；
  2. 查询被拒且违规码指向能力（第 7 节）时，立即重新验证一次；
  3. 自动刷新的每个周期不单独重新验证，按第 1 条的 5 分钟节流。
- **版本变了**：重新收窄，按 `(定义 id, 描述版本)` 记忆结果，并通知打开着的运行时。
  - 当前配置在新的生效定义下仍然成立：界面上的选项随之更新，结果不动；
  - 不再成立：按 Q2 处置。
- **不持久化**：不写浏览器存储，也不经宿主的 `ViewStore`。描述取决于部署，不属于用户；页面重开时的一次 GET 已经足够便宜。
- **取不到时**：分三种。
  - 源没有 `describe`：安静地按定义与 `FALLBACK_SOURCE_LIMITS` 运行；
  - 服务端答 404（Wow 9.2 之前）：同上，另经 `onIssue` 报一条 note `capability.descriptor.unavailable`；
  - 其他错误（网络、5xx）：同上并报 note，下一次按第 1 条的时机重试。
  - 三种都不给最终用户看任何东西。用户看到的只有查询真被拒时的那条错误，与今天相同。
- **副本漂移**：各副本的版本可能暂时不同。缓存只认最后一次拿到的版本，不做版本比较。按「最后答的为准」处理，最坏是一次多余的重新收窄。

## 7. 与违规码的配合（#3461、#3471）

描述说「单独用一定被准入」，违规码说「这一次为什么被拒」。二者配合如下：

| 违规码                                                                                                                                                                                                                                                 | 含义                   | 引擎怎么做                                                                                                                                                                                                                  |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `UNKNOWN_FIELD`、`UNSUPPORTED_CAPABILITY`、`MODEL_SEARCH_UNSUPPORTED`、`CURSOR_NOT_ALLOWED`、`ELEMENT_SCOPE_REQUIRED`、`PROTECTED_AGGREGATION`、`MISSING_KEY_REQUIRES_STRING`、`ANY_REQUIRES_SINGLE_VALUE`、`METRIC_FILTER_*`、`INCOMPLETE_PROJECTION` | 能力层面：描述可能落后 | 立即重新验证描述。版本变了：重新收窄，把被拒的配置按 Q2 处置，`violation.path`（逻辑字段路径）落到对应的条件、列或维度上。版本没变：说明描述与准入不一致，是服务端的缺陷，照今天的方式如实报出，并经 `onIssue` 报一条 error |
| `VALUE_MISMATCH`、`NOT_COLLECTION`、`NOT_SINGLE_STRING`                                                                                                                                                                                                | 取值层面               | 不重新验证；按 `path` 标到条件上，文案用违规码的译文                                                                                                                                                                        |
| 解码类（`INVALID_JSON`、`UNKNOWN_PROPERTY`、`UNKNOWN_TYPE`、`INVALID_VALUE` 等）                                                                                                                                                                       | 引擎发错了请求         | 不重新验证；照今天的方式报出（这是引擎的缺陷）                                                                                                                                                                              |
| 不带码的预算拒绝（`HTTP list query limit[...]` 等）                                                                                                                                                                                                    | 上限                   | 上限已经读描述，出现即说明描述落后；按第一行处理                                                                                                                                                                            |

- 违规码的读取与文案（`runtime/sourceReason`、`ui/messages`）由违规码批次负责。本方案只在它之上加「哪些码触发重新验证」这一张表，放在 `src/capabilities/`，不改 `sourceReason`。
- **查询不附带描述版本**。目标架构 §7.3 提到视图引擎可以在查询时附带它所依据的版本，用来发现漂移，但服务端目前不收这个版本，本方案不做。

## 8. 定义里留下什么

描述回答「能不能」，定义回答「给谁看、怎样看」。留在定义里的：

- 字段的取舍与顺序、`label`、`fieldGroups`、`kind`（编辑器由它推出）、`cell`、`numberFormat`、`options` 的显示名与语气、`elementTitle`；
- 收窄：`operators` 子集、`sortable: false`、`groups`／`functions` 子集、`maxLimit`／`maxWindow`；
- `dateUnits` 的取舍（与 `analysis.dateUnits` 取交集，见 4.4）、`stringComparison`、`searchFields` 的取舍、`searchMode` 的首选；
- `temporal`：留在定义里，并由描述核对（4.1）；
- `rowKey`、`paging`、`layouts`、`rowFields`、默认配置、系统视图、看板。

从定义里去掉的：替服务端声明缺省上限的写法。比如补偿控制台的 `maxWindow: 10_000`、`limits: { maxLimit: 1000 }`、「schema 里只有全文」所以只给 `IS_NULL`／`IS_NOT_NULL` 的手写算子表。这些在 C6 删掉。

开发与 CI 阶段的校验（`validateDefinition` 的 `options.descriptor`、对照已提交的描述文件、记录描述版本以发现漂移）按目标架构 §8.2 和 §8.3（`wow-view-definition` Skill）做，排在首发之后（C7）。运行时的收窄已经保证不会发出越界的查询。

## 9. 已定（2026-09-25，协调者按用户「按推荐」）

- **Q1 能力去掉的地方，隐藏还是置灰并说明原因**：**已定，隐藏**。
  - 推荐：**隐藏**，与目标架构 §8.2 一致。最终用户面对的是一个部署：MongoDB 上的补偿控制台用户从来没见过检索框，不必向他解释「别的部署有」。研发经 `onIssue` 知道少了什么。
  - 代价：同一份定义在两个部署上看起来不同，排查时要先看描述。
  - 备选：检索框这类整块的功能置灰，并写「这个部署不支持全文检索」。
- **Q2 已保存视图（含系统视图）用到了当前服务端不再允许的条件、排序或维度**：**已定，标出来，修好之前不发查询，另给「移除不可用的条件」一键操作**（C4）。
  - 推荐：**标出来，修好之前不发查询**。与今天配置校验出 error 时的行为相同：视图进入待修复，对应的条件 pill、排序项或维度标为不可用并写明原因，另给一个「移除不可用的条件」的一键操作。
  - 理由：悄悄丢掉条件会让结果变宽而用户不知道，是这类问题里最坏的一种；照发则注定被拒。
  - 代价：换了存储的部署上，老视图需要用户动手一次。
- **Q3 `COUNT_REQUIRES_FILTER` 的入口上，没有条件的记录视图**（服务端关掉昂贵查询时才出现；示例服务端的缺省配置没有这条）：**已定，空态「先添加一个条件」，不发查询**（C4）。
  - 推荐：空态提示「先添加一个条件」，不发查询。
  - 备选：源支持游标时改走游标分页，不显示总数，加了条件再切回来。两种分页方式来回切，界面状态更复杂。

## 10. 批次

范围冻结期间只收「只加不改」的接口（N5 在列）。下面每批都是加法，只有 `RuntimeLimits` 第一类成员由必填改为可选是例外。本包还没有发布，这一改不欠兼容债。

| 批  | 内容                                                                                                                                                                                                                          | 依赖                                   | 判据                                                                                                                                          |
| --- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| C1  | wow-client：`QueryDescriptorClient`、描述类型、条件请求（**已合并，#3482**）                                                                                                                                                  | —                                      | #3482 合并                                                                                                                                    |
| C2  | 引擎内核：`ViewSource.describe?`、`src/capabilities/`（收窄函数 `narrowDefinition(…)`、生效上限、描述缓存）、`RuntimeLimits` 第一类改为可选、`capability.*` 发现码与中英文案；Storybook 的 G15 对照故事（同一定义配两份描述） | C1；违规码批次先合并（文案文件）       | 收窄的每一行（第 4 节）各有一个用例；没有 `describe` 时行为与今天逐字节相同（端点表与现有测试不变）；端到端去掉定义里手写的 `maxLimit` 仍通过 |
| C3  | 条件节点与值按服务端口径计数（`maxFilterNodes`、`maxFilterValues` 在编译后的查询上数）                                                                                                                                        | C2                                     | [todo.md](todo.md) 那一条的判据，上限读描述                                                                                                   |
| C4  | Q1、Q2、Q3 按拍板落地：不可用条件的标记与一键移除、空态                                                                                                                                                                       | C2、拍板                               | 已保存视图用到被去掉的能力时不发查询、可一键修好；故事与交互测试                                                                              |
| C5  | 违规码驱动的重新验证（第 7 节的表），`violation.path` 落点                                                                                                                                                                    | C2、违规码批次                         | 服务端在版本变化后拒绝时，引擎重新验证一次并把错误落到对应的条件上；版本未变时报 error                                                        |
| C6  | 补偿控制台采用：`describe` 接入 `engine.ts`；删掉 `executionFailed.ts` 里手写的 `maxWindow`、`maxLimit` 和只给 `IS_NULL`／`IS_NOT_NULL` 的算子表；G15 关闭                                                                    | C2（C4 之后更完整）；与控制台批 2 协调 | MongoDB 上检索框不出现，ES 上按短语检索可用；控制台不再有替服务端声明的上限                                                                   |
| C7  | 首发之后：`validateDefinition` 的 `options.descriptor`、对照已提交的描述文件校验、`wow-view-definition` Skill                                                                                                                 | C2                                     | 按目标架构 §8.2、§8.3                                                                                                                         |

C2～C6 在首发之前完成（[todo.md](todo.md)「首发前的门」的 N5 一条）。C2 分两次合并，每次都能单独发布：第一部分见第 12 节，余项（要给定义加成员的那几行）排在它之后。

## 11. 服务端的缺口（已报协调会话）

已全部由 #3489 补上：`analysis.approximate`（估算的指标类型）、`analysis.dateUnits`（日期直方图的单位）、`dynamic` 每个模式一条，以及 schema 路由在 OpenAPI 里声明的 `If-None-Match`、`ETag` 与 304。目前没有待服务端补的缺口。

## 12. C2 第一部分的落地记录（2026-09-25）

**已落地**：

- `ViewSource.describe?`（第 3 节，签名与 wow-client 的 `describeSnapshot` 相同）；没有它的源与今天逐字节相同：运行时拿到的就是声明的那份定义、引擎的 `limits`（`test/capabilitiesRuntime.test.ts`「a source without a descriptor」，现有测试全部不改）。
- `src/capabilities/`：`narrowDefinition`（第 4 节，4.1～4.4 中不需要新成员的各行）、`sourceLimits`（4.5）、`DescriptorCache`（第 6 节）。与四个内核平级，只引 `model` 与 `filter`：收窄要用 `operatorsOf` 与 `FieldKindRegistry`（第 4 节原写「只引 `model/`」，按此修订，`test/architecture.test.ts` 守着）。
- `runtime/capabilities.ts` 的 `SourceCapabilities`：`open` 先读描述再发第一条查询（看板的自有视图在 `open` 里读，引用的已保存视图在面板解析时读）；按（定义, 描述版本）收窄一次、发现报一次；刷新、页面切回来时超过 5 分钟就带版本重新验证；读不到时报 note `capability.descriptor.unavailable`，照定义运行。
- 界面只改两处读法，不加分支（第 5 节）：`searchFieldOf` 跳过收窄成没有算子的检索字段（G15：MongoDB 上检索框不出现），`useFilterEditor.fieldsFor` 不列没有算子的字段（「添加」清单与高级编辑器都读它）。Storybook「能力/随部署收窄」是同一份定义配两份描述的 G15 对照，孪生故事在浏览器里断言。
- `capability.*` 发现码与中英文案（`ui/messages/capabilities.ts`）。

**与方案不同处**（技术细节，按第一性原理定）：

- **`DEFAULT_RUNTIME_LIMITS` 仍给源预算赋值**，改的是宿主的入口：`ViewEngineOptions.limits` 改为 `Partial<RuntimeLimits>`，叠在缺省之上，宿主只传要改的；传了的源预算只压低描述的值。理由：`RuntimeLimits` 是内核读的完整类型，五十多个测试与各内核的缺省参数都拿 `DEFAULT_RUNTIME_LIMITS` 当完整对象；把第一类改成可选，就要在每个读它的内核里补一个缺省，或者再造一个「解析后」的类型。方案担心的「宿主照抄 `DEFAULT_RUNTIME_LIMITS` 把引擎压回 100」由 README 写明（不要展开缺省），补偿控制台那一处在 C6 删掉。`FALLBACK_SOURCE_LIMITS` 因此不需要。
- **描述与定义冲突（error）时，这份定义像准入不过一样被拒**（`view.definition.invalid`），而不是只让记录视图不可用：4.6 说「与今天定义准入的 error 同级」，同级就是同一种处置。
- **`STARTS_WITH_REQUIRES_PREFIX`**：约束在时，不区分大小写的字段去掉 `STARTS_WITH`（服务端只收区分大小写的前缀，照发会被拒）；空前缀本来就不编译（空叶子是未完成的编辑）。「提示区分大小写」的界面随 C4。
- **记录视图的汇总也收窄**：汇总是一条聚合查询，`COUNT` 要 `analysis.metrics` 有 `COUNT`，其余要字段的 `aggregate.functions` 与 `NUMERIC`。
- **`maxSortFields`** 经定义的新成员 `RecordCapability.maxSortFields`（只压低）写进收窄后的定义：`limits.maxSortFields − 1`，行键占一位（分页与游标的查询都以行键收尾）；分页源声明了它时也按它准入。
- **`create`（新建、下钻）不能等**：它照已持有的描述收窄；这个源还没读过描述时按声明的定义运行，同时开始读，下一个视图就用上。
- **版本变了**：之后打开的视图按新版本收窄、发现按新版本报；已打开的视图换定义随 C4（它要 Q2 的处置）一起做。

**C2 余项**（2026-09-25 第二次合并落地）：

- 定义新成员，都只能收窄，不写即不收窄：`AggregationFieldCapability` 的 `missingKey`、`inMetricFilter`、`expressionInput`；`AnalysisCapability` 的 `metricSort`、`dense`、`havingMetrics`、`approximate`；`FieldDefinition.projectable`；收窄后的定义带 `narrowing`（描述版本与发现，定义里的代码不写它）。
- 内核：`missingKey` 为假时默认维度不带哨兵桶、保存的配置报 `analysis.group.missing-key-unsupported`；`dense` 为假时报 `analysis.group.dense-unsupported`；`metricSort` 为假时按指标排序报 `analysis.sort.metric-unsupported`，新视图与条件的候选改按维度升序；`havingMetrics` 之外的指标报 `analysis.having.metric-unsupported`；`inMetricFilter`、`expressionInput` 分别报 `analysis.metric.filter-field-unsupported`、`analysis.expression.operand-unsupported`；`projectable: false` 的字段不进投影、列显示为空（warning `capability.field.not-projectable`）。
- 界面：维度卡片菜单不列补齐空档、结果表的指标列不可点排序、排序设置不列指标、「只保留」只列可比较的指标、指标条件与公式只列可用的字段；检索按词时占位文字为「{字段}（按词检索）…」。
- **「近似值」改读描述**：列上的「≈」与说明、箱线图的「近似」都读 `AnalysisCapability.approximate`（描述的 `analysis.approximate` 原样写入）。没有描述时缺省为百分位（`DEFAULT_APPROXIMATE_METRICS`），与之前逐字相同；Elasticsearch 上去重计数也标「≈」，精确计算的源两者都不标。
- `having.metrics` 作收窄时，定义不写 `havingMetrics` 视为全部类型，与描述取交集。

C3 见第 14 节。

## 13. 描述新增内容带来的引擎后续（Wow 查询第 4 步，#3486～#3503）

wow-client 已镜像这些描述字段，引擎尚未采用；各记一行线索，到 C2～C5 时再细化。

- **不可比较的字段**（`sensitivity.comparable: false`，`CONFIDENTIAL` 恒为此）：不列算子、`sort.paged` 为 false、不进 `record.search`，模型有这样的字段时整模型检索没有模式；筛选与检索框已随第 12 节去掉（它没有算子），余下排序按 `sort.paged` 收窄、只留展示；`PROTECTED_COMPARISON` 进第 7 节的表，落到对应条件上。
- **别名**（`FieldDescriptor.aliases`）：定义与已保存视图里写的别名在准入前映射回 `path`（服务端也会换，但结果与违规的路径都是规范名，映射后才对得上列与条件）；保存时写规范名。
- **弃用提示**（`deprecated.message`）：字段拾取与已保存视图用到弃用字段时给出提示（附 `message`），不阻断查询。
- **变体**（`QueryModelDescriptor.variants`）：事件流按 `bodyType` 分组列出载荷字段，路径相对 `body`；字段拾取按事件类型分组，条件自动包进 `body` 上的 `ELEMENT_MATCH` 并带上 `bodyType` 条件。

## 14. C3 的落地记录（2026-09-25）

- `RuntimeLimits` 新增两项源预算：`maxQueryFilterNodes`（缺省 128，守卫的 `max-filter-nodes`；描述的 `limits.maxFilterNodes`）与 `maxFilterValues`（缺省 1,000，`max-filter-values`；描述的 `limits.maxFilterValues`）。**与方案不同**：方案写的是让 `maxFilterNodes` 改口径，实际分成两项——`maxFilterNodes`（缺省 256）一直也是表达式与「只保留」树的守卫（与 Wow 的 `MAX_EXPRESSION_NODES` 同值），改成 128 或读描述会连带收紧它们，D42 当时就记了这一条；它留作引擎对存储里来的树的守卫，源预算另起一名。
- 数法照 wow-query 的 `QueryBudget`：编译后的查询里每个过滤节点算一个（`AND`／`OR`／`NOR` 的操作数、`ELEMENT_MATCH` 的谓词逐个下探），聚合查询依次数自己的过滤、每个元素的过滤（没写即 `MATCH_ALL`，算一个）、每个收窄了什么的指标过滤，再接着数「只保留」的节点；值取单个节点最长的列表。服务端按调用者身份加的作用域引擎看不到，不在数里。
- 落点是 `runtime/execute.ts` 的 `validateDataConfig`：内核放行后编译一次再数（`runtime/queryWeight.ts`，不出包），超出报 error `runtime.query.too-many-nodes`／`runtime.query.too-many-values`，视图进入待修复、不发查询。放在运行时而不是内核，因为要数的是编译后的整条查询（注入的作用域在内），而编译需要三个内核里的两个。
- 端到端（Wow 仓 `typescript/integration-test` 的 `view-engine/recordView.test.ts`）加了一条 130 个节点的用例：在本地就被拦下，不发请求。

## 15. C4 的落地记录（2026-09-25）

- **Q2 已保存视图用到不再允许的能力**：`ViewRuntime.unavailable()` 是按收窄后的定义准入报出、按声明的定义准入不报的 error——配置本来没错，是部署变了。视图照任何 error 一样待修复、不查询；各条仍在它所在的地方说（条件 pill、排序、维度），状态行另起一条「这个视图用到了数据源现在不支持的功能，移除之前不会查询」，带「移除不可用的条件」（`removeUnavailable()`，React 里是 `useUnavailable`）。一键移除逐条去掉能去掉的：条件（视图的与指标的）、排序项、「只保留」整条、维度的缺失值一组与补齐空档；维度或指标本身算不了时不去掉——那是换一个问题，不是修剪——留给读者。只改草稿，按「应用」才查询。
- **Q3 入口要求带条件**：收窄在 `COUNT_REQUIRES_FILTER` 下给分页的记录能力写上 `requiresFilter`（游标不计数，不写）；记录准入对没有一个填了值的条件（「含已删除」不算）的配置报 `record.filter.required`，不查询。它不是待修正的错：状态行不把它列进「要先修正」，结果区画「先添加一个条件」的空态（`RecordTableController.filterRequired`、`WorkbenchShell.resultWithoutQuery`），也不算「不可用」。
- **打开着的视图随版本重新收窄**：`DescriptorCache` 在第一次读到与每次版本变化时告知（`changed`），`SourceCapabilities.watch` 转告这个源上打开着的每个数据视图（看板的面板也在内）。运行时换上新的定义与上限、重新准入草稿与已应用的配置：仍然成立的，结果留在屏上，只是编辑器提供的选项变了；不再成立的，按 Q2 待修复，刷新与定时器都不再发。描述与定义冲突时视图的准入以 `view.definition.invalid` 开头，直到描述再变。`create` 在第一次读之前做出的视图也在第一次读到时收窄（C2 第一部分记下的缺口就此补上）。
- **游标（后端 #3502 改了游标的写法）**：改排序或条件都经 `apply`，从第一页重新开始（原来就是，这次加了用例）；翻页或刷新时服务端答 400 `Invalid cursor.`，记录视图回到第一页再问一次，不报失败、不告知 `onError`；第一页也被拒时照常报出。
- **Storybook**：「能力/随部署收窄」加了「已保存的视图用到了不可用的条件」与「先加条件」两个故事及回归；宿主导航加了「随部署收窄」，只重截了带导航的三张关键屏基线（首页日报、运营日报工作台、分析工作台，差别只在导航多一项）。Storybook 自己的目录次序（`.storybook/preview.tsx`）没动（主题 S2 在改它），新页排在「能力」一组的已列各页之后。
- 公开面：`ViewRuntime` 多 `unavailable()`、`removeUnavailable()`；`RecordCapability` 多 `requiresFilter`；`RecordTableController` 多可选的 `filterRequired`；`SearchBoxController` 多 `byWords`（C2 余项）；`/react` 多 `useUnavailable`、`UnavailableController`。
- **嵌入视图**（C4 后续）：`EmbeddedView` 同样说出「这个视图用到了数据源现在不支持的功能，移除之前不会查询」。`interactive` 一档给出「移除不可用的条件」：嵌入从不写入（D36），所以去掉只对这一页有效——修剪后的视图当场查询（嵌入没有编辑器可按「应用」），结果上方说「数据源不再支持的条件在这里已去掉；保存的视图没有改」；`static` 一档只说原因、不给控件。看板面板不给这个按钮：面板显示的是另一份已保存视图，修它的地方是那个视图自己的工作台，面板照旧在正文说出原因。

## 16. C5 的落地记录（2026-09-25）

- 第 7 节的表在 `src/capabilities/violations.ts`（`checksDescriptorAgain`）：能力层面的违规码（`UNKNOWN_FIELD`、`UNSUPPORTED_CAPABILITY`、`MODEL_SEARCH_UNSUPPORTED`、`CURSOR_NOT_ALLOWED`、`ELEMENT_SCOPE_REQUIRED`、`PROTECTED_AGGREGATION`、`PROTECTED_COMPARISON`、`MISSING_KEY_REQUIRES_STRING`、`ANY_REQUIRES_SINGLE_VALUE`、`METRIC_FILTER_*`、`INCOMPLETE_PROJECTION`、`NOT_PROJECTABLE`），以及不带码的 400 守卫拒绝（消息里点名的预算 `limit[…]`／`size[…]`／`window[…]`／`nodes[…]`／`values[…]`、入口关掉的昂贵操作、计数查询不能不带条件）。取值类与解码类的码不触发。`sourceReason` 没动。
- 视图自己的查询被这样拒绝时，运行时立即带版本重新验证（`SourceCapabilities.recheck`，不看 5 分钟节流）：版本变了，这个源上打开着的视图按 C4 当场重新收窄，被拒的配置因此按 Q2 待修复、不再发；版本没变，说明描述与服务的准入不一致，是服务端的缺陷，经 `onIssue` 报 error `capability.descriptor.disagrees`（带源、违规码与版本）。两种情况下拒绝本身都照旧报出，`violation.path` 照 #3480 落到对应的条件上。
- 汇总、合计、拆分「其他」、导出与整条记录的查询被拒不触发重新验证：它们各自照旧退回或报出，视图自己的下一次查询被拒时会触发。

## 17. 第 4 步描述字段与 #3515 存储事实的落地记录（2026-09-25）

第 13 节的线索按条落地；别名与变体各起一个 PR（第 18、19 节）。

- **不可比较的字段**（`sensitivity.comparable: false`）：描述本来就不列算子、不给分页排序，收窄随之去掉；另报一条 warning `capability.field.protected`，代替逐条的「不能筛选」「不能排序」。`PROTECTED_COMPARISON` 已在 C5 的表里，拒绝时重新验证描述。
- **弃用**（`deprecated.message`；wow-client 的 `QueryDeprecation.message` 按 #3522 收窄为 `message?: string`）：收窄把它写进 `FieldDefinition.deprecated`（定义也可以自己写），报 warning `capability.field.deprecated`／`deprecated-because`；已保存视图的查询用到它时，准入报 warning `view.field.deprecated`／`deprecated-because`，不挡查询；条件拾取在字段旁标「已弃用」，原因放在徽标的 `title`。
- **`NULL_OR_EMPTY_AS_MISSING`**（#3515）：约束里的字段收窄为 `FieldDefinition.emptyIsMissing`；在这些字段上写判空（`IS_NULL`、`IS_NOT_NULL`、`EXISTS`、`NOT_EXISTS`、`IS_EMPTY`）时，筛选准入报 note `filter.presence.empty-is-missing`——判空问的是「有没有非空的值」，说出来免得读成「有没有这个键」。
- **`PARALLEL_ARRAY_SORT`**（#3504）：收窄写进 `RecordCapability.parallelArrays`；排序同时点名同一组里两个数组时，准入在第二个上报 `record.sort.parallel-arrays`。
- **`ARRAY_EQUALITY`**（#3515）：引擎不会写出——数组与元素匹配两个种类都不提供 `EQ`／`NE`，数组按元素比较（`IN`、`CONTAINS_ALL`、`ELEMENT_MATCH`）；测试守着这一点，不需要收窄。

## 18. 别名的落地记录（2026-09-25）

- **匹配**：`describedField` 先按路径找，找不到再按 `aliases` 找（同一作用域）；按别名找到时带上规范路径。
- **定义**：用别名写的字段改名为规范路径（元素字段改为相对元素的名字），显示名不变；定义里其余引用它的地方一起改——字段分组、行键与 `rowFields`、分析能力的字段与元素、系统视图的配置；检索字段的 `searchFields` 也按规范路径与 `record.search.fields` 取交集。报 note `capability.field.alias`，改名记进 `narrowing.renamed`。
- **配置**：打开视图时（`RuntimeFactory`，看板面板也在内）与版本变化重新收窄时（`renameFields`），草稿、已应用与保存基线的配置都经 `withCanonicalNames` 改成规范路径——条件（视图的与指标的）、排序、列、卡片、汇总、维度、指标（`ANY` 的字段、表达式里的字段）、展开路径。基线一起改，所以打开不算改动；下次保存写的是规范名。元素谓词里的条件点名的是元素自己的字段，不改。
- **没做的**：看板筛选的接线（`panelField`）若写的是别名，仍按原名找字段；定义的 `record.defaults`（部分配置）不改。

## 19. 变体的落地记录（2026-09-25）

- **匹配**：元素的字段在 `fields` 里找不到、而这个元素正是 `variants.element` 时，到各个变体里按相对元素的路径（或别名）找；有它的变体都记下，算子取并集，排序、聚合取第一个（按描述，它们是同一逻辑路径的，各变体一致）。
- **定义**：收窄给这个数组字段写上 `variantKey`（判别字段，如 `bodyType`），给只在部分变体里的元素字段写上 `variants`（判别值）。定义照旧只收窄：定义没写的载荷字段不会补进来。
- **编译**：元素匹配的谓词里点名了带 `variants` 的字段、又没有点名判别字段时，谓词与 `判别字段 IN [这些变体]` 用 AND 合起来——描述要求两者对同一个元素成立。谓词里已经写了判别字段条件的，照写的发。
- **拾取**：谓词的字段拾取按变体分组（`variantGroups`）：只属于一个变体的字段列在这个变体下，组名取判别字段选项的显示名（没有时用值），次序按选项；几个变体共有的字段与不随变体变化的字段列在各组之前。

## 20. C6 撞到的一处（2026-09-25）

- **`ELEMENT_MATCH` 来自 `elements[]`，不来自数组自己的算子**：Wow 服务端描述一个数组时，`filter.operators` 只列判空、判存在（MongoDB 上补偿事件流的 `body` 是 `IS_EMPTY`、`IS_NULL`、`IS_NOT_NULL`、`EXISTS`、`NOT_EXISTS`），能否按元素筛选由 `elements[].filter` 说。收窄原先拿元素匹配种类的算子与数组自己的算子取交集，`ELEMENT_MATCH` 因此总被去掉、报 `capability.field.unfilterable`，控制台概览上四张「元素匹配」条件的结局卡在真服务上成了「保存的设置已经用不了了」。改为 `elements[].filter` 为真时把 `ELEMENT_MATCH` 加进这条路径准入的算子（4.1 表里「元素字段」一行本来就是这个意思）；测试夹具的 `describedField` 给数组列出了全部算子，掩盖了它，新用例按服务端的真实写法描述数组。
