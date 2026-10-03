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
  type ChartSpec,
  type ChartType,
} from '../model/index.js';
import { fiveNumberSets } from './boxplot.js';
import { ohlcSets } from './candlestick.js';
import { isAdditiveMetric, readsOffSums } from './additive.js';
import { familyRules } from './familyRules.js';
import { headlines, slot, type SlotShape } from './familySlots.js';

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
  const shape: SlotShape = {
    measures,
    groups: groups.map(group => group.alias),
    metrics: metrics.map(metric => metric.alias),
    quantities,
    fiveNumbers: fiveNumberSets(metrics, new Set(quantities)),
    ohlc: ohlcSets(metrics, new Set(quantities)),
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
  // A stored config may name a type this package does not have; there is
  // no family to fill and `validateChart` reports the type itself.
  return familyRules(chart.type)?.fit(chart, shape) ?? chart;
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
function drawableType(chart: ChartSpec, shape: SlotShape): ChartType {
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

/** Nothing is a moment: the shape of a chart fitted without a definition. */
const NO_MOMENTS: ReadonlySet<string> = new Set();

/** Every metric one measure: a chart fitted without a definition. */
const NO_MEASURES: ReadonlyMap<string, string> = new Map();
