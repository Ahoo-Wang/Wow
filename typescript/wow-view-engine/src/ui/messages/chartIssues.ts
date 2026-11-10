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
 * What the chart kernels report (`chart.*` issue codes): a slot a chart
 * family cannot fill, a reference it cannot place, an option it does not
 * take.
 */
export const chartIssueMessages = {
  'chart.fit.needs-dimension': 'Needs a dimension',
  'chart.fit.needs-one-dimension': 'Needs exactly one dimension',
  'chart.fit.needs-two-dimensions': 'Needs two dimensions',
  'chart.fit.too-many-dimensions': 'At most two dimensions',
  'chart.fit.needs-two-metrics': 'Needs two metrics',
  'chart.fit.needs-no-dimension': 'Not with dimensions',
  // Every metric left is the earliest or the latest of a date: a moment,
  // which a mark has no length, share or shade for (`momentMetrics`).
  'chart.fit.needs-quantity': 'Needs an amount; a time is not drawn',
  // A funnel's stages are steps: the values of a category, two at least.
  'chart.fit.needs-category': 'Stages need a category dimension',
  'chart.fit.needs-two-stages': 'Needs two groups or more as stages',
  'chart.fit.not-steps':
    'These groups are categories side by side, not the steps of one process',
  // A funnel counts what entered and what remained: a record count or a
  // sum, never an average, a distinct count or an extreme.
  'chart.fit.needs-additive': 'Needs a count or a sum',
  // A pie's slices are shares of a whole (D33 Q56): an average has none.
  'chart.fit.needs-share': 'Shares hold only for a count or a sum',
  // A box is five numbers of one field (`fiveNumberSets`), which the tray
  // adds in one go from a metric card's menu.
  'chart.fit.needs-five-numbers':
    'Needs a field’s minimum, three percentiles and maximum',
  'chart.fit.needs-date': 'Needs one date dimension',
  'chart.fit.needs-ohlc':
    'Needs a field’s opening value, maximum, minimum and closing value',
  // A radar's shapes and parallel axes' lines cross an axis per metric.
  'chart.fit.needs-three-metrics': 'Needs three metrics or more',
  // A sunburst's rings, a tree's columns and a sankey's stops: past four
  // levels they are slivers.
  'chart.fit.too-many-levels': 'At most four dimensions',
  // A calendar lays out days; a theme river runs along a date, a stream per
  // value of the other dimension.
  'chart.fit.needs-day': 'Needs one date dimension by day',
  'chart.fit.needs-date-and-split': 'Needs a date and one more dimension',
  // A map shades regions named by one dimension's values, on a map the
  // host registered (D41): the package ships none.
  'chart.fit.needs-region': 'Needs one dimension of regions by value',
  'chart.fit.needs-map': 'No map is available here',

  // Charts. A finding names a dimension or a metric by its alias (`alias`,
  // `metric`); the surface says it as the result's column is headed
  // (`analysisIssueNamer`), so none of these ever prints a program's name.
  'chart.as-table':
    'The chosen chart cannot draw this result; it shows as a table.',
  'chart.cartesian.percent-not-additive':
    'A 100% stack needs metrics that add up (a record count or a sum), not {metric}.',
  'chart.combo.series-type-missing':
    'Every series of a combo chart needs its own type.',
  'chart.colors.invalid': 'This is not a colour the chart can paint with.',
  'chart.colors.malformed':
    'The pinned chart colours must be listed one per series or category.',
  'chart.family.missing': 'The {type} chart has no {family} settings yet.',
  'chart.funnel.duplicate-stage': 'A funnel stage is listed twice.',
  'chart.funnel.metrics-need-no-group':
    'A funnel staged by metrics can carry no dimension.',
  'chart.funnel.too-few-stages': 'A funnel needs at least two stages.',
  'chart.funnel.not-additive':
    'A funnel needs a metric that adds up (a record count or a sum), not {metric}.',
  'chart.funnel.stages-need-category':
    'A funnel’s stages are the values of a category, not dates or number ranges.',
  'chart.group.unconsumed': 'The chart does not use the dimension {alias}.',
  // An alias the analysis does not have has no header to be said by, and
  // its program name tells a reader nothing: the slot is marked instead.
  'chart.group.unknown':
    'The chart uses a dimension this analysis does not have.',
  'chart.heatmap.same-axes': 'A heatmap needs two different axes.',
  'chart.treemap.not-additive':
    'A treemap’s tiles are parts of a whole, so it needs a metric that adds up (a record count or a sum), not {metric}.',
  'chart.treemap.same-levels': 'A treemap needs two different levels.',
  'chart.boxplot.not-five-numbers':
    'A boxplot needs one field’s minimum, three rising percentiles and maximum, under one condition.',
  'chart.candlestick.needs-date': 'A candlestick runs along a date dimension.',
  'chart.candlestick.not-ohlc':
    'A candlestick needs one field’s opening value, maximum, minimum and closing value, under one condition.',
  'chart.gauge.needs-no-group': 'A gauge can carry no dimension.',
  'chart.gauge.not-a-number': 'This needs a number.',
  'chart.gauge.empty-scale': 'The scale must end above where it starts.',
  'chart.radar.too-few-metrics': 'A radar needs three metrics or more.',
  'chart.radar.duplicate-metric': 'A radar lists one metric twice.',
  'chart.sunburst.too-few-levels': 'A sunburst needs two dimensions or more.',
  'chart.sunburst.too-many-levels': 'A sunburst draws at most four dimensions.',
  'chart.sunburst.same-levels': 'A sunburst lists one dimension twice.',
  'chart.sunburst.not-additive':
    'A sunburst’s parts add up to their parent, so it needs a metric that adds up (a record count or a sum), not {metric}.',
  'chart.tree.too-few-levels': 'A tree needs two dimensions or more.',
  'chart.tree.too-many-levels': 'A tree draws at most four dimensions.',
  'chart.tree.same-levels': 'A tree lists one dimension twice.',
  'chart.tree.not-additive':
    'A tree’s parts add up to their parent, so it needs a metric that adds up (a record count or a sum), not {metric}.',
  'chart.sankey.too-few-levels': 'A sankey needs two dimensions or more.',
  'chart.sankey.too-many-levels': 'A sankey draws at most four dimensions.',
  'chart.sankey.same-levels': 'A sankey lists one dimension twice.',
  'chart.sankey.not-additive':
    'A sankey’s bands add up to what flows through, so it needs a metric that adds up (a record count or a sum), not {metric}.',
  'chart.map.needs-region':
    'A map’s regions are the values of a dimension, not ranges or dates.',
  'chart.map.name-invalid': 'The map must be named.',
  'chart.calendar.needs-day':
    'A calendar lays out days: its dimension must be a date by day.',
  'chart.themeRiver.needs-date': 'A theme river runs along a date dimension.',
  'chart.themeRiver.same-axes': 'A theme river needs two different dimensions.',
  'chart.themeRiver.not-additive':
    'A theme river stacks its streams, so it needs a metric that adds up (a record count or a sum), not {metric}.',
  'chart.parallel.too-few-metrics':
    'Parallel coordinates need three metrics or more.',
  'chart.parallel.duplicate-metric':
    'Parallel coordinates list one metric twice.',
  'chart.waterfall.not-additive':
    'A waterfall adds up its steps, so it needs a metric that adds up (a record count or a sum), not {metric}.',
  'chart.metric.needs-no-group': 'A metric card can carry no dimension.',
  'chart.metric.trend-alias-mismatch':
    'The trend must use the {alias} dimension.',
  'chart.metric.trend-needs-one-date-group':
    'A trend needs exactly one time dimension.',
  'chart.metric.trend-not-additive':
    'A trend headline needs a metric that adds up, or one calculated from such metrics — not {metric}.',
  'chart.metric.unknown':
    'The chart uses a metric this analysis does not have.',
  'chart.metric.moment':
    '{alias} is a point in time; a chart does not draw or compare it.',
  'chart.pie.not-additive':
    'A pie’s slices are shares of a whole, so it needs a metric that adds up (a record count or a sum), not {metric}.',
  'chart.axis.scale-unknown': 'This axis scale is not available.',
  'chart.pie.maxSlices-too-small': 'Keep at least two slices.',
  'chart.referenceLine.empty-axis':
    'A reference line needs a series on its axis.',
  'chart.referenceLine.value-missing':
    'A reference line stands at a number or at a statistic.',
  'chart.referenceLine.statistic-unknown': 'This statistic is not available.',
  'chart.referenceLine.metric-not-drawn':
    'The reference line is taken of {metric}, which is not drawn on its axis.',
  'chart.referenceBand.order':
    'A target band runs from a smaller number to a larger one.',
  'chart.derived.kind-unknown': 'This computed line is not available.',
  'chart.derived.metric-not-drawn':
    'The computed line follows {metric}, which the chart does not draw.',
  'chart.derived.window': 'A moving average runs over 2 to {max} periods.',
  'chart.scatter.same-metrics': 'A scatter plot needs two different metrics.',
  'chart.splitBy.needs-one-series': 'A split chart shows exactly one metric.',
  'chart.splitBy.same-as-x': 'The split cannot repeat the horizontal axis.',
  'chart.type.unknown': 'This chart type is not available.',
} as const satisfies Record<string, string>;
