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
  // The result's footer: what is on screen and how long it took to get.
  'label.analysis.caption': 'Showing {count} groups · took {seconds} s',

  'label.analysis.editor': 'Analysis',
  // The tray's slots, in the analyst's order (D20): the range, the array
  // fields to expand, the dimensions and the metrics — each named, each
  // with a plain-words hint of the question it answers.
  'label.analysis.slot.range': 'Range',
  'label.analysis.slot.dimensions': 'Dimensions',
  'label.analysis.slot.metrics': 'Metrics',
  'label.analysis.hint.dimensions': 'compare by what',
  'label.analysis.hint.metrics': 'which numbers',
  // The expansion slot (D20 屏 G): the chain of arrays counted inside.
  'label.analysis.slot.elements': 'Expand',
  'label.analysis.hint.elements': 'count what',
  'label.analysis.expand-into': 'Expand into {name}',
  'label.analysis.collapse': 'Stop expanding {name}',
  'label.analysis.unit': 'Counting: {name}',
  'label.analysis.element-condition-of': 'Conditions on the entries of {name}',
  'label.analysis.element-condition-title': 'Only entries where',
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
  'label.analysis.open-editor': 'Open analysis',
  'label.analysis.open-chart-options': 'Open chart options',
  'label.analysis.reading': 'By {dimensions} · {metrics}',
  'label.analysis.reading-flat': '{metrics}',
  'label.analysis.layout': 'Show result as',
  'label.analysis.add-group': 'Add dimension',
  'label.analysis.add-metric': 'Add metric',
  // The two metrics written rather than picked (D20 屏 B), and their cards.
  'label.analysis.add-formula': 'By formula',
  'label.analysis.add-derived': 'From other metrics',
  'label.analysis.operand-left': 'Left side of {name}',
  'label.analysis.operand-right': 'Right side of {name}',
  'label.analysis.operand-number': 'A number',
  'label.analysis.operand-value': 'Number in {name}',
  'label.analysis.operator': 'Operation of {name}',
  // A stored expression deeper than one operation: the card shows what it
  // says and leaves it alone, rather than drawing nothing at all.
  'label.analysis.expression-unreadable':
    'This metric is written with an expression the card cannot show.',
  // 「只保留」: the groups kept, as rows of one comparison each.
  'label.analysis.having': 'Keep only…',
  'label.analysis.having-title': 'Keep only the groups where',
  // Every row is four controls with the same four names, so the row itself
  // is a named group and its number is where a reader is.
  'label.analysis.having-row': 'Keep-only condition {index}',
  'label.analysis.having-keep': 'Keep where',
  'label.analysis.having-metric': 'Metric to keep by',
  'label.analysis.having-operator': 'Comparison',
  'label.analysis.having-value': 'Value to compare with',
  'label.analysis.remove-having': 'Remove this condition',
  'label.analysis.having-note': 'Groups without a value are not kept.',
  'label.analysis.having-unreadable':
    'This view keeps groups by a rule this editor cannot show.',
  'label.analysis.clear-having': 'Clear',
  'label.having.op.GT': 'more than',
  'label.having.op.GTE': 'at least',
  'label.having.op.LT': 'less than',
  'label.having.op.LTE': 'at most',
  'label.having.op.EQ': 'equal to',
  'label.having.op.NE': 'not equal to',
  'label.analysis.totals': 'Totals row',
  'label.analysis.row-limit': 'Top N groups',
  // Beside the field, which already says what it is: only the range it
  // takes. The same bounds as `analysis.limit.out-of-range`, from the same
  // `limitBounds`.
  'label.analysis.row-limit-invalid': 'A whole number from 1 to {max}',
  // The three readings D20 asks the screen to say out loud, because each is
  // a number that means something other than what it looks like.
  //
  // The totals row comes from its own ungrouped query, so it covers the whole
  // range — the groups past the top N and the groups 「只保留」 dropped
  // included. That is why the rows above it can add up to less than it does
  // without either number being wrong.
  'label.analysis.totals-scope': 'Totals = every record in the range',
  // Wow computes a percentile approximately, so the column says so: 「≈」 in
  // the header (`columnTitle`) and this word where there is room for one.
  'label.analysis.approximate': 'Approximate',
  // «Any value» returns some value of the group and promises nothing about
  // which; two runs of the same analysis may not agree. The menu item says it
  // in a parenthesis, the card at rest in a sentence.
  'label.analysis.any-note':
    'Any value: it may differ from one run to the next.',
  // The visualization panel (D20 屏 I): the way in from the result's
  // toolbar, its title, the way back, and why a tile is greyed.
  'label.analysis.visualize': 'Visualize',
  // 「改了就跑」 (D20): the switch on the tray's footer.
  'label.analysis.auto-run': 'Run as I change the question',
  'label.chart.picker': 'Visualization',
  'label.chart.picker-back': 'Back to the views',
  'label.chart.recommended': 'Recommended',
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
  // A funnel counts what entered and what remained: a record count or a
  // sum, never an average, a distinct count or an extreme.
  'chart.fit.needs-additive': 'Needs a count or a sum',
  // The panel's second level (D20 屏 J): the chosen type's options on
  // three pages, and the way in and out.
  'label.chart.options': '{name} options',
  'label.chart.options-back': 'Back to the chart types',
  'label.chart.tab.data': 'Data',
  'label.chart.tab.display': 'Display',
  'label.chart.tab.axes': 'Axes',
  // The slots, named as the analyst reads the picture: what runs along
  // the axis, what the bars are split by, what each series measures.
  'label.chart.slot.x': 'Horizontal axis',
  'label.chart.slot.split': 'Split by',
  'label.chart.slot.series': 'Series',
  'label.chart.add-series': 'Add series',
  'label.chart.remove-series': 'Remove series {name}',
  // Which series comes first is read off a stack from the bottom up and off
  // a legend from the left, so the order is something to take hold of.
  'label.chart.series-instructions':
    'Press the arrow keys to move a series one place. Press space to pick it up, the arrow keys to move it, space again to drop it and escape to cancel.',
  'label.chart.drag-series': 'Reorder {name}',
  'label.chart.series-moved': '{name} moved to position {index} of {total}',
  'label.chart.series-picked': '{name} picked up',
  'label.chart.series-cancelled': 'Move cancelled; {name} stayed where it was',
  'label.chart.mark-of': 'Drawn as, for {name}',
  'label.chart.axis-of': 'Axis for {name}',
  'label.chart.mark.bar': 'Bars',
  'label.chart.mark.line': 'Line',
  'label.chart.mark.area': 'Area',
  'label.chart.axis.left': 'Left axis',
  'label.chart.axis.right': 'Right axis',
  'label.chart.slot.category': 'Slices by',
  'label.chart.slot.value': 'Value',
  'label.chart.slot.rows': 'Vertical dimension',
  'label.chart.slot.columns': 'Horizontal dimension',
  'label.chart.slot.point': 'One point per',
  'label.chart.slot.x-metric': 'Across',
  'label.chart.slot.y-metric': 'Up',
  'label.chart.slot.size': 'Point size',
  'label.chart.slot.none': 'None',
  'label.chart.slot.stages': 'Stages',
  'label.chart.stage-name': 'Name of the stage {name}',
  'label.chart.slot.stage-of': 'Stages are the values of',
  // The two numbers a metric card carries beside its headline. The
  // reading table heads the same two with `label.chart.column.*`; a
  // control is asked for, a heading is read, so each has its own words.
  'label.chart.compare-with': 'Compared with',
  'label.chart.target-value': 'Target value',
  'label.chart.slot.stage-order': 'Stage order',
  'label.chart.move-up': 'Move {name} up',
  'label.chart.move-down': 'Move {name} down',
  'label.chart.remove-stage': 'Remove stage {name}',
  'label.chart.compare-mode': 'Comparison',
  'label.chart.compare.delta': 'Difference',
  'label.chart.compare.percent': 'Percent',
  // Display: what every drawn chart shares, then each family's own.
  'label.chart.legend': 'Legend',
  'label.chart.legend.auto': 'Auto',
  'label.chart.legend.top': 'Top',
  'label.chart.legend.bottom': 'Bottom',
  'label.chart.legend.right': 'Right',
  'label.chart.legend.none': 'None',
  'label.chart.labels': 'Value labels',
  'label.chart.stacked': 'Stacked',
  'label.chart.stacked-alone': 'Stacking needs two or more series',
  'label.chart.horizontal': 'Horizontal',
  'label.chart.smooth': 'Smooth lines',
  'label.chart.reference-lines': 'Reference lines',
  'label.chart.reference-row': 'Reference line {index}',
  'label.chart.add-reference-line': 'Add reference line',
  'label.chart.remove-reference-line': 'Remove reference line',
  'label.chart.reference-axis': 'Axis of the reference line',
  'label.chart.reference-label': 'Caption',
  'label.chart.reference-value': 'Value of the reference line',
  'label.chart.donut': 'Donut',
  'label.chart.max-slices': 'Slices at most',
  // The ceiling is the palette's size: a slice past it would repeat a colour.
  'label.chart.max-slices.hint':
    'The rest merge into “Other”. At most {count}, one colour each — and {count} when left empty.',
  'label.chart.scale': 'Colour scale',
  'label.chart.scale.linear': 'Linear',
  'label.chart.scale.log': 'Logarithmic',
  'label.chart.conversion': 'Conversion relative to',
  'label.chart.conversion.previous': 'Previous stage',
  'label.chart.conversion.first': 'First stage',
  'label.chart.conversion.none': 'Not shown',
  'label.chart.cumulative': 'Cumulative',
  'label.chart.format': 'Number format',
  'label.chart.format.auto': 'Auto',
  'label.chart.format.compact': 'Compact',
  'label.chart.format.percent': 'Percent',
  'label.chart.axis-title': 'Axis title',
  'label.chart.axis-min': 'Minimum',
  'label.chart.axis-max': 'Maximum',
  // `{name}` is the field's display name, never its alias: a control that
  // says "Remove dimension amount_1" names the query, not the column.
  'label.analysis.grouping-of': 'Dimension settings for {name}',
  // The card's own menu (D20 屏 B): a display name for any card, and for a
  // dimension the two choices Wow keeps behind its bucketing.
  'label.analysis.card-menu': 'More settings for {name}',
  // A metric's own conditions (D20 屏 H): the funnel on the card, the block
  // under it, and the sentence the card wears at rest.
  'label.analysis.condition-of': 'Conditions for {name}',
  'label.analysis.condition-title': 'Only records where',
  'label.analysis.condition-close': 'Close the conditions',
  'label.analysis.condition-remove': 'Count every record again',
  'label.analysis.only-where': 'Only where {conditions}',
  'label.analysis.copy-with-condition': 'Copy “{name}” with a condition',
  'label.analysis.rename': 'Display name…',
  'label.analysis.display-name': 'Display name for {name}',
  'label.analysis.missing-bucket': 'Missing values as their own group',
  'label.analysis.dense': 'Fill in empty periods',
  'label.analysis.dense-alone':
    'Fill in empty periods (needs to be the only dimension)',
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
  'label.chart.share-basis': 'Shares of the groups shown',
  'label.chart.legend.more': '{count} more',
  'label.chart.legend.less': 'Show less',
  'label.chart.total': 'Total',
  'label.chart.trend': 'Trend',

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
  // Which stage a conversion is relative to, said where the percentages are.
  'label.chart.column.conversion.previous': 'Conversion from previous stage',
  'label.chart.column.conversion.first': 'Conversion from first stage',
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
  // A validation message names a group or a metric by its alias: the
  // kernel raises it, and the display name is composed in the catalogue
  // (`columnTitle`), which no headless rule can reach. So the alias stands,
  // and the sentence says nothing about columns or rows.
  'analysis.column.duplicate': 'This result lists {alias} twice.',
  'analysis.column.unknown-alias': 'There is no {alias} in this result.',
  'analysis.config.malformed': 'This analysis has no usable shape.',
  'analysis.constant.not-finite': 'A constant must be a finite number.',
  'analysis.count.undeclared': 'This dataset does not offer a record count.',
  'analysis.derived.unknown-metric':
    'The derived metric refers to {metric}, which is not declared before it.',
  'analysis.derived.moment-operand':
    '{metric} is a point in time and cannot be calculated with.',
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
  'analysis.expression.date-operand':
    '{field} is a date and cannot be calculated with.',
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
  'analysis.label.blank': 'The display name is empty.',
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
  'analysis.limit.out-of-range':
    'Top N groups must be a whole number from 1 to {max}.',
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
  // The query asks for one row more than the limit, so "there are more" is a
  // fact and says so. The maybe below it is the one case that cannot be
  // probed: the limit already sits on the ceiling, so no row is left to ask
  // for and "exactly full" is all there is (see `analysisProbeLimit`).
  'analysis.result.more-groups':
    'Showing the first {limit} groups; there are more.',
  'analysis.result.at-limit':
    'Showing the first {limit} groups; there may be more.',
  'analysis.sort.duplicate': 'The sort already orders by {alias}.',
  'analysis.sort.requires-group': 'Sorting needs at least one dimension.',
  'analysis.sort.too-many':
    'A result sorts by at most {max} dimensions and metrics.',
  'analysis.sort.unknown-alias':
    'The sort orders by {alias}, which this result does not have.',

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
  'chart.funnel.not-additive':
    'A funnel needs a metric that adds up (a record count or a sum), not {metric}.',
  'chart.funnel.stages-need-category':
    'A funnel’s stages are the values of a category, not dates or number ranges.',
  'chart.group.unconsumed': 'The chart does not use every dimension: {groups}.',
  'chart.group.unknown':
    'The chart uses {alias}, which is not a dimension of this analysis.',
  'chart.heatmap.same-axes': 'A heatmap needs two different axes.',
  'chart.metric.needs-no-group': 'A metric card can carry no dimension.',
  'chart.metric.trend-alias-mismatch':
    'The trend must use the {alias} dimension.',
  'chart.metric.trend-needs-one-date-group':
    'A trend needs exactly one time dimension.',
  'chart.metric.trend-not-additive':
    'A trend headline needs an additive metric, not {metric}.',
  'chart.metric.unknown': '{alias} is not a metric of this analysis.',
  'chart.metric.moment':
    '{alias} is a point in time; a chart does not draw or compare it.',
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
