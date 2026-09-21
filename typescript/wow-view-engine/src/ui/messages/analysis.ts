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

/** The analysis editor and its charts, with the two kernels behind them. */
export const analysisMessages = {
  'label.analysis.row-count': 'Row count',

  // The analysis editor. Its "Add group" is its own: a grouping is not a
  // condition group, and a translation that shares one word for both is a
  // translation this package forced.
  'label.analysis.editor': 'Analysis',
  'label.analysis.layout': 'Analysis layout',
  'label.analysis.add-group': 'Add group',
  'label.analysis.add-metric': 'Add metric',
  'label.analysis.show-totals': 'Show totals',
  'label.analysis.totals': 'Totals',
  'label.analysis.row-limit': 'Row limit',
  'label.analysis.run': 'Run',
  'label.analysis.chart-type': 'Chart type',
  'label.analysis.grouping-of': '{alias} grouping',
  'label.analysis.remove-group': 'Remove group {alias}',
  'label.analysis.function-of': '{alias} function',
  'label.analysis.remove-metric': 'Remove metric {alias}',
  'label.analysis.empty': 'Nothing to aggregate',

  // The three closed enums the editor offers as choices. They used to reach
  // the screen as the identifier itself — `bar`, `date histogram`, `sum` —
  // which is readable enough in English and untranslatable in anything else,
  // so `messages={zhCN}` left the whole editor in English. The English
  // wording is what each one already showed, so naming them changed no
  // screen; `test/messages.test.tsx` walks the three enums and fails on a
  // gap.
  'label.chart.type.bar': 'bar',
  'label.chart.type.line': 'line',
  'label.chart.type.area': 'area',
  'label.chart.type.combo': 'combo',
  'label.chart.type.pie': 'pie',
  'label.chart.type.heatmap': 'heatmap',
  'label.chart.type.scatter': 'scatter',
  'label.chart.type.funnel': 'funnel',
  'label.chart.type.metric': 'metric',

  'label.group.type.TERMS': 'terms',
  'label.group.type.HISTOGRAM': 'histogram',
  'label.group.type.DATE_HISTOGRAM': 'date histogram',

  'label.metric.function.SUM': 'sum',
  'label.metric.function.AVG': 'avg',
  'label.metric.function.MIN': 'min',
  'label.metric.function.MAX': 'max',
  'label.metric.function.STDDEV': 'stddev',
  'label.metric.function.VARIANCE': 'variance',

  // Series names the chart writes itself, for legends and tooltips.
  'label.chart.other': 'Other',
  'label.chart.points': 'Points',
  'label.chart.trend': 'Trend',
  'label.chart.cell': '{y} · {x}: {value}',

  // What a chart says about itself. The drawing is one image with a name —
  // the marks are a picture, not an application — and the numbers it draws
  // are read from the table beside it.
  'label.chart.figure': '{type}: {measures} by {category}',
  'label.chart.figure.plain': '{type}: {measures}',
  'label.chart.sparkline': '{name}, over time',
  'label.chart.reading': '{name}, as a table',
  'label.chart.column.category': 'Category',
  'label.chart.column.value': 'Value',
  'label.chart.column.x': 'X',
  'label.chart.column.y': 'Y',
  'label.chart.column.stage': 'Stage',
  'label.chart.column.conversion': 'Conversion',
  'label.chart.column.compare': 'Compared with',
  'label.chart.column.target': 'Target',

  // The metric card's target bar. The bar is a progressbar, so it says its
  // own name and reads its position as the two numbers behind it rather than
  // as the percentage the role would otherwise announce.
  'label.chart.target': 'Toward target',
  'label.chart.target.reached': '{value} of {target}',

  // Analysis kernel.
  'analysis.alias.duplicate': 'The name {alias} is used twice.',
  'analysis.alias.invalid': '{alias} is not a usable name.',
  'analysis.alias.not-a-segment': '{alias} cannot contain a dot.',
  'analysis.alias.reserved': '{alias} uses a reserved prefix.',
  'analysis.any.undeclared': '{field} cannot be shown as a sample value.',
  'analysis.capability.missing':
    '{definition} does not offer an analysis view any more.',
  'analysis.column.duplicate': 'The column {alias} is listed twice.',
  'analysis.column.unknown-alias': 'The column {alias} is not in this result.',
  'analysis.config.malformed': 'This analysis has no usable shape.',
  'analysis.constant.not-finite': 'A constant must be a finite number.',
  'analysis.count.undeclared': 'This dataset does not offer a row count.',
  'analysis.derived.unknown-metric':
    'The derived metric refers to {metric}, which is not declared before it.',
  'analysis.distinctCount.undeclared':
    '{field} does not offer distinct counts.',
  'analysis.element.undeclared': 'The nested path {path} is not available.',
  'analysis.elementFilter.empty':
    'This filter has no conditions, so every entry is expanded.',
  'analysis.elementFilter.incomplete':
    'Give {field} a value, or every entry is expanded.',
  'analysis.elements.too-many': 'Too many nested paths for this dataset.',
  'analysis.expression.divide-by-zero': 'This expression divides by zero.',
  'analysis.expression.malformed': 'This metric has no usable expression.',
  'analysis.expression.too-deep':
    'The expression nests deeper than {max} levels.',
  'analysis.expression.too-many-nodes': 'The expressions exceed {max} entries.',
  'analysis.expressions.undeclared':
    'This dataset does not offer computed expressions.',
  'analysis.field.unknown': 'The field {field} is not available here.',
  'analysis.function.unsupported': '{field} does not offer {fn}.',
  'analysis.group.blank-missing-key':
    'The placeholder for missing values is empty.',
  'analysis.group.blank-time-zone': 'The time zone is empty.',
  'analysis.group.dense-not-alone':
    'A gap-filling date grouping must be the only grouping.',
  'analysis.group.interval-not-positive':
    'A histogram interval must be greater than zero.',
  'analysis.group.unit-unsupported': 'The {unit} unit is not available here.',
  'analysis.group.unsupported': '{field} cannot be grouped by {type}.',
  'analysis.groups.too-many': 'Too many groupings for this dataset.',
  'analysis.having.malformed': 'This result filter has no usable shape.',
  'analysis.having.requires-group':
    'Filtering the result needs at least one grouping.',
  'analysis.having.too-deep':
    'The result filter nests deeper than {max} levels.',
  'analysis.having.too-many-nodes': 'The result filter exceeds {max} entries.',
  'analysis.having.undeclared':
    'This dataset does not offer filtering the result.',
  'analysis.having.unknown-metric':
    'The filter refers to {metric}, which is not a usable metric.',
  'analysis.limit.not-positive': 'The row limit must be a positive number.',
  'analysis.limit.too-large': 'The row limit cannot exceed {max}.',
  'analysis.metric.type-unknown': 'The metric type {type} is not available.',
  'analysis.metricFilter.empty':
    'This filter has no conditions, so the metric covers every record.',
  'analysis.metricFilter.incomplete':
    'Give {field} a value, or the metric covers every record.',
  'analysis.metricFilter.not-scalar':
    '{field} has no single value for a metric filter to test.',
  'analysis.metrics.empty': 'An analysis needs at least one metric.',
  'analysis.metrics.too-many': 'Too many metrics for this dataset.',
  'analysis.percentile.out-of-range':
    'A percentile must be between 0 and 100, exclusive.',
  'analysis.percentile.undeclared': '{field} does not offer percentiles.',
  // "May": exactly `limit` rows is what a grouping of that size and a larger
  // one cut down to it both look like, and the source says nothing more.
  'analysis.result.at-limit':
    'The result filled its limit of {limit} rows, so the grouping may have been cut short: every share and slice below is of what is shown, not of the whole.',
  'analysis.sort.duplicate': 'The sort already orders by {alias}.',
  'analysis.sort.requires-group': 'Sorting needs at least one grouping.',
  'analysis.sort.too-many': 'A result sorts on at most {max} columns.',
  'analysis.sort.unknown-alias':
    'Sorting refers to {alias}, which is not shown.',

  // Charts.
  'chart.combo.series-type-missing':
    'Every series of a combo chart needs its own type.',
  'chart.colors.invalid': 'This is not a colour the chart can paint with.',
  'chart.colors.malformed':
    'The pinned chart colours must be listed one per series or category.',
  'chart.family.missing': 'The {type} chart has no {family} settings yet.',
  'chart.funnel.duplicate-stage': 'A funnel stage is listed twice.',
  'chart.funnel.metrics-need-no-group':
    'A metric funnel cannot be grouped as well.',
  'chart.funnel.too-few-stages': 'A funnel needs at least two stages.',
  'chart.group.unconsumed': 'The chart does not use every grouping: {groups}.',
  'chart.group.unknown': '{alias} is not a grouping of this analysis.',
  'chart.heatmap.same-axes': 'A heatmap needs two different axes.',
  'chart.metric.needs-no-group': 'A metric card cannot be grouped.',
  'chart.metric.trend-alias-mismatch':
    'The trend must use the {alias} grouping.',
  'chart.metric.trend-needs-one-date-group':
    'A trend needs exactly one date grouping.',
  'chart.metric.trend-not-additive':
    'A trend headline needs an additive metric, not {metric}.',
  'chart.metric.unknown': '{alias} is not a metric of this analysis.',
  'chart.pie.maxSlices-not-additive':
    'Merging the smallest slices needs an additive metric.',
  'chart.pie.maxSlices-too-small': 'Keep at least two slices.',
  'chart.referenceLine.empty-axis':
    'A reference line needs a series on its axis.',
  'chart.scatter.same-metrics': 'A scatter plot needs two different metrics.',
  'chart.splitBy.needs-one-series': 'A split chart shows exactly one metric.',
  'chart.splitBy.same-as-x': 'The split cannot repeat the horizontal axis.',
  'chart.type.unknown': 'This chart type is not available.',
} as const satisfies Record<string, string>;
