# 纯内核

四个目录都是同步纯函数，签名统一为"定义 + 配置 进，结果 或 Issue 出"。

纯内核报出的 `Issue` 与它们校验的配置类型见 [model.md](model.md) 与 [model-shapes.md](model-shapes.md)。

## 函数签名

```ts
// filter
validateFilter(fields: FieldDefinition[], tree: FilterTree, kinds: FieldKindRegistry): Issue[]   // 取字段表而非整份定义，Dashboard 可传 cfg.fields
isSimpleTree(tree: FilterTree): boolean                  // filterMode 'simple' 的准入判断：一个 AND 组，子节点是叶子或取反
isNegation(node: unknown): boolean                       // 取反：只含一片叶子的 nor 分组（D18-7）
negateAt(tree, path: FilterPath): FilterTree              // 给 path 处的叶子包一层 nor，已包着的则拆掉
conditionOf(node: unknown): FilterLeaf | null            // 这个节点问的那一条：叶子本身，或取反里那片叶子；不是单条（多子节点分组、坏节点）则 null
conditions(group: unknown, at?: FilterPath): GroupCondition[]   // 一个分组里读作单条的子节点，依次给 { leaf, index, path, negated }；path 是叶子自己的（取反的在包装里一层），at 传分组自己的路径即得整棵树的路径
removeConditionAt(tree, path: FilterPath): FilterTree     // 按叶子路径删这一条，取反的连它那层 nor 一起删；path 指别的（嵌套分组、独立叶子）就删它自己
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
defaultAnalysisConfig(def, limits?: RuntimeLimits): AnalysisViewConfig   // 按固定优先级从已声明能力挑选指标；有可分组字段时取其一并按指标降序排序，否则分组为空且 `sort` 为空（无分组聚合只有一行，Wow 拒绝对其排序）；`limit` 取能力的 `defaultLimit`、能力的 `maxLimit` 与 `limits.maxAnalysisRows` 三者最小值，缺省 `DEFAULT_RUNTIME_LIMITS`
validateAnalysis(def, cfg: AnalysisViewConfig, kinds): Issue[]   // 见下方规则
compileAnalysis(def, cfg, kinds, ctx): AggregationQuery          // 同构映射；三处 FilterTree 编译为 FilterExpression
compileAnalysisTotals(def, cfg, kinds, ctx): AggregationQuery | null   // table.totals 为 true 时的无分组聚合，否则 null
projectAnalysis(def, cfg, result, totals?): AnalysisView          // 表格列与行；图表系列；合计行取自 totals，metric 卡片趋势模式的标题值亦取自 totals；多回来的那一行（探针）丢掉并记 truncated，探不成时退回 atLimit
fitChartSlots(chart, groups, metrics): ChartSpec          // 当前图型的家族子对象，按现有维度与指标装槽；用户选过且仍有效的槽保留
metricFormat(metric, field?): NumberFormat | undefined    // 一个聚合值怎么打印（与字段自己的值怎么打印是两回事）

// dashboard
emptyDashboardConfig(): DashboardViewConfig                     // 无面板、无全局字段的完整初始配置
validateDashboard(cfg: DashboardViewConfig, scope: ViewInstance['scope'], refs: Map<string, PanelReference>, kinds: FieldKindRegistry): Issue[]   // 含 bindings 的字段 kind 兼容性与引用实例的可见范围；PanelReference = { instance; definition; fields }，fields 就是被引用定义自己的字段，由 Engine 解析引用时给出：面板的 filter 是查询根，全局筛选也只能映射到根字段上，分析视图即便展开了 elements 也一样（元素里的事从根上只能由 elementMatch 条件去问，而那是一个根字段）
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
- 配置中引用 element 字段一律写成从根起的完整路径（`state.orders.lines.sku`），因此与同名根字段不会混淆，能力查找也有确定归属；发给 Wow 时再按作用域剥成相对名；
- 反过来，声明按自身作用域写相对名——`elements[].aggregations[].field` 写元素内的名字，路径由 `qualify` 唯一合成，它一律加前缀，不再放过「已经以路径开头」的名字（两种拼写都接受等于没有约定，还会让 `items.sku` 在元素根部和在一个恰好同名的嵌套对象里含义不同）；
- `elements[].aggregations[].field` 必须是该元素声明过的字段，与根字段的同名检查对齐；
- `cell` 必须是 `FieldCellId` 里的一个（`definition.field.cell-invalid`），`options[].tone` 必须是四档语气里的一档（`definition.field.tone-invalid`，Issue 落在那一项选项上而不是字段上，好让一个有八个状态的定义知道该去改哪一个）。两者都是闭合取值：`/ui` 没有渲染器注册表，没人分派的键不会报错，只会悄悄走默认渲染，于是一列声明成链接的 URL 仍旧是一串点不动的字——正是这类沉默让"引擎给得出的，定义才写得出"（D4）在这里也成立；
- `fieldGroups[]` 的 `id` 与 `label` 非空且 `id` 唯一（`definition.fieldGroup.invalid`／`duplicate`），其 `fields[]` 必须是已声明的根字段，且一个字段不得被两个分组同时列出（`definition.fieldGroup.field-unknown`／`field-duplicate`）；
- `views[].id` 在定义内唯一，否则按名称查找无法确定使用哪一份能力；
- `RecordCapability.layouts` 非空且 `rowKey` 指向已声明字段，`AnalysisCapability.fields[].field` 同样必须存在，使各 `default*Config` 对任何被接受的定义都能返回合法完整配置；
- `AnalysisCapability.elements` 是一条链，按链自根向内走：第一层是根上一个声明了 `elements` 的字段，第 i 层必须是第 i−1 层元素里声明了 `elements` 的字段，否则报 `definition.analysis.element-undeclared`——并列的两个根数组就是在这里被拒的，那是一条断链而不是两条链；层数不得超过 Wow 的 `AGGREGATION_LIMITS.MAX_ELEMENTS`（5），否则报 `definition.analysis.elements-too-many`：再深的一层任何配置都展开不到；
- 每个 `views[].config.kind` 必须与所属定义的能力匹配：`kind: 'data'` 只接受 `record`／`analysis` 且对应能力已声明，`kind: 'dashboard'` 只接受 `dashboard`，否则报 error（系统视图不可覆盖，不能交付一个只能待修复的只读视图）；
- 每个系统视图的配置还要通过对应的 `validate*`（Dashboard 系统视图在此只做本地结构校验，面板引用需要加载被引用实例与其定义，因此由 Engine 在注册或首次打开时用 `validateDashboard` 的完整入参复验，失败按该面板不可用处理）；
- `definition.id` 与每个 `views[].id` 都不得包含 `:`，否则合成的 `system:${definitionId}:${id}` 会歧义（`('a:b','c')` 与 `('a','b:c')` 撞车），报 error；
- `AnalysisCapability` 至少要能构造一个指标（`count` 为 true，或某个字段声明了非空 `functions`、`any`、`distinctCount` 或 `percentile`），否则该能力不可用，报 error。

`defaultAnalysisConfig` 按固定优先级取第一个可用者，因此总能返回合法配置：`COUNT` → 首个有非空 `functions` 的字段（取其首个函数）→ 首个 `distinctCount` 字段 → 首个 `percentile` 字段（`percentile: 95`）→ 首个 `any` 字段。分组可以为空，Wow 允许无分组聚合；此时默认配置不带 `sort`，因为 Wow 对无分组的 `sort` 与 `having` 都会抛错。默认的 TERMS 维度若落在单值字符串字段上，带一个 `missingKey`（`DEFAULT_MISSING_KEY`，`'(empty)'`）：**不写它，Wow 把没有该值的记录整条丢出结果**，屏幕上没有任何地方会说这件事。它是存下来、发出去、再作为桶键回来的**数据**，所以是一个固定字符串而不是一句译文；那一组在界面上叫什么由 `/ui` 决定（批 4）。这个默认维度与百分位的 `DEFAULT_PERCENTILE`（95）都出自下面那两个唯一的构造器，规则只有一处。（见 test/definition.test.ts「validateDefinition fields」「validateDefinition capabilities」「validateDefinition system views」「field groups」与 test/analysisValidate.test.ts「defaultAnalysisConfig」）

`analysis/defaults.ts` 里还有几样东西是「新建一条」这件事的公共答案，所以它们在内核而不在卡片里：

- **一个字段怎样变成维度，只有一个构造器**：`groupOfType(facts, type, alias, unit?)`。新配置的第一个维度、追问菜单的「按…拆分」（`drill.ts` 的 `groupFor`）、托盘上挑一个字段（`ui/analysis/editing.ts` 的 `defaultGroup`）、卡片上换一种分组方式，都从它造出来，各自只决定**哪种类型**和**哪个别名**；托盘与拆分选的类型都是定义给这个字段列的**第一种**——声明的顺序是研发在说这个字段首先该怎么看，追问没有理由另有一套偏好（test/analysisBuilders.test.ts「takes the type a definition lists first, in the tray and in a split alike」）。它只要构造所需的事实 `GroupFacts`——字段名、`missingKey`（单值字符串才担得起哨兵桶，`isSingleStringField`）、能力给的日期单位——于是定义里的字段（`groupFacts(field, dateUnits, kind?)` 读出来）与编辑器的 `AnalysisFieldOption` 是同一种输入。每个默认值只说一次：按值带哨兵桶（`DEFAULT_MISSING_KEY`），按日期取调用处推荐的单位、否则能力给的第一个、再否则 `DAY`，按区间宽度为 1；
- **一个字段怎样变成指标，也只有一个构造器**：`metricOfSummary(facts, choice, alias)`。`choice` 是一个 `SummaryChoice`——数值指标的函数，或另外三种量一个字段的指标类型本身；`summaryChoices(facts)` 是一个字段给得出的那几种，`summaryOf(metric)` 反过来读。`firstMetric`、托盘的 `defaultMetric` 与卡片换汇总方式都走它，百分位统一是 `DEFAULT_PERCENTILE`；
- `firstMetric(count, fields)` 就是上面那条优先级本身，**从一组聚合能力算起**。新建一份配置问的是根能力，而展开之后 `withElements` 问的是新单位那一层的能力——同一条规则，两处调用；
- `groupableFields(fields, groups)` 是「还能按哪些字段加维度」：能分组、且 `groups` 还没按它分过的字段，保持原顺序。托盘的「添加维度」读草稿，追问菜单的「按…拆分」读跑出这份结果的配置——同一个函数，两份输入；
- `freeAlias(base, taken)` 是「一个没人在用的别名」：`base` 的词干加上第一个空出来的编号。按行数编号在删掉一行之后立刻撞车（删一加二，两行都叫 `amount_2`，React 当成同一个 key、准入报重复别名），第一个空号则怎么加怎么删都不撞。Wow 的别名是单段的，所以字段路径先拼成一个词；词干末尾已有的编号先剥掉，于是 `amount_2` 的复制件是 `amount_<下一个>` 而不是 `amount_2_1`；
- `metricWithCondition(metric, taken)` 是「复制并加条件」（D20 屏 H）：拿一个空别名、**不带显示名**（两张卡叫同一个名字正是显示名要消解的歧义）、汇总方式照旧，外加一个空条件等着填。派生指标没有 `filter`，所以返回 `undefined`，界面据此不画那一项。（见 test/metricCondition.test.tsx「copies a metric with an empty condition to fill in」「gives a derived metric no funnel at all」）

构造器的规则由测试钉住：两种输入造出同一个维度、每种类型的默认值、哨兵桶只给担得起的字段、每种汇总造得出也读得回、第一个指标出自同一个构造器、`groupableFields` 的筛法（见 test/analysisBuilders.test.ts「groupOfType」「the summaries a field offers」「groupableFields」）；托盘与追问菜单给出的是同一份字段（见 test/drillMenu.test.tsx「offers to split by exactly the fields the tray would add a dimension on」）；`groupFor` 造出的每一种维度，拿一行结果的桶键交给 `drillConditions`，得到的条件**恰好**选中这个桶的记录、不多不少（见 test/analysisDrill.test.ts「a dimension groupFor builds, drilled back」）。

**从模型推出来的清单**：量一个字段的指标类型是 `model/analysis.ts` 的 `FIELD_METRIC_TYPES`（`NUMERIC`／`DISTINCT_COUNT`／`PERCENTILE`／`ANY`，以 `satisfies` 对着指标联合声明，与 `ANALYSIS_PRESENTATION_MEMBERS` 同法），`SummaryChoice` 与 `summaryChoices` 的顺序都由它推出，不再手列一遍；「只保留」的比较 `HavingOperator` 是 `Extract<AnalysisHavingExpression, { type: 'CONDITION' }>['operator']`，`HAVING_OPERATORS` 与公式的 `EXPRESSION_OPERATORS` 都从一个以操作符类型为键的 `Record` 取键——Wow 多一个操作符，这里先编译不过，而不是托盘悄悄不提供它。（见 test/analysisBuilders.test.ts「the summaries a field offers」「the operator lists」）

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
- **`elementMatch` 的谓词编译时字段从数组内部起名**：Wow 读 `ELEMENT_MATCH` 谓词里的字段是相对元素的（`sku`，不是 `items.sku`——那会被当成 `items.items.sku` 去找），而配置从根写全名，所以编译前把谓词按容器前缀改写一遍，再对元素自己的字段声明编译；元素的元素由它自己那次编译再剥一层。校验、描述与空白判定仍按全名，那是条件面向用户的写法。（见 test/elementMatch.test.ts「compiles a predicate onto the array, its fields named from inside it」）
- 字段未知、kind 未注册或操作符不受支持的叶子不算空白，否则会藏掉 `validateFilter` 该报的问题。不需要输入的操作符（`IS_NULL` 一类，kind 把其编辑器声明为 `none`）永远不算未填写，否则条件会被当成空值丢掉。（见 test/filter.test.ts「unfinished conditions」）

## FieldKind 与时钟

`FieldKindRegistry` 是 filter 的核心扩展点，见 [extension.md](extension.md)。相对时间条件在 `compile*` 中依据注入的 `ctx.now` 求值，纯内核不读系统时钟。

## 日期条件：一个字符串算哪一刻

绝对日期的两条边是**存下来的字符串**，`resolveDateTimeRange`／`resolveDateTimeBound`（`filter/time.ts`）按下面三条读它，两条边各带一个 `RangeEdge`（`start`／`end`）——同一个字符串在两条边上可能是两个时刻，只有问的那一方知道是哪一边：

- **自带偏移的原样保留**：`...Z` 或 `...+09:00` 已经指定了唯一的一刻，再套一次时区就是把它挪走；
- **不带偏移的是挂钟时间**，按条件自己的 `timeZone`、没有则按 `ctx.timeZone` 解析。这是 `AbsoluteDateTimeValue.timeZone` 存在的理由，它一度被存下、被校验、然后被忽略，于是只差时区的两条条件编译成同一个查询；
- **只写了日子的那一条按区间读（D17-1）**：`2026-01-31` 作为起点是 `00:00:00.000`，作为终点是 `23:59:59.999`。`BETWEEN 01-01..01-31` 因此走完 31 号那一整天，`LTE 01-31` 不会把它自己命名的那一天排除在外；写了时刻的（`2026-01-31T15:30:00`）两条边上都是那一刻本身。

带时刻的字段（`withTime`，即 `datetime` kind）在界面上于日历旁给出时刻输入，**未填时刻存下来的就是只有日子的那个字符串**，于是走上面第三条；旧配置里只存了日子的值读法完全相同，不另作一份"保持原状"的读法。相对日期与预设不受这条影响：它们本来就是按 `ctx.now` 求出的一段区间，`BETWEEN` 取整段，`GTE`／`LTE` 取远端那一刻（见[已应用摘要](#已应用摘要是部件不是句子)）。摘要侧同样分得清两者：`describeFilter` 交出的仍是存下来的字符串，带时刻的带着时刻，`/ui` 据此决定显示到日还是显示到秒。（见 test/filterTime.test.ts「a calendar day as a bound」「a bound with a time of day」）

## 软删除口径（D17-2）

`deletion` kind（`filter/kinds/deletion.ts`）编译成 Wow 的 `DELETION` 根筛选：`ACTIVE`／`DELETED`／`ALL` 三个值原样成为 `state`，别的值以 `filter.value.expected-deletion-state` 拒绝；空值走「未填写的条件不是错误」那条路——`compileFilter` 丢掉它，查询里没有 `DELETION`，源按自己的缺省只回未删除的记录。三种口径因此都有确定的查询：缺省与显式 `ACTIVE` 是同一个查询，只差前者是「没人写」。`impliedDeletion(fields, trees, kinds)` 回答「哪些声明了的 deletion 字段在这些树里都没被作答」，给已应用条说缺省口径用；它读的树与 `describeFilter` 描述的是同一批（跑出结果的那份自有条件、宿主的作用域）。作为根筛选，它在元素谓词与元素筛选里被 Wow 拒绝，与其它元数据种类相同。（见 test/deletionKind.test.ts）

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

**分桶的反向映射**（`analysis/drill.ts`，K1／K6）：`drillConditions(config, fields, kinds, row, ctx)` 把结果的一行还原成选出它背后记录的条件——TERMS 是一个值（kind 有 `EQ` 用 `EQ`，否则用它的「其中之一」：枚举 `IN [v]`、引用 `IN { items: [{ id, label }] }`；空桶与 `missingKey` 哨兵一律 `IS_NULL`），HISTOGRAM 是半开区间 `GTE key` + `LT key+interval`，DATE_HISTOGRAM 是 `bucketRange(unit, start, timeZone)` 给出的 `[start, 下一桶起)` 写成 `BETWEEN`、上界减 1ms（给日期加 `LT` 会波及所有日期编辑器；一毫秒比一个控件便宜）。桶在分组自己的 `timeZone`、没有则 `ctx.timeZone` 里推进：日历单位按该时区的挂钟走（跨夏令时的那一天按日历长），时钟单位按长度走。展开了 elements 的分析交不出条件（分组是最内层元素的字段，记录视图看的是根文档）——返回 `null`，`canDrill` 因此为假；这是线索。`drillFilter(applied, conditions)`：分析视图已应用的树是简单树就平铺成一个「都满足」组（记录视图因此以简单模式打开），否则嵌套。内核只交出条件（K6），开什么视图、带什么列由 `react/useWorkbench.ts` 与 `defaultRecordConfig` 合成，因为 `analysis` 与 `record` 互不引用。

留在分析视图里的那两问（D20 追问）也在这里，交出的同样只是一份配置补丁，由运行时 `edit` 后 `apply`：`focusOn(config, conditions)` 把这一行的条件加进范围（`drillFilter` 的平铺／嵌套规则，因此简单树仍是简单模式，已经进阶的才留在进阶）；`splitBy(config, conditions, group)` 在此之上把维度整个换成 `group`——同一个问题换一个维度问，不是多分一层——并且把**指名旧别名的东西一起放掉**：`sort` 清空、`table.columns` 清空（表的其余设置留着）、`chart` 过一遍 `fitChartSlots` 重新配槽，否则那份补丁一跑就会被 `validateAnalysis` 以别名未知／分组未消费顶回来。`groupFor(field, offered, kind)` 是"这个字段当维度长什么样"：能按值就 `TERMS`，否则 `DATE_HISTOGRAM`，再不然 `HISTOGRAM`——它只挑类型，形状（哨兵桶、日期单位、区间宽度）出自唯一的构造器 `groupOfType`；别名用的是 `aliasOf(field, 'group')`，与新配置第一个维度的别名同一个写法，所以拆出来的视图存得下去。（见 test/analysisDrill.test.ts「bucketRange」「drillConditions」「drillFilter」「focusOn」「splitBy」「groupFor」）

规则按族分文件，`analysis/validate.ts` 只说它们跑的顺序：骨架 `validateShape.ts`、元素域 `validateElements.ts`、分组 `validateGroups.ts`、指标 `validateMetrics.ts`、别名 `validateAliases.ts`、having `validateHaving.ts`、排序与表列 `validateSort.ts`、上限 `validateLimits.ts`、图表 `validateChart.ts`；被它们共用的是预算 `budget.ts`（深度与节点数）、表达式走查 `expressions.ts` 与查询内筛选 `queryFilter.ts`（指标位与元素位）。加一条分析规则，先看它属于哪一族。

`validateAnalysis` 的规则：

- 别名在 groups 与 metrics 之间唯一，且必须是单段（不含 `.`）、不以保留前缀 `__wow` 开头，并且必须匹配 Wow 的查询字段单段语法（否则报 `analysis.alias.invalid`），与 Wow 的 `aggregationAlias` 一致；
- `sort` 只能引用已存在的 group 或 metric 别名，`having` 只能引用非 `ANY` 的 metric 别名（Wow 协议不支持）；
- 二者都要求至少一个分组，无分组时分别报 `analysis.sort.requires-group` 与 `analysis.having.requires-group`，与 Wow `aggregation.query()` 的 `validateSort`／`validateHaving` 一致，让存储的配置在准入阶段而不是服务端被拒；
- `DERIVED` 只能引用在它之前声明的非 `ANY` metric 别名，按 `metrics` 顺序维护可引用集合，前向引用与环报 error；
- `percentile` 在开区间 (0, 100)，与 Wow 的 `aggregation.percentile` 一致，`100` 报 error；
- `HISTOGRAM` 的 `interval` 必须是大于 0 的有限数，与 `aggregation.histogram` 一致；
- 表达式中的每个 `CONSTANT.value` 必须有限（在预算准入之后递归检查普通表达式与派生表达式），与 `aggregation.constant` 一致；
- `BINARY` 的 `DIVIDE` 右侧为常量 0 报 error；
- **`elements` 是一条由外到内的链**（Wow 的口径，D20）：配置的第 i 层必须等于能力声明的第 i 层，可以只走链的前几层，链断在哪一层就从哪一层起不再往下判；能力从没声明过这个 path 报 `analysis.element.undeclared`，声明过但属于另一层（例如越过父数组直接展开 `lines`）报 `analysis.element.out-of-chain`；
- **展开之后，计数单位是最内层元素**，维度、指标、数值表达式与指标条件的字段只能是那一层的；根字段或外层元素字段写在这些位置报 `analysis.field.outside-scope`（Wow 以「requires its declared element scope」拒绝，字段确实存在，所以不说「未知」）。根 `filter`（范围）反过来只认根字段——它在任何展开之前执行，要问元素里的事由 `elementMatch` 条件去问；第 i 层元素自己的 `filter` 只认该层持有的字段。（见 test/analysisCapability.test.ts「the expansion chain」「element scope」）
- `any`、`distinctCount`、`percentile`、`expressions`、`having` 等未在能力中声明却被使用报 error；
- 每个 group 的 `type` 必须在该字段的 `groups` 中，`DATE_HISTOGRAM.unit` 必须在其 `dateUnits` 中，`NUMERIC.function` 必须在该字段的 `functions` 中，能力未声明即报 error；
- `limit` 必须是不超过 `RuntimeLimits.maxAnalysisRows` 的正整数，`groups`、`metrics`、`elements`、`sort` 的数量与 `limit` 始终受 Wow `AGGREGATION_LIMITS` 约束，`AnalysisCapability.limits` 只能进一步收紧（取更小者）；
- `sort[].alias` 不得重复（`analysis.sort.duplicate`），`DATE_HISTOGRAM.dense` 只能用于唯一分组（`analysis.group.dense-not-alone`）；
- `metrics` 不能为空，报 `analysis.metrics.empty`，与 Wow `metrics must not be empty.` 一致；
- `metrics[].type` 不在六种之内报 `analysis.metric.type-unknown`，`compileMetric` 对此抛错而不发出空洞（定义准入另行检查这些上限与 `defaultLimit` 本身是正整数且 `defaultLimit` 不超过 `maxLimit`）；
- `TERMS.missingKey` 与 `DATE_HISTOGRAM.timeZone` 若存在则不能为空白字符串，与 Wow 的 `aggregation.terms`／`dateHistogram` 一致；**`missingKey` 只能给单值字符串字段**（Wow 只允许这一种，多值／数字／布尔在 schema 校验处被拒），否则报 `analysis.group.missing-key-unsupported`。判据由 kind 自述 `FieldKind.singleString`（内置里 `string` 与 `enum` 声明它，`reference` 不声明：远端候选的 id 可能是数字），再由字段自己的 `options` 否决——一组数字码是数字字段，不管 kind 叫什么（`isSingleStringField`，`model/field.ts`；见 test/analysisValidate.test.ts「allows a missing-value bucket on single-valued text only」「gives a text dimension a bucket for the records with no value」）；
- **显示名给了就得是个词**：group 与 metric 的 `label`（`AnalysisNamed`，D20 显示名）不给则罢，给了必须是字符串（否则 `analysis.config.malformed`）且不能是空白（否则 `analysis.label.blank`）——一个空名字顶在列头上什么也没说。它是视图自己的东西，`compileAnalysis` 逐成员拼 Wow 对象，因此永远不会被发出去（见 test/analysisValidate.test.ts「a display name」「is admitted when given, refused when blank, and never sent to Wow」）；
- `table.columns[].alias` 必须是当前 groups 或 metrics 的别名且不重复。

### 展开链把问题重新划一遍范围：expand.ts

D20 屏 G。展开一个数组就是换掉计数单位：`订单 → 明细项` 之后，一行是一个明细项，维度与指标只能指明细项的字段，而一个按订单仓库切的维度问的是另一件事——Wow 以「requires its declared element scope」拒绝它。所以进出这条链的每一步都要把配置重新划一遍范围，这就是 `withElements(config, elements, definition, capability)`：

- **不再指向新单位字段的维度与指标离开**。`COUNT` 永远留下（能数记录就能数条目），`ANY` 看它那个字段，其余看它表达式里的每个字段；
- **指标自己的条件指着外面的字段时，条件离开而指标留下**：那句话问的是错的东西，但这个数本身还问得出来；
- **操作数都走光的派生指标离开**：它引用的是别名，别名没了就算不出来；
- **什么都不剩时指标重新起头**：`firstMetric(capability.count, 新单位的聚合能力)`，跟一份全新的分析一样。一份没有指标的聚合查询什么也答不上来，所以"空着"不是一个可选项；
- **还指得着的东西原样留着**：明细项的货号维度在收起批次之后仍然是明细项的货号维度，不该因为链动了一下就重挑一遍。

图表、排序与表列跟着这次形状变化走的方式，跟它们跟着任何一次分组／指标变化走的方式完全一样（`useAnalysisEditor` 的 `reshape`），所以这里只回答"分什么组、测什么数"。链本身的三个动作也在这儿：`expanded(elements, path)` 往里走一层，`collapsed(elements, index)` 从这一层切断（里面的层一起走），`nextExpansion(declaredChain, elements)` 是能力声明的下一步——链是一条线，所以至多只有一个可展开的东西；`levelLabel` 与 `nextLevel` 把层与下一步按它们的字段显示名说出来，给界面用。（见 test/expand.test.ts「withElements」与 test/elementsSlot.test.tsx「the expansion slot」）

### 粒度推荐（K4）

新加的时间维度从哪个粒度起步，是 `analysis/granularity.ts` 回答的。从前它起步于字段声明的第一个单位，不管范围有多长：一年的订单按小时切是八千个没人要的桶，一周按月切是一个。**它只决定「起步」**——粒度选择就在卡片上，手选过的单位永远优先，推荐只给新维度播个种。

- `recommendDateUnit(span, offered)`：在字段声明的那些单位里，挑**仍能切出至少 6 个桶的最粗那个**；没有一个切得够就取最细的那个（桶太少总好过没法看），连单位都没声明就取第一个。单位长度按近似值算（月 30.4375 天、季 91.3125 天、年 365.25 天）——它选的是一档粗细，不是一个要对齐到日历的边界；
- `rangeSpan(filter, field, now, timeZone)`：已应用的范围在这个字段上圈出的毫秒数。**只有树 AND 在一起的叶子算数**——OR／NOR 子树里的条件是一种可能而不是一道边界，整棵跳过；`BETWEEN` 两端都给，`GTE`／`GT` 是下界（`GT` 取那一天的末尾，`GTE` 取开头），`LTE`／`LT` 是上界（`LT` 取开头，`LTE` 取末尾），多个同向的边界取更紧的那个。**只有下界就一直算到现在**（「三月以来」是一段真实的跨度），只有上界则什么也说明不了——数据从哪天开始没人知道——返回 `null`；
- `resultSpan(rows, groups, field, timeZone)`：已经有结果时退而读结果，从第一个桶的开头到最后一个桶的末尾（末尾由 `bucketRange` 给，与下钻用的是同一个反向映射）。字段上没有时间维度或没有行就 `null`；
- 两者的顺序在 `react/useAnalysisEditor.ts` 的 `dateUnitFor` 里：范围优先（它是分析师刚刚说的话），其次结果，都没有就是字段的第一个单位。（见 test/granularity.test.ts「recommendDateUnit」「rangeSpan」「resultSpan」与 test/analysisCards.test.tsx「the granularity a new time dimension starts at」）

### 图表规则

- **系列是作者的**：一张只画两个指标里那一个的笛卡尔图，要熬过其余的每一次编辑——改个显示名、加个维度、旁边添个指标都会各跑一次 `fitChartSlots`，每次都把系列重新铺满，「只看金额」就成了只活一次编辑的选择。所以只有**指标已经没了的系列**才离开，只有**一个都不剩的列表**才重新铺满全部指标（透视时同理：留下的第一个仍是作者选的那个）。（见 test/analysisChartSlots.test.ts「keeps the series the chart names, and fills the list only when it is empty」「pivots on a second dimension and opens back up when it goes」）
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

### 哪些图型画得了这个形态：`fitCharts`（K3）

`validateChart` 回答的是「这份配置对不对」，它只在用户选完之后说话；列出图型的地方要在用户选之前就说清「这一个为什么不能选」。同一套规则正着读一遍就是 `fitCharts({ groups, metrics })`，交出每个 `ChartType` 的 `{ available, reason?, recommended? }`（`analysis/fitCharts.ts`，结清 Q6）。每个图型答的是它所属**家族**的那一条：规则按家族写在 `analysis/chartFamilies.ts` 的 `CHART_FAMILIES` 里，一个家族一行——选项有哪几页、有没有图例、能不能在图上写数、画得了什么形态都在这一行，加一个家族就是加一行，而不是在几个内核与组件里各添一个 `switch` 分支（阶段 2 审查 E1）。**正着读与事后读是同一条规则**：对每一种形态、每一个图型，「列表里可选」当且仅当「`fitChartSlots` 为它填好的槽能过 `validateChart`」，这由一个遍历形态的测试守着；两边从前分开写，已经漂开过——三个维度时柱状图可选、两个维度时散点可选，选了都报 `chart.group.unconsumed`。**能力决定在不在，形态决定灰不灰**（D4）：定义没声明的图型根本不在列表里，声明了的由这里判灰，`reason` 是文案目录的键（`chart.fit.*`），因为灰掉的卡片要把缺什么写在自己身下：

- 直角坐标系的四个（bar／line／area／combo）各要一个维度当横轴——没有维度就没有轴（`chart.fit.needs-dimension`）；再多只能拆一层（`splitBy`），所以**最多两个维度**，第三个会让每个点下有几行，平均、去重计数这类指标在投影里加不回去——置灰并写「最多两个维度」（`chart.fit.too-many-dimensions`），而不是悄悄丢掉一个维度（D20，用户 2026-09-22 定）；
- 饼图与按维度分阶段的漏斗要**恰好**一个维度（`chart.fit.needs-one-dimension`），漏斗另有「按指标分阶段」的形态，那一种要零维度加两个以上指标；
- 热力图要两个维度（`chart.fit.needs-two-dimensions`）；
- 散点把两个指标画成一个点、一个维度值一个点，所以要**恰好**一个维度（多了写 `chart.fit.needs-one-dimension`）再加两个指标（`chart.fit.needs-two-metrics`）；
- 指标卡是一个数：没有维度时成立，有**恰好一个时间维度且主数可加**时也成立（那是迷你趋势，判据与 `maxSlices` 同一条），其余写 `chart.fit.needs-no-dimension`；
- **推荐只有一个，而且只推荐画得出来的那个**：没有维度推指标卡，一个日期维度推折线，其余推柱状；三个维度起不推荐任何一个——那是表格的活。推荐是记号不是动作，不自动换图（D20）。

表格不经过这里：它画得了任何形态，所以它在列出图型的地方是一张永远可选的卡片，而不是一条规则。（见 test/fitCharts.test.ts「fitCharts」、test/chartFamilies.test.ts「chartFamilies」与 test/chartPicker.test.tsx「the visualization panel」）

可视化面板第二层上那些"改一个设置不许弄坏另一个"的规则同样是内核的，不在组件里：`analysis/chartOptions.ts`（D20 屏 J）。`optionTabs` 说一个图型有哪几页；`placed` 让两个槽对调而不是重复（选中另一个槽正拿着的别名时）；`without` 是"取消一项"的写法；`isStacked`／`withStacked` 与 `isSmooth`／`withSmooth` 把堆叠与平滑当作整张图的一个选择，全体加入或全体退出；`moved` 排阶段；`stageValues`／`withStagesFrom`／`withStageOrder` 让按分组值分阶段的漏斗一被选中就有顺序可画——业务顺序内核不知道，但"结果行来的顺序"总好过空白。它们都是纯函数，不用 DOM 就能钉住；面板怎么用它们见 [ui/analysis.md#可视化的第二层选中图型的选项三个页签](ui/analysis.md#可视化的第二层选中图型的选项三个页签)。（见 test/chartOptions.test.ts「chartOptions」）

### 图表的槽跟着形态走：`fitChartSlots`

图表按别名寻址自己的行，所以它不是一份能熬过「问题换了形状」的设置。从前换一个图型只改 `chart.type`、不建家族子对象，于是 `chart.family.missing`、图消失；加减一个维度之后图表仍念着旧别名，于是 `chart.group.unconsumed`／`chart.group.unknown`。两件事是同一个问题——哪个别名坐哪个槽——所以只有一个答案，图型、维度、指标任一改动都过它，`defaultAnalysisConfig` 的第一张图也过它：

- **用户选过的槽只要还指着存在的东西就保留**，只有别名没了、或新形态放不下的槽才重填；其余家族的子对象原样带着，换走再换回来还是那一套设置；
- **列表类的槽也是"用户选过的槽"。** 笛卡尔家族的 `series` 与按指标分阶段的漏斗的 `items` 从前每次都按当前指标从头排一遍，而这个函数在每次重画时都要跑——于是可视化面板上「移除系列」按下去又长回来、阶段下移一格又弹回原位，两个按得动却不生效的控件。现在 `series` 只有两种时候重填：列表空了（或它点的指标全没了），以及**拆分维度刚刚离开**——收成一个系列那一次是形态逼的，不是谁选的，所以维度走了它就该开回去；漏斗的 `items` 按 spec 自己的顺序排，没被点到的指标补在末尾（那里够得着上移），而不是把手排的顺序抹掉；
- **形态放不下时图型跟着动，而不是把配置变红**：除了指标卡与按指标分阶段的漏斗，每个家族都要靠维度寻址，所以没有任何维度时只有指标卡画得出来；而指标卡是一个数，所以有一个它画不成迷你趋势的维度时它就不是指标卡了（趋势要恰好一个时间维度，且主数可加——无合计行时主数就是各桶之和）。删掉最后一个维度改的是问题不是图，用户没有放弃过哪个选择；
- **不替形态编东西**：一个维度的热力图、一个指标的散点、以及阶段没人命名过的漏斗都不可表达，槽留空，于是 `validateChart` 说的是缺哪个槽而不是整个家族不在。这些是用户对着装不下它的形态选的图型；「某个形态提供哪些图型」是另一个问题，在列出它们的地方回答（阶段 5）；
- 维度或指标的改动同样带走指向消失别名的 `sort` 与 `table.columns`，没有维度时 `sort` 清空（Wow 拒绝对无分组聚合排序，而它本来就只有一行）——这一步在 `react/useAnalysisEditor.ts` 的 `reshape` 里，它是「一次编辑要捎上什么」的那一处。（见 test/analysisChartSlots.test.ts「fitChartSlots」与 test/analysisUi.test.tsx「re-fits the chart and the sort when the shape changes」）

### 指标的数怎么读：`metricFormat`

字段的 `numberFormat` 描述的是**一个存下来的值**，把它原样套到该字段的每一个聚合上，说出来的是查询从没算过的东西：整数字段的平均值成了整数，金额字段的去重计数成了钱。决定读法的是**这个聚合是什么**：

| 聚合                              | 读法                                      |
| --------------------------------- | ----------------------------------------- |
| `COUNT`、`DISTINCT_COUNT`         | 整数，不带任何货币（数的是记录）          |
| `AVG`、`STDDEV`、`VARIANCE`       | 字段格式 + 两位小数（金额的平均仍是金额） |
| `SUM`、`MIN`、`MAX`、`PERCENTILE` | 字段格式（它们就是该字段的值）            |
| `ANY`                             | 字段格式与字段自己的 `cell`／`options`    |
| `DERIVED`                         | 不属于任何字段，两位小数的普通数          |

`projectAnalysis` 把结果写进 `AnalysisColumnView.numberFormat`，表格、合计行、坐标轴、提示与指标卡因此都按同一份格式打印；列另带 `fn`（这一列是哪一种汇总），供界面把表头拼成「〈字段〉 的 〈汇总方式〉」，同一字段的两个汇总方式于是是两个不同的表头。（见 test/analysisProject.test.ts「metricFormat」）

### compileAnalysis 与 projectAnalysis

`compileAnalysis` 因同构而退化为映射：

- 查询级、指标级、元素级三处 `FilterTree` 分别编译为 `FilterExpression`，各按自己的作用域：查询级是根字段（绝对名），第 i 层元素级是该层字段，指标级是最内层元素的字段；
- **名字按作用域剥前缀**：配置存从根起的全名（`state.orders.lines.sku`），Wow 读的是相对名（`sku`），因此 `compileGroup`／`compileMetric`／`compileExpression` 与两处 `compileFilter` 都先把当前作用域的前缀去掉（`relativeName`／`relativeFields`／`relativeTree`，`analysis/capability.ts`；持有谓词的叶子连它的谓词一起剥，因为谓词的名字也是从根拼出来的）。`elements[].path` 本身已是相对上一层的写法，原样发出。原样发全名的后果不是报错而是错数：Wow 按 `parent.append(field)` 解析，`lines.sku` 在 `lines` 之下成了 `lines.lines.sku`；
- 未声明时区的 DATE_HISTOGRAM 补上 `ctx.timeZone`，否则 Wow 按 UTC 切桶，东八区的"一天"从早上八点算起；
- `projectAnalysis` 的结果列为全部 group 别名加全部 metric 别名，`DERIVED` 也是普通列。这份别名清单是默认列序与 `schema` 的来源，**不导出**：它从前以 `resultSchema` 的名义对外宣称自己是「结果行的校验依据」，而没有任何人校验过结果行——行从 Wow 回来就直接投影，合同因此删掉而不是改写；
- 分组列与 `ANY` 列带上字段的 `kind`、`cell`、`options`，DATE_HISTOGRAM 列另带 `dateUnit` 与所声明的 `timeZone`，供界面按字段显示（见 [ui/README.md#值按字段显示](ui/README.md#值按字段显示)），其余指标是算出的数，不带；
- `columns` 按 `table.columns` 挑选，`schema` 以同样的描述覆盖结果里的每个别名——表格可以只显示计数，而图表仍按表格没显示的分组画，类目要经 `schema` 取名；
- 合计行来自 `compileAnalysisTotals` 的独立结果，因此 `AVG`、`DISTINCT_COUNT`、百分位等不可加指标也正确；
- 该查询与主查询共享同一调度预算，失败只使合计行不可用，不影响主结果。图表所需的派生整形也在此完成：`splitBy` 透视、饼图"其他"合并、漏斗累计与转化率、热力图矩阵、metric 卡片的比较值。metric 卡片带 `trend` 时的标题值取自合计行（`projectAnalysis` 的 `totals`），无合计行时按分桶求和；
- `compare` 与 `target` 在有无 `trend` 时同样生效。
- **分组值写成文本只有一种写法：`groupKeyText`**（`analysis/chart.ts`，null 与缺值为空串，数字、布尔按 `String`，其余按 JSON）——`ChartSpec.colors` 的键、透视系列的图例标签都是它，饼图与笛卡尔图查钉住的颜色也都经过它，所以同一个键在两种图里给同一个分类上色，而不是两条碰巧一致的路。（见 test/analysisChart.test.tsx「colours a number, a boolean and null by one key in a pie and in a split」）
- **分组查询要的是 `limit + 1`，多出来的那一行是探针**（`analysisProbeLimit`，D20 Ⅷ）。聚合回答的是至多 `limit` 行，并不告诉调用方它省略了多少，所以"正好填满上限"曾是唯一可用的信号，而它本身是二义的：刚好这么多组，和被截到这么多组，长得一模一样。多要一行把猜变成问：那一行回来了，就是还有更多组；没回来，就是没有。`compileAnalysisTotals` 不受影响——合计查询本来就不带 `limit`；
- **探针不越过天花板**：能力声明的 `maxLimit` 与 Wow 自己的 `AGGREGATION_LIMITS.MAX_LIMIT` 取小，越过任何一个的查询是被**拒绝**而不是被回答，拿整份结果换一行探针不划算。因此**配置上限已经顶到天花板时不探**——没有行可要了——这一种保留旧读法：行数填满上限时记下 `AnalysisView.atLimit`，报成"可能被截断"（`analysis.result.at-limit`）。没有分组时也不探：无分组聚合按定义只答一行，`limit: 1` 于是被每一次成功的查询填满，而没有任何分组可以被截掉。上限缺失或不是正整数时原样发出，把拒绝留在它本来在的地方（Wow）——准入会拒绝这些，但编译是导出的，宿主可能拿没被准入的配置来编译；
- **`projectAnalysis` 把探针行读回来再丢掉**：回来的行数多于 `limit` 就是 `AnalysisView.truncated: true`，否则 `false`；`rows` 永远至多 `limit` 行，图表整形（占比、饼图的"其他"、漏斗）也只看这些行，否则屏幕上会出现一个表里没有的组的份额。`truncated` 与 `atLimit` 不会同时成立：前者是问出来的答案，后者是问不出来时剩下的那点线索。运行时据此报 `analysis.result.more-groups`（事实）或 `analysis.result.at-limit`（可能），见 [runtime.md#规则](runtime.md#规则)。**合计行不受影响**：它来自无分组查询，截没截断都覆盖范围内全部记录，这正是可见几行之和小于合计的原因。（见 test/analysisCompile.test.ts「the probe row」与 test/analysisProject.test.ts「the probe row read back」）

### 只保留与写出来的指标：`having.ts` 与 `formula.ts`

D20 屏 B 的两件事各有一个内核文件，都只是纯函数——托盘因此只剩标记，而「这份配置说得出来吗」只有一处答案：

- **`analysis/having.ts`** 把 Wow 的 `having` 读成／写成**一行一条比较**。`havingRows(having)` 交出 `{metric, operator, value}[]`：一个 `CONDITION` 是一行，一棵一层的 `AND` 是几行，**其余一律 `null`**——区间、集合、空值判断、任何位置上的 OR、嵌套的 AND 都不摊平。摊平会把作者写的那份配置换成一份他没写过的、下一次保存就覆盖掉原件的配置，而「我读不出来」是一句可以老实说的话。`withHavingRows(rows)` 反过来：一条写成 `CONDITION`，几条写成一棵 `AND`，一条都没有就整个不写；**没有值的行直接落掉**，所以存下去的配置永远是 Wow 收得下的那一份，编辑到一半的状态归组件自己拿着。`HAVING_OPERATORS` 是那六个比较，顺序就是选择框里的顺序；
- **`analysis/formula.ts`** 是两种写出来的指标的第一形态与它们的读法。`formulaMetric(left, right, fn, taken)` 造 Wow 的 `NUMERIC` 套 `BINARY`（两个字段相减再汇总），`derivedMetric(left, right, taken)` 造 `DERIVED`（前一个指标除以后一个）——都是**一张待改的卡片**，不是一个猜出来的答案。`expressionText`／`derivedText` 把式子说成作者会说的那句话（「金额 − 成本」「金额合计 ÷ 客户数」，嵌套的加括号），列头、图例与图表的文字读法共用它；`isFormula` 是「这条指标是卡片编得动的那一种吗」——一个操作两个操作数。`EXPRESSION_OPERATORS` 与 `OPERATOR_SIGN` 是那四则运算和它们在任何语言里都一样的符号。

两者都不知道目录也不知道语言：`expressionText` 接一个 `nameOf` 回调，字段叫什么由调用处说。（见 test/having.test.ts「having rows」「formulas」；界面见 [ui/analysis.md#只保留一行一条比较](ui/analysis.md) 与 [ui/analysis.md#公式与派生写出来的指标](ui/analysis.md)）

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
