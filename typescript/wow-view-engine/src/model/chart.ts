/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Charts are stored per family: the sub-object of the current `type` is
 * required and the others are kept, so switching families loses nothing.
 * Every reference is a group or metric alias; no charting library types.
 */
export interface ChartSpec {
  type: ChartType;
  /** Shared by bar, line, area and combo. */
  cartesian?: CartesianSpec;
  pie?: PieSpec;
  heatmap?: HeatmapSpec;
  scatter?: ScatterSpec;
  funnel?: FunnelSpec;
  metric?: MetricCardSpec;
  waterfall?: WaterfallSpec;
  treemap?: TreemapSpec;
  boxplot?: BoxplotSpec;
  candlestick?: CandlestickSpec;
  gauge?: GaugeSpec;
  radar?: RadarSpec;
  parallel?: ParallelSpec;
  sunburst?: HierarchySpec;
  tree?: HierarchySpec;
  sankey?: SankeySpec;
  calendar?: CalendarSpec;
  themeRiver?: ThemeRiverSpec;
  map?: MapSpec;
  legend?: 'auto' | 'top' | 'bottom' | 'right' | 'none';
  /**
   * Whether the values are written on the marks. Left out, the mark
   * decides (`valueLabelsOn`): a bar writes them — a combo's bars too — a
   * line, an area and the other families do not; and an upright row of
   * `PEAKS_ONLY_FROM` bars or more that stand on their own writes only its
   * highest and lowest (`peaksOnlyLabels`). `true` writes them on every
   * mark however many there are, and `false` writes none; either is a
   * choice and stands. A pie writes each slice's share whatever this says,
   * and `true` writes its value with it.
   */
  labels?: boolean;
  /**
   * The colours pinned by hand; the theme fills in the rest from its palette.
   *
   * A key is the series or slice as the kernel labels it: the category value
   * of a split or a pie — a number or a boolean as text, `null` as the empty
   * string — or, where nothing is split, the metric alias. Internal keys are
   * never addressable, so a numeric category is named `"1"`, not `1`; nor is
   * the text a chart shows, so an enum is named by its code rather than its
   * label, and a pinned colour holds in every language.
   */
  colors?: Record<string, string>;
}

export type ChartType =
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
  | 'metric'
  | 'gauge'
  | 'boxplot'
  | 'candlestick'
  | 'radar'
  | 'parallel'
  | 'sunburst'
  | 'tree'
  | 'sankey'
  | 'calendar'
  | 'themeRiver'
  | 'map';

/**
 * Every type, in the order the picker lays them out: the everyday ones
 * first, each new one beside the one it is read against — the gauge after
 * the metric card, whose number it places on a scale; the boxplot, the
 * radar and the parallel axes after the scatter, the other charts of
 * several metrics per group; the sunburst and the tree after the treemap,
 * the other parts of a whole, and the sankey beside them; the calendar
 * after the heatmap, whose cells it lays out as days, and the theme river
 * after the area, whose stack it lets flow; the candlestick after the
 * boxplot, the other chart that draws several numbers of one field as one
 * mark.
 */
export const CHART_TYPES: readonly ChartType[] = [
  'bar',
  'line',
  'area',
  'themeRiver',
  'combo',
  'waterfall',
  'pie',
  'treemap',
  'sunburst',
  'tree',
  'sankey',
  'heatmap',
  'calendar',
  'map',
  'scatter',
  'boxplot',
  'candlestick',
  'radar',
  'parallel',
  'funnel',
  'metric',
  'gauge',
];

/**
 * The families a chart type belongs to, each the name of the sub-object of
 * `ChartSpec` its types share: four cartesian types draw one kind of spec,
 * every other type is a family of its own.
 */
export type ChartFamily = Extract<
  keyof ChartSpec,
  | 'cartesian'
  | 'pie'
  | 'heatmap'
  | 'scatter'
  | 'funnel'
  | 'metric'
  | 'waterfall'
  | 'treemap'
  | 'boxplot'
  | 'candlestick'
  | 'gauge'
  | 'radar'
  | 'parallel'
  | 'sunburst'
  | 'tree'
  | 'sankey'
  | 'calendar'
  | 'themeRiver'
  | 'map'
>;

/**
 * How many categorical colours the theme holds: `--chart-1` to `--chart-8`
 * in `styles.css`, the same eight hues in both modes, in a fixed order.
 *
 * It lives here rather than beside the palette in `/ui` because the kernel
 * shapes to it: a slot handed out twice is two categories wearing one
 * colour, which a legend cannot tell apart, so a pie folds its tail into
 * "Other" before a ninth slice would need a colour (`shapeChart`). A number
 * the projection and the palette both read has to sit where both may import
 * it, and the theme is held to the same count by a test that reads the
 * stylesheet. A host can restyle a slot through `--fve-chart-N`; it cannot
 * add one, so the count is the engine's and not the host's.
 */
export const CHART_COLOR_SLOTS = 8;

/** Which sub-object each chart type requires. */
export const CHART_FAMILY: Readonly<Record<ChartType, ChartFamily>> =
  Object.freeze({
    bar: 'cartesian',
    line: 'cartesian',
    area: 'cartesian',
    combo: 'cartesian',
    pie: 'pie',
    heatmap: 'heatmap',
    scatter: 'scatter',
    funnel: 'funnel',
    metric: 'metric',
    waterfall: 'waterfall',
    treemap: 'treemap',
    boxplot: 'boxplot',
    candlestick: 'candlestick',
    gauge: 'gauge',
    radar: 'radar',
    parallel: 'parallel',
    sunburst: 'sunburst',
    tree: 'tree',
    sankey: 'sankey',
    calendar: 'calendar',
    themeRiver: 'themeRiver',
    map: 'map',
  });

export interface CartesianSeries {
  /** Metric alias. */
  metric: string;
  /** Required per series when the chart type is `combo`. */
  type?: 'bar' | 'line' | 'area';
  axis?: 'left' | 'right';
  /**
   * Series sharing a stack name are stacked — bars and areas; a line draws
   * its own values whatever it says (`stacks`).
   */
  stack?: string;
  smooth?: boolean;
}

export interface CartesianSpec {
  /** Group alias. */
  x: string;
  /** Second group alias, pivoting one metric into a series per value. */
  splitBy?: string;
  series: CartesianSeries[];
  orientation?: 'vertical' | 'horizontal';
  yAxis?: { left?: AxisSpec; right?: AxisSpec };
  referenceLines?: ReferenceLine[];
  /**
   * What a point the rows lack draws as — a bucket missing from a time axis,
   * a combination missing from a split. Left out or `zero`, the kernel's
   * rule: 0 where the group is known to have had no records and the metric
   * adds up, nothing otherwise (`absenceReader`). `gap`: nothing anywhere, a
   * line broken where the rows are. Drawing only; the query is the same.
   */
  missing?: CartesianMissing;
  /**
   * Stacked bars or areas drawn as each category's shares of its stack, so
   * every stack reaches 100%. Only a stack of metrics that add up has a
   * whole to share; a line or a chart that does not stack ignores it.
   */
  percentStack?: boolean;
  /**
   * Target bands: a stretch of one value axis shaded behind the marks, from
   * one number to another — 「目标区间」, where a value should land.
   */
  referenceBands?: ReferenceBand[];
  /**
   * Marks each drawn series' highest and lowest measured value — never a 0
   * the kernel filled in. A series drawn inside a stack is not marked: its
   * mark stands at the stack's height, not at its own value.
   */
  extremes?: boolean;
  /**
   * Lines computed from one drawn metric over the time axis — a trend, a
   * moving average, a running total — drawn dashed over the marks, never
   * stacked and in no colour slot (D33 batch B). The kernel draws one only
   * over rows that are whole (`derivedGap`, Q53).
   */
  derived?: DerivedSeries[];
}

/**
 * What a derived series computes: the least-squares line through the
 * measured points (`trend`), the mean of each point and the ones before it
 * (`moving-average`, over `window` points), the running total
 * (`cumulative`, a metric that adds up only), and the running total as a
 * share of the whole (`cumulative-share`, 累计占比). The two running kinds
 * also run along a categorical axis the result is sorted by the metric on —
 * the Pareto chart (D38).
 */
export const DERIVED_KINDS = [
  'trend',
  'moving-average',
  'cumulative',
  'cumulative-share',
] as const satisfies readonly string[];

export type DerivedKind = (typeof DERIVED_KINDS)[number];

/** The widest window a moving average takes: a year of days. */
export const MAX_MOVING_WINDOW = 366;

export interface DerivedSeries {
  kind: DerivedKind;
  /** The metric alias of the drawn series it is computed from. */
  metric: string;
  /** A moving average's points, the current one included; 2 or more. */
  window?: number;
}

/** The two readings of a missing point, the default first. */
export const CARTESIAN_MISSING = [
  'zero',
  'gap',
] as const satisfies readonly string[];

export type CartesianMissing = (typeof CARTESIAN_MISSING)[number];

export interface AxisSpec {
  label?: string;
  min?: number;
  max?: number;
  format?: ValueFormat;
  /**
   * How the axis is stepped: evenly (`linear`, the default) or by powers of
   * ten (`log`), for numbers that span several orders of magnitude. A log
   * axis has no place for 0 or a negative number, so over values that hold
   * one it is drawn linear and says why (D33 batch E).
   */
  scale?: 'linear' | 'log';
}

/**
 * A horizontal rule across the plot: at a constant `value`, or at a
 * `statistic` of one drawn metric's measured values — its average or its
 * median over the points drawn, a filled-in 0 left out (D33 batch B). One of
 * the two places it; a statistic names its `metric`.
 */
export interface ReferenceLine {
  axis: 'left' | 'right';
  value?: number;
  statistic?: ReferenceStatistic;
  /** The metric alias a statistic is taken of. */
  metric?: string;
  label?: string;
}

/** Where a statistic reference line stands: the mean, or the middle value. */
export const REFERENCE_STATISTICS = [
  'average',
  'median',
] as const satisfies readonly string[];

export type ReferenceStatistic = (typeof REFERENCE_STATISTICS)[number];

/** A stretch of one value axis, `from` below `to`, shaded behind the marks. */
export interface ReferenceBand {
  axis: 'left' | 'right';
  from: number;
  to: number;
  label?: string;
}

export type ValueFormat = 'auto' | 'percent' | 'compact';

export interface PieSpec {
  /** Group alias. */
  category: string;
  /** Metric alias. */
  value: string;
  donut?: boolean;
  /**
   * Merges the remainder into "other"; additive metrics only. Left out, an
   * additive pie still folds at `CHART_COLOR_SLOTS`, and a larger number is
   * read as that many — past it two slices would share a colour.
   */
  maxSlices?: number;
}

export interface HeatmapSpec {
  /** Group alias. */
  x: string;
  /** Another group alias. */
  y: string;
  /** Metric alias. */
  value: string;
  scale?: 'linear' | 'log';
}

export interface ScatterSpec {
  /** Group alias; one point per value. */
  category: string;
  /** Metric aliases. */
  x: string;
  y: string;
  size?: string;
  /** Title, bounds, number format and scale of the horizontal axis. */
  xAxis?: AxisSpec;
  /** The same of the vertical axis. */
  yAxis?: AxisSpec;
}

/**
 * A waterfall: how a total is built up step by step. Each value of one
 * dimension is a step — its row's number the change it brings — drawn as a
 * bar floating from where the steps before it left the running total, up
 * for an increase and down for a decrease; the last bar is the total the
 * steps arrive at, from zero. The steps are added up, so only a metric that
 * adds (a record count or a sum) has one: a running total of averages is no
 * total of anything. It is drawn with bars, stacked on an unseen base.
 */
export interface WaterfallSpec {
  /** Group alias: one step per value, in the rows' order (time runs forward). */
  x: string;
  /** Metric alias: each step's change. */
  value: string;
  /** Whether the closing bar, the steps' total, is drawn. Left out, it is. */
  total?: boolean;
}

/**
 * A treemap: a whole cut into tiles whose areas are the groups' shares of
 * it — the reading for a composition of more categories than the palette
 * has colours (D33 Q56), since every tile carries its own name. One
 * dimension is one level of tiles; a second nests them, the tiles of
 * `category` inside a block for each value of `parent`. Areas are parts of
 * a sum, so only a metric that adds up draws one.
 */
export interface TreemapSpec {
  /** Group alias: one tile per value. */
  category: string;
  /** Group alias of the outer level, when there are two dimensions. */
  parent?: string;
  /** Metric alias: a tile's area. */
  value: string;
}

/**
 * A boxplot: how a number is spread within each group — its lowest and
 * highest value, its lower and upper quartile and its median, one box per
 * value of one dimension. The five are five metrics of one field (its
 * `MIN`, three `PERCENTILE`s from low to high and its `MAX`, under one
 * condition), which Wow computes in the one query; the kernel only reads
 * them (`fiveNumberSets`). Wow's percentiles are approximate, and the chart
 * says so.
 */
export interface BoxplotSpec {
  /** Group alias: one box per value. */
  category: string;
  /** Metric alias of the lowest value (the `MIN`). */
  low: string;
  /** Metric alias of the lower quartile (the lowest percentile). */
  q1: string;
  /** Metric alias of the median (the middle percentile). */
  median: string;
  /** Metric alias of the upper quartile (the highest percentile). */
  q3: string;
  /** Metric alias of the highest value (the `MAX`). */
  high: string;
}

/**
 * A candlestick (K 线): how one number moved within each period — where it
 * opened and closed, and how high and low it went — one candle per bucket
 * of one time dimension. The four are four metrics of one field under one
 * condition: its `FIRST` (the open, 期初值), `MAX`, `MIN` and `LAST` (the
 * close, 期末值), the two ends ordered by one time (N1); the kernel only
 * reads them (`ohlcSets`). A candle is coloured by whether it closed above
 * its open, in the host's rise/fall convention.
 */
export interface CandlestickSpec {
  /** Group alias of the time dimension: one candle per bucket. */
  x: string;
  /** Metric alias of the opening value (the `FIRST`). */
  open: string;
  /** Metric alias of the highest value (the `MAX`). */
  high: string;
  /** Metric alias of the lowest value (the `MIN`). */
  low: string;
  /** Metric alias of the closing value (the `LAST`). */
  close: string;
}

/**
 * A gauge: one number placed on a scale, and how far it is from a target —
 * 「离目标多远」 on a wall screen. The metric card says the number and how
 * it moved; a gauge says where it stands between two ends. It groups by
 * nothing.
 */
export interface GaugeSpec {
  /** Metric alias: the needle. */
  metric: string;
  /** Where the number should reach, marked on the scale. */
  target?: number;
  /** The scale's start. Left out, 0. */
  min?: number;
  /**
   * The scale's end. Left out, a round number at or past the value and the
   * target (`gaugeScale`), so the needle never leaves the dial.
   */
  max?: number;
  format?: ValueFormat;
}

/**
 * A radar: several metrics per group as one closed shape, an axis per
 * metric each on its own scale — the outline of each group against the
 * others. Three metrics at least: two axes enclose nothing.
 */
export interface RadarSpec {
  /** Group alias: one shape per value. */
  category: string;
  /** Metric aliases, one axis each, in the order they go round. */
  metrics: string[];
}

/**
 * Parallel coordinates: several metrics per group as one line across as
 * many upright axes, each on its own scale — where a group stands on each,
 * and which move together. Three metrics at least: two are a scatter.
 */
export interface ParallelSpec {
  /** Group alias: one line per value. */
  category: string;
  /** Metric aliases, one axis each, left to right. */
  metrics: string[];
}

/** The most levels a hierarchy or a flow draws: past four, rings and columns are slivers. */
export const MAX_CHART_LEVELS = 4;

/**
 * A sunburst or a tree: a whole broken down level by level, one dimension a
 * level, outermost first — 品类 → 子类. Every level's parts add up to their
 * parent, so only a metric that adds up draws one; a leaf is one row, its
 * parents the sums of their rows.
 */
export interface HierarchySpec {
  /** Group aliases, outermost level first; two to `MAX_CHART_LEVELS`. */
  levels: string[];
  /** Metric alias: a part's size. */
  value: string;
}

/**
 * A sankey: how an amount flows from one dimension's values to the next's —
 * 渠道 → 支付方式 — each band as wide as the metric over the rows of that
 * pair. The bands out of a value add up to it, so only a metric that adds
 * up draws one.
 */
export interface SankeySpec {
  /** Group aliases, left to right; two to `MAX_CHART_LEVELS`. */
  levels: string[];
  /** Metric alias: a band's width. */
  value: string;
}

/**
 * A calendar heatmap: a number a day, laid out as the calendar lays the
 * days out — weeks across, weekdays down, a year a block — so a season, a
 * weekday rhythm and a single odd day are seen at once. Its one dimension
 * is a daily date bucket.
 */
export interface CalendarSpec {
  /** Alias of the one `DATE_HISTOGRAM` group, by `DAY`. */
  date: string;
  /** Metric alias: a day's shade. */
  value: string;
}

/**
 * A theme river: a stacked stream over time, one stream per value of a
 * second dimension, each as wide as its number — how a whole's make-up
 * flows. The streams add up to the river, so only a metric that adds up
 * draws one.
 */
export interface ThemeRiverSpec {
  /** Alias of the `DATE_HISTOGRAM` group the river runs along. */
  x: string;
  /** Alias of the other group: one stream per value. */
  splitBy: string;
  /** Metric alias: a stream's width. */
  value: string;
}

/**
 * A map: a number per region, each region shaded by it — 按地区看. The
 * region dimension's values, as its column shows them, are the names of the
 * map's areas. The package ships no map: a host registers the geography it
 * may lawfully show (`registerChartMap` in `/ui`, D41) and names it here;
 * left out, the first map the host registered.
 */
export interface MapSpec {
  /** Group alias: one region per value. */
  region: string;
  /** Metric alias: a region's shade. */
  value: string;
  /** The registered map's name. */
  map?: string;
}

/**
 * Stages come either from one filtered metric each, or from the values of a
 * single group with an explicit business order.
 */
/**
 * A funnel: its stages in the business's order. There is no choice of what
 * a conversion is relative to — the drawing says each stage against the one
 * before and against the first, and the drop between them (2026-09-25).
 */
export interface FunnelSpec {
  stages: FunnelStages;
  orientation?: 'vertical' | 'horizontal';
}

export type FunnelStages =
  | { from: 'metrics'; items: { metric: string; label?: string }[] }
  | {
      from: 'group';
      category: string;
      value: string;
      /** Business order of the stages, taken from the group values. */
      order: string[];
      /**
       * Accumulate into "reached at least this stage" — each stage plus
       * every later one, which is right only when each object sits in
       * exactly one stage. Defaults to false: each stage is its own rows'
       * number, the one the table shows.
       */
      cumulative?: boolean;
    };

export interface MetricCardSpec {
  /** Metric alias. */
  metric: string;
  /**
   * Another metric the headline is compared with, read over the same span
   * as the headline: the same period's value in the `last` mode of a trend,
   * the whole's otherwise.
   */
  compare?: { metric: string; mode: 'delta' | 'percent' };
  /**
   * Rendered as progress towards this value — a goal for the span the
   * headline covers: one period in the `last` mode of a trend (a daily
   * goal over a daily trend), the whole otherwise.
   */
  target?: number;
  /** Sparkline; requires exactly one DATE_HISTOGRAM group with this alias. */
  trend?: MetricTrend;
  format?: ValueFormat;
  /**
   * Whether a fall is the good direction — failures, latency, refunds — so
   * every change the card draws is coloured the other way round: its change
   * against the period before and its comparison (`compare`) alike.
   */
  lowerIsBetter?: boolean;
}

/** The time dimension a metric card draws as its sparkline, and how it reads. */
export interface MetricTrend {
  /** Alias of the one DATE_HISTOGRAM group. */
  x: string;
  /**
   * What the headline is. `last`, the default: the last period that had
   * ended when the question was asked, with its change against the period
   * before — the reading an operator wants from a trend card. `whole`: every
   * record in the range, from the ungrouped query (`asksForWhole`).
   */
  headline?: MetricHeadline;
}

export type MetricHeadline = 'last' | 'whole';

/** The two readings of a trend card's headline, the default first. */
export const METRIC_HEADLINES = [
  'last',
  'whole',
] as const satisfies readonly MetricHeadline[];
