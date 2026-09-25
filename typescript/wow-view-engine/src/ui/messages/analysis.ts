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
  'label.analysis.caption-one': 'Showing 1 group · took {seconds} s',
  // With no dimension the result is one row — the whole range — so the
  // footer counts no groups: it says how long the answer took.
  'label.analysis.caption-whole': 'Took {seconds} s',
  'label.analysis.answered': 'Result updated',

  'label.analysis.editor': 'Analysis',
  // The tray's slots, in the analyst's order (D20): the range, the array
  // fields to expand, the dimensions and the metrics — each named, each
  // with a plain-words hint of the question it answers.
  'label.analysis.slot.range': 'Range',
  'label.analysis.slot.dimensions': 'Dimensions',
  'label.analysis.slot.metrics': 'Metrics',
  'label.analysis.hint.dimensions': 'compare by what',
  'label.analysis.hint.metrics': 'which numbers',
  // The step after the question (2026-09-23 audit): which groups the result
  // keeps, in which order, and how many — Wow's having, sort and limit, in
  // the order it applies them.
  'label.analysis.slot.result': 'Result',
  'label.analysis.hint.result': 'which groups, in what order',
  // The expansion slot (D20 屏 G): the chain of arrays counted inside.
  'label.analysis.slot.elements': 'Expand',
  'label.analysis.hint.elements': 'count what',
  'label.analysis.expand-into': 'Expand into {name}',
  'label.analysis.collapse': 'Stop expanding {name}',
  'label.analysis.unit': 'Counting: {name}',
  // What one record is when the definition does not say (`recordNoun`).
  'label.analysis.records': 'records',
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
  // A time dimension's column says what one of its rows spans: 「创建时间
  // （按日）」, "Created (by day)". One key a unit rather than the unit's
  // own word in a pattern, because the English select says "By day" and a
  // header in the middle of a title says "by day".
  'label.analysis.dated.YEAR': '{field} (by year)',
  'label.analysis.dated.QUARTER': '{field} (by quarter)',
  'label.analysis.dated.MONTH': '{field} (by month)',
  'label.analysis.dated.WEEK': '{field} (by week)',
  'label.analysis.dated.DAY': '{field} (by day)',
  'label.analysis.dated.HOUR': '{field} (by hour)',
  'label.analysis.dated.MINUTE': '{field} (by minute)',
  'label.analysis.dated.SECOND': '{field} (by second)',
  // A number band: its lower bound to its upper, an en dash between.
  'label.analysis.band': '{from}–{to}',
  'label.analysis.interval': 'Band width',
  'label.analysis.percentile': 'Percentile',
  'label.analysis.open-editor': 'Open analysis',
  'label.analysis.open-chart-options': 'Open chart options',
  'label.analysis.reading': 'By {dimensions} · {metrics}',
  'label.analysis.reading-flat': '{metrics}',
  // The reading goes on to say which groups were kept (「只保留」, Wow's
  // having), in the tray's own words: a saved view opens with the tray
  // folded, and groups missing from the table with nothing saying why read
  // as groups without data. One row is `reading-kept-row`, several are
  // joined by `having-and`. A having the tray's rows cannot say — a range,
  // a set, an OR — is said to be there without claiming what it keeps.
  'label.analysis.reading-kept': '{reading} · Keep only {conditions}',
  'label.analysis.reading-kept-row': '{metric} {operator} {value}',
  'label.analysis.reading-kept-custom':
    '{reading} · Keep only groups matching a custom rule',
  'label.analysis.layout': 'Show result as',
  'label.analysis.add-group': 'Add dimension',
  'label.analysis.add-metric': 'Add metric',
  // The two metrics written rather than picked (D20 屏 B), and their cards.
  'label.analysis.add-formula': 'By formula',
  'label.analysis.add-derived': 'From other metrics',
  // How a derived metric's number reads (D38).
  'label.analysis.derived.style-of': 'How {name} reads',
  'label.analysis.derived.style.number': 'Number',
  'label.analysis.derived.style.percent': 'Percent',
  'label.analysis.derived.style.currency': 'Money',
  'label.analysis.derived.decimals': 'Decimals of {name}',
  'label.analysis.derived.currency': 'Currency of {name}',
  'label.analysis.derived.currency-inherited': 'As its operands',
  'label.analysis.operand-left': 'Left side of {name}',
  'label.analysis.operand-right': 'Right side of {name}',
  'label.analysis.operand-number': 'A number',
  'label.analysis.operand-value': 'Number in {name}',
  'label.analysis.operator': 'Operation of {name}',
  // A stored expression deeper than one operation: the card shows what it
  // says and leaves it alone, rather than drawing nothing at all.
  'label.analysis.expression-unreadable':
    'This metric is written with an expression the card cannot show.',
  // 「只保留」: the groups kept, as rows of one comparison each. The title
  // is the field's visible label in the result slot; the button adds a row
  // under it, so it says what it adds rather than repeating the title.
  'label.analysis.having': 'Add condition',
  // The way to a first 「只保留」 row, on the result's line: with no row
  // there is no legend to say what 「Add condition」 would add.
  'label.analysis.having-first': 'Keep only…',
  'label.analysis.having-title': 'Keep only',
  // Every row is four controls with the same four names, so the row itself
  // is a named group and its number is where a reader is.
  'label.analysis.having-row': 'Keep-only condition {index}',
  // Between two rows: every one of them must hold.
  'label.analysis.having-and': 'and',
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
  'label.analysis.totals-whole':
    'With no dimension the one row is every record in the range, so there is no totals row.',
  'label.analysis.row-limit': 'Top N groups',
  // Beside the field, which already says what it is: only the range it
  // takes. The same bounds as `analysis.limit.out-of-range`, from the same
  // `limitBounds`.
  'label.analysis.row-limit-invalid': 'A whole number from 1 to {max}',
  'label.analysis.row-limit-unsorted':
    'Unsorted, the source decides which groups',
  // The three readings D20 asks the screen to say out loud, because each is
  // a number that means something other than what it looks like.
  //
  // The totals row comes from its own ungrouped query, so it covers the whole
  // range — the groups past the top N and the groups 「只保留」 dropped
  // included. That is why the rows above it can add up to less than it does
  // without either number being wrong.
  'label.analysis.totals-scope': 'Every record in the range',
  // Where the totals and the rows above them part ways (2026-09-23 audit):
  // the totals keep their meaning, and say what they hold that no row shows.
  'label.analysis.totals-hidden.cut': 'Includes groups past the first {limit}',
  'label.analysis.totals-hidden.maybe-cut':
    'May include groups past the first {limit}',
  'label.analysis.totals-hidden.kept': 'Includes groups Keep only removed',
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
  // Auto-run (D20): the switch on the tray's footer, and what it does and
  // does not cover — the range's conditions still wait for Apply.
  'label.analysis.auto-run': 'Run automatically',
  'label.analysis.auto-run-hint':
    'Changes to dimensions, metrics and the result run on their own; the range waits for Apply.',
  // Auto-run on, and the range's conditions edited and not applied (a
  // condition added and not filled in, most often): nothing runs on its own
  // until Apply, however the question changes.
  'label.analysis.auto-run-held':
    'The range has conditions not applied (or not filled in); nothing runs until Apply.',
  'label.chart.picker': 'Visualization',
  'label.chart.picker-back': 'Back to the views',
  'label.chart.recommended': 'Recommended',
  // The picker's two groups (D33 Q54): the types that can draw the result,
  // the table among them, and the rest, greyed, each saying what it lacks.
  'label.chart.group.suits': 'Fits this result',
  'label.chart.group.others': 'Other charts',
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
  // A pie's slices are shares of a whole (D33 Q56): an average has none.
  'chart.fit.needs-share': 'Shares hold only for a count or a sum',
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
  // A waterfall steps along one dimension; a treemap tiles one, nested in
  // the blocks of a second when there is one.
  'label.chart.slot.steps': 'One step per',
  'label.chart.slot.tiles': 'One tile per',
  'label.chart.slot.parent': 'Grouped in',
  'label.chart.waterfall.total': 'Show the total',
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
  // What a trend card's big number is: the last period that has ended, or
  // every record in the range. Each hint says what the comparison with
  // another metric and the target are measured over in that reading.
  'label.chart.headline': 'Big number',
  'label.chart.headline.last': 'Last period',
  'label.chart.headline.whole': 'All',
  'label.chart.headline.last.hint':
    'The last period that has ended, against the one before; the comparison and the target are for that period.',
  'label.chart.headline.whole.hint':
    'Every record in the range; the comparison and the target are for all of it.',
  'label.chart.lower-is-better': 'A fall is good',
  'label.chart.lower-is-better.hint':
    'Colours a fall green and a rise red — for failures, latency and the like.',
  // Display: what every drawn chart shares, then each family's own.
  'label.chart.legend': 'Legend',
  'label.chart.legend.auto': 'Auto',
  'label.chart.legend.top': 'Top',
  'label.chart.legend.bottom': 'Bottom',
  'label.chart.legend.right': 'Right',
  'label.chart.legend.none': 'None',
  'label.chart.labels': 'Value labels',
  'label.chart.stacked': 'Stacked',
  'label.chart.stacked-alone': 'Stacking needs two or more bar or area series',
  'label.chart.percent-stack': 'Stacked to 100%',
  'label.chart.percent-stack.hint':
    'Each stack reads as shares of its total; the tooltip keeps the values',
  'label.chart.horizontal': 'Horizontal',
  'label.chart.smooth': 'Smooth lines',
  'label.chart.missing': 'Missing values',
  'label.chart.missing.zero': 'Zero',
  'label.chart.missing.gap': 'Leave a gap',
  'label.chart.missing.zero.hint':
    'Zero where the group is known to have no records and the metric adds up (a count, a sum); a gap elsewhere',
  'label.chart.missing.gap.hint':
    'A point with no rows is not drawn, and a line breaks there',
  'label.chart.reference-lines': 'Reference lines',
  'label.chart.reference-row': 'Reference line {index}',
  'label.chart.add-reference-line': 'Add reference line',
  'label.chart.remove-reference-line': 'Remove reference line',
  'label.chart.reference-axis': 'Axis of the reference line',
  'label.chart.reference-label': 'Caption',
  'label.chart.reference-value': 'Value of the reference line',
  'label.chart.reference-kind': 'Stands at',
  'label.chart.reference-kind.value': 'A number',
  'label.chart.reference-kind.average': 'The average',
  'label.chart.reference-kind.median': 'The median',
  'label.chart.reference-metric': 'Of the metric',
  'label.chart.reference-bands': 'Target bands',
  'label.chart.reference-band-row': 'Target band {index}',
  'label.chart.add-reference-band': 'Add target band',
  'label.chart.remove-reference-band': 'Remove target band',
  'label.chart.band-from': 'From',
  'label.chart.band-to': 'To',
  'label.chart.band-order':
    'The band runs from the smaller number to the larger one.',
  'label.chart.extremes': 'Mark the highest and lowest points',
  'label.chart.extremes.stacked':
    'A stacked segment stands at its stack’s height, so it is not marked.',
  'label.chart.derived': 'Computed lines',
  'label.chart.derived.hint':
    'Computed from the rows on screen along the whole time axis, drawn dashed; no query is sent.',
  'label.chart.derived.add.trend': 'Trend line',
  'label.chart.derived.add.moving-average': 'Moving average',
  'label.chart.derived.add.cumulative': 'Running total',
  'label.chart.derived.add.cumulative-share': 'Running share',
  'label.chart.derived.window': 'Periods',
  'label.chart.derived.window.hint': 'Left empty: {count}.',
  'label.chart.derived.metric': 'Computed from',
  'label.chart.derived.trend': 'Trend',
  'label.chart.derived.moving-average': '{window}-period moving average',
  'label.chart.derived.cumulative': 'Running total',
  'label.chart.derived.cumulative-share': 'Running share',
  'label.chart.derived.computed': '{name} (computed)',
  'label.chart.derived.of': '{name} · {series}',
  'label.chart.statistic.average': 'Average',
  'label.chart.statistic.median': 'Median',
  'label.chart.statistic.caption': '{statistic} {value}',
  'label.chart.extreme.high': 'High',
  'label.chart.extreme.low': 'Low',
  'label.chart.gap.note': '{what} is not drawn: {reason}',
  'label.chart.gap.split':
    'a split draws a line per value, and computed lines are drawn on an unsplit chart only.',
  'label.chart.gap.not-sorted':
    'along categories a running line reads only in its metric’s order: sort the result by it first.',
  'label.chart.gap.not-time':
    'the horizontal axis is not time, and these lines are computed along time only.',
  'label.chart.gap.narrowed':
    '“Keep only” dropped some groups, so the line would be incomplete.',
  'label.chart.gap.cut-short':
    'the result shows only the first {limit} groups, so the line would be incomplete.',
  'label.chart.gap.holes':
    'the time axis has gaps no one can put a number on, so the line would be wrong.',
  'label.chart.gap.not-additive':
    'a running total holds only for a metric that adds up (a record count or a sum).',
  'label.chart.gap.too-few': 'there are too few points to compute it.',
  'label.chart.gap.shares':
    'the chart draws shares of 100%, and a computed line of shares means nothing.',
  'label.chart.gap.statistic-split':
    'a split draws a line per value, and an average or median line is drawn on an unsplit chart only.',
  'label.chart.gap.none-measured': 'there is no measured value to take it of.',
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
  'label.chart.cumulative': 'Cumulative (reached at least this stage)',
  'label.chart.format': 'Number format',
  'label.chart.format.auto': 'Auto',
  'label.chart.format.compact': 'Compact',
  'label.chart.format.percent': 'Percent',
  'label.chart.axis-title': 'Axis title',
  'label.chart.axis-title.none': 'None — the legend names the series',
  'label.chart.axis-min': 'Minimum',
  'label.chart.axis-max': 'Maximum',
  // How a value axis is stepped (D33 batch E): evenly, or by powers of ten.
  'label.chart.axis-scale': 'Scale',
  'label.chart.axis-scale.linear': 'Linear',
  'label.chart.axis-scale.log': 'Logarithmic',
  'label.chart.axis-scale.not-positive':
    'A log scale has no place for 0 or a negative number, and this axis holds one.',
  // A scatter's two axes, each measuring a metric.
  'label.chart.axis.x': 'Horizontal axis',
  'label.chart.axis.y': 'Vertical axis',
  // Said over a chart whose saved log scale its values cannot take.
  'label.chart.log-refused':
    '{axis} is drawn on a linear scale: it holds 0 or a negative number, which a log scale has no place for.',
  // A split past the palette whose metric does not add up (D33 Q56).
  'label.chart.crowded':
    'More than {slots} series, so colours repeat. A heatmap or the table reads them better.',
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
  'label.analysis.condition-remove': 'Remove the condition, count every record',
  'label.analysis.only-where': 'Only where {conditions}',
  // A conditioned metric's default name (D20 显示名), wherever the metric is
  // named — its column, the chart, the sort, 「只保留」: the one value the
  // condition keeps, else that it has one. `{metric}` is the metric as it
  // reads without one ("Sum of Amount"). Metabase says "Sum of Total where
  // Status is Shipped"; the whole condition is the header's description.
  'label.analysis.metric-where': '{metric} · {value}',
  'label.analysis.metric-conditioned': '{metric} · conditioned',
  // Said on a card whose condition has no one value to name it by, so the
  // name stays 「… · conditioned」 until the analyst gives it one.
  'label.analysis.name-it': 'Give it a display name',
  'label.analysis.copy-with-condition': 'Copy “{name}” with a condition',
  'label.analysis.rename': 'Display name…',
  'label.analysis.display-name': 'Display name for {name}',
  'label.analysis.missing-bucket': 'Missing values as their own group',
  // The group of records with no value, as a table cell, an axis, a legend
  // and a file name it (the sentinel key itself never reaches the reader).
  'label.analysis.missing-group': '(empty)',
  'label.analysis.dense': 'Fill in empty periods',
  'label.analysis.dense-alone':
    'Fill in empty periods (needs to be the only dimension)',
  'label.analysis.remove-group': 'Remove dimension {name}',
  'label.analysis.function-of': 'Summary for {name}',
  'label.analysis.remove-metric': 'Remove metric {name}',
  // Not "nothing to aggregate", which reads as "this analysis computes
  // nothing": the analysis is fine, the range simply matched no group.
  'label.analysis.empty': 'No groups match',
  // Under the title, which of the three it is — the conditions left nothing
  // to group, the saved view has nothing right now, the range is everything
  // and still holds nothing — and the way out where there is one, as the
  // record view's empty result words them (`record/emptyWayOut.ts`). The
  // range is what the tray calls the conditions, so the way into it names
  // it. With no condition in force there is no way out to name.
  'label.analysis.empty-hint':
    'No record under the current conditions falls into a group.',
  'label.analysis.empty-view': 'This view has no records to group right now.',
  'label.analysis.empty-none': 'There are no records in the range to group',
  'label.analysis.empty-clear': 'Clear the conditions',
  'label.analysis.empty-restore': 'Back to the saved conditions',
  'label.analysis.empty-edit': 'Change the range',

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
  'label.chart.type.waterfall': 'waterfall',
  'label.chart.type.treemap': 'treemap',
  // A chart the shape leaves no room for, said with its reason where the
  // surface can word both (`chart.as-table`, `analysisIssueNamer`).
  'label.analysis.as-table':
    'The {type} chart cannot draw this result ({reason}); it shows as a table.',

  // What a dimension cuts by, said as the cut rather than as Wow's enum.
  'label.group.type.TERMS': 'By value',
  'label.group.type.HISTOGRAM': 'By number range',
  'label.group.type.DATE_HISTOGRAM': 'By time unit',

  // Series names the chart writes itself, for legends and tooltips.
  'label.chart.other': 'Other',
  // A series or a slice standing for a yes or a no, named with its field.
  'label.chart.series.of-field': '{field}: {value}',
  'label.chart.share-basis': 'Shares of the groups shown',
  // A waterfall's closing bar adds up the steps drawn: over rows cut short,
  // those are the groups shown and not every group.
  'label.chart.waterfall.total-basis': 'The total is of the groups shown',
  'label.chart.waterfall.increase': 'Increase',
  'label.chart.waterfall.decrease': 'Decrease',
  // Rows whose number is not above zero have no area a tile could take.
  'label.chart.treemap.omitted': '{count} groups not above zero are not drawn',
  // A 0 the chart filled in rather than measured (decisions.md D23, Q14):
  // a bucket or a group the rows lack, said in the tooltip and the reading
  // table, never written on the mark.
  'label.chart.filled.YEAR': '{value} (no records this year)',
  'label.chart.filled.QUARTER': '{value} (no records this quarter)',
  'label.chart.filled.MONTH': '{value} (no records this month)',
  'label.chart.filled.WEEK': '{value} (no records this week)',
  'label.chart.filled.DAY': '{value} (no records this day)',
  'label.chart.filled.HOUR': '{value} (no records this hour)',
  'label.chart.filled.MINUTE': '{value} (no records this minute)',
  'label.chart.filled.SECOND': '{value} (no records this second)',
  'label.chart.filled.group': '{value} (no records in this group)',
  'label.chart.legend.more': '{count} more',
  'label.chart.legend.less': 'Show less',
  'label.chart.legend.toggle': 'Series shown — press one to hide or show it',
  'label.chart.total': 'Total',
  'label.chart.trend': 'Trend',

  // What a chart says about itself. The drawing is one image with a name —
  // the marks are a picture, not an application — and the numbers it draws
  // are read from the table beside it.
  'label.chart.figure': '{type}: {measures} by {category}',
  'label.chart.figure.plain': '{type}: {measures}',
  'label.chart.sparkline': '{name}, over time',
  'label.chart.reading': '{name}, as a table',
  // The chart in one sentence, said after its name (analysis-echarts.md
  // 2.3): how many groups, the highest and the lowest; over a time axis
  // where it starts, where it ends and which way it went.
  'label.chart.sentence':
    '{count} groups; highest {high}, {highValue}; lowest {low}, {lowValue}.',
  'label.chart.sentence.time':
    '{count} periods from {first} to {last}, {trend}; highest {high}, {highValue}; lowest {low}, {lowValue}.',
  'label.chart.sentence.up': 'rising overall',
  'label.chart.sentence.down': 'falling overall',
  'label.chart.sentence.flat': 'about level overall',
  'label.chart.sentence.scatter':
    '{count} points; {x} from {xLow} to {xHigh}, {y} from {yLow} to {yHigh}.',
  'label.chart.column.category': 'Category',
  'label.chart.column.value': 'Value',
  'label.chart.column.x': 'X',
  'label.chart.column.y': 'Y',
  'label.chart.column.stage': 'Stage',
  // Which stage a conversion is relative to, said where the percentages are.
  'label.chart.column.conversion.previous': 'Conversion from previous stage',
  'label.chart.column.conversion.first': 'Conversion from first stage',
  // A cumulative funnel's numbers are not the table's, said over the drawing
  // and over its reading table's value column.
  'label.chart.column.cumulative': 'Cumulative: reached at least this stage',
  'label.chart.column.compare': 'Compared with',
  'label.chart.column.target': 'Target',
  'label.chart.column.change': 'Change',
  'label.chart.column.running': 'Running total',
  'label.chart.column.share': 'Share',

  // The metric card's target bar. The bar is a progressbar, so it says its
  // own name and reads its position as the two numbers behind it rather than
  // as the percentage the role would otherwise announce.
  'label.chart.target': 'Toward target',
  'label.chart.target.reached': '{value} of {target}',

  // Which span a trend card's big number covers, said over it, and how it
  // moved from the period before.
  'label.chart.period.week': 'Week of {start}',
  'label.chart.period.so-far': '{period} so far',
  'label.chart.period.whole': 'All in range',
  'label.chart.period.skipped': '{period} is not over yet and is not counted',
  'label.chart.change.against': 'vs previous period',
  // A metric card's change, against the period right before by its unit.
  'label.chart.change.against.YEAR': 'vs the year before',
  'label.chart.change.against.QUARTER': 'vs the quarter before',
  'label.chart.change.against.MONTH': 'vs the month before',
  'label.chart.change.against.WEEK': 'vs the week before',
  'label.chart.change.against.DAY': 'vs the day before',
  'label.chart.change.against.HOUR': 'vs the hour before',
  'label.chart.change.against.MINUTE': 'vs the minute before',
  'label.chart.change.against.SECOND': 'vs the second before',
  // A metric card's comparison, against the metric it is compared with.
  'label.chart.compare.against': 'vs {metric}',
  'label.chart.change.none': 'No previous period to compare with',
  'label.chart.change.unknown':
    'No number in the previous period to compare with',

  // Analysis kernel.
  'analysis.alias.duplicate': 'The display name {alias} is used twice.',
  'analysis.alias.invalid': '{alias} is not a usable display name.',
  'analysis.alias.not-a-segment': '{alias} cannot contain a dot.',
  'analysis.alias.reserved': '{alias} uses a reserved prefix.',
  'analysis.any.undeclared': '{field} cannot be shown as a sample value.',
  'analysis.capability.missing':
    '{definition} does not offer an analysis view any more.',
  // The kernel names a group or a metric by its alias, a field by its path
  // and a summary or a unit as Wow spells it; the surface says each as the
  // screen does (`analysisIssueNamer`) — a column as it is headed, a field
  // by its label, 'Average', 'By month'. The `analysis.alias.*` findings
  // keep the alias: the key itself is what is wrong there.
  'analysis.column.duplicate': 'This result lists {alias} twice.',
  'analysis.column.unknown-alias': 'There is no {alias} in this result.',
  'analysis.config.malformed': 'This analysis has no usable shape.',
  'analysis.constant.not-finite': 'A constant must be a finite number.',
  'analysis.count.undeclared': 'This dataset does not offer a record count.',
  'analysis.derived.unknown-metric':
    'The derived metric refers to {metric}, which is not declared before it.',
  'analysis.derived.moment-operand':
    '{metric} is a point in time and cannot be calculated with.',
  'analysis.derived.format-invalid':
    'A calculated metric reads as a number, a percent or money.',
  'analysis.derived.decimals': 'Decimals are a whole number from 0 to {max}.',
  'analysis.derived.currency-invalid': 'That is not a currency code.',
  'analysis.derived.currency-unknown':
    'Its operands are in no one currency: choose the currency it is in.',
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
  'analysis.function.unsupported': '{field} cannot be summarised as {fn}.',
  'analysis.group.blank-missing-key':
    'The placeholder for missing values is empty.',
  'analysis.group.blank-time-zone': 'The time zone is empty.',
  'analysis.group.dense-not-alone':
    'A gap-filling time dimension must be the only dimension.',
  'analysis.group.interval-not-positive':
    'A number range must be wider than zero.',
  'analysis.group.missing-key-unsupported':
    '{field} cannot hold a bucket for missing values; only single-valued text fields can.',
  'analysis.group.unit-unsupported':
    'This dimension cannot group this way: {unit}.',
  'analysis.label.blank': 'The display name is empty.',
  'analysis.group.unsupported': '{field} cannot be grouped this way: {type}.',
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
