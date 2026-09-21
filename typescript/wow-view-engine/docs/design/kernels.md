# 纯内核

四个目录都是同步纯函数，签名统一为"定义 + 配置 进，结果 或 Issue 出"。

纯内核报出的 `Issue` 与它们校验的配置类型见 [model.md](model.md) 与 [model-shapes.md](model-shapes.md)。

## 函数签名

```ts
// filter
validateFilter(fields: FieldDefinition[], tree: FilterTree, kinds: FieldKindRegistry): Issue[]   // 取字段表而非整份定义，Dashboard 可传 cfg.fields
isSimpleTree(tree: FilterTree): boolean                  // filterMode 'simple' 的准入判断
compileFilter(fields, tree, kinds, ctx: { now: Date; timeZone: string }): FilterExpression
clearFilter(tree): FilterTree
describeFilter(fields, tree, kinds): FilterSummaryItem[]     // 已应用条件的摘要：结构化的部件 + 英文兜底句

// record
defaultRecordConfig(def): RecordViewConfig                      // 按 RecordCapability.defaults 补全的完整初始配置
validateRecord(def, cfg: RecordViewConfig, kinds): Issue[]   // 见下方规则
compileRecord(def, cfg, kinds, ctx, page: RecordPageTarget): FilterPagedQuery | CursorQuery   // 按 RecordCapability.paging 判别；首次查询为 { index: 1 } 或 { cursor: null }（Wow 页码从 1 开始）
projectRecord(def, cfg, page: PagedList<RecordData> | CursorPage<RecordData>): RecordView   // 列语义、行、行键；paging 为 { mode: 'paged'; index; total? } | { mode: 'cursor'; nextCursor: string | null }
compileSummaries(def, cfg, kinds, ctx): AggregationQuery | null    // 全范围汇总
projectSummaries(def, cfg, rows | aggregation): SummaryRow        // scope 是结果的一部分：'total' 来自自己的聚合，'page' 来自屏幕上的行

// analysis
defaultAnalysisConfig(def): AnalysisViewConfig                  // 按固定优先级从已声明能力挑选指标；有可分组字段时取其一并按指标降序排序，否则分组为空且 `sort` 为空（无分组聚合只有一行，Wow 拒绝对其排序）
validateAnalysis(def, cfg: AnalysisViewConfig, kinds): Issue[]   // 见下方规则
compileAnalysis(def, cfg, kinds, ctx): AggregationQuery          // 同构映射；三处 FilterTree 编译为 FilterExpression
compileAnalysisTotals(def, cfg, kinds, ctx): AggregationQuery | null   // table.totals 为 true 时的无分组聚合，否则 null
projectAnalysis(def, cfg, result, totals?): AnalysisView          // 表格列与行；图表系列；合计行取自 totals，metric 卡片趋势模式的标题值亦取自 totals；行数恰好等于 cfg.limit 时记 atLimit
resultSchema(def, cfg): ResultSchema                     // 结果行校验依据

// dashboard
emptyDashboardConfig(): DashboardViewConfig                     // 无面板、无全局字段的完整初始配置
validateDashboard(cfg: DashboardViewConfig, scope: ViewInstance['scope'], refs: Map<string, PanelReference>, kinds: FieldKindRegistry): Issue[]   // 含 bindings 的字段 kind 兼容性与引用实例的可见范围；PanelReference = { instance; definition; fields }，fields 是该视图可触及的字段集，由 Engine 解析引用时计算（分析视图含其展开的元素字段），面板的 filter 与 bindings 都据此判断而不是只看根字段
mergeGlobalFilter(panel, dashboardFilter, bindings): FilterTree   // 把 Dashboard 的 filter 经 bindings 映射后 AND 合并到面板已应用筛选
```

## Dashboard 骨架与内容面板

- `validateDashboard` 先检查骨架：`fields`、`panels` 必须是数组，每个面板、字段、binding 与链接项必须是对象，Markdown 的 `content` 必须是字符串，否则以 `dashboard.shape.invalid` 在出错的路径上报 error 并跳过该节点，而不是让存储里的畸形配置以 `TypeError` 击穿 `open`；
- `DashboardRuntime` 读取 `panels` 与 `refresh.interval` 时同样把它们当作未信任输入，骨架未修好之前没有面板可加载，也没有计时器可开。`validateDashboard` 同时覆盖内容面板：Markdown 内容与链接数量有上限；
- `src` 与 `href` 只接受 http、https、mailto 与相对路径，其余产生 error 级 Issue。内容面板不进入 `mergeGlobalFilter`，`DashboardRuntime` 不为其创建子 runtime，它们只是布局中的静态项。（见 test/dashboard.test.ts「validateDashboard malformed configs」「validateDashboard content panels」「isSafeContentUrl」）URL 合法不代表资源可信，UI 层按 [ui/README.md#组件清单](ui/README.md#组件清单) 处理渲染安全。

## 定义准入

`validateDefinition(def, kinds): Issue[]` 是纯函数，但放在 `runtime/` 而不是某个内核里：它同时需要三个内核的 `validate*` 去校验系统视图，而内核之间不得互相引用。`ViewEngine` 在构造时对每份定义跑一次，结果经 `onIssue` 报出并留在 `definitionIssues(id)`；含 error 的定义仍在注册表里，但 `open`／`create`／`list` 一律以 `view.definition.invalid` 拒绝——比启动即崩溃温和，也比让它在用户打开视图时抛 `TypeError` 诚实。检查内容：

- 字段名必须匹配 Wow 的查询字段语法 `^@?[A-Za-z_][A-Za-z0-9_-]*(\.(?:@?[A-Za-z_][A-Za-z0-9_-]*|[0-9]+))*$`，根字段与 element 字段同样适用，否则编译期会抛 `TypeError` 而不是产生 Issue；
- 字段名在根字段内唯一，每个 element 的字段作用域内同样唯一；
- 配置中引用 element 字段一律写成 `${path}.${field}` 的完整路径，因此与同名根字段不会混淆，能力查找也有确定归属；
- 反过来，声明按自身作用域写相对名——`elements[].aggregations[].field` 写元素内的名字，路径由 `qualify` 唯一合成，它一律加前缀，不再放过「已经以路径开头」的名字（两种拼写都接受等于没有约定，还会让 `items.sku` 在元素根部和在一个恰好同名的嵌套对象里含义不同）；
- `elements[].aggregations[].field` 必须是该元素声明过的字段，与根字段的同名检查对齐；
- `cell` 必须是 `FieldCellId` 里的一个（`definition.field.cell-invalid`），`options[].tone` 必须是四档语气里的一档（`definition.field.tone-invalid`，Issue 落在那一项选项上而不是字段上，好让一个有八个状态的定义知道该去改哪一个）。两者都是闭合取值：`/ui` 没有渲染器注册表，没人分派的键不会报错，只会悄悄走默认渲染，于是一列声明成链接的 URL 仍旧是一串点不动的字——正是这类沉默让"引擎给得出的，定义才写得出"（D4）在这里也成立；
- `fieldGroups[]` 的 `id` 与 `label` 非空且 `id` 唯一（`definition.fieldGroup.invalid`／`duplicate`），其 `fields[]` 必须是已声明的根字段，且一个字段不得被两个分组同时列出（`definition.fieldGroup.field-unknown`／`field-duplicate`）；
- `views[].id` 在定义内唯一，否则按名称查找无法确定使用哪一份能力；
- `RecordCapability.layouts` 非空且 `rowKey` 指向已声明字段，`AnalysisCapability.fields[].field` 与 `elements[].path` 同样必须存在，使各 `default*Config` 对任何被接受的定义都能返回合法完整配置；
- 每个 `views[].config.kind` 必须与所属定义的能力匹配：`kind: 'data'` 只接受 `record`／`analysis` 且对应能力已声明，`kind: 'dashboard'` 只接受 `dashboard`，否则报 error（系统视图不可覆盖，不能交付一个只能待修复的只读视图）；
- 每个系统视图的配置还要通过对应的 `validate*`（Dashboard 系统视图在此只做本地结构校验，面板引用需要加载被引用实例与其定义，因此由 Engine 在注册或首次打开时用 `validateDashboard` 的完整入参复验，失败按该面板不可用处理）；
- `definition.id` 与每个 `views[].id` 都不得包含 `:`，否则合成的 `system:${definitionId}:${id}` 会歧义（`('a:b','c')` 与 `('a','b:c')` 撞车），报 error；
- `AnalysisCapability` 至少要能构造一个指标（`count` 为 true，或某个字段声明了非空 `functions`、`any`、`distinctCount` 或 `percentile`），否则该能力不可用，报 error。

`defaultAnalysisConfig` 按固定优先级取第一个可用者，因此总能返回合法配置：`COUNT` → 首个有非空 `functions` 的字段（取其首个函数）→ 首个 `distinctCount` 字段 → 首个 `percentile` 字段（`percentile: 95`）→ 首个 `any` 字段。分组可以为空，Wow 允许无分组聚合；此时默认配置不带 `sort`，因为 Wow 对无分组的 `sort` 与 `having` 都会抛错。（见 test/definition.test.ts「validateDefinition fields」「validateDefinition capabilities」「validateDefinition system views」「field groups」与 test/analysisValidate.test.ts「defaultAnalysisConfig」）

## 未信任的配置：骨架与预算

配置来自持久化端口，不可假设结构可信：

- `validateFilter` 以同一次迭代遍历检查骨架与预算——根必须是分组，子节点必须是分组或带字符串 `field`／`operator` 的叶子，否则以 `filter.node.invalid` 在该路径报 error 并跳过该节点，畸形条目同样计入 `maxFilterNodes`；
- 超出 `maxFilterDepth`（缺省 8）或 `maxFilterNodes`（缺省 256）立即报 error 并停止遍历，且是唯一的发现。骨架有问题时不再按 kind 校验；
- `compileFilter` 与 `describeFilter` 对同样的输入不抛异常而是跳过畸形条目，因此递归不会耗尽调用栈，`TypeError` 也不会击穿 `open`。`ViewConfigBase` 本身同理：非对象的配置报 `config.invalid`，`filter` 不是分组报 `config.filter.invalid`。`validateAnalysis` 先检查配置骨架（`groups`、`metrics`、`sort`、`table.columns`、`elements` 须为数组且成员为对象、别名为字符串，`table`、`chart` 须为对象），不满足只报 `analysis.config.malformed` 并停止；
- 随后对 NUMERIC／DISTINCT_COUNT／PERCENTILE 表达式、DERIVED 表达式与 `having` 树按同一份 `maxFilterDepth`／`maxFilterNodes` 以迭代遍历准入（与 Wow 的 `MAX_EXPRESSION_DEPTH`／`MAX_EXPRESSION_NODES` 同为 8／256；深度按每棵树计，节点数按同类树共享一份），超限报 `analysis.expression.too-deep`／`too-many-nodes` 或 `analysis.having.too-deep`／`too-many-nodes` 且不再递归。（见 test/filter.test.ts「malformed trees」与 test/analysisValidate.test.ts「a malformed skeleton」「expression budgets」）

## 已应用摘要是部件，不是句子

`describeFilter` 交出的每一项都拆成可被措辞目录重写的部件，而不是一句写死的英文：结果区最显眼的那一行以前由各个 kind 自己拼出来——原始操作符名、`is empty`、`on or before`——于是 `messages={zhCN}` 之下整页中文、唯独条件 badge 是英文。

- `FilterSummaryItem` 带 `path`、`unresolved`、`field`／`label`／`kind`／`cell`／`numberFormat`、`operator`（kind 认为操作符自己的词会说错时另带 `relation`：数组的 `IN` 问的是「这个数组含不含其中任一」，而 `is any of`／「是其中之一」说的是「这个值是不是其中之一」，两句话问的不是同一件事，所以 `array` 交出 `has-any`／`has-none`／`has-all`，界面按 `label.relation.*` 取词），以及 `value`（`cell` 也要跟着走，显示规则是 `cell ?? kind` 而不是 `kind`）；分组不带 `field` 与 `value`，带 `group`（自身操作符）与 `items`（组内各项）。持有谓词的条件（`ELEMENT_MATCH`）同样带 `items` 与 `group`——它是一条条件而不是一层嵌套，`isGroupItem` 以"有 `items` 且没有 `field`"区分两者；谓词的 `unresolved` 由内层汇总而来（与分组同理）：元素定义里被删掉的字段在里面读不出来，而条上只画外层这一个 badge，标记只能落在它身上；
- `value` 是封闭联合 `FilterSummaryValue`：`none`（操作符本身就是全部条件）、`blank`（kind 读不出这个值，只有字段名是真的）、`text`、`list`（原始值，另带按位对应的 `labels`——**只放定义真的命名过的**，没命名的位置留 `undefined`，一个都没有就整个不带：标签是「定义管这个值叫什么」，不是值本身的替身，把值 `String()` 一下冒充标签会盖过字段自己的格式，开放式数组里的金额就成了光秃秃的数字）、`range`、`relative`、`preset`。命名时段交成 `preset`，因此"下季度"到了界面仍是一个键而不是一句话；
- **同一个存下来的值可能是两种条件**，部件必须分开说，否则摘要描述的是一次没跑过的查询：
  - `relative` 带 `bound`。`BETWEEN` 问的是此刻到"七天前"之间那一段（`window`），`GTE`／`LTE` 比的是"七天前"那一刻本身（`instant`，`resolveDateTimeBound` 取的就是远端那条边）。它不是把操作符再说一遍：方向由 `direction` 说，比较朝哪边由 `operator` 说，而 `before`／`after` 这种拼法在某个 kind 提供 `LT` 的第一天就过时了；
  - `range` 的两条边都是必填。缺上界的绝对 `BETWEEN` 编译出来是 `filter.gte(from)`，所以那一项由 kind 自报 `operator: 'GTE'`、值交 `text`——一条边不是区间，而 `BETWEEN` 配一个日期在界面上读作"介于 1 月 1 日"，既不是区间也不是"从……起"。缺 `from` 的绝对值进不来：`isDateTimeFilterValue` 不认它，它走 `blank`；
- 持有谓词的 kind 交 `items` 前要先把 `describeFilter` 的**折叠**拆开：根是多子 `or` 或任意非空 `nor` 时，`describeFilter` 会折成一个自带该操作符的分组项——条件在栏里并排时没有别的地方能说清它们怎么合——而谓词自己已经在旁边说了一遍操作符，原样传下去就会读成"满足任一（满足任一 A、B）"，只有一条条件的 `nor` 更会读成双重否定。折叠项以"路径为空"认出：它代表根，不代表谁写下的分组；
- `text` 保留为**英文兜底**，逐字节保持原样：宿主可能直接读 `FilterSummaryItem.text`，且 `test/describeFilter.test.ts` 的用例按它断言。渲染成words 的是 `/ui`，见 [ui/README.md](ui/README.md#三态各有一处凭据)；
- 字段消失或 kind 抛异常的那一项 `unresolved` 为 true，`value` 为 `blank`，但 `operator` 仍在：值读不出来，问题本身还在。（见 test/describeFilter.test.ts「describeFilter」「describeFilter parts」）

## 一个分组内每个字段只出现一次

区间用一个 `BETWEEN`，多选用一个 `IN`，同一分组的直接叶子里同一字段出现第二次不是第二个问题而是笔误，`validateFilter` 在第二次出现处报 `filter.field.duplicate-in-group`；AND、OR、NOR 一律如此，要对同一字段叠加不同条件请建子分组。`ELEMENT_MATCH` 的谓词是独立的树，单独计。编辑器的字段选择器据此只列出本分组尚未使用的字段（`fieldsFor(parent)`）。（见 test/filter.test.ts「validateFilter」）

## 未填写的条件不是错误

用户选好字段、还没说要比什么，这是编辑器的正常中间态：这样的叶子不按 kind 校验，也不进入编译，因此加一行条件既不收窄结果也不阻塞 apply。`FieldKind.emptyValue` 因此返回"尚未填写"而不是某个可用默认值——给数字播种 `0`、给日期播种"今天"，会在用户还没表达任何意图时就把列表筛掉。

- 判断由 `isBlankLeafValue` 统一做：缺省认 `null`、`''` 与空数组；
- 值形状自成一体的 kind 用可选的 `isBlank({ value, operator, field, kinds })` 自述，它**替代**而不是补充缺省规则，所以同时也从 `''` 或 `[]` 起步的 kind（metadata 各 kind）必须一并认出这些形状，纯空白字符串同样算未填写。持有树的 kind（`elementMatch`）的空白就是其谓词的空白：谓词里没有任何有效叶子——没有条件，或每条条件本身仍未填写——即为空白，由 `isBlankFilter` 按与外层同一条规则判定；
- 字段未知、kind 未注册或操作符不受支持的叶子不算空白，否则会藏掉 `validateFilter` 该报的问题。不需要输入的操作符（`IS_NULL` 一类，kind 把其编辑器声明为 `none`）永远不算未填写，否则条件会被当成空值丢掉。（见 test/filter.test.ts「unfinished conditions」）

## FieldKind 与时钟

`FieldKindRegistry` 是 filter 的核心扩展点，见 [extension.md](extension.md)。相对时间条件在 `compile*` 中依据注入的 `ctx.now` 求值，纯内核不读系统时钟。

## 日期条件：一个字符串算哪一刻

绝对日期的两条边是**存下来的字符串**，`resolveDateTimeRange`／`resolveDateTimeBound`（`filter/time.ts`）按下面三条读它，两条边各带一个 `RangeEdge`（`start`／`end`）——同一个字符串在两条边上可能是两个时刻，只有问的那一方知道是哪一边：

- **自带偏移的原样保留**：`...Z` 或 `...+09:00` 已经指定了唯一的一刻，再套一次时区就是把它挪走；
- **不带偏移的是挂钟时间**，按条件自己的 `timeZone`、没有则按 `ctx.timeZone` 解析。这是 `AbsoluteDateTimeValue.timeZone` 存在的理由，它一度被存下、被校验、然后被忽略，于是只差时区的两条条件编译成同一个查询；
- **只写了日子的那一条按区间读（D17-1）**：`2026-01-31` 作为起点是 `00:00:00.000`，作为终点是 `23:59:59.999`。`BETWEEN 01-01..01-31` 因此走完 31 号那一整天，`LTE 01-31` 不会把它自己命名的那一天排除在外；写了时刻的（`2026-01-31T15:30:00`）两条边上都是那一刻本身。

带时刻的字段（`withTime`，即 `datetime` kind）在界面上于日历旁给出时刻输入，**未填时刻存下来的就是只有日子的那个字符串**，于是走上面第三条；旧配置里只存了日子的值读法完全相同，不另作一份"保持原状"的读法。相对日期与预设不受这条影响：它们本来就是按 `ctx.now` 求出的一段区间，`BETWEEN` 取整段，`GTE`／`LTE` 取远端那一刻（见[已应用摘要](#已应用摘要是部件不是句子)）。摘要侧同样分得清两者：`describeFilter` 交出的仍是存下来的字符串，带时刻的带着时刻，`/ui` 据此决定显示到日还是显示到秒。（见 test/filterTime.test.ts「a calendar day as a bound」「a bound with a time of day」）

## Record 内核的规则

`validateRecord` 先检查骨架：`sort` 必须是数组且每项是带字符串 `field` 的对象，`table.columns` 同样，`card` 必须是对象且 `title` 为字符串、`fields` 为字符串数组，`summaries` 若存在必须是数组且每项带字符串 `field` 与 `fn`，否则以 `record.sort.invalid`／`record.table.invalid`／`record.card.invalid`／`record.summaries.invalid` 在出错路径报 error，共享配置的 Issue 照常保留，其余规则不再运行。骨架完好时的规则：

- `layout` 在 `RecordCapability.layouts` 之内；
- `table.columns`、`card.title`／`fields`／`image` 引用的字段都必须存在且不是无字段种类（`record.field.not-a-column`）；
- `table.columns[].field`、`sort[].field` 与 `summaries[]` 的字段-函数对都不得重复，在第二次出现处报 `record.column.duplicate`／`record.sort.duplicate`／`record.summary.duplicate`；
- `pageSize` 为不超过 `RuntimeLimits.maxPageSize` 的正整数；
- `sort[].field` 必须存在且 `sortable` 为 true，游标模式下 `sort.length` 不超过 Wow 的 `MAX_CURSOR_SORT_FIELDS`（32）；
- 每个 `summaries[]` 的函数必须出现在该字段的 `summary` 集合中。（见 test/record.test.ts「validateRecord」「a record config that lost its shape」）

## 导出序列化

`serializeCsv(rows, columns, format)`（`record/export.ts`）是纯函数：表头是给过来的列的标签、顺序就是给过来的顺序（也就是投影后的可见列），每格的值由 `recordValue` 按 Wow 查询路径取出，再交给**调用方注入的** `format(value, column)` 读成文本。

- **格式化注入而不是在这里决定**：枚举的标签、时间的时区、数字的格式都是 `/ui` 的答案（`cellText`），内核没有目录、没有语言、也没有渲染器，读法写在这里就等于第二份读法；
- 按 RFC 4180 转义：含 `,`、`"`、`\r`、`\n` 的字段加引号、内部引号翻倍，记录以 `CRLF` 结尾（含最后一条），前面加 UTF-8 BOM（`CSV_BOM`）——没有它 Excel 会按机器的 ANSI 代码页读，中文全成乱码；
- **值原样写，不改写**：以 `=` 开头的值是表格软件可能会去求值的东西，但一个悄悄加上单引号的导出交出的文件，内容已经不是屏幕上说的那份了；
- 零行时只有表头。`format` 由宿主提供，因此返回值再被强制成字符串：`undefined` 写成 `undefined` 这个词，是表格从没显示过的值。（见 test/recordExport.test.ts）

## Analysis 内核的规则

`validateAnalysis` 的规则：

- 别名在 groups 与 metrics 之间唯一，且必须是单段（不含 `.`）、不以保留前缀 `__wow` 开头，并且必须匹配 Wow 的查询字段单段语法（否则报 `analysis.alias.invalid`），与 Wow 的 `aggregationAlias` 一致；
- `sort` 只能引用已存在的 group 或 metric 别名，`having` 只能引用非 `ANY` 的 metric 别名（Wow 协议不支持）；
- 二者都要求至少一个分组，无分组时分别报 `analysis.sort.requires-group` 与 `analysis.having.requires-group`，与 Wow `aggregation.query()` 的 `validateSort`／`validateHaving` 一致，让存储的配置在准入阶段而不是服务端被拒；
- `DERIVED` 只能引用在它之前声明的非 `ANY` metric 别名，按 `metrics` 顺序维护可引用集合，前向引用与环报 error；
- `percentile` 在开区间 (0, 100)，与 Wow 的 `aggregation.percentile` 一致，`100` 报 error；
- `HISTOGRAM` 的 `interval` 必须是大于 0 的有限数，与 `aggregation.histogram` 一致；
- 表达式中的每个 `CONSTANT.value` 必须有限（在预算准入之后递归检查普通表达式与派生表达式），与 `aggregation.constant` 一致；
- `BINARY` 的 `DIVIDE` 右侧为常量 0 报 error；
- `elements[].path` 必须在能力中声明，展开后可用字段为根字段加元素字段；
- `any`、`distinctCount`、`percentile`、`expressions`、`having` 等未在能力中声明却被使用报 error；
- 每个 group 的 `type` 必须在该字段的 `groups` 中，`DATE_HISTOGRAM.unit` 必须在其 `dateUnits` 中，`NUMERIC.function` 必须在该字段的 `functions` 中，能力未声明即报 error；
- `limit` 必须是不超过 `RuntimeLimits.maxAnalysisRows` 的正整数，`groups`、`metrics`、`elements`、`sort` 的数量与 `limit` 始终受 Wow `AGGREGATION_LIMITS` 约束，`AnalysisCapability.limits` 只能进一步收紧（取更小者）；
- `sort[].alias` 不得重复（`analysis.sort.duplicate`），`DATE_HISTOGRAM.dense` 只能用于唯一分组（`analysis.group.dense-not-alone`）；
- `metrics` 不能为空，报 `analysis.metrics.empty`，与 Wow `metrics must not be empty.` 一致；
- `metrics[].type` 不在六种之内报 `analysis.metric.type-unknown`，`compileMetric` 对此抛错而不发出空洞（定义准入另行检查这些上限与 `defaultLimit` 本身是正整数且 `defaultLimit` 不超过 `maxLimit`）；
- `TERMS.missingKey` 与 `DATE_HISTOGRAM.timeZone` 若存在则不能为空白字符串，与 Wow 的 `aggregation.terms`／`dateHistogram` 一致；
- `table.columns[].alias` 必须是当前 groups 或 metrics 的别名且不重复。

### 图表规则

- `chart[族(type)]` 必须存在；
- `x`、`splitBy`、`category`、heatmap 的 `x`／`y`、`funnel.group.category` 必须是分组别名，`series[].metric`、`value`、scatter 的 `x`／`y`／`size`、`metric`、`compare.metric`、`funnel.metrics.items[].metric` 必须是指标别名；
- `splitBy` 不等于 `x`，且存在时 `series` 恰有一个指标；
- `combo` 的每个系列必须有 `type`；
- heatmap 的 `x`、`y` 不同，scatter 的 `x`、`y` 不同；
- `maxSlices` 必须是不小于 2 的整数（NaN 与小数会让全部分类并入"其他"），且只能用于可加指标（`COUNT` 或 `SUM` 的 `NUMERIC`）：`AVG`、`MIN`／`MAX`、`DISTINCT_COUNT`、百分位无法由各分类结果推出合并值，报 error；
- `referenceLines` 引用的轴必须有系列；
- **图表必须消费全部分组别名**（cartesian 用 `x` 加可选 `splitBy`，pie 用 `category`，heatmap 用 `x`／`y`，scatter 用 `category`，group 漏斗用 `category`，metric 卡片要求无分组或仅 `trend.x`），否则结果里同一坐标会有多行，而 AVG、百分位、DISTINCT_COUNT 无法在投影层安全再聚合，报 error；
- 漏斗至少两个阶段，`metrics` 形态要求分组为空，`group` 形态的 `order` 无重复；
- `metric` 无 `trend` 时要求分组为空，有 `trend` 时要求恰有一个 DATE_HISTOGRAM 分组且别名等于 `trend.x`，且 `metric` 与 `compare.metric` 必须是可加指标（与 `maxSlices` 同一判据），否则报 `chart.metric.trend-not-additive`。

### compileAnalysis 与 projectAnalysis

`compileAnalysis` 因同构而退化为映射：

- 查询级、指标级、元素级三处 `FilterTree` 分别编译为 `FilterExpression`，元素级以元素字段为作用域；
- 未声明时区的 DATE_HISTOGRAM 补上 `ctx.timeZone`，否则 Wow 按 UTC 切桶，东八区的"一天"从早上八点算起；
- `projectAnalysis` 的结果列为全部 group 别名加全部 metric 别名，`DERIVED` 也是普通列；
- 分组列与 `ANY` 列带上字段的 `kind`、`cell`、`options`，DATE_HISTOGRAM 列另带 `dateUnit` 与所声明的 `timeZone`，供界面按字段显示（见 [ui/README.md#值按字段显示](ui/README.md#值按字段显示)），其余指标是算出的数，不带；
- `columns` 按 `table.columns` 挑选，`schema` 以同样的描述覆盖结果里的每个别名——表格可以只显示计数，而图表仍按表格没显示的分组画，类目要经 `schema` 取名；
- 合计行来自 `compileAnalysisTotals` 的独立结果，因此 `AVG`、`DISTINCT_COUNT`、百分位等不可加指标也正确；
- 该查询与主查询共享同一调度预算，失败只使合计行不可用，不影响主结果。图表所需的派生整形也在此完成：`splitBy` 透视、饼图"其他"合并、漏斗累计与转化率、热力图矩阵、metric 卡片的比较值。metric 卡片带 `trend` 时的标题值取自合计行（`projectAnalysis` 的 `totals`），无合计行时按分桶求和；
- `compare` 与 `target` 在有无 `trend` 时同样生效。
- **结果行数恰好等于 `limit` 时记下 `AnalysisView.atLimit`**。聚合回答的是至多 `limit` 行，并不告诉调用方它省略了多少，因此"正好填满上限"是唯一可用的信号，而它本身是二义的：刚好这么多组，和被截到这么多组，长得一模一样。所以它只被报成"可能被截断"（运行时的 `analysis.result.at-limit`，见 [runtime.md#规则](runtime.md#规则)），从不被报成事实——但一张看起来完整、每个占比与扇区却都是按前缀算出来的表，是读者自己查不出来的那一种错。没有分组（无分组聚合按定义只答一行，`limit: 1` 于是被每一次成功的查询填满，而没有任何分组可以被截掉）、行数少于上限、配置里没有可用上限（缺失、非正整数、非有限数——准入会拒绝这些，但投影是导出的，宿主可能拿没被准入的配置来投影），以及数据源答得比上限还多（它根本没把上限当天花板，行数因此什么也说明不了）四种情况都不记：不知道被截掉了什么，与知道没被截掉，不是同一回事。（见 test/resultIssues.test.tsx「projectAnalysis row limit」）

## Dashboard 内核的规则

`validateDashboard` 的规则：

- `cfg.fields[].name` 非空、符合字段语法且唯一；
- `panels` 数量不超过 `RuntimeLimits.maxDashboardPanels`，该检查先于创建任何子 runtime；
- `panels[].id` 非空且全局唯一，重复或为空报 error（面板 id 是运行时查找、布局 key 与错误归属的依据）；
- `layout` 的 `x`、`y` 为非负有限整数，`w`、`h` 为正有限整数，且 `x + w` 不超过栅格列数，否则面板会在适配层消失或重叠；
- `bindings[].globalField` 必须在 `cfg.fields` 中，`bindings[].panelField` 必须在被引用实例的定义中，且两者 kind 兼容；
- 全局筛选按 `cfg.fields` 与传入的 `kinds` 走 `validateFilter`，因此自定义 kind 与值形状同样受检；
- 同一面板内 `bindings[].globalField` 不能重复（一个筛选叶子只能替换成一个目标字段，一对多展开的布尔语义未定义）；
- **每个数据面板必须绑定全局筛选树实际引用的全部字段**，否则部分映射无法保持布尔语义（`region = CN OR product = X` 丢掉一支会错误收窄，视为真会抹掉整个条件），缺绑定报 error；
- 映射后的树还要以被引用定义的字段与 `kinds` 再跑一次 `validateFilter`，因为目标字段可能限制了操作符或候选，kind 相同不等于可接受同一条件；
- 该次校验同时重新核对深度与节点预算，两棵各自合规的树 AND 合并后仍可能超限，超限记为该面板的 error 而不进入编译；
- 被引用实例必须是 Record 或 Analysis；
- **被引用实例的可见范围必须覆盖 Dashboard 自身的范围**：`personal` Dashboard 可以引用任何可读实例，`shared` 或 `system` Dashboard 只能引用 `shared` 或 `system` 实例，否则产生 error 级 Issue，UI 提示先把被引用视图另存为共享。打开时若某个被引用实例不可读（已删除或无权限），只有该面板显示"不可访问"，其余面板照常工作。内容面板规则见 [Dashboard 骨架与内容面板](#dashboard-骨架与内容面板)。
