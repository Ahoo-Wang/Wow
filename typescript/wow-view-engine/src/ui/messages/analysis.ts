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
 * The analysis editor and its charts, with the two kernels behind them.
 *
 * The words are the analyst's, one meaning each (D20): what a result is cut
 * by is a **dimension**, what it measures is a **metric**, how a metric is
 * computed is its **summary**, a count of records is the **record count**, the
 * ceiling on a grouping is **top N groups**, and the ungrouped row under the
 * table is the **totals row**. Nothing here says "grouping" — a condition
 * group is a different thing with the same English word — and nothing shows
 * an alias, which names the query rather than the column.
 */
export const analysisMessages = {
  'label.analysis.row-count': 'Record count',

  'label.analysis.editor': 'Analysis',
  // The tray's slots, in the analyst's order (D20): the range, the array
  // fields to expand, the dimensions and the metrics — each named, each
  // with a plain-words hint of the question it answers.
  'label.analysis.slot.range': 'Range',
  'label.analysis.slot.dimensions': 'Dimensions',
  'label.analysis.slot.metrics': 'Metrics',
  'label.analysis.hint.dimensions': 'compare by what',
  'label.analysis.hint.metrics': 'which numbers',
  'label.analysis.conditions-mode': 'Conditions: {mode}',
  'label.analysis.granularity': 'Granularity',
  // A date dimension's granularity, as Wow names the units.
  'label.date-unit.YEAR': 'By year',
  'label.date-unit.QUARTER': 'By quarter',
  'label.date-unit.MONTH': 'By month',
  'label.date-unit.WEEK': 'By week',
  'label.date-unit.DAY': 'By day',
  'label.date-unit.HOUR': 'By hour',
  'label.date-unit.MINUTE': 'By minute',
  'label.date-unit.SECOND': 'By second',
  'label.analysis.interval': 'Band width',
  'label.analysis.percentile': 'Percentile',
  'label.analysis.sort': 'Sort',
  'label.analysis.sort-none': 'Not sorted',
  'label.analysis.open-editor': 'Open analysis',
  'label.analysis.reading': 'By {dimensions} · {metrics}',
  'label.analysis.reading-flat': '{metrics}',
  'label.analysis.layout': 'Show result as',
  'label.analysis.add-group': 'Add dimension',
  'label.analysis.add-metric': 'Add metric',
  'label.analysis.totals': 'Totals row',
  'label.analysis.row-limit': 'Top N groups',
  // The visualization panel (D20 屏 I): the way in from the result's
  // toolbar, its title, the way back, and why a tile is greyed.
  'label.analysis.visualize': 'Visualize',
  'label.chart.picker': 'Visualization',
  'label.chart.picker-back': 'Back to the views',
  'label.chart.recommended': 'Recommended',
  'chart.fit.needs-dimension': 'Needs a dimension',
  'chart.fit.needs-one-dimension': 'Needs exactly one dimension',
  'chart.fit.needs-two-dimensions': 'Needs two dimensions',
  'chart.fit.needs-two-metrics': 'Needs two metrics',
  'chart.fit.needs-no-dimension': 'Not with dimensions',
  // `{name}` is the field's display name, never its alias: a control that
  // says "Remove dimension amount_1" names the query, not the column.
  'label.analysis.grouping-of': 'Dimension settings for {name}',
  'label.analysis.remove-group': 'Remove dimension {name}',
  'label.analysis.function-of': 'Summary for {name}',
  'label.analysis.remove-metric': 'Remove metric {name}',
  // Not "nothing to aggregate", which reads as "this analysis computes
  // nothing": the analysis is fine, the range simply matched no group.
  'label.analysis.empty': 'No groups match',

  // The two closed enums the editor offers as choices. They used to reach
  // the screen as the identifier itself — `bar`, `date histogram` — which is
  // readable enough in English and untranslatable in anything else, so
  // `messages={zhCN}` left the whole editor in English.
  // `test/messages.test.tsx` walks both enums and fails on a gap; the
  // summary functions are one set with the record view's, under
  // `label.summary.fn.*`.
  'label.chart.type.bar': 'bar',
  'label.chart.type.line': 'line',
  'label.chart.type.area': 'area',
  'label.chart.type.combo': 'combo',
  'label.chart.type.pie': 'pie',
  'label.chart.type.heatmap': 'heatmap',
  'label.chart.type.scatter': 'scatter',
  'label.chart.type.funnel': 'funnel',
  'label.chart.type.metric': 'metric',

  // What a dimension cuts by, said as the cut rather than as Wow's enum.
  'label.group.type.TERMS': 'By value',
  'label.group.type.HISTOGRAM': 'By number range',
  'label.group.type.DATE_HISTOGRAM': 'By time unit',

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
  'analysis.alias.duplicate': 'The display name {alias} is used twice.',
  'analysis.alias.invalid': '{alias} is not a usable display name.',
  'analysis.alias.not-a-segment': '{alias} cannot contain a dot.',
  'analysis.alias.reserved': '{alias} uses a reserved prefix.',
  'analysis.any.undeclared': '{field} cannot be shown as a sample value.',
  'analysis.capability.missing':
    '{definition} does not offer an analysis view any more.',
  'analysis.column.duplicate': 'The column {alias} is listed twice.',
  'analysis.column.unknown-alias': 'The column {alias} is not in this result.',
  'analysis.config.malformed': 'This analysis has no usable shape.',
  'analysis.constant.not-finite': 'A constant must be a finite number.',
  'analysis.count.undeclared': 'This dataset does not offer a record count.',
  'analysis.derived.unknown-metric':
    'The derived metric refers to {metric}, which is not declared before it.',
  'analysis.distinctCount.undeclared':
    '{field} does not offer distinct counts.',
  'analysis.element.out-of-chain':
    'The expansion of {path} belongs to another level of the chain.',
  'analysis.element.undeclared': 'The expansion of {path} is not available.',
  'analysis.elementFilter.empty':
    'This filter has no conditions, so every item is expanded.',
  'analysis.elementFilter.incomplete':
    'Give {field} a value, or every item is expanded.',
  'analysis.elements.too-many': 'Too many expansions for this dataset.',
  'analysis.expression.divide-by-zero': 'This expression divides by zero.',
  'analysis.expression.malformed': 'This metric has no usable expression.',
  'analysis.expression.too-deep':
    'The expression nests deeper than {max} levels.',
  'analysis.expression.too-many-nodes': 'The expressions exceed {max} entries.',
  'analysis.expressions.undeclared':
    'This dataset does not offer computed expressions.',
  'analysis.field.outside-scope':
    'The field {field} sits outside the expanded scope.',
  'analysis.field.unknown': 'The field {field} is not available here.',
  'analysis.function.unsupported': '{field} does not offer {fn}.',
  'analysis.group.blank-missing-key':
    'The placeholder for missing values is empty.',
  'analysis.group.blank-time-zone': 'The time zone is empty.',
  'analysis.group.dense-not-alone':
    'A gap-filling time dimension must be the only dimension.',
  'analysis.group.interval-not-positive':
    'A number range must be wider than zero.',
  'analysis.group.missing-key-unsupported':
    '{field} cannot hold a bucket for missing values; only single-valued text fields can.',
  'analysis.group.unit-unsupported': 'The {unit} unit is not available here.',
  'analysis.group.unsupported': '{field} offers no {type} dimension.',
  'analysis.groups.too-many': 'Too many dimensions for this dataset.',
  'analysis.having.malformed': 'This result filter has no usable shape.',
  'analysis.having.requires-group':
    'Filtering the result needs at least one dimension.',
  'analysis.having.too-deep':
    'The result filter nests deeper than {max} levels.',
  'analysis.having.too-many-nodes': 'The result filter exceeds {max} entries.',
  'analysis.having.undeclared':
    'This dataset does not offer filtering the result.',
  'analysis.having.unknown-metric':
    'The filter refers to {metric}, which is not a usable metric.',
  'analysis.limit.not-positive': 'Top N groups must be a positive number.',
  'analysis.limit.too-large': 'Top N groups cannot exceed {max}.',
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
  // Exactly `limit` groups is what a grouping of that size and a larger one
  // cut down to it both look like, and the source says nothing more — so the
  // line says what is on screen rather than guessing at what is not.
  'analysis.result.at-limit':
    'Showing the first {limit} groups, more not listed.',
  'analysis.sort.duplicate': 'The sort already orders by {alias}.',
  'analysis.sort.requires-group': 'Sorting needs at least one dimension.',
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
    'A funnel staged by metrics can carry no dimension.',
  'chart.funnel.too-few-stages': 'A funnel needs at least two stages.',
  'chart.group.unconsumed': 'The chart does not use every dimension: {groups}.',
  'chart.group.unknown': '{alias} is not a dimension of this analysis.',
  'chart.heatmap.same-axes': 'A heatmap needs two different axes.',
  'chart.metric.needs-no-group': 'A metric card can carry no dimension.',
  'chart.metric.trend-alias-mismatch':
    'The trend must use the {alias} dimension.',
  'chart.metric.trend-needs-one-date-group':
    'A trend needs exactly one time dimension.',
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
