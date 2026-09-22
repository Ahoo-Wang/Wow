# 核心模型（一）：定义与配置

`model/` 目录只含类型与常量，不依赖其他目录。

本页给出定义、三类配置与配置模型原则。图表规格、Filter 树、实例与偏好、`Issue` 在 [model-shapes.md](model-shapes.md)；校验与编译这些配置的纯函数在 [kernels.md](kernels.md)。

## 定义

```ts
// ---- 定义 ----
export type ViewDefinition =
  | {
      id: string;
      title: string;
      kind: 'data';
      source: string; // resolveSource 的键
      fields: FieldDefinition[];
      fieldGroups?: { id: string; label: string; fields: string[] }[]; // 选择器的分组目录，按此顺序列出各组，组内按 fields 顺序
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
  //                  | 'documentId' | 'aggregateId' | 'tenantId' | 'ownerId' | 'spaceId'
  //                  | 'deletion' | 自定义
  operators?: FilterOperator[]; // 缺省取 FieldKind 的默认集
  options?: FieldOption[]; // enum 的静态候选；每一项可带一档闭合的语气 tone
  remote?: string; // reference 的远程候选源键，由 resolveOptions 解析
  sortable?: boolean;
  numberFormat?: Intl.NumberFormatOptions & { locale?: string };
  stringComparison?: 'CASE_SENSITIVE' | 'CASE_INSENSITIVE'; // CONTAINS／STARTS_WITH／ENDS_WITH 的比较方式，缺省不区分大小写
  searchFields?: string[]; // search 字段查哪些文档字段；缺省交给后端索引
  searchMode?: 'TERMS' | 'PHRASE'; // 按词还是按短语，缺省 TERMS
  summary?: SummaryFunction[]; // 允许的汇总函数；一列时刻（kind 或 cell 为 date／datetime）只认 MIN／MAX／COUNT，声明 SUM／AVG 会被准入按 record.summary.unsupported 拒绝（`summaryFunctionsOf`）
  cell?: FieldCellId; // 这一列怎么读，缺省按 kind；闭合取值，准入拒绝未知值
  // 数组字段的元素持有什么。它属于字段本身：items 就是那个数组，这些是它装的东西。
  // 声明在别处就得用路径字符串回指，而路径可以指向不存在的字段——那一整类悬空引用
  // 在这里根本写不出来。元素名字自成作用域，可与根字段重名，引用一律写 `field.element`。
  elements?: FieldDefinition[];
}

export type FieldCellId =
  // 各 kind 自己的渲染，写在这里是为了借用：一个存毫秒时刻的数字声明 'date' 就读成日期
  | 'string'
  | 'number'
  | 'boolean'
  | 'date'
  | 'datetime'
  | 'enum'
  // 没有哪个 kind 蕴含的五个读法：一枚徽章、一枚一枚的标签、外链、多行文本、
  // 带一颗复制按钮的值
  | 'status'
  | 'tags'
  | 'link'
  | 'text'
  | 'copyable';

export interface FieldOption {
  value: string | number;
  label: string;
  group?: string;
  disabled?: boolean;
  tone?: 'neutral' | 'success' | 'warning' | 'danger'; // 徽章的语气，映射到主题 token
}

export interface RecordCapability {
  rowKey: string;
  paging: 'paged' | 'cursor'; // 数据源提供哪种分页；决定 runtime 调用 source.paged 还是 source.cursor
  layouts: ('table' | 'card')[];
  defaults?: Partial<RecordViewConfig>;
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
```

### 软删除是定义声明的一维（D17-2）

Wow 的源对没有说明的查询只回未删除的记录；一份把已删除记录悄悄混进来的列表是**数据口径的沉默**，比少一个筛选项严重。所以「看不看已删除」不是每个视图天生带着的开关，而是定义按 D4 声明的一维：字段 `kind: 'deletion'`（无字段种类，`name` 只是编辑器与标签的把手，编译成 Wow 的 `DELETION` 根筛选，与 `tenantId`／`ownerId` 同一族）。

- **未声明**：界面上没有这个东西——选择器里没有，已应用条上也不说。
- **声明了**：它是一个普通条件——一个操作符（`DELETION`），三个答案（`ACTIVE` 仅未删除／`DELETED` 仅已删除／`ALL` 含已删除，措辞在目录里）；空值编译成**没有条件**，由源的缺省作答。
- **缺省口径是「仅未删除」**：视图自己的条件与宿主的作用域都没答这一维时（旧配置、新配置、留空的 pill 都算），`impliedDeletion` 给已应用条一枚不可删的 badge 说出来（[ui/README.md#三态各有一处凭据](ui/README.md#三态各有一处凭据)）——一个在生效却没人写下的口径仍然在生效。「仅已删除」「含已删除」是显式选择。

（见 test/deletionKind.test.ts）

## 配置

```ts
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
    // hidden 为 true 时表格不画这一列，但它仍**留在列表里**，也就保住了自己的位
    // 置（D17-8）：再打开时回到原处而不是排到末尾，关着也照样能拖。缺这个成员
    // （旧配置）读作显示；除 true 外的任何值都读作显示，另由 validateColumns 报
    // record.column.hidden-invalid。
    //
    // pinned 是一个布尔：true 即固定在左侧（D19）。固定没有"侧"——右边那一
    // 列是宿主的操作列，没有行操作时是投影画在最后的那一列（D13），两者都不
    // 是配置说得上的。取消固定删键而不是写 false；读法与 hidden 同一套
    // （columnPinned()，只有 true 算固定），读不出的值当作不固定，另由
    // validateColumns 报 record.column.pin-invalid——两端也报。
    columns: {
      field: string;
      width?: number;
      pinned?: boolean;
      hidden?: true;
    }[];
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
  // pinned 与记录表是同一个词、同一个取值：一个布尔，true 即固定在左侧
  // （D19；右边那一列从来不是配置说得上的）。分析表目前不冻结列，所以
  // projectAnalysis 不带它出去——投影里只放渲染真的会读的东西。
  columns: { alias: string; width?: number; pinned?: boolean }[]; // 缺省为全部 group + metric
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
      timeZone?: string; // 缺省为引擎的 environment.timeZone，与相对日期、界面显示同一时区
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

## 查询筛选必须真的筛掉东西

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

## Dashboard 配置

```ts
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
```

## 配置模型原则

**配置模型原则：配置是意图模型，不是协议 DTO，也不是组件树。**

- 保存用户表达的语义，不保存编译结果。"最近 7 天"保存为 `{ type: 'relative', amount: 7, unit: 'day' }`，而不是两个绝对时间；编译在每次执行时依据 `ctx.now` 进行。
- 组件选择不进配置。编辑器由 `(field.kind, operator, value.type)` 推出，**没有第二条路**：`FieldDefinition.editor` 已删除（D17-11）——它声明了却全树无人读，写了它的字段拿到的仍是推出来的那个控件，是一个看着像合同、什么也不担保的成员。想换控件就换 `FieldKind`（`EditorDescriptor`），那是定义的事。旧定义仍然写着它时，`validateDefinition` 报一条 warning（`definition.field.editor-removed`）而不是拒绝：那个发布没有一处因此不工作，只是多了一行该删的字。UI 组件改名或重写不影响任何已保存视图。
- **`cell` 与 `FieldOption.tone` 是闭合取值，不是自由字符串**：`/ui` 没有渲染器注册表，`RecordTable` 按 `cell` 分派、按 `tone` 挑 variant，所以没人分派的键不会报错，只会悄悄走默认渲染，一列声明成链接的 URL 仍旧是一串点不动的字；一档没人认识的语气则留下中性徽章，而那正是声明语气要改的那一件事。两者都在定义准入处被拒（`definition.field.cell-invalid`／`tone-invalid`，见 [kernels.md#定义准入](kernels.md#定义准入)），与其余能力同一条规矩：引擎给得出的，定义才写得出（D4）。语气闭合还有第二层意思——定义说得出"这是坏消息"，说不出 `#22c55e`：每一档映射到主题已有的 token，宿主改 `--fve-success` 就一并改掉所有穿着它的徽章。
- 语义变体用值内部的 `type` 判别，由 FieldKind 拥有。它承担组件节点模型中 `component` 字段的作用，但描述的是语义而不是 UI。自定义 kind 自行定义值的形状、校验、编译与编辑器描述，扩展能力与组件节点模型等价。
- 配置是纯 JSON：其中的枚举一律使用与 Wow 枚举同值的字符串字面量（`${Enum}` 模板字面量类型），编译时映射回枚举。Wow 协议类型只在"本身就是意图、不含字段引用与枚举"时直接复用；`HavingExpression`、`DerivedExpression` 结构可复用但含枚举，故以 `LiteralEnums<>` 派生同构的字面量版本；`AggregationGroup`、`AggregationMetric`、`AggregationExpression`、`AggregationElement`、`FieldSort` 引用字段或筛选，因此有配置层孪生类型。定义是代码，可以直接使用 Wow 枚举。
- 编辑器状态不进配置：折叠、当前标签页、未完成的输入、拖动中的临时位置归控制器；节点在编辑期的稳定 key 由控制器分配，不持久化；Issue 用路径定位节点。保存要求配置无 error，因此不存在保存半成品再恢复的问题。
- 两种缺失要分开：**FieldKind 未注册**时内核没有它的 `validate` 与 `compile`，因此报 error 级 Issue，视图进入待修复，`apply` 被拒绝；**kind 已注册但缺少 React 渲染器**只影响编辑，配置照常校验与编译，UI 以只读方式显示原值并给出 warning。

## `RuntimeLimits.exportMax`

预算大多是"一次请求有多大"，`exportMax`（缺省 10000）是唯一一条"一次命令能带走多少行"：导出按已应用条件在后台分页拉全量，规模由**结果**而不是由屏幕上那一页决定，没有上限就意味着一次点错的导出可以向后端要一百万行、并在浏览器里把它们拼成一个字符串。它与其余预算同一条规矩——由调用方设定、按调用方设定的那份执行（[runtime.md#导出](runtime.md#导出)）：拉取在到达这个数时停下并声明文件是截断的；条数事先知道且超过它时，先把条数与上限摆给用户，答应了也只导出前 `exportMax` 条（[ui/record.md#导出](ui/record.md#导出)）。它不是配置成员，视图里没有任何一处写得出它，所以没有对应的 Issue 码——够不着的东西不报错（[ui/record.md](ui/record.md)）。

## `ViewConfigBase` 的三个字段

`ViewConfigBase` 的三个字段都是"观察方式"的一部分，所以随视图保存而不是作为个人偏好：

- **`filterMode`。** 高级模式写出的含 OR 或嵌套分组的树无法在简单模式中呈现，重开时必须仍是高级模式。`simple` 只允许"单个 AND 组、子节点是叶子或**取反**（只含一片叶子的 `nor` 分组，`isNegation`——D18-7 把否定做成 pill 上的开关而不是每种 kind 的否定操作符）"的树；`filterMode: 'simple'` 配上不满足的树产生 warning，UI 以高级模式打开，不改配置。这个判断只看配置自己的树：作用域条件以嵌套分组并入执行树后它必然不再是简单树，但那说的不是 draft，runtime 在 draft 本身简单时不报这条 warning。
- **`refresh`。** "每 30 秒刷一次"属于视图本身。`refresh` 缺失、为 `null` 或不是对象报 `config.refresh.missing`；`interval` 不是整数（含缺失与非数字）报 `config.refresh.not-an-integer`。`interval` 为 `null`，或介于 `RuntimeLimits.minRefreshInterval` 与 `maxRefreshInterval` 之间的有限整数；上界保证换算成毫秒后不超过计时器的 32 位上限，否则 Node 会把超长延迟压成约 1ms 而变成紧密轮询。`0`、负数、非有限值、过小或过大的值一律是 error 级 Issue，无论配置来自代码还是从 store 读入，因为运行时只执行通过校验的 `applied`。计时器归运行时，见 [runtime.md#自动刷新](runtime.md#自动刷新)。
- **布局成对保存。** Record 的 `table` 与 `card`、Analysis 的 `table` 与 `chart` 始终同时持久化，`layout` 只记录当前选择；`chart` 内部再按族保存子对象，跨族切换时控制器保留上一次的子对象。切换布局或图型不会丢失另一套设置。`RecordCapability.layouts` 限制允许的布局，`defaults` 可分别给初值。
