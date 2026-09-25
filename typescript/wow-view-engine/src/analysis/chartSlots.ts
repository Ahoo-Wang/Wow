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

import {
  CHART_FAMILY,
  CHART_TYPES,
  type AnalysisGroup,
  type AnalysisMetric,
  type CartesianSeries,
  type CartesianSpec,
  type ChartSpec,
  type ChartType,
  type FunnelSpec,
  type HeatmapSpec,
  type MetricCardSpec,
  type PieSpec,
  type ScatterSpec,
  type TreemapSpec,
  type WaterfallSpec,
} from '../model/index.js';
import { boxSet, fiveNumberSets, type FiveNumbers } from './boxplot.js';
import { chartLevels } from './hierarchy.js';
import { profileAxes } from './profiles.js';
import { isAdditiveMetric, readsOffSums } from './validateChart.js';

/**
 * The chart, with the family its `type` asks for filled in from the groups and
 * metrics the analysis actually has.
 *
 * A chart addresses its rows by alias, so it is not a setting that survives
 * the query changing shape: a `type` switched without its sub-object reported
 * `chart.family.missing` and drew nothing, and a group added or removed under
 * a chart that still named the old ones reported `chart.group.unconsumed` or
 * `chart.group.unknown`. Both are the same question — which alias sits in
 * which slot — so there is one answer, run on every change to the type, the
 * groups or the metrics.
 *
 * **A slot the user chose is kept while it is still valid**; only a slot whose
 * alias is gone, or that the new shape has no room for, is filled again. Every
 * other family's sub-object is carried over untouched, so switching away and
 * back returns to the settings that family had.
 *
 * **The family follows the shape where the shape leaves it undrawable.** Every
 * family but the metric card and a metric-staged funnel addresses its rows by
 * a dimension, so an analysis with none can only be a card; and a card is one
 * number, so an analysis with a dimension it cannot draw as a sparkline is not
 * one. Those two are not preferences the user gave up — removing the last
 * dimension is a change to the question, not to the chart — so the type moves
 * rather than the config turning red.
 *
 * What it does not do is invent a shape: a heatmap of one dimension, a scatter
 * of one metric and a funnel whose stages nobody has named are not
 * expressible, and the slot is left empty so that `validateChart` says which
 * one is missing rather than the family being absent altogether. Those are
 * types the user picked against a shape that cannot carry them; which types a
 * given shape *offers* is a separate question, answered where they are listed.
 *
 * **A mark measures quantities, never a moment** (`momentMetrics`, passed as
 * `moments`): every slot a mark is drawn from — a series, a slice, a shade, a
 * scatter axis, a funnel stage — is filled from the metrics that are not
 * moments, and a card's comparison, target and number format stay only over
 * a headline that is a quantity. A shape with dimensions and nothing to
 * measure is drawn as bars with no series: a chart that is valid and empty,
 * which the picker greys (`chart.fit.needs-quantity`) and the result block
 * shows as its table.
 *
 * **A combo puts on the right axis what measures something else**
 * (`measures`, `metricMeasures`): a series arriving in a combo takes the
 * right axis when its metric is a quantity of another kind than the first
 * series' (`comboAxis`). Left out, every metric reads as one measure.
 */
export function fitChartSlots(
  chart: ChartSpec,
  groups: readonly AnalysisGroup[],
  metrics: readonly AnalysisMetric[],
  moments: ReadonlySet<string> = NO_MOMENTS,
  measures: ReadonlyMap<string, string> = NO_MEASURES,
): ChartSpec {
  const quantities = metrics
    .filter(metric => !moments.has(metric.alias))
    .map(metric => metric.alias);
  const shape: Shape = {
    measures,
    groups: groups.map(group => group.alias),
    metrics: metrics.map(metric => metric.alias),
    quantities,
    fiveNumbers: fiveNumberSets(metrics, new Set(quantities)),
    additive: new Set(
      metrics.filter(isAdditiveMetric).map(metric => metric.alias),
    ),
    trendable: new Set(
      metrics
        .filter(metric =>
          readsOffSums(metric, alias =>
            metrics.find(entry => entry.alias === alias),
          ),
        )
        .map(metric => metric.alias),
    ),
    dateGroups: groups
      .filter(group => group.type === 'DATE_HISTOGRAM')
      .map(group => group.alias),
    dayGroups: groups
      .filter(group => group.type === 'DATE_HISTOGRAM' && group.unit === 'DAY')
      .map(group => group.alias),
  };
  const drawable = drawableType(chart, shape);
  if (drawable !== chart.type) chart = { ...chart, type: drawable };
  switch (chart.type) {
    case 'bar':
    case 'line':
    case 'area':
    case 'combo':
      return {
        ...chart,
        cartesian: cartesian(chart.cartesian, shape, chart.type),
      };
    case 'pie':
      return { ...chart, pie: pie(chart.pie, shape) };
    case 'heatmap':
      return { ...chart, heatmap: heatmap(chart.heatmap, shape) };
    case 'scatter':
      return { ...chart, scatter: scatter(chart.scatter, shape) };
    case 'funnel':
      return { ...chart, funnel: funnel(chart.funnel, shape) };
    case 'metric':
      return { ...chart, metric: metricCard(chart.metric, shape) };
    case 'waterfall':
      return { ...chart, waterfall: waterfall(chart.waterfall, shape) };
    case 'treemap':
      return { ...chart, treemap: treemap(chart.treemap, shape) };
    case 'boxplot':
      return {
        ...chart,
        boxplot: {
          category: slot(chart.boxplot?.category, shape.groups),
          ...boxSet(chart.boxplot, shape.fiveNumbers),
        },
      };
    case 'gauge':
      // Its scale and target are the analyst's, whichever metric it reads.
      return {
        ...chart,
        gauge: {
          ...chart.gauge,
          metric: slot(chart.gauge?.metric, shape.quantities),
        },
      };
    case 'calendar':
      return {
        ...chart,
        calendar: {
          date: slot(chart.calendar?.date, shape.dayGroups),
          value: slot(chart.calendar?.value, shape.quantities),
        },
      };
    case 'themeRiver': {
      // The river runs along the date; the other dimension is its streams.
      const x = slot(chart.themeRiver?.x, shape.dateGroups);
      return {
        ...chart,
        themeRiver: {
          x,
          splitBy: slot(
            chart.themeRiver?.splitBy,
            shape.groups.filter(alias => alias !== x),
          ),
          value: slot(chart.themeRiver?.value, summed(shape)),
        },
      };
    }
    case 'sunburst':
    case 'tree':
    case 'sankey': {
      // Every dimension is a level, in the analyst's order; the parts are
      // sizes of a whole, so only what adds up measures them.
      const family = chart.type;
      return {
        ...chart,
        [family]: {
          levels: chartLevels(chart[family]?.levels, shape.groups),
          value: slot(chart[family]?.value, summed(shape)),
        },
      };
    }
    case 'radar':
    case 'parallel': {
      const family = chart.type;
      return {
        ...chart,
        [family]: {
          category: slot(chart[family]?.category, shape.groups),
          metrics: profileAxes(chart[family]?.metrics, shape.quantities),
        },
      };
    }
    default:
      // A stored config may name a type this package does not have; there is
      // no family to fill and `validateChart` reports the type itself.
      return chart;
  }
}

/**
 * The type this shape can actually carry, which is the current one unless
 * the dimensions have left it nothing to address.
 *
 * Only two families draw an analysis with no dimension at all: the card, and
 * a funnel whose stages are metrics. And only one thing puts a dimension on a
 * card: a sparkline, which needs exactly one time dimension and a headline
 * read off sums (`readsOffSums`: one that adds, or a ratio of sums, D38).
 */
function drawableType(chart: ChartSpec, shape: Shape): ChartType {
  if (!CHART_TYPES.includes(chart.type)) return chart.type;
  // A gauge is the card's one number on a scale, and keeps its type too.
  if (shape.groups.length === 0)
    return chart.type === 'funnel' || chart.type === 'gauge'
      ? chart.type
      : 'metric';
  // Nothing a mark can measure: the empty bars are the one chart such a
  // shape is valid as, whatever was picked.
  if (shape.quantities.length === 0)
    return CHART_FAMILY[chart.type] === 'cartesian' ? chart.type : 'bar';
  if (chart.type !== 'metric') return chart.type;
  return shape.groups.length === 1 &&
    shape.dateGroups.length === 1 &&
    shape.trendable.has(slot(chart.metric?.metric, headlines(shape)))
    ? 'metric'
    : 'bar';
}

/**
 * What a card's headline may be: any metric over no dimension; over the one
 * date dimension only one read off sums (`readsOffSums`, D38) — the first
 * of those, when the one chosen is not.
 */
function headlines(shape: Shape): string[] {
  return shape.groups.length === 0
    ? shape.metrics
    : shape.metrics.filter(alias => shape.trendable.has(alias));
}

/** Nothing is a moment: the shape of a chart fitted without a definition. */
const NO_MOMENTS: ReadonlySet<string> = new Set();

/** Every metric one measure: a chart fitted without a definition. */
const NO_MEASURES: ReadonlyMap<string, string> = new Map();

/** The aliases a chart may reference, and the facts a slot rule asks. */
interface Shape {
  /** What each metric is a quantity of (`metricMeasure`), by alias. */
  measures: ReadonlyMap<string, string>;
  groups: string[];
  metrics: string[];
  /** The metrics a mark can measure: every one that is not a moment. */
  quantities: string[];
  /** Each field's five numbers the quantities hold (`fiveNumberSets`). */
  fiveNumbers: FiveNumbers[];
  /** Metrics the projection may add up across rows (a pie's merged tail). */
  additive: Set<string>;
  /**
   * Metrics a card's trend can headline (`readsOffSums`): those that add,
   * and the ratios of their sums (D38).
   */
  trendable: Set<string>;
  dateGroups: string[];
  /** The date groups bucketed by day: what a calendar lays out. */
  dayGroups: string[];
}

/**
 * The alias in this slot, or the one the shape offers for it.
 *
 * An empty string is what a slot with nothing to hold reads as — no group is
 * named that — so the chart keeps a complete sub-object and validation names
 * the slot instead of the family.
 */
function slot(
  chosen: string | undefined,
  available: readonly string[],
  at = 0,
): string {
  if (chosen !== undefined && available.includes(chosen)) return chosen;
  return available[at] ?? '';
}

/**
 * A cartesian chart consumes every group: one on the horizontal axis, a second
 * pivoted into a series per value. A pivot draws exactly one metric, so the
 * series list narrows to one the moment a second group appears and opens back
 * up to every metric when it goes.
 *
 * Between those two moments the series list is the analyst's (D20 屏 J): a
 * list that still names metrics the shape has is kept exactly as it stands,
 * because this function runs again on every redraw and a list re-derived
 * there is a series the options panel cannot remove — pressed, it came
 * straight back.
 */
function cartesian(
  spec: CartesianSpec | undefined,
  shape: Shape,
  type: ChartType,
): CartesianSpec {
  const x = slot(spec?.x, shape.groups);
  const others = shape.groups.filter(alias => alias !== x);
  const splitBy = others.length > 0 ? slot(spec?.splitBy, others) : undefined;
  const previous = new Map(
    (spec?.series ?? []).map(series => [series.metric, series]),
  );
  // What the spec draws now, minus any metric the shape no longer has. The
  // pivot that narrowed it is the one thing that reopens it: that narrowing
  // was the shape's doing, not a choice anyone made.
  const named =
    spec?.splitBy === undefined
      ? (spec?.series ?? [])
          .map(series => series.metric)
          .filter(metric => shape.quantities.includes(metric))
      : [];
  const pivoted = slot(spec?.series?.[0]?.metric, shape.quantities);
  const drawn =
    splitBy !== undefined
      ? pivoted === ''
        ? []
        : [pivoted]
      : named.length > 0
        ? arrivingCombo(type, named, previous)
          ? [
              ...named,
              ...shape.quantities.filter(metric => !named.includes(metric)),
            ]
          : named
        : shape.quantities;
  const lead = drawn[0];
  const leadSide =
    lead === undefined ? 'left' : (previous.get(lead)?.axis ?? 'left');
  const series = drawn.map((metric, index) =>
    oneSeries(
      previous.get(metric),
      metric,
      type,
      index,
      comboAxis(shape.measures, lead, metric, leadSide),
    ),
  );
  // A reference line names the axis it hangs on, and an axis with no series
  // on it is not an axis; the line goes with the series that left.
  const axes = new Set(series.map(entry => entry.axis ?? 'left'));
  // A statistic line, like a derived series, is taken of one drawn metric,
  // and goes with it too.
  const drawnOn = (metric: string | undefined, axis?: 'left' | 'right') =>
    series.some(
      entry =>
        entry.metric === metric &&
        (axis === undefined || (entry.axis ?? 'left') === axis),
    );
  const lines = (spec?.referenceLines ?? []).filter(
    line =>
      axes.has(line.axis) &&
      (line.statistic === undefined || drawnOn(line.metric, line.axis)),
  );
  const bands = (spec?.referenceBands ?? []).filter(band =>
    axes.has(band.axis),
  );
  const derived = (spec?.derived ?? []).filter(entry => drawnOn(entry.metric));
  // A share of a stack is a part of a sum: over a metric that does not add
  // up there is no whole to be a part of (`chart.cartesian.percent-not-additive`).
  const shares =
    spec?.percentStack === true &&
    series.length > 0 &&
    series.every(entry => shape.additive.has(entry.metric));
  return {
    x,
    ...(splitBy === undefined ? {} : { splitBy }),
    series,
    ...(spec?.orientation === undefined
      ? {}
      : { orientation: spec.orientation }),
    ...(spec?.yAxis === undefined ? {} : { yAxis: spec.yAxis }),
    ...(lines.length > 0 ? { referenceLines: lines } : {}),
    ...(bands.length > 0 ? { referenceBands: bands } : {}),
    ...(spec?.extremes === true ? { extremes: true } : {}),
    ...(derived.length > 0 ? { derived } : {}),
    ...(spec?.missing === undefined ? {} : { missing: spec.missing }),
    ...(shares ? { percentStack: true } : {}),
  };
}

/**
 * One drawn series, keeping how it was drawn; a combo names its own mark,
 * and a series that has none yet takes the combo's default for its place
 * (`comboMark`) and the axis its measure asks for (`comboAxis`), unless an
 * axis was chosen for it already.
 */
function oneSeries(
  previous: CartesianSeries | undefined,
  metric: string,
  type: ChartType,
  index: number,
  axis: 'left' | 'right',
): CartesianSeries {
  const series: CartesianSeries = { ...previous, metric };
  if (type !== 'combo' || series.type !== undefined) return series;
  return {
    ...series,
    type: comboMark(index),
    ...(series.axis === undefined && axis === 'right' ? { axis } : {}),
  };
}

/**
 * The mark a combo gives the series at `index` when nobody has chosen one:
 * the first metric as bars, every other as a line — Metabase's combo, and
 * the only reading of 「组合图」 that is not a bar chart under another name.
 * Picking it used to draw every series as bars, and the analyst who asked
 * for bars with a line saw nothing change (2026-09-23 audit P1-8). A mark
 * the analyst picked on the options page is kept.
 */
export function comboMark(index: number): 'bar' | 'line' {
  return index === 0 ? 'bar' : 'line';
}

/**
 * The axis a combo gives `metric` when nobody has chosen one: the first
 * series' side (`leadSide`) when it measures the same kind of thing as the
 * first series (`lead`), the other side when it does not.
 *
 * Decided by what the metrics are, not by their values, which this layer
 * never sees (`metricMeasure`): a count beside an amount, an amount beside
 * an average, a percent beside a count are two scales, and drawn on one the
 * smaller lies flat along zero — the combo's second metric always sat on the
 * left axis, and a count beside money read as nothing. Two metrics of one
 * measure stay together even when their values are far apart: nothing says
 * they differ, and one scale is what lets them be compared. An axis the
 * analyst picked on the options page is theirs and is kept.
 */
export function comboAxis(
  measures: ReadonlyMap<string, string>,
  lead: string | undefined,
  metric: string,
  leadSide: 'left' | 'right' = 'left',
): 'left' | 'right' {
  const same =
    lead === undefined ||
    lead === metric ||
    measures.get(lead) === measures.get(metric);
  if (same) return leadSide;
  return leadSide === 'left' ? 'right' : 'left';
}

/**
 * A combo just picked over a chart that draws one metric of several: one
 * mark is no combo, so the others join as lines. Told apart by the series
 * naming no mark — every series a combo has drawn names one, so a combo the
 * analyst narrowed to one series on its options page stays one series.
 */
function arrivingCombo(
  type: ChartType,
  named: readonly string[],
  previous: ReadonlyMap<string, CartesianSeries>,
): boolean {
  return (
    type === 'combo' &&
    named.length === 1 &&
    previous.get(named[0])?.type === undefined
  );
}

/**
 * A pie's slices are shares of a whole, so it measures what adds up (D33
 * Q56), as the families below do (`summed`).
 */
function pie(spec: PieSpec | undefined, shape: Shape): PieSpec {
  const value = slot(spec?.value, summed(shape));
  const merges =
    spec?.maxSlices !== undefined &&
    Number.isInteger(spec.maxSlices) &&
    spec.maxSlices >= 2;
  return {
    category: slot(spec?.category, shape.groups),
    value,
    ...(spec?.donut === undefined ? {} : { donut: spec.donut }),
    ...(merges ? { maxSlices: spec?.maxSlices } : {}),
  };
}

function heatmap(spec: HeatmapSpec | undefined, shape: Shape): HeatmapSpec {
  const x = slot(spec?.x, shape.groups);
  const y = slot(
    spec?.y,
    shape.groups.filter(alias => alias !== x),
  );
  return {
    x,
    y,
    value: slot(spec?.value, shape.quantities),
    ...(spec?.scale === undefined ? {} : { scale: spec.scale }),
  };
}

function scatter(spec: ScatterSpec | undefined, shape: Shape): ScatterSpec {
  const x = slot(spec?.x, shape.quantities);
  const y = slot(
    spec?.y,
    shape.quantities.filter(alias => alias !== x),
  );
  const size =
    spec?.size !== undefined && shape.quantities.includes(spec.size)
      ? spec.size
      : undefined;
  return {
    category: slot(spec?.category, shape.groups),
    x,
    y,
    ...(size === undefined ? {} : { size }),
    // How the axes are drawn is the analyst's, whichever metrics they hold.
    ...(spec?.xAxis === undefined ? {} : { xAxis: spec.xAxis }),
    ...(spec?.yAxis === undefined ? {} : { yAxis: spec.yAxis }),
  };
}

/**
 * Stages come from metrics when there is nothing to group by, and otherwise
 * from the values of the one group, whose business order is data this layer
 * has never seen, so an order already written down is kept and an absent one
 * stays absent for the editor to ask about. Either way a stage measures only
 * what adds up — a record count or a sum.
 */
function funnel(spec: FunnelSpec | undefined, shape: Shape): FunnelSpec {
  // A stage is a count of what entered or remained: only a metric that adds
  // up is one (`chart.funnel.not-additive`), so a lead that does not gives
  // way to the first that does.
  const counted = shape.quantities.filter(alias => shape.additive.has(alias));
  const rest = {
    ...(spec?.conversion === undefined ? {} : { conversion: spec.conversion }),
    ...(spec?.orientation === undefined
      ? {}
      : { orientation: spec.orientation }),
  };
  if (shape.groups.length === 0) {
    // The stages stay in the order the spec puts them in — that order is
    // the analyst's, set by hand on the options page, and this runs again
    // on every redraw — with any metric the list does not name yet added at
    // the end, where it can be moved from.
    const named =
      spec?.stages.from === 'metrics'
        ? spec.stages.items.filter(item => counted.includes(item.metric))
        : [];
    const taken = new Set(named.map(item => item.metric));
    return {
      stages: {
        from: 'metrics',
        items: [
          ...named,
          ...counted
            .filter(metric => !taken.has(metric))
            .map(metric => ({ metric })),
        ],
      },
      ...rest,
    };
  }
  const category = slot(
    spec?.stages.from === 'group' ? spec.stages.category : undefined,
    shape.groups,
  );
  const kept = spec?.stages.from === 'group' ? spec.stages : undefined;
  return {
    stages: {
      from: 'group',
      category,
      value: slot(kept?.value, counted),
      // A stage listed twice is one stage (`chart.funnel.duplicate-stage`).
      order: [...new Set(kept?.order ?? [])],
      ...(kept?.cumulative === undefined
        ? {}
        : { cumulative: kept.cumulative }),
    },
    ...rest,
  };
}

/**
 * The metrics a family that adds its numbers up may measure: the quantities
 * that add (a record count or a sum), so a lead that does not — an average
 * carried in from a bar chart — gives way to the first that does.
 */
function summed(shape: Shape): string[] {
  return shape.quantities.filter(alias => shape.additive.has(alias));
}

/** A waterfall steps along its one dimension and adds up one metric. */
function waterfall(
  spec: WaterfallSpec | undefined,
  shape: Shape,
): WaterfallSpec {
  return {
    x: slot(spec?.x, shape.groups),
    value: slot(spec?.value, summed(shape)),
    ...(spec?.total === undefined ? {} : { total: spec.total }),
  };
}

/**
 * A treemap tiles one dimension; a second, when there is one, is the outer
 * level the tiles nest in (`parent`), and goes when it does.
 */
function treemap(spec: TreemapSpec | undefined, shape: Shape): TreemapSpec {
  const category = slot(spec?.category, shape.groups);
  const others = shape.groups.filter(alias => alias !== category);
  return {
    category,
    ...(others.length > 0 ? { parent: slot(spec?.parent, others) } : {}),
    value: slot(spec?.value, summed(shape)),
  };
}

/**
 * A card is one number, so it groups by nothing — unless it draws a sparkline,
 * which needs exactly one date grouping and a headline read off sums, since
 * that headline is the buckets summed whenever the totals query did not run
 * — or, for a ratio of sums, their sums divided (D38).
 */
function metricCard(
  spec: MetricCardSpec | undefined,
  shape: Shape,
): MetricCardSpec {
  const metric = slot(spec?.metric, headlines(shape));
  // A moment is written out as it is: nothing compares to it, nothing is a
  // target for it, and a number format does not print it.
  const quantity = shape.quantities.includes(metric);
  const compare =
    quantity &&
    spec?.compare !== undefined &&
    shape.quantities.includes(spec.compare.metric)
      ? spec.compare
      : undefined;
  const trending =
    shape.groups.length === 1 &&
    shape.dateGroups.length === 1 &&
    shape.trendable.has(metric) &&
    (compare === undefined || shape.trendable.has(compare.metric));
  return {
    metric,
    ...(compare === undefined ? {} : { compare }),
    ...(spec?.target === undefined || !quantity ? {} : { target: spec.target }),
    ...(spec?.format === undefined || !quantity ? {} : { format: spec.format }),
    ...(spec?.lowerIsBetter === true && quantity
      ? { lowerIsBetter: true }
      : {}),
    // How the trend reads — its last period or the whole — is the
    // analyst's, and survives a refit onto another date group.
    ...(trending ? { trend: { ...spec?.trend, x: shape.dateGroups[0] } } : {}),
  };
}
