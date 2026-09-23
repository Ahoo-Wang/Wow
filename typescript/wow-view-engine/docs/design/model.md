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
  temporal?: FieldTemporal; // date／datetime 字段的时间怎样存，决定条件的边怎样发出；缺省纪元毫秒，见下文
  // 数组字段的元素持有什么。它属于字段本身：items 就是那个数组，这些是它装的东西。
  // 声明在别处就得用路径字符串回指，而路径可以指向不存在的字段——那一整类悬空引用
  // 在这里根本写不出来。元素名字自成作用域，可与根字段重名，引用一律写 `field.element`。
  elements?: FieldDefinition[];
  // 数组对象字段的元素**以哪个元素字段为标题**：单元格把数组读成它的一个个元素，
  // 每个元素按这个字段、照这个字段的读法读（enum → 选项标签与语气的徽章，string → 文字）。
  // 必须是 elements 里声明过、且自己持有值的字段（不是无字段种类、也不是又一层对象数组），
  // 否则准入报 definition.field.element-title-unknown／element-title-not-a-value。
  // 不声明时单元格说它装了几项（「3 项」），绝不写出 JSON。见下文「数组对象在单元格里」。
  elementTitle?: string;
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

// Wow 查询 schema 的 semanticType，照 Wow 的名字写：TEMPORAL_EPOCH → epoch，TEMPORAL_DATE → date
export type FieldTemporal =
  | { type: 'epoch'; timeUnit?: 'MILLISECONDS' | 'SECONDS' } // 整数；缺省毫秒，与 Wow 的缺省一致
  | { type: 'date' }; // 存储自己的日期类型，查询里写 ISO 8601 文本

export interface FieldOption {
  value: string | number;
  label: string;
  group?: string;
  disabled?: boolean;
  tone?: 'neutral' | 'success' | 'warning' | 'danger'; // 徽章的语气，映射到主题 token
}

export interface RecordCapability {
  rowKey: string; // 每行的身份；必须是已声明且 sortable: true 的字段——每条记录查询以它升序收尾，分页才不重不漏（kernels.md「Record 内核的规则」）
  paging: 'paged' | 'cursor'; // 数据源提供哪种分页；决定 runtime 调用 source.paged 还是 source.cursor
  // 分页查询最多能够到的行数：页码 × 每页条数。走搜索引擎的源自己有这道上限——
  // Wow 走 Elasticsearch 时 index × size 超过 10 000 直接 400（`HTTP page window[12000]
  // must not exceed 10000`）。声明了，分页条只数得到窗口里的页、停在最后一页并说明原因，
  // 导出也停在窗口里；不声明即无上限（不给默认值：不是每个源都有窗口）。
  // 只对 paged 源有意义；准入时须为正整数，游标源声明它报错。
  maxWindow?: number;
  layouts: ('table' | 'card')[];
  // 宿主代码要从行上读、视图未必显示的字段（行动作、批量动作、自定义单元格）；
  // 一页只要视图显示的字段（kernels.md「一页要哪些字段」），这里说的每页都另要
  rowFields?: string[];
  defaults?: Partial<RecordViewConfig>;
}

export interface AnalysisCapability {
  count: boolean;
  fields: AggregationFieldCapability[];
  // 可展开的数组路径，是一条由外到内的**链**而不是并列的数组（与 Wow 一致）：
  // elements[0].path 是根上的一个数组字段，elements[i].path 相对 elements[i-1]
  // 的元素，最多 5 层（AGGREGATION_LIMITS.MAX_ELEMENTS）。元素持有什么由字段
  // 自己的 elements 说，这里只说本分析可以展开哪条链、每层如何聚合。
  // 写成两个根数组即为一条断链，validateDefinition 报 error。
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

### 数组对象在单元格里：按元素的标题读

真实的 Wow 事件流（`execution_failed/event`，178 万条）把一次流里的事件放在数组 `body` 里，每个元素带 `name`、`bodyType`、`revision` 和载荷 `body[].body`。一列 `body` 从前把整个数组写成 JSON——堆栈一并在内——把表格撑到屏幕外很远，而运维要问的那一件事（这一步是「准备重试」还是「重试失败」）哪儿也读不到；定义也绕不过去：服务拒绝 `body.0.name`（`Unknown logical field`）。订单明细、地址列表……凡是 Wow 聚合里装着对象数组的地方都是同一个形状。

所以对象数组是一种**读法**，由定义说：`elementTitle` 点名元素里哪个字段是一个元素的**标题**。取名与卡片的 `card.title` 同一个词、同一个意思——那个说出「这是哪一个」的值；引擎挑不出它（事件按 `name` 读，明细按 `sku` 读，哪个成员说出元素**是什么**是业务知识），所以由定义声明，并且只能指向元素里声明过、自己持有值的字段（准入见 [kernels.md#定义准入](kernels.md#定义准入)）。

- **声明了**：数组读成它的元素，每个元素一枚徽章，徽章上是标题字段**自己的读法**——enum 的选项标签与语气、string 的文字、时刻按表面的时区。一个元素一枚，与 `tags` 一个值一枚同理：拼成一枚会读成名字里带逗号的一个东西。标题为空的元素仍是一个元素，读作「未命名」而不从计数里消失。一个对象值（不是数组）就是它那一个元素。
- **不声明**：数组说它装了几项（「3 项」／"3 items"），对象说它有几个字段（「2 个字段」）。这句话是真的、短的，也是不知道元素是什么时能说的全部；原始 JSON 从来不是运维要的东西。空数组、空对象什么也不装，读作空，如同空的 `tags` 画零枚。
- **标量数组**（`['a','b']`）照每个值的读法、以目录的列表分隔符连成一行，同样不再是 `["a","b"]`。
- **一行文本**（CSV、`title`）：各元素的标题以目录的列表分隔符（中文「、」、英文 ", "）连起来；徽章与标量列表也用这同一个分隔符。

表格一行放几枚、卡片怎么折，见 [ui/record.md#数组对象的一列](ui/record.md#数组对象的一列)。投影不必另加什么：列显示时它的数组路径本来就在 `recordProjection` 里，元素整个带回来，标题在其中；列隐藏时不取。（见 test/display.test.ts「cellText of an array of objects」、test/recordCells.test.tsx「an array of objects」、test/recordProjection.test.tsx、test/definition.test.ts）

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
    // pinned 与 hidden 同一种成员：写就写 true，取消固定删键而不是写 false，
    // 所以类型也写成 `pinned?: true`（B8）——true 即固定在左侧（D19）。固定
    // 没有"侧"——右边那一列是宿主的操作列，没有行操作时是投影画在最后的那一
    // 列（D13），两者都不是配置说得上的。读法与 hidden 同一套（columnPinned()，
    // 只有 true 算固定），读不出的值当作不固定，另由 validateColumns 报
    // record.column.pin-invalid——两端也报；存量配置里的 false 说的正是"不固
    // 定"，照旧收下不报。
    columns: {
      field: string;
      width?: number;
      pinned?: true;
      hidden?: true;
    }[];
  };
  card: {
    title: string; // 作为卡片标题的字段
    fields: string[]; // 卡片正文字段
    image?: string; // 可选的图片字段
    perRow?: 1 | 2 | 3 | 4; // 每行几张卡（不叫 columns：全包其余的 columns 都是表格的列，评审 B9）
  };
}

export interface AnalysisViewConfig extends ViewConfigBase {
  kind: 'analysis';
  // 展开链，与能力声明的链同形：path 相对上一层，可以只走链的前几层。
  // 每层的 filter 以**该层元素自己**的字段为作用域；维度、指标、数值表达式与
  // 指标条件的字段一律属于**最内层**元素（计数单位），根字段只留给 filter。
  // 配置里字段名一律写从根起的全名（state.orders.lines.sku），编译时按作用域
  // 剥去前缀发给 Wow（sku）。
  elements?: { path: string; filter?: FilterTree }[];
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
  // 没有 pinned：分析表不冻结任何列，所以存下来的那个「固定」是一份没有
  // 渲染器去兑现、也没有控件去设置的设置——界面许诺了一件屏幕不会做的事。
  // 将来分析表长出冻结列时，照记录表那一个词、那一个取值来（D19）。
  columns: { alias: string; width?: number }[]; // 缺省为全部 group + metric
  totals?: boolean; // 合计行；开启时执行一次无分组聚合，不由分组行推导
}

// 与 Wow AggregationGroup / AggregationExpression / AggregationMetric 同构；
// 差别只在字段按定义校验、筛选使用 FilterTree，以及多一个显示名。

// 显示名（D20 显示名）：分析师给这一维／这一指标起的名字，列头、图例与结果
// 那句读法都说它，而不说「字段 + 汇总方式」拼出来的那一句。它是**视图自己
// 的东西**——compileAnalysis 逐成员拼 Wow 对象，所以它永远发不出去；查询的
// 键仍然是 alias。给了就得是个词：空白报 analysis.label.blank，不是字符串报
// analysis.config.malformed；不给就整个键都不在（「取消」写的是删键而不是
// undefined，配置始终是普通 JSON）。projectAnalysis 把它写成列的 label 并另
// 带一个 named: true，columnTitle 据此把名字当作整个标题、不再缀「的 合计」。
export interface AnalysisNamed {
  label?: string;
}

export type AnalysisGroup = AnalysisNamed &
  // missingKey 是「没有该值的记录落进哪一组」的哨兵键：不写它，Wow 把这些记录
  // 从结果里**整条丢掉**（不是留成一组空值），所以单值字符串维度缺省带一个
  // DEFAULT_MISSING_KEY（'(empty)'，analysis/defaults.ts）；Wow 只允许单值
  // 字符串字段带它，其余报 analysis.group.missing-key-unsupported。
  (
    | { type: 'TERMS'; field: string; alias: string; missingKey?: string }
    | { type: 'HISTOGRAM'; field: string; alias: string; interval: number }
    | {
        type: 'DATE_HISTOGRAM';
        field: string;
        alias: string;
        unit: `${AggregationDateUnit}`;
        timeZone?: string; // 缺省为引擎的 environment.timeZone，与相对日期、界面显示同一时区
        dense?: boolean; // 补齐空的时段；Wow 只允许唯一分组这么做
      }
  );

export type AnalysisExpression =
  | { type: 'FIELD'; field: string }
  | { type: 'CONSTANT'; value: number }
  | {
      type: 'BINARY';
      operator: `${AggregationExpressionOperator}`;
      left: AnalysisExpression;
      right: AnalysisExpression;
    };

export type AnalysisMetric = AnalysisNamed &
  (
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
    | { type: 'DERIVED'; alias: string; expression: AnalysisDerivedExpression } // 与 Wow DerivedExpression 同构
  );
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

## 时间字段怎样存

一条日期条件存的是意图（"今天"、"9 月 1 日到 30 日"），发出去的却必须是存储比得了的那个值，而同样是一刻，Wow 的查询 schema 有三种存法（`me.ahoo.wow.api.query.schema.Temporal`）：`TEMPORAL_EPOCH(timeUnit)` 存整数，`TEMPORAL_DATE` 存存储自己的日期类型、查询里写 ISO 8601，`TEMPORAL_FORMATTED(pattern)` 存按格式写的字符串。字段用 `temporal` 声明它是哪一种，照 Wow 的名字写，从 schema 抄过来就对：`{ type: 'epoch', timeUnit: 'MILLISECONDS' }`、`{ type: 'date' }`。`TEMPORAL_FORMATTED` 没有建模——还没有哪份定义用到它，而按错的格式写出的边会被当文本比较、悄悄匹配错的行。编译怎样照它写见 [kernels.md#求出来的那一刻按字段的存法发出](kernels.md#求出来的那一刻按字段的存法发出)。

**不声明时是纪元毫秒**（`DEFAULT_TEMPORAL`，`temporalOf(field)` 是唯一的读法）。引擎的对象是 Wow 数据：快照里的每一个时间——`eventTime`、`firstEventTime`、聚合上 `@QueryTemporal` 标注的 long——都是毫秒，Wow 自己的 `timeUnit` 缺省也是毫秒；照一份 Wow schema 写定义的人不该为了正确而在每个时间字段上多记一个成员。反过来的缺省（ISO 文本）只照顾了故事与测试的夹具，而它在真实服务上的代价是**每一条日期条件都被 400 拒绝**。存成原生日期的那一种由它自己说出来：故事里订单与运单的时间存成 ISO 文本，所以它们的字段写着 `temporal: { type: 'date' }`。两种错法都响亮：Wow 的 schema 校验对类型不符的值一律报 `Filter value does not match`，不会静默地少返回。

**读值也按这个单位**：条件按 `temporal` 写出时间，显示与汇总读时间时同样按它读——`epochUnitOf(field)` 在字段存的是纪元**秒**时答 `SECONDS`（毫秒是缺省，不说），`readInstant(value, unit)` 按它换算；记录列、卡片字段（因而详情）、汇总行的最早／最晚、分析里取字段自身值的指标（`MIN`／`MAX`／分位／任取）与按该字段分组的列都带上 `timeUnit`，所以一个存秒的时间不会被当成毫秒显示成 1970 年 1 月。日期直方图的桶键是聚合给出的桶起点，不受字段存储单位影响。（见 test/epochSeconds.test.ts「a time kept in epoch seconds」与 test/dateMetrics.test.ts「a moment kept in epoch seconds」）

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
- **自己会跑的成员：`autoRunMembers(kind)`**（`model/config.ts`，同一手法）。「改了就跑」（D20）跑的是**问题**，哪些成员算问题由每一种视图在自己的类型旁声明：分析是 `ANALYSIS_AUTO_RUN_MEMBERS`（展开、维度、指标、只保留、排序、前 N 组、合计行），记录视图与仪表盘一个都没有——所以 runtime 里没有「只有分析才跑」这种按种类的判断，新加一种视图也不必假装一个空实现（见 [runtime.md#改了就跑](runtime.md#改了就跑)；test/model.test.ts「names the question members that run on their own, per kind」）。
- **只画结果的成员：`presentationMembers(kind)`**（`model/config.ts`）。一个成员只把已经回来的结果重画一遍、够不着查询，它就不算"改过没应用"：`comparePending` 跳过它，托盘的提交按钮因此不为它亮点——一颗要求按下、按下却什么也不跑的点，教会用户的只是按没用的按钮。三种视图共有的是 `filterMode`（两种语法呈现的是同一棵树）；Record 多一个 `layout`（同一批行画成表或画成卡）；**Analysis 多 `layout` 与 `chart`**（D20：图是结果的属性不是问题的一部分，换布局、换图型都是拿同一批行重画，`ANALYSIS_PRESENTATION_MEMBERS`）。它们照旧随视图保存——保存下来的那张图是作者的意思——所以 `dirty` 看得见它们。Analysis 的 `table` **不在**其中：它的合计行是一次自己的无分组查询。清单声明在各自类型旁边而不是写成读取处的字符串比较，`satisfies` 于是拒绝一个配置没有的成员。（见 test/model.test.ts「counts the editor mode as presentation for every kind」与 test/chartPicker.test.tsx「a layout is a redraw, not a run」）
