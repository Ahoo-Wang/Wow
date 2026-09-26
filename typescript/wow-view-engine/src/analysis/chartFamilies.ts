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

import type {
  CartesianSeries,
  ChartFamily,
  ChartSpec,
  ChartType,
} from '../model/index.js';
import { CHART_FAMILY, MAX_CHART_LEVELS } from '../model/index.js';

/**
 * What a chart family is, in one place (phase-2 review E1). A family is the
 * unit a new kind of chart is added as, so everything the kernels and the
 * options panel ask of it by family is a row here rather than a `switch` in
 * each of them: which pages its options have, whether it has a legend and
 * value labels, and what shape of result it can draw.
 *
 * The fit is the forward reading of `validateChart`'s rules: a family is
 * offered for a shape exactly when the slots `fitChartSlots` fills for it
 * pass validation. That agreement is a test over every shape
 * (test/chartFamilies.test.ts「one rule, read forward and after the fact」);
 * the day the two drift, it fails rather than a tile being offered that
 * then refuses to draw.
 */

/** The three pages a chart's options are laid out on. */
export type OptionsTab = 'data' | 'display' | 'axes';

/**
 * Why a chart type cannot draw the shape on hand, as a catalogue key. Each
 * names what the shape lacks, or has too much of, in the analyst's words,
 * because that is what the greyed tile says under itself (D20 屏 I).
 */
export type ChartUnfit =
  | 'chart.fit.needs-dimension'
  | 'chart.fit.needs-one-dimension'
  | 'chart.fit.needs-two-dimensions'
  | 'chart.fit.too-many-dimensions'
  | 'chart.fit.needs-two-metrics'
  | 'chart.fit.needs-no-dimension'
  | 'chart.fit.needs-quantity'
  | 'chart.fit.needs-category'
  | 'chart.fit.needs-two-stages'
  | 'chart.fit.needs-additive'
  | 'chart.fit.needs-share'
  | 'chart.fit.needs-five-numbers'
  | 'chart.fit.needs-date'
  | 'chart.fit.needs-ohlc'
  | 'chart.fit.needs-three-metrics'
  | 'chart.fit.too-many-levels'
  | 'chart.fit.needs-day'
  | 'chart.fit.needs-date-and-split'
  | 'chart.fit.needs-region'
  | 'chart.fit.needs-map';

/** The facts of a result's shape a family's fit reads. */
export interface ShapeFacts {
  /** How many dimensions. */
  groups: number;
  /** How many metrics. */
  metrics: number;
  /**
   * How many of them are quantities rather than moments (`momentMetrics`):
   * what a mark can measure. A moment is read on a card, never drawn.
   */
  quantities: number;
  /** One dimension, and it is a date bucket. */
  dated: boolean;
  /**
   * How many of the quantities may be added up across rows
   * (`isAdditiveMetric`): a record count or a sum. A trend's headline and a
   * funnel's stages are counted from these alone.
   */
  additive: number;
  /**
   * One dimension, and it names categories (`TERMS`) rather than cutting a
   * scale into buckets: only a category's values can be a funnel's stages.
   */
  categorical: boolean;
  /**
   * Rows are known and none of the one dimension's values is text: nothing
   * a stage could be named by.
   */
  textless: boolean;
  /**
   * How many stages a funnel over the one dimension would have: the text
   * values the rows on hand give, once each (`stageValues`, what picking the
   * funnel fills its order with); with no rows, the stages the chart already
   * names; `undefined` while neither is known.
   */
  stages?: number;
  /**
   * Whether the quantities hold a box's five numbers of one field — its
   * lowest, three percentiles and its highest (`fiveNumberSets`).
   */
  fiveNumbers: boolean;
  /**
   * Whether the quantities hold a candle's four numbers of one field — its
   * opening value, highest, lowest and closing value (`ohlcSets`).
   */
  ohlc: boolean;
  /** How many of the dimensions are date buckets. */
  datedGroups: number;
  /** One dimension, and it is a date bucket by day: a calendar's. */
  daily: boolean;
}

export interface ChartFamilyTraits {
  /** The pages of its options panel. */
  tabs: readonly OptionsTab[];
  /** Whether it draws a legend worth placing. */
  legend: boolean;
  /** Whether it can write the values on its marks. */
  labels: boolean;
  /**
   * Which of its marks write their values while nobody has said
   * (`ChartSpec.labels` left out). A bar does: its length is compared at a
   * glance but read off the axis only roughly, and the labels give way where
   * they would land on each other. A line or an area does not — it answers
   * where the numbers are heading and how two series move together, and a
   * number on every point drowned a monthly line of 23 points (2026-09-23
   * audit P1-3; Metabase leaves its data-point values off until asked). A
   * pie already writes its shares on its slices, and a heatmap's cells are
   * too many for a number each, so neither has a mark here.
   */
  labelsByDefault: readonly SeriesMark[];
  /** Why it cannot draw this shape, or `null` when it can. */
  unfit(shape: ShapeFacts): ChartUnfit | null;
}

/**
 * Every family's traits. The fits, in words: a cartesian chart puts one
 * dimension on its axis and may split by a second, so it needs one and takes
 * at most two (a third would leave several rows per point, which AVG and
 * DISTINCT_COUNT cannot be added back up over — D20 left that a table's
 * job rather than drop a dimension silently); a pie needs exactly one, and
 * a count or a sum, since its slices are shares of a whole; a heatmap two; a scatter plots two metrics per value of one dimension; a
 * funnel's stages are the values of one category dimension — two of them at
 * least, counted in the rows when there are rows — or, with none, the
 * metrics themselves, and either way it counts only what adds up (a record
 * count or a sum); a card is one number, or a sparkline over the one
 * date dimension when its headline adds up. A waterfall steps along one
 * dimension and a treemap tiles one, or nests a second inside it; both add
 * their numbers up — into a running total, into a whole — so both count
 * only what adds up, as a funnel does. A boxplot draws one box per value of
 * one dimension from a field's five numbers (`fiveNumberSets`); a
 * candlestick one candle per bucket of one date dimension from a field's
 * opening, highest, lowest and closing values (`ohlcSets`); a gauge is
 * the card's one number on a scale; a radar and parallel axes draw each
 * group of one dimension across three metrics or more. A sunburst, a tree
 * and a sankey read two to four dimensions as levels, and add their numbers
 * up as a treemap does.
 *
 * Every family but the card measures its metrics as marks — a length, a
 * slice, a shade, a position against zero — so it counts only the metrics
 * that are quantities: the earliest or the latest of a date is a moment, and
 * a shape of nothing else is greyed with `chart.fit.needs-quantity` once its
 * dimensions would fit. A card writes its headline out, so a moment is one.
 */
export const CHART_FAMILIES: Readonly<Record<ChartFamily, ChartFamilyTraits>> =
  Object.freeze({
    cartesian: {
      tabs: ['data', 'display', 'axes'],
      legend: true,
      labels: true,
      labelsByDefault: ['bar'],
      unfit: ({ groups, quantities }) =>
        groups === 0
          ? 'chart.fit.needs-dimension'
          : groups > 2
            ? 'chart.fit.too-many-dimensions'
            : measured(quantities, 1),
    },
    pie: {
      tabs: ['data', 'display'],
      legend: true,
      labels: true,
      labelsByDefault: [],
      unfit: ({ groups, quantities, additive }) =>
        groups === 1
          ? (measured(quantities, 1) ?? shared(additive))
          : 'chart.fit.needs-one-dimension',
    },
    heatmap: {
      tabs: ['data', 'display'],
      legend: false,
      labels: true,
      labelsByDefault: [],
      unfit: ({ groups, quantities }) =>
        groups === 2
          ? measured(quantities, 1)
          : 'chart.fit.needs-two-dimensions',
    },
    scatter: {
      tabs: ['data', 'axes'],
      legend: false,
      labels: false,
      labelsByDefault: [],
      unfit: ({ groups, metrics, quantities }) =>
        groups === 0
          ? 'chart.fit.needs-dimension'
          : groups > 1
            ? 'chart.fit.needs-one-dimension'
            : metrics < 2
              ? 'chart.fit.needs-two-metrics'
              : measured(quantities, 2),
    },
    funnel: {
      tabs: ['data', 'display'],
      legend: false,
      labels: false,
      labelsByDefault: [],
      unfit: ({
        groups,
        metrics,
        quantities,
        additive,
        categorical,
        textless,
        stages,
      }) =>
        groups === 1
          ? (staged(categorical, textless, stages) ??
            measured(quantities, 1) ??
            counted(additive, 1))
          : groups === 0 && metrics >= 2
            ? (measured(quantities, 2) ?? counted(additive, 2))
            : groups === 0
              ? 'chart.fit.needs-two-metrics'
              : 'chart.fit.needs-one-dimension',
    },
    metric: {
      tabs: ['data', 'display'],
      legend: false,
      labels: false,
      labelsByDefault: [],
      unfit: ({ groups, dated, additive }) =>
        groups === 0 || (dated && additive > 0)
          ? null
          : 'chart.fit.needs-no-dimension',
    },
    waterfall: {
      tabs: ['data', 'display'],
      legend: false,
      labels: true,
      labelsByDefault: ['bar'],
      unfit: ({ groups, quantities, additive }) =>
        groups === 1
          ? (measured(quantities, 1) ?? counted(additive, 1))
          : 'chart.fit.needs-one-dimension',
    },
    treemap: {
      tabs: ['data'],
      legend: false,
      labels: false,
      labelsByDefault: [],
      unfit: ({ groups, quantities, additive }) =>
        groups === 0
          ? 'chart.fit.needs-dimension'
          : groups > 2
            ? 'chart.fit.too-many-dimensions'
            : (measured(quantities, 1) ?? counted(additive, 1)),
    },
    boxplot: {
      tabs: ['data'],
      legend: false,
      labels: false,
      labelsByDefault: [],
      unfit: ({ groups, fiveNumbers }) =>
        groups === 0
          ? 'chart.fit.needs-dimension'
          : groups > 1
            ? 'chart.fit.needs-one-dimension'
            : fiveNumbers
              ? null
              : 'chart.fit.needs-five-numbers',
    },
    candlestick: {
      tabs: ['data'],
      legend: false,
      labels: false,
      labelsByDefault: [],
      unfit: ({ groups, dated, ohlc }) =>
        groups === 0
          ? 'chart.fit.needs-dimension'
          : groups > 1
            ? 'chart.fit.needs-one-dimension'
            : !dated
              ? 'chart.fit.needs-date'
              : ohlc
                ? null
                : 'chart.fit.needs-ohlc',
    },
    gauge: {
      tabs: ['data', 'display'],
      legend: false,
      labels: false,
      labelsByDefault: [],
      unfit: ({ groups, quantities }) =>
        groups === 0 ? measured(quantities, 1) : 'chart.fit.needs-no-dimension',
    },
    radar: {
      tabs: ['data', 'display'],
      legend: true,
      labels: false,
      labelsByDefault: [],
      unfit: profiled,
    },
    parallel: {
      tabs: ['data', 'display'],
      legend: true,
      labels: false,
      labelsByDefault: [],
      unfit: profiled,
    },
    sunburst: {
      tabs: ['data'],
      legend: false,
      labels: false,
      labelsByDefault: [],
      unfit: levelled,
    },
    tree: {
      tabs: ['data'],
      legend: false,
      labels: false,
      labelsByDefault: [],
      unfit: levelled,
    },
    calendar: {
      tabs: ['data'],
      legend: false,
      labels: false,
      labelsByDefault: [],
      unfit: ({ groups, quantities, daily }) =>
        groups === 0
          ? 'chart.fit.needs-dimension'
          : groups > 1
            ? 'chart.fit.needs-one-dimension'
            : daily
              ? measured(quantities, 1)
              : 'chart.fit.needs-day',
    },
    themeRiver: {
      tabs: ['data', 'display'],
      legend: true,
      labels: false,
      labelsByDefault: [],
      unfit: ({ groups, datedGroups, quantities, additive }) =>
        groups !== 2
          ? 'chart.fit.needs-two-dimensions'
          : datedGroups < 1
            ? 'chart.fit.needs-date-and-split'
            : (measured(quantities, 1) ?? counted(additive, 1)),
    },
    map: {
      tabs: ['data'],
      legend: false,
      labels: false,
      labelsByDefault: [],
      unfit: ({ groups, quantities, categorical }) =>
        groups === 0
          ? 'chart.fit.needs-dimension'
          : groups > 1
            ? 'chart.fit.needs-one-dimension'
            : categorical
              ? measured(quantities, 1)
              : 'chart.fit.needs-region',
    },
    sankey: {
      tabs: ['data'],
      legend: false,
      labels: false,
      labelsByDefault: [],
      unfit: levelled,
    },
  });

/**
 * A sunburst, a tree or a sankey: a dimension a level, two at least — one
 * level is a pie or a bar — and at most `MAX_CHART_LEVELS`, past which the
 * rings and the columns are slivers; and a metric that adds up, since each
 * level's parts add up to their parent, and the bands out of a value to it.
 */
function levelled({
  groups,
  quantities,
  additive,
}: ShapeFacts): ChartUnfit | null {
  if (groups < 2) return 'chart.fit.needs-two-dimensions';
  if (groups > MAX_CHART_LEVELS) return 'chart.fit.too-many-levels';
  return measured(quantities, 1) ?? counted(additive, 1);
}

/**
 * A radar or parallel axes: one dimension, whose groups are the shapes or
 * the lines, and three metrics at least that a mark can measure — each is
 * an axis, and two axes enclose no shape (a radar) or are a scatter drawn
 * sideways (parallel axes).
 */
function profiled({
  groups,
  metrics,
  quantities,
}: ShapeFacts): ChartUnfit | null {
  if (groups === 0) return 'chart.fit.needs-dimension';
  if (groups > 1) return 'chart.fit.needs-one-dimension';
  if (metrics < 3) return 'chart.fit.needs-three-metrics';
  return measured(quantities, 3);
}

/**
 * A family whose dimensions fit, asked whether it has enough to measure:
 * `needed` quantities, the metrics beyond them being moments.
 */
function measured(quantities: number, needed: number): ChartUnfit | null {
  return quantities >= needed ? null : 'chart.fit.needs-quantity';
}

/**
 * A family that adds its numbers up, asked whether it has enough that does:
 * a funnel's stages are counts of what entered and what remained, and a
 * conversion of averages, distinct counts, percentiles or extremes means
 * nothing (`chart.funnel.not-additive`); a waterfall's running total and a
 * treemap's whole are sums, and a sum of averages is no total of anything.
 */
function counted(additive: number, needed: number): ChartUnfit | null {
  return additive >= needed ? null : 'chart.fit.needs-additive';
}

/**
 * A pie, asked whether it has a metric whose slices are shares: a slice is
 * a part of a whole, and only a count or a sum has one — an average's
 * 「占比」 is no share of anything (D33 Q56, settling Q9). Its own reason,
 * because the reader is told what a pie is, not what a funnel counts.
 */
function shared(additive: number): ChartUnfit | null {
  return additive >= 1 ? null : 'chart.fit.needs-share';
}

/**
 * Whether one dimension's values can be a funnel's stages. A stage is a step
 * of a process, named — a status, a page — so a date bucket or a number band
 * is a scale rather than steps (`chart.funnel.stages-need-category`), and so
 * are rows whose values are none of them text: a stage is read back by its
 * name, which a number or a yes/no never matches. Two steps at least, or
 * there is nothing to convert from (`chart.funnel.too-few-stages`) — and the
 * rows are what the order is filled from when the funnel is picked, so they
 * are what is counted.
 */
function staged(
  categorical: boolean,
  textless: boolean,
  stages: number | undefined,
): ChartUnfit | null {
  if (!categorical || textless) return 'chart.fit.needs-category';
  return stages !== undefined && stages < 2
    ? 'chart.fit.needs-two-stages'
    : null;
}

/** The mark a cartesian series is drawn as. */
export type SeriesMark = NonNullable<CartesianSeries['type']>;

/**
 * The mark one series of a chart of this type is drawn as: a combo's names
 * its own and is a bar where it names none; every other cartesian type
 * draws all its series alike.
 */
export function seriesMark(
  type: ChartType,
  series?: Pick<CartesianSeries, 'type'>,
): SeriesMark {
  if (type === 'combo') return series?.type ?? 'bar';
  return type === 'line' || type === 'area' ? type : 'bar';
}

/**
 * The marks a chart draws its series as, once each series: a waterfall's
 * steps are bars; none for any other family that has no series.
 */
export function chartMarks(chart: ChartSpec): SeriesMark[] {
  // A waterfall's steps are bars, and write their values as bars do.
  if (CHART_FAMILY[chart.type] === 'waterfall') return ['bar'];
  if (CHART_FAMILY[chart.type] !== 'cartesian') return [];
  const series = chart.cartesian?.series ?? [];
  return chart.type === 'combo' && series.length > 0
    ? series.map(one => seriesMark(chart.type, one))
    : [seriesMark(chart.type)];
}

/**
 * Whether a chart writes the values on its marks — or, given a `mark`, on
 * the series drawn as that mark: what the spec says, and its family's
 * default for the mark where it says nothing. An explicit `false` is a
 * choice and stands, as is an explicit `true`, which writes them on every
 * mark; a family that cannot write them never does.
 *
 * Asked of the whole chart, it is whether any of its marks writes them: a
 * combo of bars and a line writes the bars' values by default, and the
 * options' checkbox reads as ticked because numbers are on the screen.
 */
export function valueLabelsOn(
  chart: ChartSpec | undefined,
  mark?: SeriesMark,
): boolean {
  if (!chart) return false;
  const family = familyOf(chart.type);
  if (!family.labels) return false;
  if (chart.labels !== undefined) return chart.labels;
  const marks = mark === undefined ? chartMarks(chart) : [mark];
  return marks.some(one => family.labelsByDefault.includes(one));
}

/**
 * From this many categories a bar chart left to its default writes only
 * its peak and trough (`peaksOnlyLabels`): about the count where a number
 * over every bar has to be turned on its side to fit, and the row of
 * upright numbers is noise rather than a reading (2026-09-26 review P1-6).
 */
export const PEAKS_ONLY_FROM = 12;

/**
 * Whether a cartesian chart's bars, left to their default, write only
 * their highest and lowest value — as the extremes are marked — rather
 * than a number over every bar: `labels` unset and `PEAKS_ONLY_FROM`
 * categories or more. An explicit `labels: true` writes every bar's number
 * however many there are, and `false` writes none. The kernel finds the
 * extremes for it (`shapeChart`); which way the bars lie is the drawing's
 * to decide, and a chart lying on its side keeps a number on each row.
 */
export function peaksOnlyLabels(
  chart: ChartSpec | undefined,
  categories: number,
): boolean {
  return (
    chart !== undefined &&
    CHART_FAMILY[chart.type] === 'cartesian' &&
    chart.labels === undefined &&
    chartMarks(chart).includes('bar') &&
    categories >= PEAKS_ONLY_FROM
  );
}

/** The traits of the family a chart type belongs to. */
export function familyOf(type: ChartType): ChartFamilyTraits {
  return CHART_FAMILIES[CHART_FAMILY[type]];
}
