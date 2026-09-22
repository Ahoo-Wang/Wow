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
} from '../model/index.js';
import { isAdditiveMetric } from './validateChart.js';

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
 */
export function fitChartSlots(
  chart: ChartSpec,
  groups: readonly AnalysisGroup[],
  metrics: readonly AnalysisMetric[],
): ChartSpec {
  const shape: Shape = {
    groups: groups.map(group => group.alias),
    metrics: metrics.map(metric => metric.alias),
    additive: new Set(
      metrics.filter(isAdditiveMetric).map(metric => metric.alias),
    ),
    dateGroups: groups
      .filter(group => group.type === 'DATE_HISTOGRAM')
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
 * that adds up, because over a trend the headline is the buckets summed.
 */
function drawableType(chart: ChartSpec, shape: Shape): ChartType {
  if (!CHART_TYPES.includes(chart.type)) return chart.type;
  if (shape.groups.length === 0)
    return chart.type === 'funnel' ? 'funnel' : 'metric';
  if (chart.type !== 'metric') return chart.type;
  return shape.groups.length === 1 &&
    shape.dateGroups.length === 1 &&
    shape.additive.has(slot(chart.metric?.metric, shape.metrics))
    ? 'metric'
    : 'bar';
}

/** The aliases a chart may reference, and the two facts a slot rule asks. */
interface Shape {
  groups: string[];
  metrics: string[];
  /** Metrics the projection may add up across rows (a pie's merged tail). */
  additive: Set<string>;
  dateGroups: string[];
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
  const drawn =
    splitBy === undefined
      ? shape.metrics
      : [slot(spec?.series?.[0]?.metric, shape.metrics)];
  const series = drawn.map(metric =>
    oneSeries(previous.get(metric), metric, type),
  );
  // A reference line names the axis it hangs on, and an axis with no series
  // on it is not an axis; the line goes with the series that left.
  const axes = new Set(series.map(entry => entry.axis ?? 'left'));
  const lines = (spec?.referenceLines ?? []).filter(line =>
    axes.has(line.axis),
  );
  return {
    x,
    ...(splitBy === undefined ? {} : { splitBy }),
    series,
    ...(spec?.orientation === undefined
      ? {}
      : { orientation: spec.orientation }),
    ...(spec?.yAxis === undefined ? {} : { yAxis: spec.yAxis }),
    ...(lines.length > 0 ? { referenceLines: lines } : {}),
  };
}

/** One drawn series, keeping how it was drawn; a combo names its own mark. */
function oneSeries(
  previous: CartesianSeries | undefined,
  metric: string,
  type: ChartType,
): CartesianSeries {
  const series: CartesianSeries = { ...previous, metric };
  return type === 'combo' && series.type === undefined
    ? { ...series, type: 'bar' }
    : series;
}

function pie(spec: PieSpec | undefined, shape: Shape): PieSpec {
  const value = slot(spec?.value, shape.metrics);
  // The merged tail is the sum of the slices it swallowed, which only means
  // something for a metric that adds up.
  const merges =
    spec?.maxSlices !== undefined &&
    Number.isInteger(spec.maxSlices) &&
    spec.maxSlices >= 2 &&
    shape.additive.has(value);
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
    value: slot(spec?.value, shape.metrics),
    ...(spec?.scale === undefined ? {} : { scale: spec.scale }),
  };
}

function scatter(spec: ScatterSpec | undefined, shape: Shape): ScatterSpec {
  const x = slot(spec?.x, shape.metrics);
  const y = slot(
    spec?.y,
    shape.metrics.filter(alias => alias !== x),
  );
  const size =
    spec?.size !== undefined && shape.metrics.includes(spec.size)
      ? spec.size
      : undefined;
  return {
    category: slot(spec?.category, shape.groups),
    x,
    y,
    ...(size === undefined ? {} : { size }),
  };
}

/**
 * Stages come from metrics when there is nothing to group by, and otherwise
 * from the values of the one group — whose business order is data this layer
 * has never seen, so an order already written down is kept and an absent one
 * stays absent for the editor to ask about.
 */
function funnel(spec: FunnelSpec | undefined, shape: Shape): FunnelSpec {
  const rest = {
    ...(spec?.conversion === undefined ? {} : { conversion: spec.conversion }),
    ...(spec?.orientation === undefined
      ? {}
      : { orientation: spec.orientation }),
  };
  if (shape.groups.length === 0) {
    const labelled = new Map(
      spec?.stages.from === 'metrics'
        ? spec.stages.items.map(item => [item.metric, item])
        : [],
    );
    return {
      stages: {
        from: 'metrics',
        items: shape.metrics.map(metric => labelled.get(metric) ?? { metric }),
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
      value: slot(kept?.value, shape.metrics),
      order: kept?.order ?? [],
      ...(kept?.cumulative === undefined
        ? {}
        : { cumulative: kept.cumulative }),
    },
    ...rest,
  };
}

/**
 * A card is one number, so it groups by nothing — unless it draws a sparkline,
 * which needs exactly one date grouping and a headline that adds up, since
 * that headline is the buckets summed whenever the totals query did not run.
 */
function metricCard(
  spec: MetricCardSpec | undefined,
  shape: Shape,
): MetricCardSpec {
  const metric = slot(spec?.metric, shape.metrics);
  const compare =
    spec?.compare !== undefined && shape.metrics.includes(spec.compare.metric)
      ? spec.compare
      : undefined;
  const trending =
    shape.groups.length === 1 &&
    shape.dateGroups.length === 1 &&
    shape.additive.has(metric) &&
    (compare === undefined || shape.additive.has(compare.metric));
  return {
    metric,
    ...(compare === undefined ? {} : { compare }),
    ...(spec?.target === undefined ? {} : { target: spec.target }),
    ...(spec?.format === undefined ? {} : { format: spec.format }),
    ...(trending ? { trend: { x: shape.dateGroups[0] } } : {}),
  };
}
