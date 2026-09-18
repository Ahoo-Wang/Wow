# View Engine 架构设计

**状态**：重写设计稿，替代 `refactor-spec.md`、`invariants.md`、`first-deliverable.md`。
**基线**：当前 `packages/view-engine` 以 tag 冻结为只读参考；新树在同一包名下自下而上重建。
**原则**：行为约束以测试存在，本文只写模型、边界、合同与顺序。

## 1. 定位与第一性原理

View Engine 让业务应用**用配置而不是页面代码**表达对 Wow 查询数据的观察方式：明细（Record）、分组分析（Analysis）、组合概览（Dashboard），并让有价值的配置可以保存、重开、共享与嵌入。

**解决的问题。** 业务系统的大多数页面是"列表加筛选、排序、分页，偶尔一张图"，每个业务对象各写一套；观察方式的每次调整都要改代码与发版。数据没有变，变的只是观察方式，而观察方式被写死在页面代码里。引擎把观察方式抽成可校验、可执行、可保存的配置，让用户自行调整，让研发对每个业务对象只接入一次。

价值链只有一条：

```text
ViewDefinition + ViewConfig ──compile──▶ Wow 查询 ──execute──▶ 结果 ──project──▶ 呈现
                    └──────────── save / open ────────────┘
```

设计建立在三个事实上：

| 事实                                                                                  | 推论                                                                                           |
| ------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| **定义是代码。** 字段、类型、操作符、聚合能力来源于 Wow 聚合的查询 schema，随应用部署 | 没有定义维护服务、定义版本或定义重载协议；定义变更就是一次发版，实例在打开时按当前定义校验一次 |
| **配置是数据。** 用户保存的是观察方式，不是数据快照                                   | 持久化对象只有 `ViewInstance` 和个人偏好；一致性策略是乐观版本加幂等 requestId                 |
| **运行状态是临时的。** 草稿、结果、分页、选择只活在一次打开中                         | 运行时是每个打开的视图一个小 store，不持久化、不进入契约                                       |

## 2. 范围

**做**：Filter 树编辑与编译；Record 分页明细、排序、列、汇总、行动作；Analysis 分组与指标、图表与表格投影；Dashboard 面板组合、全局筛选、栅格布局；实例保存、另存、改名、删除、个人排序与默认；`ViewStore` 端口与 Memory 实现；无样式钩子与默认 UI 两种消费方式；独立嵌入。

**不做**：视图种类插件；定义 CRUD 后端与定义版本；写入回执核对、读屏障、精确一次；并发与页大小以外的资源预算账本；SSR 预载；通用 region 或事件总线；跨页全选、单元格编辑、Dashboard 嵌套。

## 3. 核心模型

`model/` 目录只含类型与常量，不依赖其他目录。

```ts
// ---- 定义 ----
export type ViewDefinition =
  | {
      id: string;
      title: string;
      kind: 'data';
      source: string; // resolveSource 的键
      fields: FieldDefinition[];
      record?: RecordCapability;
      analysis?: AnalysisCapability;
      views?: SystemView[]; // 代码声明的系统视图，随定义部署
    }
  | { id: string; title: string; kind: 'dashboard'; views?: SystemView[] }; // Dashboard 实例的归属目录

export interface SystemView {
  id: string; // 在定义内唯一且不含 ':'；Engine 以 `system:${definitionId}:${id}` 作为实例 id
  title: string;
  config: ViewConfig;
}

export interface FieldDefinition {
  name: string; // 支持 a.b 路径
  label: string;
  kind: FieldKindId; // 'string' | 'number' | 'boolean' | 'date' | 'datetime' | 'enum'
  //                  | 'reference' | 'array' | 'elementMatch' | 'search'
  //                  | 'documentId' | 'aggregateId' | 'tenantId' | 'ownerId' | 'spaceId' | 自定义
  operators?: FilterOperator[]; // 缺省取 FieldKind 的默认集
  options?: FieldOption[]; // enum 的静态候选
  remote?: string; // reference 的远程候选源键，由 resolveOptions 解析
  sortable?: boolean;
  group?: string; // 添加筛选时的分组
  numberFormat?: Intl.NumberFormatOptions & { locale?: string };
  stringComparison?: 'CASE_SENSITIVE' | 'CASE_INSENSITIVE'; // CONTAINS／STARTS_WITH／ENDS_WITH 的比较方式，缺省不区分大小写
  searchFields?: string[]; // search 字段查哪些文档字段；缺省交给后端索引
  searchMode?: 'TERMS' | 'PHRASE'; // 按词还是按短语，缺省 TERMS
  summary?: SummaryFunction[]; // 允许的汇总函数
  cell?: string; // 单元格渲染器键，缺省按 kind
  editor?: string; // 筛选编辑器键，缺省由 kind、operator 与 value.type 推出
  // 数组字段的元素持有什么。它属于字段本身：items 就是那个数组，这些是它装的东西。
  // 声明在别处就得用路径字符串回指，而路径可以指向不存在的字段——那一整类悬空引用
  // 在这里根本写不出来。元素名字自成作用域，可与根字段重名，引用一律写 `field.element`。
  elements?: FieldDefinition[];
}

export interface RecordCapability {
  rowKey: string;
  paging: 'paged' | 'cursor'; // 数据源提供哪种分页；决定 runtime 调用 source.paged 还是 source.cursor
  layouts: ('table' | 'card')[];
  defaults?: Partial<RecordViewConfig>;
  actions?: { toolbar?: string; row?: string; bulk?: string }; // 渲染器键
}

export interface AnalysisCapability {
  count: boolean;
  fields: AggregationFieldCapability[];
  // 可展开的数组路径：path 指向一个声明了 elements 的字段，
  // 元素持有什么由那个字段说，这里只说哪些数组本分析可以展开、如何聚合
  elements?: { path: string; aggregations: AggregationFieldCapability[] }[];
  expressions?: boolean; // 允许 BINARY 表达式与 DERIVED 指标
  having?: boolean;
  limits?: {
    maxGroups?: number;
    maxMetrics?: number;
    maxElements?: number;
    maxLimit?: number;
    defaultLimit?: number;
  };
}

/** 一个字段被允许的聚合方式；根字段与 element 字段共用。 */
export interface AggregationFieldCapability {
  field: string;
  groups: AggregationGroupType[];
  functions: AggregationFunction[];
  dateUnits?: AggregationDateUnit[];
  any?: boolean;
  distinctCount?: boolean;
  percentile?: boolean;
}

// ---- 配置 ----
export type ViewConfig =
  RecordViewConfig | AnalysisViewConfig | DashboardViewConfig;

/** 三类配置的公共部分。 */
interface ViewConfigBase {
  filter: FilterTree; // Dashboard 中作用于全部数据面板
  filterMode: 'simple' | 'advanced'; // 编辑器模式跟随视图保存
  refresh: { interval: number | null }; // 自动刷新间隔，秒；null 关闭；开启时为有限整数，介于 RuntimeLimits.minRefreshInterval（缺省 5）与 maxRefreshInterval（缺省 86400）之间
}

export interface RecordViewConfig extends ViewConfigBase {
  kind: 'record';
  sort: { field: string; direction: 'ASC' | 'DESC' }[];
  pageSize: number;
  summaries?: { field: string; fn: SummaryFunction }[];
  layout: 'table' | 'card'; // 当前布局；切换只改此字段
  table: {
    columns: { field: string; width?: number; pinned?: 'left' | 'right' }[];
  };
  card: {
    title: string; // 作为卡片标题的字段
    fields: string[]; // 卡片正文字段
    image?: string; // 可选的图片字段
    columns?: 1 | 2 | 3 | 4; // 每行卡片数
  };
}

export interface AnalysisViewConfig extends ViewConfigBase {
  kind: 'analysis';
  elements?: { path: string; filter?: FilterTree }[]; // 数组展开；filter 以元素字段为作用域
  groups: AnalysisGroup[];
  metrics: [AnalysisMetric, ...AnalysisMetric[]];
  having?: AnalysisHavingExpression; // 与 Wow HavingExpression 同构，枚举为字面量；只引用指标别名与数字
  sort: { alias: string; direction: 'ASC' | 'DESC' }[];
  limit: number;
  layout: 'table' | 'chart'; // 当前呈现；切换只改此字段
  table: AnalysisTableSpec; // 与 chart 成对保存
  chart: ChartSpec;
}

export interface AnalysisTableSpec {
  columns: { alias: string; width?: number; pinned?: 'left' | 'right' }[]; // 缺省为全部 group + metric
  totals?: boolean; // 合计行；开启时执行一次无分组聚合，不由分组行推导
}

// 与 Wow AggregationGroup / AggregationExpression / AggregationMetric 同构；
// 差别只在字段按定义校验、筛选使用 FilterTree。
export type AnalysisGroup =
  | { type: 'TERMS'; field: string; alias: string; missingKey?: string }
  | { type: 'HISTOGRAM'; field: string; alias: string; interval: number }
  | {
      type: 'DATE_HISTOGRAM';
      field: string;
      alias: string;
      unit: `${AggregationDateUnit}`;
      timeZone?: string;
      dense?: boolean;
    };

export type AnalysisExpression =
  | { type: 'FIELD'; field: string }
  | { type: 'CONSTANT'; value: number }
  | {
      type: 'BINARY';
      operator: `${AggregationExpressionOperator}`;
      left: AnalysisExpression;
      right: AnalysisExpression;
    };

export type AnalysisMetric =
  | { type: 'COUNT'; alias: string; filter?: FilterTree }
  | {
      type: 'NUMERIC';
      alias: string;
      function: `${AggregationFunction}`;
      expression: AnalysisExpression;
      filter?: FilterTree;
    }
  | { type: 'ANY'; alias: string; field: string; filter?: FilterTree }
  | {
      type: 'DISTINCT_COUNT';
      alias: string;
      expression: AnalysisExpression;
      filter?: FilterTree;
    }
  | {
      type: 'PERCENTILE';
      alias: string;
      expression: AnalysisExpression;
      percentile: number;
      filter?: FilterTree;
    }
  | { type: 'DERIVED'; alias: string; expression: AnalysisDerivedExpression }; // 与 Wow DerivedExpression 同构
```

**查询筛选必须真的筛掉东西**。`metrics[].filter` 和 `elements[].filter` 都**没有编辑器**——筛选面板（`useFilterEditor`）绑的是 `config.filter`，`useAnalysisEditor` 根本不碰 `elements`。它们是查询定义的一部分，"不筛"表示成 `filter` 属性不存在；一旦写了却说不出任何东西，`compileFilter` 回以 `MATCH_ALL`，于是静默放宽：本来数已付款订单变成数全部，本来只展开已发货明细变成展开全部。数字错了，一声不吭。

两种"说不出东西"都拒绝：

- **没有任何条件**（空树，或只剩空分组）→ `analysis.metricFilter.empty`／`analysis.elementFilter.empty`
- **条件没填值** → `analysis.metricFilter.incomplete`／`analysis.elementFilter.incomplete`

这跟筛选面板的规则相反，是有意的：面板是**界面**，用户把常用条件摆上去、暂时不填值，表达的是"这次先不按它筛"。查询筛选不是界面，没有这层含义。

超预算时两者都**不再走第二遍**：预算本就是为了让存储里来的树不会耗掉无界算力，再全量遍历一次正好把它挡下的开销花掉。两者也都花**调用方设定的** `RuntimeLimits`，不是默认值。

**指标筛选（`metrics[].filter`）多一条限制**：它决定"每条记录算不算进这一个指标"，所以拿到的是**一条记录的一个值**。

- 由 **kind 自己声明** `scalar: false` 的种类被拒（`analysis.metricFilter.not-scalar`）：`array`、`elementMatch` 编译成对集合内元素的条件，`search` 编译成对整条记录文本的匹配，三者都没有"这条记录在这里的那一个值"可判
- 判据是 kind 而非写死的 id 列表——`withFieldKinds` 允许替换内建 kind 或注册自定义 kind，决定这件事的是**编译出来的形状**，而只有 kind 知道自己编译成什么
- **元数据字段（`@id`／`@ownerId`／`@tenantId`…）在这里是允许的**，与元素谓词里被拒相反：元素没有所有者，而指标筛选看的正是整条记录
- 这条限制**只属于指标位置**。元素筛选是对元素自身字段的普通筛选，多值字段在那里是正当的
- `DERIVED` 不参与：协议里它就不带筛选，`compileMetric` 也从不发出去。存储里残留的 `filter`（比如指标类型改过）不该拦住整份配置

Wow 的对应规则在 `requireScalarMetricFilterFields`：形状那半条（`SEARCH`／`ELEMENT_MATCH`）由 `packages/wow` 的 `aggregation.query()` 在协议层挡住，需要 schema 那半条（数组值字段）由这里挡住——因为只有这里知道 `FieldKind`。

除此之外它就是一棵普通的 Filter 树，按 analysis scope 的字段走 `validateFilter`，问题路径挂在 `['metrics', i, 'filter', ...]` 下。

```ts
export type AnalysisHavingExpression = LiteralEnums<HavingExpression>;
export type AnalysisDerivedExpression = LiteralEnums<DerivedExpression>;

// 图表按族保存：type 所属族的子对象必填，其余可选并在切换时保留，跨族切换不丢配置。
// 所有引用均为 group / metric 别名，不含任何图表库类型。
export interface ChartSpec {
  type:
    | 'bar'
    | 'line'
    | 'area'
    | 'combo'
    | 'pie'
    | 'heatmap'
    | 'scatter'
    | 'funnel'
    | 'metric';
  cartesian?: CartesianSpec; // bar / line / area / combo 共用
  pie?: PieSpec;
  heatmap?: HeatmapSpec;
  scatter?: ScatterSpec;
  funnel?: FunnelSpec;
  metric?: MetricCardSpec;
  legend?: 'auto' | 'top' | 'bottom' | 'right' | 'none';
  labels?: boolean; // 数据标签
  colors?: Record<string, string>; // 系列别名或分类值 → 颜色；未列出的用主题调色板
}

export interface CartesianSpec {
  x: string; // 分组别名
  splitBy?: string; // 第二分组别名：按其取值拆分系列（透视）；存在时 series 只能有一个指标
  series: {
    metric: string; // 指标别名
    type?: 'bar' | 'line' | 'area'; // combo 时逐系列必填
    axis?: 'left' | 'right';
    stack?: string; // 同名系列堆叠
    smooth?: boolean;
  }[];
  orientation?: 'vertical' | 'horizontal';
  yAxis?: { left?: AxisSpec; right?: AxisSpec };
  referenceLines?: { axis: 'left' | 'right'; value: number; label?: string }[];
}

export interface AxisSpec {
  label?: string;
  min?: number;
  max?: number;
  format?: 'auto' | 'percent' | 'compact';
}

export interface PieSpec {
  category: string; // 分组别名
  value: string; // 指标别名
  donut?: boolean;
  maxSlices?: number; // 超出部分合并为"其他"；只允许可加指标（COUNT／SUM）
}

export interface HeatmapSpec {
  x: string; // 分组别名
  y: string; // 另一分组别名
  value: string; // 指标别名
  scale?: 'linear' | 'log';
}

export interface ScatterSpec {
  category: string; // 分组别名，每个取值一个点
  x: string; // 指标别名
  y: string; // 指标别名
  size?: string; // 指标别名
}

/** 漏斗：阶段来自"带筛选的多个指标"或"一个分组的取值加显式顺序"。 */
export interface FunnelSpec {
  stages:
    | { from: 'metrics'; items: { metric: string; label?: string }[] } // 每阶段一个指标，顺序即阶段顺序
    | {
        from: 'group';
        category: string; // 分组别名
        value: string; // 指标别名
        order: string[]; // 阶段的业务顺序，取分组值
        cumulative?: boolean; // 累计为"至少到达该阶段"，缺省 true
      };
  conversion?: 'previous' | 'first' | 'none'; // 转化率相对上一阶段或首阶段
  orientation?: 'vertical' | 'horizontal';
}

export interface MetricCardSpec {
  metric: string; // 指标别名
  compare?: { metric: string; mode: 'delta' | 'percent' }; // 与另一指标比较，如上期
  target?: number; // 渲染为进度
  trend?: { x: string }; // 迷你趋势线，需恰有一个 DATE_HISTOGRAM 分组；标题值取合计行，无合计行时按分桶求和，因此 metric 与 compare.metric 都须是可加指标（COUNT／SUM）
  format?: 'auto' | 'percent' | 'compact';
}

export interface DashboardViewConfig extends ViewConfigBase {
  kind: 'dashboard';
  fields: DashboardField[]; // Dashboard 自己的全局筛选字段；跨定义，因此由配置声明
  panels: DashboardPanel[];
}

export interface DashboardField {
  name: string;
  label: string;
  kind: FieldKindId;
  options?: FieldOption[];
}

export type DashboardPanel = DashboardViewPanel | DashboardContentPanel;

interface DashboardPanelBase {
  id: string;
  title?: string;
  layout: { x: number; y: number; w: number; h: number };
}

/** 数据面板：引用一个 Record 或 Analysis 实例，参与全局筛选与 apply。 */
export interface DashboardViewPanel extends DashboardPanelBase {
  kind: 'view';
  instanceId: string;
  bindings: { globalField: string; panelField: string }[];
}

/** 内容面板：静态说明、图片、链接；不查询，不参与全局筛选。 */
export type DashboardContentPanel = DashboardPanelBase &
  (
    | { kind: 'markdown'; content: string }
    | {
        kind: 'image';
        src: string;
        alt?: string;
        fit?: 'contain' | 'cover';
        href?: string;
      }
    | {
        kind: 'links';
        items: { label: string; href: string; description?: string }[];
      }
  );

// ---- Filter 树，三类共享 ----
export type FilterTree = FilterGroup; // 深度与节点数受 RuntimeLimits.maxFilterDepth / maxFilterNodes 约束
export interface FilterGroup {
  op: 'and' | 'or' | 'nor'; // 与 Wow 的三个逻辑操作符同值
  children: FilterNode[];
}
export interface FilterLeaf {
  field: string;
  operator: FilterOperatorName; // Wow FilterOperator 的同值字符串字面量
  value: FilterValue; // 语义值，形状由字段的 FieldKind 定义；不是编译结果
}
export type FilterNode = FilterGroup | FilterLeaf;

/** 配置中的枚举一律是与 Wow 枚举同值的字符串字面量，编译时映射回枚举；配置因此是纯 JSON。 */
export type FilterOperatorName = `${FilterOperator}`;
/** 递归地把枚举成员换成其字符串取值，其余结构不变。 */
type LiteralEnums<T> = T extends string
  ? `${T}`
  : T extends object
    ? { [K in keyof T]: LiteralEnums<T[K]> }
    : T;

// 内置 kind 的值类型（示意，完整定义在 filter/ 各 kind 中）
type StringFilterValue = string | string[];
type NumberFilterValue = number | [number, number] | number[];
type BooleanFilterValue = boolean;
type EnumFilterValue = (string | number)[];
type ReferenceFilterValue = {
  // id 与 FieldOption／OptionSource 同类型；快照 label，重开无需回填
  items: { id: string | number; label: string }[];
};
type DateTimeFilterValue =
  | {
      type: 'absolute';
      // ISO 8601 时刻或 YYYY-MM-DD 日期；两端闭区间。只写日期表示整天：
      // 作为下界取该日 00:00，作为上界取该日最后一毫秒（`to` 与 `LTE` 的单值皆是）；
      // 带时刻的字符串两侧含义相同；带显式偏移的字符串是固定时刻，不再按时区解析。
      // 不含 `to` 即上界开放；`timeZone` 仅覆盖本条件的解析时区。
      from: string;
      to?: string;
      timeZone?: string;
    }
  | {
      type: 'relative';
      amount: number;
      unit: 'hour' | 'day' | 'week' | 'month' | 'quarter' | 'year';
      // 窗口落在此刻的哪一侧；不写即过去。"最近 7 天"问已经发生了什么，
      // "未来 7 天"问什么将要到期，两者都是业务常问。
      // `amount` 为不超过 `MAX_RELATIVE_DATE_AMOUNT`（100000）的正整数，超出报
      // `filter.value.relative-too-large`；编译对超界值不抛异常而是钳到 `Date`
      // 的极限时刻，准入是唯一闸门。作为 `GTE`／`LTE` 的单值时，相对值取远离
      // 此刻的那一端："最近 7 天"两者都比较 7 天前那一刻，"未来 7 天"都比较
      // 7 天后那一刻，因为"此刻"不是用户输入的边界；preset 仍按操作符取起点或
      // 终点（`LTE today` 为今日最后一毫秒），摘要相应写成 "on or before 7 day ago"。
      direction?: 'past' | 'future';
    }
  | {
      type: 'preset';
      preset:
        | 'today'
        | 'yesterday'
        | 'tomorrow'
        | 'thisWeek'
        | 'lastWeek'
        | 'nextWeek'
        | 'thisMonth'
        | 'lastMonth'
        | 'nextMonth'
        | 'thisQuarter'
        | 'lastQuarter'
        | 'nextQuarter'
        | 'thisYear'
        | 'lastYear'
        | 'nextYear';
    };

// ---- 实例与偏好 ----
export interface ViewInstance {
  id: string;
  definitionId: string;
  title: string;
  scope: 'system' | 'shared' | 'personal';
  revision: string; // 不透明，只做相等比较；代码声明的系统视图固定为 'code'
  config: ViewConfig;
}
export type ViewInstanceSummary = Omit<ViewInstance, 'config'>;
export interface ViewPreferences {
  order: string[];
  defaultInstanceId: string | null;
  revision: string;
}

// ---- 问题 ----
export interface Issue {
  code: string; // 程序分支依据
  severity: 'error' | 'warning';
  path: (string | number)[]; // 指向 config 内的位置
  params?: Record<string, string | number>;
}
```

文案不在 model 中；`Issue.code` 由 UI 层映射到文本。

**配置模型原则：配置是意图模型，不是协议 DTO，也不是组件树。**

- 保存用户表达的语义，不保存编译结果。"最近 7 天"保存为 `{ type: 'relative', amount: 7, unit: 'day' }`，而不是两个绝对时间；编译在每次执行时依据 `ctx.now` 进行。
- 组件选择不进配置。编辑器由 `(field.kind, operator, value.type)` 推出；业务想为某字段换编辑器写在 `FieldDefinition.editor`，那是定义。UI 组件改名或重写不影响任何已保存视图。
- 语义变体用值内部的 `type` 判别，由 FieldKind 拥有。它承担组件节点模型中 `component` 字段的作用，但描述的是语义而不是 UI。自定义 kind 自行定义值的形状、校验、编译与编辑器描述，扩展能力与组件节点模型等价。
- 配置是纯 JSON：其中的枚举一律使用与 Wow 枚举同值的字符串字面量（`${Enum}` 模板字面量类型），编译时映射回枚举。Wow 协议类型只在"本身就是意图、不含字段引用与枚举"时直接复用；`HavingExpression`、`DerivedExpression` 结构可复用但含枚举，故以 `LiteralEnums<>` 派生同构的字面量版本；`AggregationGroup`、`AggregationMetric`、`AggregationExpression`、`AggregationElement`、`FieldSort` 引用字段或筛选，因此有配置层孪生类型。定义是代码，可以直接使用 Wow 枚举。
- 编辑器状态不进配置：折叠、当前标签页、未完成的输入、拖动中的临时位置归控制器；节点在编辑期的稳定 key 由控制器分配，不持久化；Issue 用路径定位节点。保存要求配置无 error，因此不存在保存半成品再恢复的问题。
- 两种缺失要分开：**FieldKind 未注册**时内核没有它的 `validate` 与 `compile`，因此报 error 级 Issue，视图进入待修复，`apply` 被拒绝；**kind 已注册但缺少 React 渲染器**只影响编辑，配置照常校验与编译，UI 以只读方式显示原值并给出 warning。

`ViewConfigBase` 的三个字段都是"观察方式"的一部分，所以随视图保存而不是作为个人偏好：

- **`filterMode`。** 高级模式写出的含 OR 或嵌套分组的树无法在简单模式中呈现，重开时必须仍是高级模式。`simple` 只允许"单个 AND 组、子节点全为叶子"的树；`filterMode: 'simple'` 配上不满足的树产生 warning，UI 以高级模式打开，不改配置。这个判断只看配置自己的树：作用域条件以嵌套分组并入执行树后它必然不再是简单树，但那说的不是 draft，runtime 在 draft 本身简单时不报这条 warning。
- **`refresh`。** "每 30 秒刷一次"属于视图本身。`refresh` 缺失、为 `null` 或不是对象报 `config.refresh.missing`；`interval` 不是整数（含缺失与非数字）报 `config.refresh.not-an-integer`。`interval` 为 `null`，或介于 `RuntimeLimits.minRefreshInterval` 与 `maxRefreshInterval` 之间的有限整数；上界保证换算成毫秒后不超过计时器的 32 位上限，否则 Node 会把超长延迟压成约 1ms 而变成紧密轮询。`0`、负数、非有限值、过小或过大的值一律是 error 级 Issue，无论配置来自代码还是从 store 读入，因为运行时只执行通过校验的 `applied`。计时器归运行时，见第 6 节。
- **布局成对保存。** Record 的 `table` 与 `card`、Analysis 的 `table` 与 `chart` 始终同时持久化，`layout` 只记录当前选择；`chart` 内部再按族保存子对象，跨族切换时控制器保留上一次的子对象。切换布局或图型不会丢失另一套设置。`RecordCapability.layouts` 限制允许的布局，`defaults` 可分别给初值。

## 4. 分层与依赖规则

```text
src/
  model/        类型与常量
  filter/       Filter 树：校验、编译到 FilterExpression、FieldKind 注册表、编辑器描述
  record/       Record：校验、编译到 FilterPagedQuery / CursorQuery、结果投影
  analysis/     Analysis：校验、编译到 AggregationQuery、结果投影为图表或表格数据
  dashboard/    Dashboard：校验、面板绑定解析、全局筛选合并
  runtime/      ViewRuntime、DashboardRuntime、RequestRunner、ViewEngine、命令、validateDefinition
  store/        ViewStore 端口；MemoryViewStore
  react/        钩子与无样式控制器
  ui/           shadcn 组件、默认视图、工作台、主题边界
```

依赖规则六条，全部由 `test/architecture.test.ts` 强制：

1. `model` 不 import 任何目录。
2. `filter` 只 import `model`。
3. `record`、`analysis`、`dashboard` 只 import `model` 和 `filter`，彼此不引用。
4. `runtime` 只 import `model`、`filter`、`record`、`analysis`、`dashboard`、`store` 的端口类型；不 import `react`、`ui`。
5. `store` 只 import `model`。
6. `react` 不 import `ui`；`ui` 可以 import 一切。

`model` 到 `store` 六个目录不出现 React、DOM、`window`、`document`。第三方库落点固定：`@ahoo-wang/fetcher-wow` 只在 `model`、`filter`、`record`、`analysis`、`runtime`，且不导入其弃用的 `Condition` 系符号；`@tanstack/react-table`、`recharts`、`react-grid-layout`、`react-markdown`、`@base-ui/react`、`lucide-react` 只在 `ui`。

包入口：

| 入口                             | 内容                                                                |
| -------------------------------- | ------------------------------------------------------------------- |
| `@ahoo-wang/fetcher-view-engine` | `model`、四个纯内核、`runtime`、`ViewStore` 端口、`MemoryViewStore` |
| `/react`                         | 钩子与控制器                                                        |
| `/ui`                            | 默认组件、默认视图、工作台                                          |
| `/styles.css`、`/themes/*`       | 样式资源，显式导入                                                  |

## 5. 纯内核

四个目录都是同步纯函数，签名统一为"定义 + 配置 进，结果 或 Issue 出"。

```ts
// filter
validateFilter(fields: FieldDefinition[], tree: FilterTree, kinds: FieldKindRegistry): Issue[]   // 取字段表而非整份定义，Dashboard 可传 cfg.fields
isSimpleTree(tree: FilterTree): boolean                  // filterMode 'simple' 的准入判断
compileFilter(fields, tree, kinds, ctx: { now: Date; timeZone: string }): FilterExpression
clearFilter(tree): FilterTree
describeFilter(fields, tree): FilterSummaryItem[]           // 已应用条件的摘要

// record
defaultRecordConfig(def): RecordViewConfig                      // 按 RecordCapability.defaults 补全的完整初始配置
validateRecord(def, cfg: RecordViewConfig, kinds): Issue[]   // 见下方规则
compileRecord(def, cfg, kinds, ctx, page: RecordPageTarget): FilterPagedQuery | CursorQuery   // 按 RecordCapability.paging 判别；首次查询为 { index: 1 } 或 { cursor: null }（Wow 页码从 1 开始）
projectRecord(def, cfg, page: PagedList<RecordData> | CursorPage<RecordData>): RecordView   // 列语义、行、行键；paging 为 { mode: 'paged'; index; total? } | { mode: 'cursor'; nextCursor: string | null }
compileSummaries(def, cfg, kinds, ctx): AggregationQuery | null    // 全范围汇总
projectSummaries(def, cfg, rows | aggregation): SummaryRow

// analysis
defaultAnalysisConfig(def): AnalysisViewConfig                  // 按固定优先级从已声明能力挑选指标；有可分组字段时取其一并按指标降序排序，否则分组为空且 `sort` 为空（无分组聚合只有一行，Wow 拒绝对其排序）
validateAnalysis(def, cfg: AnalysisViewConfig, kinds): Issue[]   // 见下方规则
compileAnalysis(def, cfg, kinds, ctx): AggregationQuery          // 同构映射；三处 FilterTree 编译为 FilterExpression
compileAnalysisTotals(def, cfg, kinds, ctx): AggregationQuery | null   // table.totals 为 true 时的无分组聚合，否则 null
projectAnalysis(def, cfg, result, totals?): AnalysisView          // 表格列与行；图表系列；合计行取自 totals，metric 卡片趋势模式的标题值亦取自 totals
resultSchema(def, cfg): ResultSchema                     // 结果行校验依据

// dashboard
emptyDashboardConfig(): DashboardViewConfig                     // 无面板、无全局字段的完整初始配置
validateDashboard(cfg: DashboardViewConfig, scope: ViewInstance['scope'], refs: Map<string, PanelReference>, kinds: FieldKindRegistry): Issue[]   // 含 bindings 的字段 kind 兼容性与引用实例的可见范围；PanelReference = { instance; definition; fields }，fields 是该视图可触及的字段集，由 Engine 解析引用时计算（分析视图含其展开的元素字段），面板的 filter 与 bindings 都据此判断而不是只看根字段
mergeGlobalFilter(panel, dashboardFilter, bindings): FilterTree   // 把 Dashboard 的 filter 经 bindings 映射后 AND 合并到面板已应用筛选
```

`validateDashboard` 先检查骨架：`fields`、`panels` 必须是数组，每个面板、字段、binding 与链接项必须是对象，Markdown 的 `content` 必须是字符串，否则以 `dashboard.shape.invalid` 在出错的路径上报 error 并跳过该节点，而不是让存储里的畸形配置以 `TypeError` 击穿 `open`；`DashboardRuntime` 读取 `panels` 与 `refresh.interval` 时同样把它们当作未信任输入，骨架未修好之前没有面板可加载，也没有计时器可开。`validateDashboard` 同时覆盖内容面板：Markdown 内容与链接数量有上限；`src` 与 `href` 只接受 http、https、mailto 与相对路径，其余产生 error 级 Issue。内容面板不进入 `mergeGlobalFilter`，`DashboardRuntime` 不为其创建子 runtime，它们只是布局中的静态项。URL 合法不代表资源可信，UI 层按第 9 节处理渲染安全。

`validateDefinition(def, kinds): Issue[]` 是纯函数，但放在 `runtime/` 而不是某个内核里：它同时需要三个内核的 `validate*` 去校验系统视图，而内核之间不得互相引用。`ViewEngine` 在构造时对每份定义跑一次，结果经 `onIssue` 报出并留在 `definitionIssues(id)`；含 error 的定义仍在注册表里，但 `open`／`create`／`list` 一律以 `view.definition.invalid` 拒绝——比启动即崩溃温和，也比让它在用户打开视图时抛 `TypeError` 诚实。检查内容：字段名必须匹配 Wow 的查询字段语法 `^@?[A-Za-z_][A-Za-z0-9_-]*(\.(?:@?[A-Za-z_][A-Za-z0-9_-]*|[0-9]+))*$`，根字段与 element 字段同样适用，否则编译期会抛 `TypeError` 而不是产生 Issue；字段名在根字段内唯一，每个 element 的字段作用域内同样唯一；配置中引用 element 字段一律写成 `${path}.${field}` 的完整路径，因此与同名根字段不会混淆，能力查找也有确定归属；反过来，声明按自身作用域写相对名——`elements[].aggregations[].field` 写元素内的名字，路径由 `qualify` 唯一合成，它一律加前缀，不再放过「已经以路径开头」的名字（两种拼写都接受等于没有约定，还会让 `items.sku` 在元素根部和在一个恰好同名的嵌套对象里含义不同）；`elements[].aggregations[].field` 必须是该元素声明过的字段，与根字段的同名检查对齐；`views[].id` 在定义内唯一，否则按名称查找无法确定使用哪一份能力；`RecordCapability.layouts` 非空且 `rowKey` 指向已声明字段，`AnalysisCapability.fields[].field` 与 `elements[].path` 同样必须存在，使各 `default*Config` 对任何被接受的定义都能返回合法完整配置；每个 `views[].config.kind` 必须与所属定义的能力匹配：`kind: 'data'` 只接受 `record`／`analysis` 且对应能力已声明，`kind: 'dashboard'` 只接受 `dashboard`，否则报 error（系统视图不可覆盖，不能交付一个只能待修复的只读视图）；每个系统视图的配置还要通过对应的 `validate*`（Dashboard 系统视图在此只做本地结构校验，面板引用需要加载被引用实例与其定义，因此由 Engine 在注册或首次打开时用 `validateDashboard` 的完整入参复验，失败按该面板不可用处理）；`definition.id` 与每个 `views[].id` 都不得包含 `:`，否则合成的 `system:${definitionId}:${id}` 会歧义（`('a:b','c')` 与 `('a','b:c')` 撞车），报 error；`AnalysisCapability` 至少要能构造一个指标（`count` 为 true，或某个字段声明了非空 `functions`、`any`、`distinctCount` 或 `percentile`），否则该能力不可用，报 error。`defaultAnalysisConfig` 按固定优先级取第一个可用者，因此总能返回合法配置：`COUNT` → 首个有非空 `functions` 的字段（取其首个函数）→ 首个 `distinctCount` 字段 → 首个 `percentile` 字段（`percentile: 95`）→ 首个 `any` 字段。分组可以为空，Wow 允许无分组聚合；此时默认配置不带 `sort`，因为 Wow 对无分组的 `sort` 与 `having` 都会抛错。

配置来自持久化端口，不可假设结构可信：`validateFilter` 以同一次迭代遍历检查骨架与预算——根必须是分组，子节点必须是分组或带字符串 `field`／`operator` 的叶子，否则以 `filter.node.invalid` 在该路径报 error 并跳过该节点，畸形条目同样计入 `maxFilterNodes`；超出 `maxFilterDepth`（缺省 8）或 `maxFilterNodes`（缺省 256）立即报 error 并停止遍历，且是唯一的发现。骨架有问题时不再按 kind 校验；`compileFilter` 与 `describeFilter` 对同样的输入不抛异常而是跳过畸形条目，因此递归不会耗尽调用栈，`TypeError` 也不会击穿 `open`。`ViewConfigBase` 本身同理：非对象的配置报 `config.invalid`，`filter` 不是分组报 `config.filter.invalid`。`validateAnalysis` 先检查配置骨架（`groups`、`metrics`、`sort`、`table.columns`、`elements` 须为数组且成员为对象、别名为字符串，`table`、`chart` 须为对象），不满足只报 `analysis.config.malformed` 并停止；随后对 NUMERIC／DISTINCT_COUNT／PERCENTILE 表达式、DERIVED 表达式与 `having` 树按同一份 `maxFilterDepth`／`maxFilterNodes` 以迭代遍历准入（与 Wow 的 `MAX_EXPRESSION_DEPTH`／`MAX_EXPRESSION_NODES` 同为 8／256；深度按每棵树计，节点数按同类树共享一份），超限报 `analysis.expression.too-deep`／`too-many-nodes` 或 `analysis.having.too-deep`／`too-many-nodes` 且不再递归。

**一个分组内每个字段只出现一次。** 区间用一个 `BETWEEN`，多选用一个 `IN`，同一分组的直接叶子里同一字段出现第二次不是第二个问题而是笔误，`validateFilter` 在第二次出现处报 `filter.field.duplicate-in-group`；AND、OR、NOR 一律如此，要对同一字段叠加不同条件请建子分组。`ELEMENT_MATCH` 的谓词是独立的树，单独计。编辑器的字段选择器据此只列出本分组尚未使用的字段（`fieldsFor(parent)`）。

**未填写的条件不是错误。** 用户选好字段、还没说要比什么，这是编辑器的正常中间态：这样的叶子不按 kind 校验，也不进入编译，因此加一行条件既不收窄结果也不阻塞 apply。`FieldKind.emptyValue` 因此返回"尚未填写"而不是某个可用默认值——给数字播种 `0`、给日期播种"今天"，会在用户还没表达任何意图时就把列表筛掉。判断由 `isBlankLeafValue` 统一做：缺省认 `null`、`''` 与空数组；值形状自成一体的 kind 用可选的 `isBlank({ value, operator, field, kinds })` 自述，它**替代**而不是补充缺省规则，所以同时也从 `''` 或 `[]` 起步的 kind（metadata 各 kind）必须一并认出这些形状，纯空白字符串同样算未填写。持有树的 kind（`elementMatch`）的空白就是其谓词的空白：谓词里没有任何有效叶子——没有条件，或每条条件本身仍未填写——即为空白，由 `isBlankFilter` 按与外层同一条规则判定；字段未知、kind 未注册或操作符不受支持的叶子不算空白，否则会藏掉 `validateFilter` 该报的问题。不需要输入的操作符（`IS_NULL` 一类，kind 把其编辑器声明为 `none`）永远不算未填写，否则条件会被当成空值丢掉。

`FieldKindRegistry` 是 filter 的核心扩展点，见第 10 节。相对时间条件在 `compile*` 中依据注入的 `ctx.now` 求值，纯内核不读系统时钟。

`validateRecord` 先检查骨架：`sort` 必须是数组且每项是带字符串 `field` 的对象，`table.columns` 同样，`card` 必须是对象且 `title` 为字符串、`fields` 为字符串数组，`summaries` 若存在必须是数组且每项带字符串 `field` 与 `fn`，否则以 `record.sort.invalid`／`record.table.invalid`／`record.card.invalid`／`record.summaries.invalid` 在出错路径报 error，共享配置的 Issue 照常保留，其余规则不再运行。骨架完好时的规则：`layout` 在 `RecordCapability.layouts` 之内；`table.columns`、`card.title`／`fields`／`image` 引用的字段都必须存在且不是无字段种类（`record.field.not-a-column`）；`table.columns[].field`、`sort[].field` 与 `summaries[]` 的字段-函数对都不得重复，在第二次出现处报 `record.column.duplicate`／`record.sort.duplicate`／`record.summary.duplicate`；`pageSize` 为不超过 `RuntimeLimits.maxPageSize` 的正整数；`sort[].field` 必须存在且 `sortable` 为 true，游标模式下 `sort.length` 不超过 Wow 的 `MAX_CURSOR_SORT_FIELDS`（32）；每个 `summaries[]` 的函数必须出现在该字段的 `summary` 集合中。

`validateAnalysis` 的规则：别名在 groups 与 metrics 之间唯一，且必须是单段（不含 `.`）、不以保留前缀 `__wow` 开头，并且必须匹配 Wow 的查询字段单段语法（否则报 `analysis.alias.invalid`），与 Wow 的 `aggregationAlias` 一致；`sort` 只能引用已存在的 group 或 metric 别名，`having` 只能引用非 `ANY` 的 metric 别名（Wow 协议不支持）；二者都要求至少一个分组，无分组时分别报 `analysis.sort.requires-group` 与 `analysis.having.requires-group`，与 Wow `aggregation.query()` 的 `validateSort`／`validateHaving` 一致，让存储的配置在准入阶段而不是服务端被拒；`DERIVED` 只能引用在它之前声明的非 `ANY` metric 别名，按 `metrics` 顺序维护可引用集合，前向引用与环报 error；`percentile` 在开区间 (0, 100)，与 Wow 的 `aggregation.percentile` 一致，`100` 报 error；`HISTOGRAM` 的 `interval` 必须是大于 0 的有限数，与 `aggregation.histogram` 一致；表达式中的每个 `CONSTANT.value` 必须有限（在预算准入之后递归检查普通表达式与派生表达式），与 `aggregation.constant` 一致；`BINARY` 的 `DIVIDE` 右侧为常量 0 报 error；`elements[].path` 必须在能力中声明，展开后可用字段为根字段加元素字段；`any`、`distinctCount`、`percentile`、`expressions`、`having` 等未在能力中声明却被使用报 error；每个 group 的 `type` 必须在该字段的 `groups` 中，`DATE_HISTOGRAM.unit` 必须在其 `dateUnits` 中，`NUMERIC.function` 必须在该字段的 `functions` 中，能力未声明即报 error；`limit` 必须是不超过 `RuntimeLimits.maxAnalysisRows` 的正整数，`groups`、`metrics`、`elements`、`sort` 的数量与 `limit` 始终受 Wow `AGGREGATION_LIMITS` 约束，`AnalysisCapability.limits` 只能进一步收紧（取更小者）；`sort[].alias` 不得重复（`analysis.sort.duplicate`），`DATE_HISTOGRAM.dense` 只能用于唯一分组（`analysis.group.dense-not-alone`）；`metrics` 不能为空，报 `analysis.metrics.empty`，与 Wow `metrics must not be empty.` 一致；`metrics[].type` 不在六种之内报 `analysis.metric.type-unknown`，`compileMetric` 对此抛错而不发出空洞（定义准入另行检查这些上限与 `defaultLimit` 本身是正整数且 `defaultLimit` 不超过 `maxLimit`）；`TERMS.missingKey` 与 `DATE_HISTOGRAM.timeZone` 若存在则不能为空白字符串，与 Wow 的 `aggregation.terms`／`dateHistogram` 一致；`table.columns[].alias` 必须是当前 groups 或 metrics 的别名且不重复。图表规则：`chart[族(type)]` 必须存在；`x`、`splitBy`、`category`、heatmap 的 `x`／`y`、`funnel.group.category` 必须是分组别名，`series[].metric`、`value`、scatter 的 `x`／`y`／`size`、`metric`、`compare.metric`、`funnel.metrics.items[].metric` 必须是指标别名；`splitBy` 不等于 `x`，且存在时 `series` 恰有一个指标；`combo` 的每个系列必须有 `type`；heatmap 的 `x`、`y` 不同，scatter 的 `x`、`y` 不同；`maxSlices` 必须是不小于 2 的整数（NaN 与小数会让全部分类并入"其他"），且只能用于可加指标（`COUNT` 或 `SUM` 的 `NUMERIC`）：`AVG`、`MIN`／`MAX`、`DISTINCT_COUNT`、百分位无法由各分类结果推出合并值，报 error；`referenceLines` 引用的轴必须有系列；**图表必须消费全部分组别名**（cartesian 用 `x` 加可选 `splitBy`，pie 用 `category`，heatmap 用 `x`／`y`，scatter 用 `category`，group 漏斗用 `category`，metric 卡片要求无分组或仅 `trend.x`），否则结果里同一坐标会有多行，而 AVG、百分位、DISTINCT_COUNT 无法在投影层安全再聚合，报 error；漏斗至少两个阶段，`metrics` 形态要求分组为空，`group` 形态的 `order` 无重复；`metric` 无 `trend` 时要求分组为空，有 `trend` 时要求恰有一个 DATE_HISTOGRAM 分组且别名等于 `trend.x`，且 `metric` 与 `compare.metric` 必须是可加指标（与 `maxSlices` 同一判据），否则报 `chart.metric.trend-not-additive`。`compileAnalysis` 因同构而退化为映射：查询级、指标级、元素级三处 `FilterTree` 分别编译为 `FilterExpression`，元素级以元素字段为作用域；`projectAnalysis` 的结果列为全部 group 别名加全部 metric 别名，`DERIVED` 也是普通列；合计行来自 `compileAnalysisTotals` 的独立结果，因此 `AVG`、`DISTINCT_COUNT`、百分位等不可加指标也正确；该查询与主查询共享同一调度预算，失败只使合计行不可用，不影响主结果。图表所需的派生整形也在此完成：`splitBy` 透视、饼图"其他"合并、漏斗累计与转化率、热力图矩阵、metric 卡片的比较值。metric 卡片带 `trend` 时的标题值取自合计行（`projectAnalysis` 的 `totals`），无合计行时按分桶求和；`compare` 与 `target` 在有无 `trend` 时同样生效。

`validateDashboard` 的规则：`cfg.fields[].name` 非空、符合字段语法且唯一；`panels` 数量不超过 `RuntimeLimits.maxDashboardPanels`，该检查先于创建任何子 runtime；`panels[].id` 非空且全局唯一，重复或为空报 error（面板 id 是运行时查找、布局 key 与错误归属的依据）；`layout` 的 `x`、`y` 为非负有限整数，`w`、`h` 为正有限整数，且 `x + w` 不超过栅格列数，否则面板会在适配层消失或重叠；`bindings[].globalField` 必须在 `cfg.fields` 中，`bindings[].panelField` 必须在被引用实例的定义中，且两者 kind 兼容；全局筛选按 `cfg.fields` 与传入的 `kinds` 走 `validateFilter`，因此自定义 kind 与值形状同样受检；同一面板内 `bindings[].globalField` 不能重复（一个筛选叶子只能替换成一个目标字段，一对多展开的布尔语义未定义）；**每个数据面板必须绑定全局筛选树实际引用的全部字段**，否则部分映射无法保持布尔语义（`region = CN OR product = X` 丢掉一支会错误收窄，视为真会抹掉整个条件），缺绑定报 error；映射后的树还要以被引用定义的字段与 `kinds` 再跑一次 `validateFilter`，因为目标字段可能限制了操作符或候选，kind 相同不等于可接受同一条件；该次校验同时重新核对深度与节点预算，两棵各自合规的树 AND 合并后仍可能超限，超限记为该面板的 error 而不进入编译；被引用实例必须是 Record 或 Analysis；**被引用实例的可见范围必须覆盖 Dashboard 自身的范围**：`personal` Dashboard 可以引用任何可读实例，`shared` 或 `system` Dashboard 只能引用 `shared` 或 `system` 实例，否则产生 error 级 Issue，UI 提示先把被引用视图另存为共享。打开时若某个被引用实例不可读（已删除或无权限），只有该面板显示"不可访问"，其余面板照常工作。内容面板规则见下段。

## 6. 运行时

一个打开的视图对应一个 `ViewRuntime`，它是带 `subscribe / getSnapshot` 的小 store。

```ts
export interface ViewRuntime<C extends ViewConfig = ViewConfig> {
  readonly id: string; // runtimeId，与 instanceId 分离
  readonly kind: C['kind']; // 判别字段，供调用方收窄
  readonly definition: ViewDefinition;
  readonly kinds: FieldKindRegistry; // 准入所用的注册表，筛选编辑器据此编辑
  readonly fields: readonly FieldDefinition[]; // 筛选编辑器编辑的字段：数据视图取定义，Dashboard 取 draft 声明的全局字段（随 draft 变化，每次渲染读取）
  readonly disposed: boolean; // dispose 之后为 true，所有命令成为空操作
  getSnapshot(): ViewRuntimeState<C>;
  subscribe(listener: () => void): () => void;

  edit(patch: Partial<C>): void; // 只改 draft，同步
  apply(): void; // validate(draft) 无 error → applied = draft，执行
  refresh(): void; // 重跑 applied
  setEditing(active: boolean): void; // 编辑器获得／失去输入焦点时调用，暂停自动刷新
  setScopeFilter(tree: FilterTree | null): Issue[]; // 外层注入的附加条件，AND 到已应用筛选；不改 draft／saved
  dispose(): void; // 最后一次通知订阅者后清空监听
}

/** 分页与选择只属于 Record；Engine 按 config.kind 返回对应的窄接口。 */
export interface RecordViewRuntime<
  P extends 'paged' | 'cursor' = 'paged' | 'cursor',
> extends ViewRuntime<RecordViewConfig> {
  page(target: RecordPageTarget<P>): void;
  select(keys: RecordKey[]): void;
}

/** 打开一个实例得到的判别联合；按 runtime.kind 收窄。 */
export type AnyViewRuntime =
  RecordViewRuntime | ViewRuntime<AnalysisViewConfig> | DashboardRuntime;

/** Dashboard 的公开面：快照多出 panels 与 resolving，并能等待引用加载、按面板取子 runtime。 */
export interface DashboardRuntime extends ViewRuntime<DashboardViewConfig> {
  getSnapshot(): DashboardRuntimeState; // ViewRuntimeState + panels: DashboardPanelState[] + resolving
  ready(): Promise<void>; // 每个面板引用都已加载或确认不可读
  panelRuntime(panelId: string): DataViewRuntime | null; // 宿主自行驱动某个面板时使用
}

/** 由配置类型推出的 runtime 类型，create 用它保留静态收窄。 */
export type RuntimeFor<C extends ViewConfig> = C extends RecordViewConfig
  ? RecordViewRuntime
  : ViewRuntime<C>;

/** 分页目标由 RecordCapability.paging 决定；游标模式的第一页是 cursor: null。 */
export type RecordPageTarget<P extends 'paged' | 'cursor'> = P extends 'paged'
  ? { index: number }
  : { cursor: string | null };

export interface ViewRuntimeState<C> {
  saved: ViewInstance | null; // 保存基线；null 表示未保存的新视图
  title: string; // 未保存时来自 create 的输入，已保存时等于 saved.title
  scope: ViewInstance['scope']; // 同上；决定首次保存使用的创建许可
  draft: C;
  applied: C;
  issues: Issue[]; // validate(draft 与作用域条件合并后的有效配置)
  dirty: boolean; // !sameJson(draft, saved?.config)
  query: {
    status: 'idle' | 'loading' | 'success' | 'error';
    error?: Issue;
    requestId?: string;
  };
  result: { config: C; data: ProjectedView; receivedAt: number } | null; // 只随成功推进
  selection: RecordKey[]; // 只覆盖当前结果；范围变化即清空，见下
  write: WriteState | null; // 最近一次写入的待处理结局，见第 7.4 节
  editing: boolean; // 由 setEditing 维护，用于暂停自动刷新
}

/** 写入的非成功结局；成功直接推进 saved 并清空该字段。 */
export type WriteState = { requestId: string; payload: WritePayload } & (
  | { kind: 'conflict'; remote: ViewInstance | ViewPreferences }
  | { kind: 'rejected'; issue: Issue }
  | { kind: 'unknown' }
);

/** 原样保留的写入正文，覆盖与重试都用它，不从当前草稿重新推导。
 *  正文自带 `id` 与期望 `revision`：重试是同一次逻辑写入，因此沿用同一期望；
 *  只有"覆盖"把期望推进到冲突报告的 `revision`，并生成新的 requestId。 */
export type WritePayload =
  | { action: 'preferences'; definitionId: string; next: ViewPreferences }
  | {
      action: 'create';
      input: Omit<ViewInstance, 'id' | 'revision'>;
      /** 'first-save' 把源 runtime 绑定到新实例，'save-as' 保持源 runtime 不变。 */
      intent: 'first-save' | 'save-as';
    }
  | { action: 'save'; id: string; revision: string; config: ViewConfig }
  | { action: 'rename'; id: string; revision: string; title: string }
  | { action: 'delete'; id: string; revision: string };

export type WriteAction = WritePayload['action'];

/** 未打开实例的写入的可寻址身份；结局存放在 engine.pendingWrites()。 */
export interface WriteHandle {
  readonly id: string;
}

/** 所有写入方法的唯一拒绝类型：非成功结局连同其句柄一并交给调用方。 */
export class ViewWriteError extends Error {
  readonly handle: WriteHandle;
  readonly state: WriteState;
}
```

规则：

- `result.config` 是产生该结果的配置，不随 draft 变化；UI 用它标注"结果对应的条件"。
- 选择绑定当前结果：`page`、`apply` 与解释环境变化清空 `selection`；`refresh` 后按新结果的行键求交集，消失的行自动移出。本轮不支持跨页选择，批量动作只作用于当前结果中仍存在的行。
- 新的 `apply / refresh / page` 替代同一 runtime 的在途请求，旧响应到达后丢弃。这由 `RequestRunner` 用 per-runtime key 实现，全局并发上限与队列来自 `RuntimeLimits`（`maxConcurrentQueries`、`maxQueuedQueries`、`maxPageSize`、`maxAnalysisRows`、`minRefreshInterval`、`maxRefreshInterval`、`maxFilterDepth`、`maxFilterNodes`、`maxDashboardPanels`）。
- 状态变更同步提交后再通知订阅者；相同状态返回相同对象，子对象引用稳定，以配合 `useSyncExternalStore`。`dispose` 是最后一次通知：订阅者据此读到 `disposed`，`useOpenView` 才能在实例被别处删除时自行重开，而不必等一次碰巧的渲染。
- runtime 不做持久化。保存是 Engine 的命令，成功后 Engine 调用 `runtime.markSaved(instance)` 推进基线。
- **注入的作用域条件同样要准入。** `setScopeFilter` 按本 runtime 的定义与 kinds 校验合并后的有效筛选（含深度与节点预算），返回 Issue 列表；含 error 时不改变已应用口径也不执行，因此自定义宿主与 Dashboard 走同一条准入路径。作用域条件从打开起就与配置一起准入：`issues` 始终是"draft AND 作用域"这份有效配置的校验结果，构造、`edit`、`adoptSaved` 与 `setScopeFilter` 都按这一条规则重算；`mergeFilters` 把作用域作为一个嵌套分组追加在 draft 自身条件之后（不拍平：分组内字段唯一，而宿主对同一字段再收窄是第二个问题而不是重复），因此指向 draft 树的 Issue 路径不因作用域而移位；作用域自身的子树多占一层深度，单条条件在线上仍编译为它本身。合并只丢弃结构合法且没有叶子的空树：含畸形条目的树不算空（`isEmptyFilter` 为 false），畸形条目保留在合并结果里由准入报出；根不是分组的 filter 不参与合并，原样交给准入报 `config.filter.invalid`。Dashboard 的 `fields` 访问器只交出结构合法的字段项，畸形项留给准入。
- **只执行准入过的配置。** `apply` 与 `setScopeFilter` 只提升通过准入的口径；打开时 `applied` 若未通过准入，`refresh` 与 `page` 同样是空操作，直到一份修正后的 draft 被 `apply`。否则"待修复"只挡住 `apply` 一个入口，刷新或翻页就会把被拒绝的配置发出去。
- **自动刷新。** `applied.refresh.interval` 非空时由该 runtime 持有唯一计时器，到期调用 `refresh()`。四种情况暂停：`issues` 含 error、`editing` 为 true（`useFilterEditor` 与 `useAnalysisEditor` 提供 `focus`／`blur`，默认 `FilterPanel` 与 `AnalysisEditor` 在焦点进入或离开其根元素时调用，内部焦点移动不触发；控件的弹层经 Portal 渲染在根元素之外，焦点进入弹层时根元素内仍有带 `data-popup-open` 的触发器，算作未离开；查询进行中不冻结编辑器）、宿主报告页面不可见、上一次请求仍在途。计时与可见性都来自注入的 `RuntimeEnvironment`（见下），runtime 不触碰 DOM。计时器随 `dispose` 释放。多个 React 组件观察同一 runtime 不会产生多个计时器。

`DashboardRuntime` 持有 N 个子 `ViewRuntime` 加一个全局筛选草稿。`apply()` 校验全局筛选，为每个面板计算 `mergeGlobalFilter` 后经 `setScopeFilter` 注入再触发子 runtime 执行；宿主注入给 Dashboard 的作用域条件与数据视图同样从打开起就并入校验。`dashboard.panels.too-many` 这类路径为 `['panels']`、不属于任何一个面板的 Issue 按 Dashboard 整体的 error 处理，阻止全部面板执行；子 runtime 拒绝注入的作用域时，该子 runtime 被释放而不是继续跑旧口径，拒绝理由以 `['panels', index, ...]` 为路径记在该面板的 `issues` 里，其余面板不受影响；作用域条件不进入子 runtime 的 `draft` 或 `saved`，面板因此不会变脏，也不会把 Dashboard 条件保存回被引用实例，执行的有效配置记录在 `result.config`；每个面板独立 loading / error / result，Dashboard 不汇总成单一状态。自动刷新由 DashboardRuntime 按自身 `refresh.interval` 统一计时并触发全部数据面板的 `refresh()`；被引用实例自身的 `refresh` 配置在 Dashboard 内忽略，避免两层计时器。布局编辑是普通 `edit({ panels })`。

`ViewEngine` 是注册表与命令入口（代码里是一个类，下面以接口形式列出其公开面），命令语义见第 7 节：

```ts
export interface ViewEngine {
  readonly store: ViewStore;
  readonly environment: RuntimeEnvironment; // 时钟、计时器、可见性；由创建方注入
  definitions: ReadonlyMap<string, ViewDefinition>;
  resolveSource(key: string): ViewSource; // Pick<QueryApi, 'paged' | 'cursor' | 'aggregate'>
  resolveOptions(key: string): OptionSource; // FieldDefinition.remote 的候选来源

  open(
    instanceId: string,
    options?: { scopeFilter?: FilterTree | null },
  ): Promise<AnyViewRuntime>; // store.get → validate → runtime；按 runtime.kind 收窄；scopeFilter 从首次查询起生效并与配置一起准入
  create<C extends ViewConfig>(
    definitionId: string,
    input: { title: string; scope: 'personal' | 'shared'; config: C },
  ): RuntimeFor<C>; // 未保存的新视图；config 必填，由 default*Config / emptyDashboardConfig 生成
  save(runtime: ViewRuntime): Promise<ViewInstance>; // saved ? store.save : store.create
  saveAs(runtime, input: { title; scope }): Promise<ViewInstance>;
  rename(id: string, title: string): Promise<ViewInstance>;
  delete(id: string): Promise<void>;
  reorder(definitionId: string, order: string[]): Promise<ViewPreferences>;
  setDefault(
    definitionId: string,
    instanceId: string | null,
  ): Promise<ViewPreferences>;
  resolveDefault(summaries, preferences, explicit?): string | null; // 第 7.3 节的解析规则
  retryWrite(target: ViewRuntime | WriteHandle): Promise<ViewInstance | void>; // 复用原 requestId 与原正文重放；创建意图返回新实例
  abandonWrite(target: ViewRuntime | WriteHandle): void; // 清除写入状态，草稿保留
  resolveConflict(
    target: ViewRuntime | WriteHandle,
    choice: 'reload' | 'overwrite',
  ): Promise<ViewInstance | void>;
  pendingWrites(): ReadonlyMap<string, WriteState>; // 未结清的写入，按 WriteHandle 索引；含列表命令
  list(definitionId: string): Promise<ViewInstanceSummary[]>; // 代码声明的系统视图 + store.list()
  preferences(definitionId: string): Promise<ViewPreferences>;
  permissions(definitionId: string): ViewPermissions; // store 同步提供，缺省全允许
  definitionIssues(definitionId: string): Issue[]; // 构造时对定义准入的结果
  openRuntimes(): readonly ViewRuntime[]; // 已打开的 runtime，供工作台管理页签
  close(runtime: ViewRuntime): void; // dispose 并从注册表移除；只调 runtime.dispose() 会留下一个死条目
  dispose(): void; // 关闭全部 runtime 并取消在途请求
}
```

```ts
/** runtime 与宿主环境之间的唯一接口；Node 缺省实现始终可见，`/react` 的 `useViewEngine` 注入基于 `document.visibilityState` 的实现。 */
/** reference 字段的远程候选：搜索分页与按 id 回填，供内置 reference 编辑器使用。 */
export interface OptionSource {
  search(
    input: { query: string; cursor?: string },
    signal?: AbortSignal,
  ): Promise<{ items: FieldOption[]; nextCursor: string | null }>;
  resolve(
    ids: (string | number)[],
    signal?: AbortSignal,
  ): Promise<FieldOption[]>;
}

export interface RuntimeEnvironment {
  now(): Date;
  timeZone: string;
  setTimeout(callback: () => void, ms: number): unknown;
  clearTimeout(handle: unknown): void;
  visibility: {
    isVisible(): boolean;
    subscribe(listener: () => void): () => void;
  };
}
```

`open` 与 `create` 对 Record 定义返回 `RecordViewRuntime`，其 `page` 只接受该定义声明的分页模式对应的目标；Analysis 与 Dashboard runtime 没有 `page`／`select`。`apply()` 与 `open()` 的首次查询按 `RecordCapability.paging` 选择目标：`paged` 用 `{ index: 1 }` 调 `source.paged`（Wow `Pagination.index` 从 1 开始），`cursor` 用 `{ cursor: null }` 调 `source.cursor`。`refresh()` 同样回到第一页。

`create`、`save`、`saveAs` 与 `open` 都先核对 `config.kind` 与所属定义的能力：`kind: 'data'` 只接受能力已声明的 `record`／`analysis`，`kind: 'dashboard'` 只接受 `dashboard`，不匹配即 error，不进入执行或保存。这与系统视图的定义期检查是同一条规则。

`open` 时的定义校验：`validate*(definition, instance.config)` 产生 `error` 级 Issue 则 runtime 进入"待修复"，`apply` 被拒绝直到用户修正；`warning` 不阻塞。这是定义演进的全部处理。

## 7. 视图管理

视图管理覆盖实例生命周期、系统／共享／个人三种范围、列表与个人偏好、许可、冲突与未知结果、离开保护。它全部由 `ViewEngine` 的命令实现，默认 UI 与自定义组合走同一条路径，不存在第二套写入逻辑。

### 7.1 实例生命周期

| 命令                                             | store 调用                                                         | 前置检查                                                                      | 成功后                                                                                                                                                                               |
| ------------------------------------------------ | ------------------------------------------------------------------ | ----------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `create(definitionId, { title, scope, config })` | 无                                                                 | 定义存在；标题非空；`scope` 对应的创建许可                                    | 返回 `saved = null` 的 runtime 并立即执行；不进入列表                                                                                                                                |
| `save(runtime)`，`saved = null`                  | `store.create({ definitionId, title, scope, config: draft }, ctx)` | `issues` 无 error；按 `runtime.scope` 检查 `createPersonal` 或 `createShared` | `runtime.markSaved(instance)`；列表刷新                                                                                                                                              |
| `save(runtime)`，`saved != null`                 | `store.save(id, draft, saved.revision, ctx)`                       | 无 error；`saved.scope != 'system'`；`permissions.instance(id).save`          | 发起保存的 runtime `markSaved(instance)`，`dirty = false`；其余打开该实例的 runtime 只推进基线（`moveBaseline`），各自的 `dirty` 按 draft 重算，未结清的写入状态保留给自己的恢复动作 |
| `saveAs(runtime, { title, scope })`              | `store.create(draftAsNew, ctx)`                                    | 无 error；标题非空；对应 scope 的创建许可                                     | 返回新实例并刷新列表；源 runtime 的 `saved` 与 `draft` 都不变；不自动打开，UI 提供"打开"                                                                                             |
| `rename(id, title)`                              | `store.rename(id, title, revision, ctx)`                           | `permissions.instance(id).rename`；标题非空                                   | 以返回的实例整体推进**每一个**打开该实例的 runtime 的 `saved`（含新 `revision`）并刷新列表摘要；`draft` 不受影响，其他 runtime 未结清的写入状态也不受影响                            |
| `delete(id)`                                     | `store.delete(id, revision, ctx)`                                  | `permissions.instance(id).delete`                                             | 打开该实例的每一个 runtime 都 `dispose`；列表刷新；偏好不改写，见 7.3                                                                                                                |
| `open(instanceId)`                               | 代码声明的系统视图直接取自定义；其余 `store.get(id)`               | 实例可读                                                                      | 校验后建立 runtime；配置合法则立即 `apply()`                                                                                                                                         |

**保存的是配置，不是浏览状态。** `ViewInstance.config` 只含 `ViewConfig`；选择、页码、游标、结果一律不保存。重开恢复配置，并从第一页重新执行。切换 Record 或 Analysis 的布局只是一次 `edit({ layout })`，切换图型只是一次 `edit({ chart: { type } })`，各套设置都在配置中，因此切换可逆且随视图一起保存。

首次保存与另存都是 `store.create`，区别只在源 runtime 的处理：首次保存把当前 runtime 绑定到新实例，另存不改变源 runtime。改名与删除不携带配置，因此不要求当前草稿合法。

### 7.2 范围与许可

`scope` 有三个值，回答"谁配置、谁看见、谁能改"：

| scope      | 谁配置                                 | 谁看见           | 普通用户能做什么                                 | 来源                                                        |
| ---------- | -------------------------------------- | ---------------- | ------------------------------------------------ | ----------------------------------------------------------- |
| `system`   | 开发或运维；是定义的基础视图与常用视图 | 该定义的所有用户 | 打开、设为默认、排序、另存；不能改名、覆盖、删除 | 代码：`definition.views`；或服务端以 `scope: 'system'` 返回 |
| `shared`   | 有共享许可的业务用户                   | 该定义的所有用户 | 依许可决定能否覆盖、改名、删除；总可另存         | `ViewStore`                                                 |
| `personal` | 任何用户                               | 仅本人           | 全部                                             | `ViewStore`                                                 |

系统视图有两种来源，Engine 对它们一视同仁。**代码声明**放在 `definition.views`，随应用部署，`revision` 固定为 `'code'`，打开时不经过 store；这是"定义是代码"的自然延伸，适合每个业务对象的默认列表与常用视角。**服务端配置**由运维通过业务系统的管理入口写入，`list()` 以 `scope: 'system'` 返回；引擎不提供这条管理入口。两种来源在列表中合并，代码声明者在前。

Dashboard 的范围受其引用约束：`shared` 或 `system` Dashboard 只能引用 `shared` 或 `system` 的 Record／Analysis 实例，`validateDashboard` 在保存与另存时按目标范围检查，见第 5 节。

系统视图对普通用户只读：`save`、`rename`、`delete` 在派发前被 Engine 拒绝，UI 不显示对应动作，只显示"另存"。运维修改代码声明的系统视图就是一次发版；修改服务端系统视图走业务系统的管理入口。谁能创建与修改共享视图由业务服务决定，前端只消费应用已取得的许可结果：

```ts
export interface ViewPermissions {
  createPersonal: boolean;
  createShared: boolean;
  reorder: boolean;
  setDefault: boolean;
  instance(id: string): { save: boolean; rename: boolean; delete: boolean };
}
```

`store.permissions(definitionId)` 同步返回；缺省全部允许。Engine 在每个命令派发前检查一次，并通过 `useSaveCommands().can` 暴露给 UI 决定按钮可用性。对无权修改的共享视图，用户仍可 `saveAs` 到个人范围。服务端返回 `FORBIDDEN` 时以 Issue 呈现，不伪装成配置错误。不建设角色模型，不在前端做授权推导。

### 7.3 列表、偏好与默认视图

列表、偏好、许可三者独立加载，各自有 loading 与 error，互不阻塞：列表失败不影响已打开的视图，偏好失败只使排序与默认退回服务端顺序。列表项只是摘要，不为其建立 runtime。`engine.list()` 把代码声明的系统视图与 `store.list()` 的结果合并；store 不可用时代码声明的系统视图仍然可用，这保证每个定义至少有一个可打开的视图。

```ts
export interface ViewPreferences {
  order: string[]; // 显式排序的实例 id
  defaultInstanceId: string | null;
  revision: string;
}
```

- **排序。** 工作台展示顺序为 `order` 中出现且仍存在于列表的 id，按 `order` 排列；其余按服务端返回顺序追加。`reorder(ids)` 提交当前可见列表的完整顺序与偏好 `revision`。
- **默认视图。** `setDefault(id | null)` 只改 `defaultInstanceId`。有效默认值的解析规则：显式指定的 `instanceId` 优先；否则 `defaultInstanceId` 存在于列表则用它；否则取排序后的第一项，通常就是第一个系统视图；列表为空时显示空态并提供新建。
- **删除与偏好。** 删除实例不写偏好。读取时忽略已不存在的 id，下一次 `reorder` 或 `setDefault` 写入自然清理。
- **偏好冲突。** `setPreferences` 返回 `CONFLICT` 时重新加载偏好，保留用户本次意图并要求再次确认，不用最新 revision 静默重试。

### 7.4 冲突与未知结果

一个 runtime 同一时刻最多一个在途写入；不同 runtime 的写入互不阻塞。同一目标（已打开的 runtime，或按实例 id／定义 id 寻址的列表命令）上存在未结清的 `unknown` 结局时，新的写入意图被 Engine 以 `view.write.unknown-pending` 拒绝，只有该结局自身的重试放行——否则一次未确认的首次保存再点一次就是两个实例；`rejected` 与 `conflict` 是明确答案，不阻塞新意图（冲突的选项里本就包括另存）。写入结果分四类：

| 结果                                              | runtime 状态                                         | UI 提供的选择                                                                                                                                                               |
| ------------------------------------------------- | ---------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 成功                                              | `markSaved(instance)`                                | 无                                                                                                                                                                          |
| `CONFLICT`                                        | `write = { kind: 'conflict', remote: ViewInstance }` | **重新加载**：保存冲突丢弃草稿（`saved = draft = remote`），改名与删除冲突只推进基线（`saved = remote`，`draft` 不变）；**覆盖**：以 `remote.revision` 重放原意图；**另存** |
| `FORBIDDEN` / `INVALID` / `NOT_FOUND`             | `write = { kind: 'rejected', issue }`                | 显示原因，草稿保留；可修改后作为新意图再保存                                                                                                                                |
| 超时、断线、`UNAVAILABLE`（请求已发出，结果未知） | `write = { kind: 'unknown', requestId, payload }`    | **重试**：同一 `requestId` 与正文再次提交，服务端去重后返回既有实例；**放弃**：清除写入状态，草稿保留                                                                       |

已打开视图的结局存放在 `ViewRuntimeState.write`；所有写入方法成功时兑现各自的结果，非成功时以 `ViewWriteError` 拒绝，其 `handle` 与 `state` 直接交给调用方，不必从 `pendingWrites()` 里猜测并发命令的归属。未结清的结局同时登记在 `engine.pendingWrites()` 中，并可把 `handle` 传给同一组恢复动作，因此未打开实例的列表命令（`rename`、`delete`、偏好写入）同样可以重试、覆盖与放弃。这四种结局的载体就是上述两处，对应的动作是 `engine.retryWrite`、`engine.abandonWrite` 与 `engine.resolveConflict`；三种结局都保留原 `requestId` 与原 `payload`（改名保留目标标题，保存保留提交的配置，创建保留 `intent` 以区分首次保存与另存，偏好保留完整的目标 `ViewPreferences`；偏好冲突的 `remote` 是最新偏好而不是实例），覆盖与重试重放的是原意图而不是当前草稿，创建意图的重试返回新实例供 UI 提供"打开"；`save` 等方法在非成功结局时先写入该状态再 reject，调用方据此展示选项。未知结果不是失败也不是成功。重试成功即推进基线；放弃后草稿仍在，用户可再次保存，此时生成新的 `requestId`；放弃或重试之前，同一目标不接受新的写入。删除遇到 `CONFLICT` 时刷新摘要后要求再次确认。

### 7.5 离开保护与导航

`dirty = draft 与 saved.config 不相等`。工作台在已打开的 runtime 之间切换不销毁 runtime，因此不提示。关闭一个 `dirty` 或存在 `unknown` 写入的 runtime 时要求确认。导航不取消在途写入：写入完成时 runtime 仍存在则更新它，已销毁则丢弃结果，服务端状态不受影响。不提供跨浏览器刷新的草稿恢复。

## 8. 持久化端口与一致性

```ts
export interface ViewStore {
  list(
    definitionId: string,
    signal?: AbortSignal,
  ): Promise<ViewInstanceSummary[]>;
  get(id: string, signal?: AbortSignal): Promise<ViewInstance>;
  create(
    input: Omit<ViewInstance, 'id' | 'revision'>,
    ctx: WriteContext,
  ): Promise<ViewInstance>;
  save(
    id: string,
    config: ViewConfig,
    revision: string,
    ctx: WriteContext,
  ): Promise<ViewInstance>;
  rename(
    id: string,
    title: string,
    revision: string,
    ctx: WriteContext,
  ): Promise<ViewInstance>;
  delete(id: string, revision: string, ctx: WriteContext): Promise<void>;
  getPreferences(
    definitionId: string,
    signal?: AbortSignal,
  ): Promise<ViewPreferences>;
  setPreferences(
    definitionId: string,
    prefs: ViewPreferences,
    ctx: WriteContext,
  ): Promise<ViewPreferences>;
  permissions?(definitionId: string): ViewPermissions; // 由应用预先取得，同步
}
export interface WriteContext {
  requestId: string;
  signal?: AbortSignal;
}
export class ViewStoreError extends Error {
  code: 'CONFLICT' | 'NOT_FOUND' | 'FORBIDDEN' | 'UNAVAILABLE' | 'INVALID';
  /** 冲突时服务端持有的状态；缺省时 Engine 自行回读一次。 */
  remote?: ViewInstance | ViewPreferences;
}
```

`ViewStoreError` 与其判定函数放在 `model/`：端口两侧都要说这门语言，运行时据此分类写入结局，却不能依赖任何 store 实现（分层规则第 4 条要求 `runtime → store` 只取端口类型）。判定按结构而非 `instanceof`，因此第二份包副本或自行构造该形状的适配器同样被识别。

一致性策略两条：

1. **乐观版本。** 覆盖写携带期望 `revision`，不匹配抛 `CONFLICT`。Engine 把冲突暴露给 UI，用户选择"重新加载后覆盖"或"另存"。
2. **幂等 requestId。** 每个逻辑写入生成一次 requestId；超时或网络错误后的重试复用同一 requestId 与同一正文，服务端按 requestId 去重。Engine 在 UI 上把这种情况表述为"保存结果未知，可重试"，不把它当作确定失败，也不当作成功。

`ViewStore` 签发的实例 id 不得以 `system:` 开头，该前缀保留给代码声明的系统视图；Engine 合并列表时丢弃此类条目并报告 Issue，`MemoryViewStore` 在 `create` 时直接拒绝。

本包只提供一个实现：`MemoryViewStore`。它是同步 Map 加自增 revision，服务测试、示例、Storybook 与"只查询不持久化"的场景；可选的 `snapshot: { load(); save(all) }` 钩子让示例把整份数据放进 localStorage，约三十行，不是第二个实现。

不提供 IndexedDB 实现。Wow 业务应用总有后端，浏览器本地库不是保存视图的真实归宿；它需要事务内版本比较与浏览器测试矩阵，却没有一个消费者。

不在本包内提供 HTTP 实现。`ViewStore` 只有八个方法，业务应用用自己的 fetcher 实现它约一百行，HTTP 状态码到 `ViewStoreError.code` 的映射在应用侧完成。官方后端若落地，其客户端随后端合同一起发布，而不是先在前端猜一份 REST 形状。服务端的授权、可见性过滤与 requestId 去重是可信边界，前端 `permissions` 只用于按钮可用性。

## 9. React 层与 UI 层

`react/` 只依赖运行时与纯内核：

```ts
useViewEngine(options): ViewEngine                      // 建一个并在卸载时释放；需要更长生命周期由应用自建后传入
useViewRuntime(runtime): ViewRuntimeState | null        // useSyncExternalStore
useOpenView(engine, instanceId, scopeFilter?): { runtime | null; loading; error; scopeIssues }   // 拥有所开 runtime：换 id 或卸载即释放；runtime 在其下被释放（如实例被删除）时不再交出，按同一 id 重新打开，得到新 runtime 或 not_found；注入的 scopeFilter 被拒时，`setScopeFilter` 返回的 Issue 由 `scopeIssues` 交出，宿主据此提示——否则旧的、更宽的条件仍在运行却无人知晓
useViewList(engine, definitionId): { items; preferences; permissions; defaultInstanceId; loading; error; preferencesError; reload }

useFilterEditor(runtime): FilterController              // 按路径增删改、模式、清空、提交；Enter 提交排除 IME 与内部弹层由 UI 层处理
useRecordTable(runtime): RecordTableController          // 列语义、排序、列宽列序、选择、分页；无 TanStack 类型
useAnalysisEditor(runtime): AnalysisController
useDashboard(runtime): DashboardController
useSaveCommands(engine, runtime): { save; saveAs; rename; delete; retry; abandon; resolveConflict; can; state }
```

措辞在 `ui/`：`model` 只带 `code` 与 `params`，`ui/messages.ts` 给出每个 code 的英文句子，`ViewSurface` 的 `messages` 属性按 key 覆盖，这也是本地化的入口。缺失的 key 沿点号回退到最长的已知前缀（`/react` 把命令与 store 结果拼成 `view.open.failed.not_found` 这类 code），再退回 key 本身，因此永远不会渲染空白。`test/messages.test.tsx` 扫描源码里所有 `issue(...)` 的 code，少一条就失败——否则 `record.summary.unsupported` 这样的键会直接出现在界面上。

控制器输出只读状态与动作函数，不输出 JSX、类名与供应商类型。受控值合同统一：是否受控由值属性决定，回调只通知，一次逻辑交互最多一次通知。命令一律以状态兑现而不是抛出：`useSaveCommands` 的每个动作都 resolve，结局落在 `state.error` 与 `state.write`，因此点击处理器不需要 try/catch。加载态由"手上的答案属于哪一次请求"推出而不是在 effect 里同步 setState，这也是 React Compiler 规则要求的形状。

排序与列的改动立即 `edit` 后 `apply`：表格渲染的列与行来自上一次成功结果，由内核按执行时的配置投影，因此不重跑就看不到改动；筛选则等提交。

`ui/` 用 shadcn + Base UI 实现默认视觉。落地约定：注册表组件原样落在 `ui/components/`，由 `shadcn add --diff` 升级，因此不启用 Tailwind 前缀——前缀会让每个文件都要手改并从此无法跟随上游；隔离改由 `.fve-root` 边界承担，全部 token 与 base 规则都挂在它上面，`ViewSurface` 渲染它，宿主页面不受影响。`cn` 取自同名包（shadcn 2026-09 起的约定），不再自写 `clsx + tailwind-merge`。主题以独立入口 `/styles.css` 交付，应用显式导入。组件清单：`FilterPanel`、`RecordTable`、`RecordCards`、`AnalysisEditor`、`AnalysisChart`（recharts 适配，覆盖 bar／line／area／combo／pie／scatter）、`Heatmap`（自绘网格）、`Funnel`、`MetricCard`、`DashboardGrid`（react-grid-layout 适配）、内容面板 `MarkdownPanel`（react-markdown，不启用原始 HTML）、`ImagePanel`（加载失败显示占位）、`LinksPanel`（外链带 `rel="noopener"`）、`Workbench`（侧栏列表 + 视图 + 保存动作）、`EmbeddedView`。每个默认组件只消费对应控制器，不直接调用 runtime 以外的对象。独立筛选器与值编辑器不需要 Engine。

`DashboardGrid` 不做自动紧凑，面板按配置中的 `layout` 原样摆放；只有用户拖动或缩放结束时才把几何写回（`edit` + `apply`），库自身在挂载或属性变化时算出的布局不写回，因此打开已保存的 Dashboard 不会变脏。

图表只画内核已经整形好的数据：透视、合并"其他"、漏斗累计与转化率、热力图矩阵、比较值都在 `shapeChart` 里完成，`AnalysisChart` 只选标记与配色，换一个图表库不触碰任何规则。热力图与漏斗自绘，用图表库画它们的成本高于收益。`projectAnalysis` 只在 `layout === 'chart'` 时整形图表，因此切换 Table／Chart 是一次新的执行而不是重绘，`useAnalysisEditor.setLayout` 据此直接 apply；其余改动等 Run。

`RecordTable` 暂不接 TanStack：控制器已经是表格模型，列语义、排序、选择与分页都从它来，再叠一层只是把同一份状态写两遍。等列宽拖拽与列序拖拽真的要做时再引入，那时它提供的才是新能力。注册表组件是上游源码：覆盖率、Prettier、ESLint 与 Codacy 都排除 `ui/components` 与 `ui/lib`，它们保持与上游逐字一致，否则每次 `shadcn add --diff` 都会变成整文件冲突；本包测的、也负责的是其上的组合。

## 10. 扩展点

| 变化轴   | 机制                                                                                          | 落点                                                       |
| -------- | --------------------------------------------------------------------------------------------- | ---------------------------------------------------------- |
| 字段类型 | `FieldKind` 包：操作符集、默认操作符、值校验、编译到 `FilterExpression`、编辑器描述（纯数据） | `filter/` 注册表；React 渲染器在 `ui/` 用同一 kind id 注册 |
| 数据来源 | `resolveSource(key)` 返回 `Pick<QueryApi, 'paged' \| 'cursor' \| 'aggregate'>`                | 应用注入                                                   |
| 持久化   | 实现 `ViewStore`                                                                              | 业务应用，或官方后端的客户端包                             |
| 渲染器   | 单元格、行动作、工具栏动作按键注册 React 组件                                                 | `ui/` 注册表                                               |
| 外观     | CSS 变量与主题文件；组件级替换通过自定义组合 `/react`                                         | `/themes/*`                                                |

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
  editor(operator: FilterOperator, value?: unknown): EditorDescriptor; // { input: 'text' | 'number' | 'select' | 'date' | 'daterange' | 'relative' | 'remote'; multiple?; ... }
}
```

内置 kind：string、number、boolean、date、datetime、enum、reference、array，各自拥有第 3 节列出的值类型。`array` 是"一个字段同时持有多个值"（标签、分类）的一等表示，它独立成 kind 而不是 string 上的一个开关，因为操作符含义不同：标量字段上 `IN` 问"这一个值是否在列表里"，数组字段上问"字段的条目是否包含其中任一"，另有 `CONTAINS_ALL`（全部包含）与 `IS_EMPTY`（有没有条目——这是 `IS_NULL` 回答不了的，字段可以持有空列表而并非缺失）。声明了 `options` 即为封闭集合，与 enum 同；声明了 `remote` 走远程候选；两者皆无则自由输入。另有五个由 Wow 元数据过滤支撑的 kind：documentId（`ID`／`IDS`）、aggregateId（`AGGREGATE_ID`／`AGGREGATE_IDS`）、tenantId、ownerId、spaceId。元数据过滤不带字段名（`{ op, value }`），因此这些 kind 的 `FieldDefinition.name` 只是编辑器、标签与 Issue 路径的句柄，不进入查询；约定写作 `@ownerId` 这类带 `@` 的名字，但不依赖它。某个元数据字段算不算合法筛选条件取决于"谁在看"——租户内的用户按所有者或工作空间收窄，平台运维按租户收窄——这个判断属于定义，定义是代码、随应用部署，所以引擎提供 kind，由每份定义决定视图能用哪些。它们不提供 presence 操作符：`IS_NULL` 一类是带字段名的，混进来会让同一个叶子的 `name` 在不同操作符下时而是路径时而是标签。`search` 是列表页顶部那个搜索框：Wow 的 `SEARCH` 不带字段名，所以它的 `name` 与元数据 kind 一样只是句柄；查哪些字段、按词还是按短语匹配属于定义（`searchFields`／`searchMode`），与 `stringComparison` 同理——匹配方式是字段的属性，值只是用户敲进去的东西。空白查询是「还没问」而非错误，非文本值则是错误：`filter.search` 对两者都抛异常，但只有前者该被宽容。它同样不提供 presence 操作符，原因与元数据 kind 相同，而这条规则由 `FieldKind.fieldless` 自述，`FIELDLESS_FIELD_KIND_IDS` 只是内置 kind 的缺省答案——**名字不是路径的那些 kind**；编译成根过滤的自定义 kind 同样要声明它，否则会溜进元素谓词而被 Wow 拒绝。

`elementMatch` 的值是一棵**条件树**而不是标量：Wow 的 `ELEMENT_MATCH` 携带完整谓词，而谓词正是用户在这里写的东西——同一棵树、同一套编辑器，作用域换成该数组元素声明的字段。它存在的理由是语义差别：`items.sku` 与 `items.qty` 两条并列写在顶层，由**任意**元素各满足一条即可；写在元素匹配里则必须由**同一个**元素同时满足。持有树的 kind 用 `nested(value, field, operator)` 自述——按操作符设限，`IS_EMPTY` 下遗留的谓词不计入——`checkShape` 据此把嵌套树计入**同一份** `maxFilterDepth`／`maxFilterNodes`——预算的存在是为了让 store 送来的树无法耗尽调用栈，每层各给一份额度等于换个方式重新放开。`FieldKind` 的四个上下文（blank、validate、compile、describe）因此带上 `kinds`，validate 另带 `limits`：嵌套谓词按**外层的**预算准入，且不重复走一遍骨架检查：持有树的 kind 要用**外层正在用的那份**注册表，自定义 kind 才能在元素谓词里按同样的条件被准入。自定义 kind 由应用注册到 `FieldKindRegistry`，自行定义值的形状；缺少对应 React 渲染器时 UI 显示不可编辑并给出 Issue，编译不受影响。

## 11. 与 Wow 协议的对应

| 内核输出                              | Wow 类型（`@ahoo-wang/fetcher-wow`）                                                                                                      | 执行入口           |
| ------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- | ------------------ |
| `compileFilter`                       | `FilterExpression`（`LogicalFilter`、`EqualityFilter`、`ComparisonFilter`、`StringFilter`、`CollectionFilter`、`BetweenFilter` 等的联合） | 嵌入下方三种查询   |
| `compileRecord` 分页                  | `FilterPagedQuery { filter; sort; pagination; projection }`                                                                               | `source.paged`     |
| `compileRecord` 游标                  | `CursorQuery { filter; sort; ... }`                                                                                                       | `source.cursor`    |
| `compileAnalysis`、`compileSummaries` | `AggregationQuery { filter; groupBy; metrics; sort; limit; having }`                                                                      | `source.aggregate` |

Wow 已将 `Condition`、`ConditionOptions`、`PagedQuery`、`ListQuery`、`SingleQuery` 等基于 condition 的 API 标记为弃用。本包只使用 `FilterExpression` 与 `Filter*Query` 系列；架构测试禁止从 `@ahoo-wang/fetcher-wow` 导入任何弃用符号，`FilterLeaf` 的编译结果类型固定为 `FilterExpression`。

`AnalysisViewConfig` 覆盖 `AggregationQuery` 的全部字段：`filter`、`elements`、`groupBy`、六种 `metrics`、`having`、`sort`、`limit`。Wow 端的能力（是否支持 aggregate、支持哪些 group 类型、函数与扩展能力）由 `AnalysisCapability` 在定义中声明；内核只按声明编译，不探测后端。

## 12. 质量守护

| 层     | 手段                                                                        | 占比预期 |
| ------ | --------------------------------------------------------------------------- | -------- |
| 架构   | 六条依赖规则 + 第三方库落点测试                                             | 常量     |
| 纯内核 | Vitest 单元测试；从旧树搬迁日期时区、操作符、编译、投影用例                 | 最大     |
| 运行时 | Memory store + 假数据源；替代在途、冲突、未知结果、待修复                   | 中       |
| React  | Testing Library 钩子与控制器测试                                            | 中       |
| UI     | 组件测试 + Storybook 交互测试（Record 工作台优先）                          | 小       |
| 包产物 | `verify-package`：入口可导入、核心入口无 DOM 类型、CSS 不被 JS 入口自动导入 | 常量     |

不再维护不变量索引。一条规则若值得存在，它是一个测试。

## 13. 交付顺序与搬迁规则

自下而上，每步独立 PR、独立可用：

| 步   | 交付                                                      | 搬迁                                                                       |
| ---- | --------------------------------------------------------- | -------------------------------------------------------------------------- |
| 1 ✅ | `model/`、架构测试、本文                                  | 全新                                                                       |
| 2 ✅ | `filter/` 内核与 FieldKind 注册表                         | 先搬测试改为新类型，再搬实现；只有"改 import 即可编译"的文件才搬，否则重写 |
| 3 ✅ | `record/`、`analysis/` 内核                               | 同上；analysisCompiler、analysisProjection、recordValidation 为主要来源    |
| 4 ✅ | `runtime/`、`store/` 端口、Memory                         | 全新；旧 engine 测试中描述行为的用例改写为 ViewRuntime 测试                |
| 5 ✅ | `/react` 最小钩子 + 朴素表格示例                          | 全新；**闭环一在此跑通，之后才进入视觉工作**                               |
| 6 ✅ | `/ui` Record 工作台：FilterPanel、RecordTable、列表、保存 | shadcn 组件与主题 CSS 直接搬；复合视图重写                                 |
| 7 ✅ | Analysis 编辑器与图表                                     | 内核已就位，UI 重写                                                        |
| 8 ✅ | `dashboard/` 内核、DashboardRuntime、DashboardGrid        | 内核搬，运行时重写                                                         |
| 9 ✅ | Storybook 状态集；README 双语；`verify-package`           | 一个用 fetcher 实现 `ViewStore` 的示例放在 examples，作为端口的第二消费者  |

不搬迁清单：旧 `contracts/`、`engine/`、`StatefulViewHost`、三个 `*View.tsx`、`AnalysisEditor.tsx`、`view/` 目录。

闭环一定义：用户从默认订单视图筛选待出库记录，调整列与排序，保存为个人视图，重开后恢复配置但不恢复选择与页码，数据变化后刷新得到新数据。第 5 步以此为退出条件，由 `test/closedLoop.test.tsx` 驱动 `examples/PlainRecordWorkbench.tsx` 逐条验证，因此退出条件是可执行的而不是口头的。

## 14. 后续方向

- **定义生成。** `fetcher-generator` 从 Wow 聚合元数据生成 `ViewDefinition`，业务方零成本获得记录、分析与概览。这是"配置代替页面"相对于手写 React 页面的决定性杠杆，也是定义作为代码的直接结果。
- **共享与嵌入。** `scope: 'shared'`、服务端配置的 `scope: 'system'` 与 `EmbeddedView` 在业务应用的 `ViewStore` 落地后由业务服务授权。
- **服务端实现。** 若需要官方后端，另立设计文档随后端代码放置；本文的 `ViewStore` 合同是它的输入。
- **更多图型。** 图表按族扩展，新增一族只增加一个子对象、一个 `type` 字面量、一条校验分支、一段投影与一个渲染器，不改既有类型。候选：帕累托（combo 加投影层累计占比，依赖结果集完整）、矩形树图、箱线图（百分位指标已能支撑）。
