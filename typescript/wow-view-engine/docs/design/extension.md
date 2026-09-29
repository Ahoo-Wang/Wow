# 扩展点与 Wow 协议的对应

## 扩展点

| 变化轴   | 机制                                                                                                                                                  | 落点                                                                                                                                                                           |
| -------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 字段类型 | `FieldKind` 包：操作符集、默认操作符、值校验、编译到 `FilterExpression`、编辑器描述（纯数据）                                                         | `filter/` 的 `FieldKindRegistry`；编辑器不是扩展点——`ui/filter/FilterValueEditor.tsx` 对封闭联合 `EDITOR_INPUTS` 做穷尽 switch，没有按 kind 注册的渲染器                       |
| 数据来源 | `resources` 里每一项把一份定义与它的 `ViewSource` 配成对（`QueryApi` 的 `paged` / `cursor` / `aggregate` 三个方法，见 [runtime.md#环境](runtime.md)） | 应用注入                                                                                                                                                                       |
| 持久化   | 实现 `ViewStore`                                                                                                                                      | 业务应用，或官方后端的客户端包                                                                                                                                                 |
| 动作槽位 | 宿主向工作台传 render 函数 `global / bulk / row`，动作是代码，不进配置、不进 ViewInstance、不进 Dashboard 面板                                        | `react/actions.ts` 的类型；工作台属性                                                                                                                                          |
| 外观     | `:root` 上的 `--fve-<token>`（亮）与 `--fve-dark-<token>`（暗），根与 portal 弹层都读到；组件级替换通过自定义组合 `/react`                            | 宿主样式表；预设即一份这些变量的赋值（`/themes.css`，`data-fve-preset` 选中）；已有 shadcn 主题的宿主引 `/shadcn-bridge.css`，`input`、`ring`、状态色与图表色不桥接（D30 Q46） |

```ts
export interface FieldKind {
  id: FieldKindId;
  operators: FilterOperator[];
  defaultOperator: FilterOperator;
  validate(
    value: unknown,
    operator: FilterOperator,
    field: FieldDefinition,
  ): Issue[];
  compile(leaf: FilterLeaf, field: FieldDefinition, ctx): FilterExpression;
  /** 由操作符与当前值的语义变体推出编辑器描述；组件名不进入配置。 */
  editor(operator: FilterOperator, value?: unknown): EditorDescriptor; // input 只能是 EDITOR_INPUTS 里的一个；界面按它分派，没有渲染器注册表 // { input: 'none' | 'text' | 'number' | 'boolean' | 'deletion' | 'select' | 'remote' | 'date' | 'dateRange' | 'relativeDate' | 'predicate' | 'duration'; multiple?; range?; options?; remote?; withTime? }
  /** 一条已应用条件的部件，以及它们读作的那句英文。 */
  describe(ctx): FieldKindDescription; // { text; operator?; relation?; value: FilterSummaryValue; items?; group? }
}
```

`describe` 交的是**部件**而不是一句话。它的结果落在结果区最显眼的那一行上，kind 自己拼出的句子——原始操作符名、`is empty`、`on or before`——是任何措辞目录都够不到的，`messages={zhCN}` 之下整页中文、唯独条件 badge 是英文就是这么来的。所以：

- `value` 必须是封闭联合 `FilterSummaryValue` 中的一个（`none`／`blank`／`text`／`list`／`range`／`relative`／`preset`，见 [kernels.md#已应用摘要是部件不是句子](kernels.md#已应用摘要是部件不是句子)）。自定义 kind 也从这七种里挑一种，`/ui` 据此渲染，正如 `EditorDescriptor.input` 是封闭的一样；
- **没注册的 kind、或注册了却要一个引擎没有的 `input`，界面不再退回文本框**（F-06，2026-09-21）：`validateFilter` 分别以 `filter.kind.unregistered`／`filter.kind.unknown-editor` 拒绝这条条件（Apply 被挡、状态条说明），条件 pill 把它画成**只读**（`ui/filter/inputs/unsupported.tsx`：配置里存的原值 + 一句「这个字段的类型（{kind}）没有注册编辑器」，✕ 照常可按），字段选择器根本不再列出这种字段（`fieldsFor` 按注册表过滤）。从前的 `default:` 分支静默退回文本框，让用户改写一个谁也读不回来的值形状。数据定义里的未注册 kind 在准入时就整份被拒（`definition.field.kind-unregistered`），真正会撞到这一条的是仪表盘保存下来的筛选字段。
- **注册了的 kind 判一条条件时抛异常，是那一条条件的 error，不是谁的崩溃**（2026-09-28）：`validateFilter` 对每片叶子调 kind 的 `editor`、`isBlank` 与 `validate`，这些是宿主的代码跑在内核里；任何一处抛出，都以 `filter.kind.failed` 报在那片叶子的路径上，其余叶子照判。读者看到的句子只按字段的标签（参数 `label`）说「这个条件没能检查、去找维护视图的人」；kind 的 id（`kind`）与宿主抛出物的 message（`reason`）只作为参数留给开发者（`onIssue`、`definitionIssues`），不进句子——宿主的 message 多半是英文、可能带着宿主内部的东西（同 #3593），而坏掉的面板按标签说字段、不露 id（#3732）。`field` 仍是字段名，供按字段定位的机器读。从前它原样穿出内核：`engine.open` 以宿主的错拒绝，编辑时加上的面板（无人 await 的引用加载）变成未处理的 rejection。落在叶子上而不是 `dashboard.panel.failed`：后者说的是面板的引用没法投入使用（视图读不到、源解析不出），属于 runtime；kind 判不了一条条件是内核的发现，与 `filter.kind.unregistered` 同层，单独打开的视图也要说得出。面板跑的筛选报在 `['panels', i, 'filter', …]` 之下，所以只有站在它上面的那块面板不跑（[runtime.md#dashboard](runtime.md#dashboard)「一个面板配错，只坏它自己」）。（见 test/filter.test.ts「reports a kind that throws on the leaf it could not judge」、test/throwingKind.test.ts「a host kind whose validate throws」）
- 读不出的值交 `blank` 而不是编一个读法；操作符本身就是全部条件（presence、`IS_EMPTY`）时交 `none`；已经解析过的候选项标签随 `list.labels`／`text.label` 一起交出去，界面不再解析一遍；
- `operator` 缺省就是叶子自己的，**编译出来的条件与叶子写的不是同一个时要自报**——缺上界的绝对 `BETWEEN` 编译成 `filter.gte(from)`，就报 `GTE`；
- 同一个值在不同操作符下含义不同时，由 `value` 自己区分而不是让界面去猜操作符：相对日期的 `bound: 'window' | 'instant'` 就是这条规则的实例；
- 持有谓词的 kind 另交 `items` 与 `group`，且要先拆掉 `describeFilter` 的折叠（见 [kernels.md](kernels.md#已应用摘要是部件不是句子)），否则操作符会被说两遍；
- `text` 仍要给，且是英文：宿主可能直接读 `FilterSummaryItem.text`。它是兜底，不是摘要。

### 内置 kind

内置 kind：string、number、boolean、date、datetime、enum、reference、array，各自拥有 [model-shapes.md](model-shapes.md#filter-树三类共享) 列出的值类型。

- **`array`** 是"一个字段同时持有多个值"（标签、分类）的一等表示，它独立成 kind 而不是 string 上的一个开关，因为操作符含义不同：标量字段上 `IN` 问"这一个值是否在列表里"，数组字段上问"字段的条目是否包含其中任一"，另有 `CONTAINS_ALL`（全部包含）。判空只问两个问题（D62，第二轮审查 R1-P1-7）：`IS_EMPTY` 读作「没有条目」、发成 `IS_EMPTY` 或 `IS_NULL`（MongoDB 的 `$size: 0` 漏掉缺失与 null 的列表），`IS_NOT_NULL` 读作「有条目」、发成两者的 `NOR`——缺失、null、空列表是存储的三种状态，对读的人是同一个回答；`elementMatch` 同样。一个算子发成几个 Wow 算子时由 kind 的 `compiledOperators` 说，按描述符收窄时要求这几个都被准入（`capabilities/fields.ts`）。声明了 `options` 即为封闭集合，与 enum 同；声明了 `remote` 走远程候选；两者皆无则自由输入。（见 test/arrayKind.test.ts「the array kind」）
- **元数据 kind** 另有五个，由 Wow 元数据过滤支撑：documentId（`ID`／`IDS`）、aggregateId（`AGGREGATE_ID`／`AGGREGATE_IDS`）、tenantId、ownerId、spaceId。元数据过滤不带字段名（`{ op, value }`），因此这些 kind 的 `FieldDefinition.name` 只是编辑器、标签与 Issue 路径的句柄，不进入查询；约定写作 `@ownerId` 这类带 `@` 的名字，但不依赖它。某个元数据字段算不算合法筛选条件取决于"谁在看"——租户内的用户按所有者或工作空间收窄，平台运维按租户收窄——这个判断属于定义，定义是代码、随应用部署，所以引擎提供 kind，由每份定义决定视图能用哪些。它们不提供 presence 操作符：`IS_NULL` 一类是带字段名的，混进来会让同一个叶子的 `name` 在不同操作符下时而是路径时而是标签。（见 test/metadataKinds.test.ts「metadata field kinds」）
- **`search`** 是列表页顶部那个搜索框：Wow 的 `SEARCH` 不带字段名，所以它的 `name` 与元数据 kind 一样只是句柄；查哪些字段、按词还是按短语匹配属于定义（`searchFields`／`searchMode`），与 `stringComparison` 同理——匹配方式是字段的属性，值只是用户敲进去的东西。空白查询是「还没问」而非错误，非文本值则是错误：`filter.search` 对两者都抛异常，但只有前者该被宽容。它同样不提供 presence 操作符，原因与元数据 kind 相同，而这条规则由 `FieldKind.fieldless` 自述，`FIELDLESS_FIELD_KIND_IDS` 只是内置 kind 的缺省答案——**名字不是路径的那些 kind**；编译成根过滤的自定义 kind 同样要声明它，否则会溜进元素谓词而被 Wow 拒绝。（见 test/searchKind.test.ts「a handle is not a record field」）

### `elementMatch` 与嵌套谓词

- `elementMatch` 的值是一棵**条件树**而不是标量：Wow 的 `ELEMENT_MATCH` 携带完整谓词，而谓词正是用户在这里写的东西——同一棵树、同一套编辑器，作用域换成该数组元素声明的字段。
- 它存在的理由是语义差别：`items.sku` 与 `items.qty` 两条并列写在顶层，由**任意**元素各满足一条即可；写在元素匹配里则必须由**同一个**元素同时满足。
- 持有树的 kind 用 `nested(value, field, operator)` 自述——按操作符设限，`IS_EMPTY` 下遗留的谓词不计入——`checkShape` 据此把嵌套树计入**同一份** `maxFilterDepth`／`maxFilterNodes`——预算的存在是为了让 store 送来的树无法耗尽调用栈，每层各给一份额度等于换个方式重新放开。（见 test/elementMatch.test.ts「the budget reaches into a predicate」）
- `FieldKind` 的四个上下文（blank、validate、compile、describe）因此带上 `kinds`，validate 另带 `limits`：嵌套谓词按**外层的**预算准入，且不重复走一遍骨架检查：持有树的 kind 要用**外层正在用的那份**注册表，自定义 kind 才能在元素谓词里按同样的条件被准入。
- 自定义 kind 由应用注册到 `FieldKindRegistry`，自行定义值的形状，但**编辑器本身不是扩展点**：`EditorDescriptor.input` 是封闭联合（成员以值的形式列在 `filter/fieldKind.ts` 的 `EDITOR_INPUTS`），`ui/filter/FilterValueEditor.tsx` 对它做穷尽 switch，自定义 kind 只能从现成的 input 里挑一个。要了引擎没有的 `input`，准入就以 `filter.kind.unknown-editor` 拒掉这条条件（`validateFilter`，Apply 被挡），未注册的 kind 同理以 `filter.kind.unregistered` 拒掉；两种情形下 pill 画的是 `ui/filter/inputs/unsupported.tsx` 的只读原值加原因（F-06），不再静默退回文本框。按 kind 注册渲染器将来要从这个 switch 切开，那条缝记在 [ui/README.md#FilterPanel 的布局](ui/README.md#filterpanel-的布局)。（见 test/unregisteredKind.test.tsx「a registered kind that asks for an editor nobody wrote」「is refused by admission rather than drawn as a text box」「a condition on a field whose kind is not registered」）

## 与 Wow 协议的对应

| 内核输出                              | Wow 类型（`@ahoo-wang/wow-client`）                                                                                                       | 执行入口           |
| ------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- | ------------------ |
| `compileFilter`                       | `FilterExpression`（`LogicalFilter`、`EqualityFilter`、`ComparisonFilter`、`StringFilter`、`CollectionFilter`、`BetweenFilter` 等的联合） | 嵌入下方三种查询   |
| `compileRecord` 分页                  | `FilterPagedQuery { filter; projection; sort; pagination }`                                                                               | `source.paged`     |
| `compileRecord` 游标                  | `CursorQuery { filter; projection; sort; size; cursor }`                                                                                  | `source.cursor`    |
| `compileAnalysis`、`compileSummaries` | `AggregationQuery { filter; elements; groupBy; metrics; sort; limit; having }`                                                            | `source.aggregate` |

两种记录查询都带 `projection.include`：一页只向数据源要视图显示的字段与宿主代码要读的字段，不要整份文档（`recordProjection`，规则见 [kernels.md#一页要哪些字段](kernels.md#一页要哪些字段)）。

Wow 已将 `Condition`、`ConditionOptions`、`PagedQuery`、`ListQuery`、`SingleQuery` 等基于 condition 的 API 标记为弃用。本包只使用 `FilterExpression` 与 `Filter*Query` 系列；架构测试禁止从 `@ahoo-wang/wow-client` 导入任何弃用符号，`FilterLeaf` 的编译结果类型固定为 `FilterExpression`。

`AnalysisViewConfig` 覆盖 `AggregationQuery` 的全部字段：`filter`、`elements`、`groupBy`、六种 `metrics`、`having`、`sort`、`limit`。Wow 端的能力（是否支持 aggregate、支持哪些 group 类型、函数与扩展能力）由 `AnalysisCapability` 在定义中声明；内核只按声明编译，不探测后端。

**名字按作用域编译。** `elements` 是一条由外到内的链，链决定计数单位，也决定每个名字写在哪个作用域里：根 `filter` 用绝对名，第 i 层元素的 `filter` 用相对该层的名字，维度／指标／数值表达式／指标条件用相对**最内层**元素的名字。配置里一律存从根起的全名（好校验、好显示），`compileAnalysis` 按作用域剥前缀——`state.orders.lines.sku` 在 `state.orders → lines` 之下发出去的是 `sku`。原样发全名会被 Wow 按 `parent.append(field)` 解析成 `state.orders.lines.lines.sku`，根字段写在维度位则以「requires its declared element scope」被拒；这两条内核都在准入阶段先拒（`analysis.field.outside-scope`）。自定义 kind 同受此约束：编译成根过滤的 kind 要声明 `fieldless`，元素域里用不了。
