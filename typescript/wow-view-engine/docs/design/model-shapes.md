# 核心模型（二）：图表规格、Filter 树、实例与问题

`model/` 的其余类型：Analysis 配置里的图表规格、三类配置共享的 Filter 树、持久化的实例与偏好，以及每个内核报告问题的 `Issue`。定义与三类配置本体见 [model.md](model.md)。

## 图表规格

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
    | 'waterfall'
    | 'pie'
    | 'treemap'
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
  waterfall?: WaterfallSpec; // D33 Q55，自成一族，由柱画
  treemap?: TreemapSpec; // D33 Q55
  legend?: 'auto' | 'top' | 'bottom' | 'right' | 'none';
  labels?: boolean; // 数据标签；不写时取家族缺省（直角坐标写，其余不写，`valueLabelsOn`）
  colors?: Record<string, string>; // 系列或分类 → 颜色，键是内核标出的分类值（数字、布尔转文本，null 为空串；是原值而不是显示出来的枚举名或日期，因此换语言、改选项措辞都不影响）或指标别名；未列出的用主题调色板；值须是 CSS 颜色（culori 解析），非对象报 malformed。写 `var(--chart-N)` 的是「第 N 个系列的颜色」这个序数，不是某个色相：预设可以自带八色（D35 Q62），换预设颜色随之变；要说好坏、涨跌，写语义 token（`var(--success)`、`var(--rise)`），不挑色位
}

export interface CartesianSpec {
  x: string; // 分组别名
  splitBy?: string; // 第二分组别名：按其取值拆分系列（透视）；存在时 series 只能有一个指标
  series: {
    metric: string; // 指标别名
    type?: 'bar' | 'line' | 'area'; // combo 时逐系列必填
    axis?: 'left' | 'right'; // 不写为左；组合图刚换进来的系列按量纲补（comboAxis）
    stack?: string; // 同名系列堆叠
    smooth?: boolean;
  }[];
  orientation?: 'vertical' | 'horizontal'; // 不写：柱状图的类目名长时横放（drawsHorizontal），其余竖放；写了照写
  yAxis?: { left?: AxisSpec; right?: AxisSpec };
  // 参考线：在一个数值（value）上，或在一个指标量到的值的统计量（statistic：average｜median，metric 为指标别名）上——补出的 0 不算（D33 批 B）
  referenceLines?: {
    axis: 'left' | 'right';
    value?: number;
    statistic?: 'average' | 'median';
    metric?: string;
    label?: string;
  }[];
  referenceBands?: {
    axis: 'left' | 'right';
    from: number;
    to: number;
    label?: string;
  }[]; // 目标区间：from < to，淡色铺在标记后面
  extremes?: boolean; // 标出每条系列量到的最高点与最低点；堆叠里的一段不标
  // 算出的线：沿整条时间轴、由拿回来的行在内核算，画成前景色虚线、不进堆叠、不占色位；行不完整时不画并说原因（Q53）
  derived?: {
    kind: 'trend' | 'moving-average' | 'cumulative' | 'cumulative-share'; // 两种累计也沿类别轴算：结果须先按这个指标排序（帕累托，D38）
    metric: string; // 由哪条系列算：图上画着的指标别名
    window?: number; // 移动平均的期数，2～366；不写按单位（日 7、周 4、月 3、季 4、时 24，其余 3）
  }[];
  missing?: 'zero' | 'gap'; // 缺的点：不写／zero 按内核规则（确知为空且可加才补 0），gap 一律留空（断开）；只改画法
  percentStack?: boolean; // 百分比堆叠：柱与面积的每一摞画成占比、顶到 100%；只用于可加指标，未堆叠时不读
}

export interface AxisSpec {
  label?: string;
  min?: number;
  max?: number;
  format?: 'auto' | 'percent' | 'compact';
  scale?: 'linear' | 'log'; // 按 10 的幂排刻度（D33 批 E）；轴上有 0 或负数时按线性画并说原因，不是准入发现
}

export interface PieSpec {
  category: string; // 分组别名
  value: string; // 指标别名
  donut?: boolean;
  maxSlices?: number; // 超出部分合并为"其他"；不写时按 CHART_COLOR_SLOTS（8）并，大于 8 按 8 读
}
// 饼图的 value 须可加（COUNT／SUM）：扇区是整体的占比（chart.pie.not-additive，D33 Q56 结清 Q9）

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
  xAxis?: AxisSpec; // 横轴的标题、范围、数值格式与刻度（D33 批 E）
  yAxis?: AxisSpec; // 纵轴的同上
}

/** 瀑布图：一个维度的每个取值是一步，这一行的数是变化量；从 0 起逐步累加，最后一根是合计。只量可累加的指标（COUNT／SUM）。 */
export interface WaterfallSpec {
  x: string; // 分组别名；时间维度按时间升序，其余按结果行的顺序（视图的排序）
  value: string; // 指标别名，须可加（chart.waterfall.not-additive）
  total?: boolean; // 画不画最后那根合计，不写为画
}

/** 矩形树图：面积是这一组在整体里的份额；一个维度一层，第二个维度作外层。只量可累加的指标。 */
export interface TreemapSpec {
  category: string; // 分组别名，每个取值一块
  parent?: string; // 另一分组别名，外层；两个维度时必有，与 category 不同（chart.treemap.same-levels）
  value: string; // 指标别名，须可加（chart.treemap.not-additive）；不大于 0 的组没有面积、不画并说出个数
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
        cumulative?: boolean; // 累计为"至少到达该阶段"（每段加上其后各段），缺省 false：每段是这一组自己的数，与表格同一个数
      };
  conversion?: 'previous' | 'first' | 'none'; // 转化率相对上一阶段或首阶段
  orientation?: 'vertical' | 'horizontal';
}

export interface MetricCardSpec {
  metric: string; // 指标别名
  compare?: { metric: string; mode: 'delta' | 'percent' }; // 与另一指标比较，跨度与主数相同：最后一期读法下是同一期的值，全部读法下是全部的值
  target?: number; // 渲染为进度；跨度与主数相同：最后一期读法下是一期的目标（日趋势即日目标）
  trend?: MetricTrend; // 迷你趋势线，需恰有一个 DATE_HISTOGRAM 分组；metric 与 compare.metric 都须是可加指标（COUNT／SUM）
  format?: 'auto' | 'percent' | 'compact';
  lowerIsBetter?: boolean; // 下降为好（失败数、耗时、退款）：卡上每一处变化——较上一期与对比指标——颜色都反过来
}

export interface MetricTrend {
  x: string; // 那一个 DATE_HISTOGRAM 分组的别名
  // 大数字是什么（2026-09-23 用户拍板，审查 P1-6）：
  // 'last'（缺省）—— 提问时已经结束的最后一期，旁边写较上一期的变化（差值与百分比、方向与语义色）；还没结束的当前一期不计入，卡上写明
  // 'whole' —— 范围内全部记录，取另问的无分组查询（asksForWhole），无合计行时按分桶求和
  headline?: 'last' | 'whole';
}
```

## Filter 树，三类共享

```ts
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
        | 'nextYear'
        // 至今（D38）：这一期的第一刻到读的那一刻；last… 是上一期的同一段
        | 'weekToDate'
        | 'lastWeekToDate'
        | 'monthToDate'
        | 'lastMonthToDate'
        | 'quarterToDate'
        | 'lastQuarterToDate'
        | 'yearToDate'
        | 'lastYearToDate';
    };
```

## 实例与偏好

```ts
// ---- 实例与偏好 ----
export interface ViewInstance {
  id: string;
  definitionId: string;
  title: string;
  scope: 'system' | 'shared' | 'personal';
  revision: string; // 不透明，只做相等比较；代码声明的系统视图固定为 'code'
  config: ViewConfig;
}
export interface ViewInstanceSummary {
  id: string;
  definitionId: string;
  title: string;
  scope: 'system' | 'shared' | 'personal';
  kind: ViewConfig['kind']; // 该实例配置的种类；摘要不带 config，列表据此区分记录与分析
  revision: string;
}
export interface ViewPreferences {
  order: string[];
  defaultInstanceId: string | null;
  revision: string;
}
```

## 问题

```ts
// ---- 问题 ----
export interface Issue {
  code: string; // 程序分支依据
  severity: 'error' | 'warning' | 'note'; // error 拦应用与保存；warning 报而不拦（有问题或可能被误读）；note 只是答案的一个事实，没有什么不对
  path: (string | number)[]; // 指向 config 内的位置
  params?: Record<string, string | number>;
}
```

文案不在 model 中；`Issue.code` 由 UI 层映射到文本。
