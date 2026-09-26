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
      recordNoun?: string; // 一条记录叫什么（「订单」「客户」），分析的计数单位说它；不写说「记录」，不拿 title 充数
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
  searchFields?: string[]; // search 字段查哪些文档字段（只收根字段，D39：Wow 的 SEARCH 做不到可移植地查数组元素里的字段）；缺省交给后端索引
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
  // 导出也停在窗口里；不声明时窗口是运行时的 `RuntimeLimits.maxPageWindow`（缺省 10 000，
  // Wow 的 HTTP 守卫缺省收的，D42），声明了只能再压低。
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
  expressions?: boolean; // 允许 BINARY／DATE_DIFF 表达式、按表达式分区间与 DERIVED 指标
  dateDiffUnits?: `${DateDiffUnit}`[]; // 两个时刻之差可用的单位，不写即四种（dateDiffUnitsOf）；expressions 关着时一种也没有；描述按 analysis.dateDiffUnits 收窄
  firstLastOrderBy?: string; // FIRST／LAST 不写 orderBy 时按什么先后（描述的 analysis.firstLastOrderBy），不写即源的缺省
  having?: boolean;
  // 下面四项都只能收窄，不写就是不收窄；数据源的能力描述把自己的值写进收窄后的定义（capabilities.md 4.4、D47）
  havingMetrics?: AnalysisMetric['type'][]; // 「只保留」能比较的指标类型，不写即全部
  metricSort?: boolean; // false：结果行只能按维度排序
  dense?: boolean; // false：时间维度不能补齐空档
  approximate?: AnalysisMetric['type'][]; // 源估算的指标类型，列标「≈」、箱线图说近似；不写即 DEFAULT_APPROXIMATE_METRICS（百分位）
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
  dateParts?: AggregationDatePart[]; // DATE_PART 可取的周期；不写即全部四种（datePartsOf），描述按 analysis.dateParts 收窄
  any?: boolean;
  firstLast?: boolean; // FIRST／LAST（期初值／期末值）可读这个字段的值；单值字段才行，不写即不可（N1）
  distinctCount?: boolean;
  percentile?: boolean;
  missingKey?: boolean; // false：按值分组不带缺失值一组（不写时单值文本字段带）
  inMetricFilter?: boolean; // false：指标自己的条件不能用它
  expressionInput?: boolean; // false：不能做公式的操作数
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
  refresh: { interval: number | null }; // 自动刷新间隔，秒；null 关闭；开启时为有限整数，介于 RuntimeLimits.minRefreshInterval（缺省 5）与 maxRefreshInterval（缺省 86400）之间
}

/** 自己问一个问题的两类视图（记录、分析）另有的：它自己的条件。仪表盘没有（D27）。 */
interface DataViewConfigBase extends ViewConfigBase {
  filter: FilterTree;
  filterMode: 'simple' | 'advanced'; // 编辑器模式跟随视图保存
}

export type DataViewConfig = RecordViewConfig | AnalysisViewConfig;

export interface RecordViewConfig extends DataViewConfigBase {
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

export interface AnalysisViewConfig extends DataViewConfigBase {
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
  columns: { alias: string; width?: number }[]; // 列序与列宽的覆盖，不是白名单：未列出的别名接在后面（维度先、指标后，按配置顺序）
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
// 带一个 named: true，columnTitle 据此把名字当作整个标题、不再缀「的总和」。
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
    // 一个逐条算出的数按区间分组（N3，「付款到发货 0–4 小时、4–8 小时…」）：没有 field，
    // 读它的 expression；读的字段是 expressionFieldsOf(expression)（groupFieldsOf）。
    | {
        type: 'HISTOGRAM';
        expression: AnalysisExpression;
        alias: string;
        interval: number;
      }
    | {
        type: 'DATE_HISTOGRAM';
        field: string;
        alias: string;
        unit: `${AggregationDateUnit}`;
        timeZone?: string; // 缺省为引擎的 environment.timeZone，与相对日期、界面显示同一时区
        dense?: boolean; // 补齐空的时段；Wow 只允许唯一分组这么做
      }
    | {
        // 按日期部件分组（N2）：星期几（ISO，1 是周一）、几点（0～23，挂钟）、
        // 几号、几月——不同周、不同天的记录落进同一组，答「哪天几点单最多」。
        // 键是部件的整数（DATE_PART_DOMAINS），界面读成「周一」「20时」。
        type: 'DATE_PART';
        field: string;
        alias: string;
        part: `${AggregationDatePart}`; // ANALYSIS_DATE_PARTS 的次序：星期、时段、几号、月份
        timeZone?: string; // 同 DATE_HISTOGRAM：缺省为引擎时区
        dense?: boolean; // 部件的全部取值都列出；同样只能是唯一分组
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
    }
  // 两个时刻之差（N3）：to − from，以 unit 计（一天正好 24 小时）；任一端缺值即这条记录没有值。
  // 两端都得是计数单位里能按日期分桶（DATE_HISTOGRAM）、能进算术（expressionInput）的时刻；
  // 算出的数按单位读（「12.5 小时」，formulaFormat）。
  | { type: 'DATE_DIFF'; from: string; to: string; unit: `${DateDiffUnit}` };

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
    // 期初值／期末值（N1）：这一组里按 orderBy 最早／最晚那条记录的值；根上不写
    // orderBy 即按模型的事件时间（描述的 analysis.firstLastOrderBy，写进
    // AnalysisCapability.firstLastOrderBy），展开的明细项没有事件时间，必须写。
    // 与 ANY 同是「一条记录的值」（VALUE_METRIC_TYPES）：派生指标与「只保留」都读不了它。
    | {
        type: 'FIRST' | 'LAST';
        alias: string;
        field: string;
        orderBy?: string;
        filter?: FilterTree;
      }
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
    | {
        type: 'DERIVED';
        alias: string;
        expression: AnalysisDerivedExpression;
        format?: DerivedFormat;
      } // 与 Wow DerivedExpression 同构；format 只是视图的读法（D38），不发给 Wow
  );

// 派生指标怎样读（D38）：百分比读比值本身（0.259 → 25.9%）；金额不写币种时取操作数共有的那个；decimals 0～6，不写时百分比 1、其余 2
type DerivedFormat =
  | { style: 'number'; decimals?: number }
  | { style: 'percent'; decimals?: number }
  | { style: 'currency'; currency?: string; decimals?: number };
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

Wow 的对应规则在 `requireScalarMetricFilterFields`：形状那半条（`SEARCH`／`ELEMENT_MATCH`）由 `typescript/wow-client` 的 `aggregation.query()` 在协议层挡住，需要 schema 那半条（数组值字段）由这里挡住——因为只有这里知道 `FieldKind`。

除此之外它就是一棵普通的 Filter 树，按 analysis scope 的字段走 `validateFilter`，问题路径挂在 `['metrics', i, 'filter', ...]` 下。

## Dashboard 配置

```ts
export interface DashboardViewConfig extends ViewConfigBase {
  kind: 'dashboard';
  columns: 24; // 布局写在几列的栅格上；见下文「24 列与旧布局」
  width?: DashboardWidth; // 'fixed' | 'full'：固定宽度居中或全宽；新建的是 'fixed'，没有就是全宽（D31）
  fixed: FilterTree; // 板子的固定范围：每块数据面板都在它之下跑，读者改不了，作者搭建时可整体删掉（成为空树，D23 Q16）；批 C 起新建的板子是空的（D26 Q31）。板子没有自己的 filter 与 filterMode（D27）
  tabs: DashboardTab[]; // 标签页，按标签栏的顺序；0 或 1 个不画标签栏
  fields: DashboardField[]; // 板子的筛选，按筛选条的顺序；跨定义，因此由配置声明
  timeGrouping?: DashboardTimeGrouping; // 整板的时间粒度；没有就不写
  panels: DashboardPanel[];
}

export interface DashboardTab {
  id: string;
  title: string;
}

export interface DashboardField {
  name: string; // 绑定、筛选值与宿主地址都按它称呼
  label: string;
  kind: FieldKindId; // 六种筛选类型之一的字段种类（filterTypeOf）；类型之外的种类自成一类
  options?: FieldOption[]; // 值从自己列的一组里选；不写就从接上的字段的值里选（enum 总是一组）
  remote?: string; // ID 筛选的候选来源；接到带 remote 的字段时没有就取它的
  default?: FilterValue; // 读者没设时它的值，形状是它的操作符要的（filterOperatorOf）
  required?: true; // 永远有值：清空回到默认值，所以必须有默认值
  multiple?: true; // 可多选
}

export type DashboardFilterType =
  'date' | 'text' | 'id' | 'number' | 'boolean' | 'search';
// DASHBOARD_FILTER_KINDS：date → datetime／date，text → string／enum，id → reference，number，boolean，search → search（只接记录视图的搜索框，boardFieldsOf）

export interface DashboardTimeGrouping {
  units: AnalysisDateUnit[]; // 筛选条上按这个顺序给的粒度
  default: AnalysisDateUnit;
}

/** 筛选此刻的值：读者的，不进配置；宿主经 initialFilters／onFiltersChange 写进地址。 */
export interface DashboardFilters {
  values: Record<string, FilterValue>; // 按筛选名；不在这里的筛选没有值
  unit?: AnalysisDateUnit; // 有时间粒度的板子才有
  from?: Record<string, string>; // 按筛选名：这个值是在哪块面板上点出来的（交叉筛选，D22 I）
}

export type DashboardPanel = DashboardViewPanel | DashboardContentPanel;

interface DashboardPanelBase {
  id: string;
  title?: string;
  layout: { x: number; y: number; w: number; h: number };
  tab?: string; // 所在标签页的 id；没有标签页的板子不写
}

/** 数据面板：显示一个 Record 或 Analysis 视图——已保存的，或只属于这块板的——参与全局筛选与 apply。 */
export type DashboardViewPanel = DashboardPanelBase & {
  kind: 'view';
  bindings: { globalField: string; panelField: string; auto?: true }[]; // 哪个筛选经哪个字段筛这个面板；auto 是自动连接接上的
  presentation?: PanelPresentation; // 只改怎么看（D22 D）
  click?: PanelClick; // 点一组时做什么（D22 I）；不写就是追问菜单（D22 H）
} & (
    | { instanceId: string } // 引用一个已保存的视图
    | { owned: { definitionId: string; config: AnalysisViewConfig } } // 板内分析（D22 C）
  );

/** 点一组时做什么（「点击时…」）；缺省的追问菜单不存。 */
export type PanelClick =
  | { kind: 'filter'; filter: string } // 交叉筛选：用这一组设这个筛选
  | { kind: 'view'; instanceId: string } // 去另一个已保存的记录／分析视图，带上这一组
  | {
      kind: 'dashboard';
      instanceId: string;
      values: Record<string, BoardValueSource>;
    } // 去另一块仪表盘：目的板筛选名 → 值从哪来（D23 Q17）
  | { kind: 'url'; url: string }; // 去宿主的页面，{{字段}} 换成这一组的值

/** 目的板一个筛选的值从哪来：这一组在这块面板一维上的值，或这块板一个筛选点的那一刻的值。 */
export type BoardValueSource = { dimension: string } | { filter: string };

/** 面板对「怎么看」的覆盖：只有展示成员，从不碰问题。 */
export interface PanelPresentation {
  layout?: 'table' | 'chart' | 'card';
  chart?: ChartSpec;
  table?: AnalysisTableSpec;
}

/** 内容面板：标题、静态说明、图片、链接；不查询，不参与全局筛选。 */
export type DashboardContentPanel = DashboardPanelBase &
  (
    | { kind: 'heading'; content: string } // 分节的标题卡片：一行纯文本
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

批 B1（D22 A～E 的模型；界面在 B2／B3）定下的几条：

- **24 列与旧布局**（D22 E，用户拍板）：`DASHBOARD_GRID_COLUMNS` 是 24。配置**自己说**布局写在几列上（`columns: 24`），而不是由读者从最宽的面板去猜——所有面板都在左半边的板子两种读法都说得通。没有 `columns` 的配置就是栅格还是 12 列时存下的：`migrateDashboardConfig`（`src/dashboard/migrate.ts`）读取时把每个 `x`、`w` 乘 2，`y`、`h` 不动，补上 `columns: 24` 与空的 `tabs`；保存写出新格式，下次读就原样通过。写了别的 `columns` 的配置不猜，准入报整板 error `dashboard.grid.unsupported`。乘 2 在像素上是恒等的：新栅格的一列加一道缝恰是旧的一半，所以宽 `2w` 的面板与原来的 `w` 一样宽、`2x` 处与原来的 `x` 处对齐——这不是论证，是量出来的：react-grid-layout 自己的算式把 12 列能放的每一个面板（78 种 `x`／`w`）在四种宽度下都算一遍，迁移前后差不到 1px（test/dashboardBuild.test.ts「draws every 12-column panel on exactly the pixels it had」）；浏览器里一块旧格式的板子逐面板比对屏幕上的框（stories/view-engine/Dashboard.test.stories.tsx「LegacyLayoutDrawsTheSame」）。故事与测试的板子都已改写成 24 列（`x`、`w` 乘 2），画出来与原来一样。拒绝的写法：`version: 2` 之类的版本号（说的是历史而不是这些数字的意思，下一次改动又得加一个）；按最宽面板猜（见上）。
- **宽度**（[D31](decisions.md#d31-仪表盘的固定宽度全宽2026-09-24)，用户拍板）：`width` 说板子按哪种宽度排——`'fixed'` 是整块板（筛选条、编辑条、标签栏与面板）限在 `FIXED_BOARD_WIDTH`（1200px）内居中，`'full'` 是铺满容器。新建的板子（`emptyDashboardConfig`）写 `'fixed'`；**没有这个成员就是全宽**（`boardWidth`）——D31 之前存下的板子是照全宽搭的，读出来仍是全宽，存下的布局不会自己变窄，所以不迁移、没有标记。作者选了全宽写 `'full'` 而不是删掉成员。准入：不认识的值是 warning `dashboard.width.unknown`（`params.width` 是找到的值，截到 40 个字符；不是字符串就是它的类型），照全宽画。编辑是 `setBoardWidth`（不认识的值不改），经 runtime 的 `setWidth` 成为撤销历史的一步。它不在 `DASHBOARD_PRESENTATION_MEMBERS` 里：和面板的 `layout` 一样是板子怎么摆，改了就是板子改了，不是「只改了展示」。（见 test/dashboardWidth.test.tsx）
- **批 C 之前的整板条件**（[D23](decisions.md#d23-搁置待议的六条拍板2026-09-23) Q16、[D26](decisions.md#d26-阶段-34-联合审查的十一条拍板2026-09-24) Q31，用户拍板）：批 C 之前板子的全局筛选是 `config.filter` 一棵条件树；批 C 起筛选是有名字、有默认值的字段，拆不开的部分是独立的成员 `fixed`（固定范围）。`migrateDashboardConfig` 在栅格之后再走一步（`intoDefaults`）：`config.filter` 顶层 AND 下的一片叶子，若落在一个六种筛选类型之一的全局字段上、操作符正是那种筛选用的（内核的 `FILTER_TYPE_OPERATOR`，与 `filterOperatorOf` 同一张表：日期 `BETWEEN`、是否 `EQ`、搜索 `SEARCH`、其余 `IN`；一个文本或数字的 `EQ` 读作一项的列表）、那个筛选还没有自己的默认值、且是它的第一片这样的叶子，就成为那个筛选的 `default`；单值筛选遇到几项的 `IN` 不迁。其余成为 `fixed`，不是纯 AND 的树整棵成为 `fixed`；`config.filter` 读走即拿掉。**标记就是 `fixed` 这个成员本身**：有它的配置已经读过（或按新形式写的），再也不拆——哪片叶子「筛选收得下」取决于筛选当下的设置（默认值、可多选），作者之后改了设置，按设置判就会把原本固定的叶子迁成读者能清掉的默认值（A-02）。`filter` 不是一棵树的这一步不读，旁边补一个空的 `fixed`，交给下一步。
- **板子自己的条件**（[D27](decisions.md#d27-仪表盘不画正在显示条2026-09-24)）：板子没有自己的 `filter`——面板问问题，读者经筛选收窄，读者改不了的是固定范围，第三处说同一件事违背 D12；`filterMode` 随它一起走，板子没有条件编辑器。批 C 到 D27 之间存下的板子在 `fixed` 旁还带着一个 `filter`（引擎写的是空的，此后没有界面写它）与 `filterMode`。`migrateDashboardConfig` 最后一步（`withoutOwnCondition`）把这两个成员拿掉；**标记就是这两个成员本身**：带着其一的配置是 D27 之前写的，读过的一个都不带，所以只走一次。它说的条件不丢：有条件的 `filter` 接在 `fixed` 之后 AND 进去——每块面板本来就在两者之下跑；不是纯 AND 的整棵接进去；不是一棵树的作为 `fixed` 的一个子节点接进去，于是准入仍拒绝这块板（`filter.node.invalid`，路径在 `fixed` 下），而不是让它比原来问得更宽。迁移只在一处调用：引擎的读取边界 `readStored`（`src/runtime/storedViews.ts`），从存储出来的每个视图——读、写的回执、冲突带回的那一份——都经过它一次，之后的一切只见新形式；写在代码里的板子是代码，不经过它。每块面板跑的整板条件是 `boardCondition`（`src/dashboard/merge.ts`）：就是 `fixed`，再经绑定映射到面板字段。（见 test/dashboardBuild.test.ts「a pre-C board condition」「a board’s own condition」、test/dashboardEditing.test.ts「a pre-C board read once, through save and reopen」）
- **标签页**（D22 E）：`tabs` 有序，每个 `{ id, title }`；0 或 1 个不画标签栏（界面的事）；全局筛选在标签页之上、对所有标签页生效。面板用 `tab` 写自己在哪一页。准入：`tabs` 不是数组是 `dashboard.shape.invalid`，id 缺失／重复是整板 error（`dashboard.tab.id-empty`／`-duplicate`，面板靠它找页），标题空白只是 warning（`dashboard.tab.title-empty`，界面按位置称呼它），超过 `MAX_DASHBOARD_TABS` 报 `dashboard.tabs.too-many`；面板写了板子没有的页（或有标签页却没写、没有标签页却写了）是 warning `dashboard.panel.tab-unknown`，读作在第一页（`panelTab`）——面板照常显示，不因为一个页 id 就消失。编辑（`src/dashboard/tabs.ts`）：`addTab` 在没有标签页的板子上一次建两个——现有面板归入第一页（名字由调用方给，内核没有措辞），新的是第二页；`renameTab`（空白不改）、`moveTab`、`removeTab`（连同该页面板；最后一页不删——只剩一页等于没有标签栏，留着它的名字等再加一页时用）。面板移到别的页见下一条。拒绝的写法：标签页里嵌面板数组（`tabs[].panels`）——移页就成了跨数组搬运，面板 id 的唯一性与问题路径 `['panels', i]` 都要改；「没有标签页时也有一个隐藏的默认页」——多出一个读者看不见的东西。
- **板内分析视图**（D22 C）：数据面板要么 `instanceId`（已保存视图），要么 `owned: { definitionId, config }`（只属于这块板：随板保存、删除、受众，从不出现在工作台的列表里），两者恰有其一，否则 `dashboard.panel.source-invalid`。首版只收分析（`config.kind === 'analysis'`，否则 `dashboard.panel.owned-invalid`）。它的配置与已保存分析**一样判**：内核判绑定与合并后的全局筛选（定义由调用方按 id 查：`ValidateDashboardOptions.definitions`；查不到报 `dashboard.panel.definition-unknown`，没给查法时只判形状——定义准入时的系统仪表盘就是这样），分析本身由它的子 runtime 的准入判（见 [runtime.md#dashboard](runtime.md#dashboard)）。「另存为视图」把它提成普通视图、面板改为 `instanceId` 引用它（`referToSaved`，展示覆盖保留）。拒绝的写法：新加一种面板 `kind: 'analysis'`——它和引用视图的面板在接线、覆盖、点击、刷新上一模一样，分成两种每条规则都要写两遍；把板内分析存成一个隐藏的普通实例——那就要在列表、权限、删除上处处过滤它，还会在板子被删后留下孤儿。
- **在工作台中打开的是哪一张**（`opens`，2026-09-25，补偿控制台 W13）：数据面板可以点名一张同一定义的已保存视图，「在工作台中打开」开它而不是面板自己的视图——面板只放得下一个短问题（几列），工作台里开完整的那一张；带着面板从板上拿走的条件交过去，与开面板自己的视图同一读法（D26 Q30）。只判形状：不是非空字符串 → warning `dashboard.panel.opens-invalid`，照开面板自己的视图；那张视图打不开由工作台照常说。替换视图时它随覆盖一起作废。
- **展示覆盖**（D22 D）：`presentation` 只收 `PANEL_PRESENTATION_MEMBERS`（`layout`、`chart`、`table`）——视图里画结果而不是问结果的那几个成员（D20），表格合计行一并算作「怎么看」；维度、指标、条件、排序永远是视图的。内核只判形状（不是对象、带了别的成员 → warning `dashboard.panel.presentation-dropped`）；合不合身由 runtime 用视图自己的内核判，不合身整份丢掉并注明同一条 warning，不是 error（见 [runtime.md#dashboard](runtime.md#dashboard)）。`setPresentation(…, null)` 就是「恢复为视图的样子」。替换视图时覆盖作废（它说的是怎么看原来那个视图）。
- **标题卡片**：`{ kind: 'heading', content }`，一行纯文本、不解析 markdown，最长 `MAX_HEADING_LENGTH`（200，超出 `dashboard.heading.too-long`）；空文本允许（界面在编辑中显示占位）。它的字就是面板的名字（`panelName`），画在面板自己的标题元素里，正文不再重复。
- **筛选**（D22 F、G，批 C1）：一个筛选就是筛选条上的一枚，经每个面板的一个字段去筛它。**六种类型对齐字段种类**（`DASHBOARD_FILTER_KINDS`）：日期（`datetime`／`date`，含相对与区间）、文本或类别（`string`／`enum`）、ID（`reference`）、数字、是否、搜索（`search`：记录视图的搜索框，一行文本，按接上的那个搜索字段的 `searchFields`／`searchMode` 编译；只接记录视图，`boardFieldsOf`，自动连接按搜索框而不按名字，见 [ui/dashboard.md](ui/dashboard.md#筛选d22-fg批-c)「板上的搜索」）；「同类型」说的是同一类（`sameFilterType`），所以日期筛选接得上 `date` 也接得上 `datetime`，文本筛选接得上 `enum`。六类之外的种类（宿主自己注册的、数组……）照样能当筛选，自成一类：只接同一种类的字段、按那个种类的缺省操作符问——扩展点不因筛选而关上。**一个值就是一条条件**（`filterCondition`，`src/dashboard/filters.ts`）：操作符只由类型定（`filterOperatorOf`）——日期 `BETWEEN`（相对窗口、区间、某天、命名时段都是它）、是否 `EQ`、搜索 `SEARCH`、其余 `IN`；**单值也存成一项的列表**，于是同一条条件原样接得上 `string`、`enum`、`reference` 字段，映射时不必翻译操作符（`enum` 本来就只有 `IN`）。空值不筛任何东西。**值是读者的，从不进配置**（用户拍板「筛选值写进地址，不写进配置」）：配置只说它从哪开始（`default`）与怎么设（`required`、`multiple`、`options`）；此刻的值 `DashboardFilters` 在 runtime 里（[runtime.md#dashboard](runtime.md#dashboard)）。准入（`src/dashboard/validateFilters.ts`）：名字照旧（非空、合 Wow 字段语法、不重名）；`required`／`multiple` 只能是 `true` 或不写；`options` 是数组，空数组是 warning（`dashboard.field.options-empty`：作者可能正在列）；默认值按这个筛选自己的值来判（种类的值形状、单值筛选给了多个是 `dashboard.field.not-multiple`）；**必填却没有默认值是 error**（`dashboard.field.required-no-default`：必填永远有值，没有默认值就没有起点）；最多 `MAX_DASHBOARD_FILTERS`（20）个。默认值还要经每个接上的面板的字段再判一次（与固定范围一起并进 `['panels', i, 'filter']` 那棵合并树）：一个面板的 `enum` 字段收不下的默认值，搭板子时就在那个面板上说，而不是等跑起来。（见 test/dashboardFilters.test.ts「the six filter types」「the condition a filter stands for」「admission of the filters」）
- **接线**（D22 G）：`PanelBinding` 仍是「哪个筛选经哪个字段筛这个面板」，多一个 `auto?: true`——自动连接接上的；作者亲手选的不写。两端类型不同是 `dashboard.binding.kind-mismatch`；`auto` 写了别的是形状错。**没接上的面板就不受这个筛选影响**：面板跑的条件只收接上它的那些筛选（`panelFilterTree`），各筛选之间是 AND，少一条只是问得更宽，不会问错——所以不再要求每个面板都接上每个筛选。旧的「每个面板必须接上全局条件引用的全部字段」（`dashboard.binding.missing`）只留给整板的条件（`boardCondition`：固定范围 `fixed`）：任意一棵树缺一支会改变布尔含义。（见 test/dashboardFilters.test.ts「admission of the wiring」「the condition one panel runs under」）
- **自动连接**（D22 G，用户拍板「同名同类型即接，跨定义也接」，`src/dashboard/wiring.ts`）：`bindPanel(config, 筛选, 面板, 字段, fieldsOf)` 先把这个面板亲手接到那个字段，再把**其余每个还没接这个筛选的数据面板**——任何标签页、任何数据定义——里**同名且同类型**的字段自动接上（`auto: true`），返回自动接上的面板（界面据此说「已自动接上 N 个」并能撤销：`unbindPanels`）。已经接着的面板不动；ID 筛选没有候选来源时取亲手接的那个字段的 `remote`。**以后新加的面板同样自动接**（`autoBindings`）：对每个它还没接的筛选，按这个筛选在板上最常用的字段名、再按筛选自己的名字找同类型的字段。什么接得上（`wireableFields`）只看类型；宿主给的 `fieldsOf` 经 `boardFieldsOf` 过一遍，分析面板上没有搜索框。**搜索筛选按搜索框接，不按名字**（`autoField`）：搜索字段的名字是把手不是路径，自动连接把它接到每个记录面板的搜索框（`searchFieldOf`），叫什么都一样。**类别从接上的字段声明的那一组里选**（`wiredOptions`）：筛选自己没列一组时，接上的字段声明的选项（`enum` 的）合成一组，同一个代码只留一次、用先见到的标签——读者选标签、筛选存代码；没有一个字段声明时才从数据里数候选值。（见 test/dashboardWiring.test.ts「wiring a filter」「a panel added comes wired」「a search filter」「the list the wired fields declare」）
- **不受影响**（D22 F「说清作用范围」）：`filterReach(config, panel, fields)` 逐筛选回答一个面板：接上了（经哪个字段、是不是自动接的），或没接上与为什么——它的视图没有这个类型的字段（`no-field`，界面说「没有可接的字段」）、有但没人接（`unwired`）；视图还没读进来时只答接上的那些。`filtersOnTab(panels, tab)` 答一个标签页上有没有面板受某个筛选影响——筛选条据此把什么也没影响的筛选淡一档。（见 test/dashboardWiring.test.ts「what reaches a panel and a tab」）
- **时间粒度**（D22 F，整板 按日／周／月）：`timeGrouping: { units, default }`，`units` 至少一个、都是 Wow 的日期粒度（`ANALYSIS_DATE_UNITS`）且不重复，`default` 在其中（`dashboard.grouping.units-empty`／`unit-unknown`／`unit-duplicate`）。它的值同筛选一样是读者的（`DashboardFilters.unit`）。它作用在每个分析面板的日期直方图维度上——视图的定义允许那个粒度时换成它，不允许的面板**保留自己的并说出来**（[runtime.md#dashboard](runtime.md#dashboard)）。（见 test/dashboardFilters.test.ts「admission of the time grouping」）
- **设置筛选是草稿里的编辑**（`src/dashboard/filterEdit.ts`，与其余搭板子的编辑同一手法）：`addFilter`（末尾，名字是第一个没用过的 `filter-n`，新的一枚单值、不必填、谁也没接）、`renameFilter`（空白不改）、`retypeFilter`（换了类型，默认值、列表、来源与所有接线都作废——它们说的是旧类型；可多选在新类型能多选时保留）、`removeFilter`（连同所有接线）、`setFilterDefault`、`setFilterRequired`、`setFilterMultiple`（关掉时默认值只留第一个，免得筛选拒绝自己的起点）、`setFilterOptions`（`null` 回到从接上的字段取值）、`moveFilter`、`setTimeGrouping`。（见 test/dashboardWiring.test.ts「setting up the filters」）
- **点击**（D22 H、I，批 D，`src/dashboard/click.ts`）：数据面板的 `click` 说读者点一组（柱、扇区、表格的一行）时做什么；**不写就是追问菜单**（D22 H），那是缺省而不是一个存下的选择。四种：`filter`——交叉筛选，用点中的一组设板子的这个筛选；`view`——去另一个已保存的记录或分析视图，带上这一组；`dashboard`——去另一块仪表盘，**它的筛选由作者逐个映射**（D23 Q17）：`values` 的键是目的板的筛选名，与它将变成的 `DashboardFilters.values` 同键；值说这个筛选拿什么（`BoardValueSource`）：`{ dimension: 字段 }` 是这一组在这块面板那一维上的值，`{ filter: 名 }` 是这块板那个同类型筛选点的那一刻的值（读者的，没设就是默认值；空着就不带——用户 2026-09-23 批准的扩展，Metabase 的「仪表盘筛选」来源）。没写的筛选不带、从默认值开始；**从不按名字猜**。为什么是「筛选名 → 来源」而不是反过来：一个来源可以喂两个筛选，一个筛选只能有一个来源；为什么来源是带种类的对象而不是一个字符串：维度与这块板的筛选各有各的名字空间，同名时不能靠猜分开；为什么维度是字段而不是分组别名：接线、交叉筛选与 `{{字段}}` 都按字段认维度，别名随作者改展示名而变。`url`——去宿主的一个页面，`{{字段}}` 换成这一组在那个字段上的值（`fillUrl`：每个值编码成一个组件，永远加不出路径、查询或协议；填好之后仍须是 http、https、mailto 或站内路径，`isSafeContentUrl`）。**点得出值的维度**（`takesGroup`）：日期筛选收日期分桶（桶就是一段时间窗），文本、ID、数字、是否收 `TERMS` 的一个值；数值区间是两个界而不是一个值，不收；类型之外的筛选不收。于是一个筛选**能被这块面板点出来**（`crossFilterChoices`），当且仅当它接在这块面板上、接的那个字段正是面板按来分组的、分法又是它收的。准入（`validatePanelClick`）**只有 warning**：读不出（`dashboard.click.invalid`）、面板没有可点的组（记录视图或展开了明细项的分析，`unpressable`）、筛选不在板上／没接这块面板／面板不按它接的字段分组（`filter-unknown`／`-unwired`／`-ungrouped`）、地址不安全或用到了面板不分组的字段（`url-unsafe`／`url-unknown-field`）、去另一块板时映射的维度面板已不分组（`board-dimension-unknown`）或映射的这块板的筛选已不在（`board-source-unknown`），以及——**那块板读到之后**——板子没了（`board-gone`）、不是仪表盘（`board-not-a-board`）、映射的筛选它已没有（`board-filter-unknown`）或收不了那一维、那个筛选的值（`board-filter-mismatch`）（`validateBoardClick`；那块板经面板引用那套 `PanelReferences` 读，只在「点击时…」打开它或读者点的那一刻读，打开这块板时不读，所以读到之前只判面板这一边）。有 warning 的点击**不生效、点一组回到追问菜单**，板子照跑照存——一个设错的点击不该让面板消失。去的视图删了或读不到只在点的那一刻才知道（运行时说 `dashboard.click.destination-unavailable`；是仪表盘说 `destination-unsupported`）。**解开接线连同点击一起走**：`unbindPanels`（以及经它的 `retypeFilter`、`removeFilter`）把设这个筛选的点击从被解开的面板上拿掉，面板回到追问菜单。**点出来的值记着来处**（`DashboardFilters.from`）：读者的状态，同值一起进宿主的地址；`admitFilters` 只留下值在、面板在、面板的点击仍设这个筛选的那几条，旧的一声不响地放掉而不是拒绝——它只说「哪块面板别筛自己」。（见 test/dashboardClick.test.ts）
- **新面板放哪、多大**（D22 A）：`addPanel` 放进目标标签页从 `fromRow`（读者屏幕上的第一行）起、按阅读顺序的第一个**放得下且托得住**的空位（`freeSpot`：下一次压紧不会把它抬走），到底都没有就放到最下面；默认大小按显示的东西（`defaultPanelSize`，24 列计）：指标卡 6×2、图 12×4、表格 24（分析表 ×4、记录 ×5）、标题 24×1、笔记 12×3、图片 8×4、链接 8×3。新 id 是第一个没被占用的 `panel-n`（纯函数，同一份配置同一个答案）。复制放在原面板那一行起的第一个空位（旁边有位就在旁边），板内分析随面板复制成各自独立的一份。

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

## `RuntimeLimits` 的源预算

`maxPageSize`（缺省 100）、`maxPageWindow`（缺省 10 000）与 `maxAnalysisRows`（缺省 1 000）说的是**数据源收多大的一次请求**，缺省就是一台保持缺省配置的 Wow 服务端的 HTTP 查询守卫（Gateway 准入时的 HTTP 预算：`wow.query.http.max-page-size`、`max-page-window`、`max-list-size`；#3454 之前在 `wow.webflux.query.*`）收的（[D42](decisions.md#d42-引擎的缺省预算不超过缺省配置的-wow-服务端2026-09-25)）。引擎发出的每一条查询都在它们之内：记录一页不超过 `maxPageSize`；分页条与导出都不越过 `pageWindow(record, limits)`（运行时的 `maxPageWindow`，定义的 `maxWindow` 更小时取它）；分析的「前 N 组」、探针行、拆分「其他」的整体查询与值候选都不超过 `limitBounds(capability, limits).max`（定义的 `maxLimit`、`maxAnalysisRows`、Wow 的 `AGGREGATION_LIMITS.MAX_LIMIT` 取小）。

- **数据源有能力描述时以描述为准**（[D47](decisions.md#d47-采用服务端的能力描述n5修订-d422026-09-25)，修订 D42）：`maxPageSize`、`maxPageWindow`、`maxAnalysisRows`（描述的 `aggregation.maxLimit`）、`maxQueryFilterNodes`（描述的 `maxFilterNodes`）与 `maxFilterValues` 读描述，`null` 为不限（页大小取引擎自己最大的一档）；宿主在 `ViewEngineOptions.limits` 里写了的只压低（`sourceLimits`）。宿主调高服务端的守卫后，引擎跟着读到，不用两边一起改。
- **没有描述的源**（`ViewSource.describe` 不提供、Wow 9.2 之前、读不到）照旧：上面的缺省就是缺省守卫，宿主调高服务端的守卫就在同一次改动里调高这几个；只调高引擎这边，服务端照样拒绝，拒绝经 `runtime.query.failed` 带着服务端的原因报出。定义里的 `maxLimit`／`maxWindow`／`maxSortFields` 是数据集自己的更小的上限（Wow 走 Elasticsearch 时的窗口），不是替服务端声明缺省守卫的地方。
- **过滤节点与值按守卫的口径数**（C3）：`maxQueryFilterNodes`（缺省 128）与 `maxFilterValues`（缺省 1,000）对照的是**编译后的整条查询**——视图自己的条件、注入的作用域、元素与指标的条件、「只保留」合在一起数节点（一个日期区间是三个节点，单个孩子的分组编译后不成节点），值数的是单个节点最长的列表（`IN`、`IDS`、「只保留」的 `IN`）。准入时（`validateDataConfig`）内核放行后编译一次再数，超出报 error `runtime.query.too-many-nodes`／`too-many-values`（路径 `['filter']`），查询不发。服务端按调用者身份另加的作用域引擎看不到，不在数里。`maxFilterNodes`（缺省 256）仍是引擎对存储里来的树的守卫（配置里的树、表达式与「只保留」的树），与源预算无关。
- **`ViewEngineOptions.limits` 叠在 `DEFAULT_RUNTIME_LIMITS` 之上**：只传要改的；把缺省整个展开进去，就把源预算钉回了缺省。
- **被拒不静默**：一条附带的查询被拒时，主结果照旧，结果带一条说明原因的 warning（汇总退回本页 `runtime.summary.page-only`、拆分「其他」没折成 `analysis.split.whole-failed`），不悄悄换一种读法。

## `RuntimeLimits.exportMax`

预算大多是"一次请求有多大"，`exportMax`（缺省 10000）是唯一一条"一次命令能带走多少行"：导出按已应用条件在后台分页拉全量，规模由**结果**而不是由屏幕上那一页决定，没有上限就意味着一次点错的导出可以向后端要一百万行、并在浏览器里把它们拼成一个字符串。它与其余预算同一条规矩——由调用方设定、按调用方设定的那份执行（[runtime.md#导出](runtime.md#导出)）：拉取在到达这个数时停下并声明文件是截断的；条数事先知道且超过它时，先把条数与上限摆给用户，答应了也只导出前 `exportMax` 条（[ui/record.md#导出](ui/record.md#导出)）。它不是配置成员，视图里没有任何一处写得出它，所以没有对应的 Issue 码——够不着的东西不报错（[ui/record.md](ui/record.md)）。

## `RuntimeLimits.exportNeutralizeFormulas`

导出的另一个宿主开关，不是预算而是产品选项，与 `pageSizes` 那几项同列（[runtime.md](runtime.md)）：缺省 `true`，默认界面的每一处导出——记录视图的行、分析工作台与分析面板的组——写文件时都中和公式，表格软件可能求值的格子前面加 `'`（[kernels.md#导出序列化](kernels.md#导出序列化)，[D37](decisions.md#d37-导出文件缺省中和公式2026-09-24)）。只有明确写 `false` 才关：宿主手写一份不带这个成员的 limits，文件照样是安全的那份。它同样不是配置成员，视图里写不出它。

## 随视图保存的公共字段

`refresh`（`ViewConfigBase`，三类都有）与 `filterMode`（`DataViewConfigBase`，记录与分析才有，连同 `filter`）都是"观察方式"的一部分，所以随视图保存而不是作为个人偏好：

- **`filterMode`。** 高级模式写出的含 OR 或嵌套分组的树无法在简单模式中呈现，重开时必须仍是高级模式。`simple` 只允许"单个 AND 组、子节点是叶子或**取反**（只含一片叶子的 `nor` 分组，`isNegation`——D18-7 把否定做成 pill 上的开关而不是每种 kind 的否定操作符）"的树；`filterMode: 'simple'` 配上不满足的树产生 warning，UI 以高级模式打开，不改配置。这个判断只看配置自己的树：作用域条件以嵌套分组并入执行树后它必然不再是简单树，但那说的不是 draft，runtime 在 draft 本身简单时不报这条 warning。
- **`refresh`。** "每 30 秒刷一次"属于视图本身。`refresh` 缺失、为 `null` 或不是对象报 `config.refresh.missing`；`interval` 不是整数（含缺失与非数字）报 `config.refresh.not-an-integer`。`interval` 为 `null`，或介于 `RuntimeLimits.minRefreshInterval` 与 `maxRefreshInterval` 之间的有限整数；上界保证换算成毫秒后不超过计时器的 32 位上限，否则 Node 会把超长延迟压成约 1ms 而变成紧密轮询。`0`、负数、非有限值、过小或过大的值一律是 error 级 Issue，无论配置来自代码还是从 store 读入，因为运行时只执行通过校验的 `applied`。计时器归运行时，见 [runtime.md#自动刷新](runtime.md#自动刷新)。
- **布局成对保存。** Record 的 `table` 与 `card`、Analysis 的 `table` 与 `chart` 始终同时持久化，`layout` 只记录当前选择；`chart` 内部再按族保存子对象，跨族切换时控制器保留上一次的子对象。切换布局或图型不会丢失另一套设置。`RecordCapability.layouts` 限制允许的布局，`defaults` 可分别给初值。
- **自己会跑的成员：`autoRunMembers(kind)`**（`model/config.ts`，同一手法）。「改了就跑」（D20）跑的是**问题**，哪些成员算问题由每一种视图在自己的类型旁声明：分析是 `ANALYSIS_AUTO_RUN_MEMBERS`（展开、维度、指标、只保留、排序、前 N 组、合计行），记录视图与仪表盘一个都没有——所以 runtime 里没有「只有分析才跑」这种按种类的判断，新加一种视图也不必假装一个空实现（见 [runtime.md#改了就跑](runtime.md#改了就跑)；test/model.test.ts「names the question members that run on their own, per kind」）。
- **只画结果的成员：`presentationMembers(kind)`**（`model/config.ts`）。一个成员只把已经回来的结果重画一遍、够不着查询，它就不算"改过没应用"：`comparePending` 跳过它，托盘的提交按钮因此不为它亮点——一颗要求按下、按下却什么也不跑的点，教会用户的只是按没用的按钮。两种数据视图共有的是 `filterMode`（两种语法呈现的是同一棵树）；仪表盘一个也没有；Record 多一个 `layout`（同一批行画成表或画成卡）；**Analysis 多 `layout` 与 `chart`**（D20：图是结果的属性不是问题的一部分，换布局、换图型都是拿同一批行重画，`ANALYSIS_PRESENTATION_MEMBERS`）。它们照旧随视图保存——保存下来的那张图是作者的意思——所以 `dirty` 看得见它们。Analysis 的 `table` **不在**其中：它的合计行是一次自己的无分组查询。清单声明在各自类型旁边而不是写成读取处的字符串比较，`satisfies` 于是拒绝一个配置没有的成员。（见 test/model.test.ts「counts the editor mode as presentation for both data views」与 test/chartPicker.test.tsx「a layout is a redraw, not a run」）
