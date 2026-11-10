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
  AggregationDateParts,
  AggregationDateUnits,
  AggregationGroupTypes,
  ComparisonOperators,
  DateDiffUnits,
  SortDirections,
} from '../../model/index.js';
import { wireKey } from './wireKey.js';

/**
 * The analysis editor and the analysis kernel.
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
  // The tray's rows, in the order each depends on the one before (D71):
  // what one counted thing is, the numbers, what they are compared by, which
  // groups the result keeps, and the records it all runs over. Each head is
  // the analyst's term, with an ⓘ beside it that says the term in a
  // sentence — the button's name, then the sentence.
  'label.analysis.slot.range': 'Range',
  'label.analysis.slot.dimensions': 'Dimensions',
  'label.analysis.slot.metrics': 'Metrics',
  'label.analysis.slot.result': 'Result',
  'label.analysis.slot.elements': 'Expand',
  'label.analysis.tip-of': 'About {term}',
  'label.analysis.tip.elements':
    'Counts the entries of an array in each record: once {name} is expanded, each {name} counts as one, and the metrics and dimensions are chosen from its fields. You can go one level further in.',
  'label.analysis.tip.metrics':
    'The numbers to work out: a record count, a sum or an average of a field, each optionally over only the records that match. Drag to reorder; a derived metric stays after the ones it reads.',
  'label.analysis.tip.dimensions':
    'What to compare by: each dimension cuts the result into groups, and several cut it in turn.',
  'label.analysis.tip.result':
    'Which groups to keep and in what order: keep only the groups that match, order them by a dimension or a metric, and take the top N.',
  'label.analysis.tip.range':
    'Which records are counted. It narrows the outermost records, and expanding does not change it.',
  // The visible word of each row's add button; the button's name says what
  // it adds (`label.analysis.add-group`, `label.analysis.add-metric`).
  'label.analysis.add': 'Add',
  // What the last edit took out of the question beyond what it was asked
  // to — an expansion leaves the dimensions and metrics outside it behind —
  // said with an undo rather than asked first (D71).
  'label.analysis.dropped.expand': 'Expanding {name} took out {what}',
  'label.analysis.dropped.collapse': 'Collapsing {name} took out {what}',
  'label.analysis.dropped.edit': 'This change also took out {what}',
  'label.analysis.dropped.metric': '1 metric: {names}',
  'label.analysis.dropped.metrics': '{count} metrics: {names}',
  'label.analysis.dropped.dimension': '1 dimension: {names}',
  'label.analysis.dropped.dimensions': '{count} dimensions: {names}',
  'label.analysis.dropped.both': '{first}; {second}',
  'label.analysis.dropped.undo': 'Undo',
  'label.analysis.dropped.dismiss': 'Dismiss',
  'label.analysis.dropped.undone': 'Undone; the question is back as it was',
  // A metric dragged past what the order allows stops short (D71): a
  // metric calculated from others stays after them, and before any that
  // is calculated from it.
  'label.analysis.move-stop.after':
    '{name} is calculated from {other}, so it stays after it.',
  'label.analysis.move-stop.before':
    '{other} is calculated from {name}, so {name} stays before it.',
  // Why a metric's ✕ is off.
  'label.analysis.remove-last': 'An analysis needs at least one metric.',
  'label.analysis.remove-cascade':
    'Every other metric is calculated from this one; removing it would leave none.',
  // The expansion row (D20 屏 G, D71): the chain of arrays counted inside.
  'label.analysis.expand-into': 'Expand into {name}',
  'label.analysis.collapse': 'Stop expanding {name}',
  'label.analysis.unit': 'Counting: {name}',
  // What one record is when the definition does not say (`recordNoun`).
  'label.analysis.records': 'records',
  'label.analysis.element-condition-of': 'Conditions on the entries of {name}',
  'label.analysis.element-condition-title': 'Only entries where',
  // The gate's button on a level's card, while the level has no gate: its
  // name (`element-condition-of`) begins with these words.
  'label.analysis.element-condition': 'Conditions on the entries',
  'label.analysis.conditions-mode': 'Conditions: {mode}',
  'label.analysis.granularity': 'Granularity',
  // A date dimension's granularity, as Wow names the units.
  [wireKey('label.date-unit', AggregationDateUnits.YEAR)]: 'By year',
  [wireKey('label.date-unit', AggregationDateUnits.QUARTER)]: 'By quarter',
  [wireKey('label.date-unit', AggregationDateUnits.MONTH)]: 'By month',
  [wireKey('label.date-unit', AggregationDateUnits.WEEK)]: 'By week',
  [wireKey('label.date-unit', AggregationDateUnits.DAY)]: 'By day',
  [wireKey('label.date-unit', AggregationDateUnits.HOUR)]: 'By hour',
  [wireKey('label.date-unit', AggregationDateUnits.MINUTE)]: 'By minute',
  [wireKey('label.date-unit', AggregationDateUnits.SECOND)]: 'By second',
  // A calendar part (DATE_PART): records of different weeks or days fall
  // into one group, so the words say which cycle they are folded along.
  'label.analysis.date-part': 'Cycle',
  [wireKey('label.date-part', AggregationDateParts.DAY_OF_WEEK)]: 'By weekday',
  [wireKey('label.date-part', AggregationDateParts.HOUR_OF_DAY)]:
    'By hour of day',
  [wireKey('label.date-part', AggregationDateParts.DAY_OF_MONTH)]:
    'By day of month',
  [wireKey('label.date-part', AggregationDateParts.MONTH_OF_YEAR)]:
    'By month of year',
  [wireKey('label.analysis.part', AggregationDateParts.DAY_OF_WEEK)]:
    '{field} (weekday)',
  [wireKey('label.analysis.part', AggregationDateParts.HOUR_OF_DAY)]:
    '{field} (hour of day)',
  [wireKey('label.analysis.part', AggregationDateParts.DAY_OF_MONTH)]:
    '{field} (day of month)',
  [wireKey('label.analysis.part', AggregationDateParts.MONTH_OF_YEAR)]:
    '{field} (month of year)',
  // A time dimension's column says what one of its rows spans: 「创建时间
  // （按日）」, "Created (by day)". One key a unit rather than the unit's
  // own word in a pattern, because the English select says "By day" and a
  // header in the middle of a title says "by day".
  [wireKey('label.analysis.dated', AggregationDateUnits.YEAR)]:
    '{field} (by year)',
  [wireKey('label.analysis.dated', AggregationDateUnits.QUARTER)]:
    '{field} (by quarter)',
  [wireKey('label.analysis.dated', AggregationDateUnits.MONTH)]:
    '{field} (by month)',
  [wireKey('label.analysis.dated', AggregationDateUnits.WEEK)]:
    '{field} (by week)',
  [wireKey('label.analysis.dated', AggregationDateUnits.DAY)]:
    '{field} (by day)',
  [wireKey('label.analysis.dated', AggregationDateUnits.HOUR)]:
    '{field} (by hour)',
  [wireKey('label.analysis.dated', AggregationDateUnits.MINUTE)]:
    '{field} (by minute)',
  [wireKey('label.analysis.dated', AggregationDateUnits.SECOND)]:
    '{field} (by second)',
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
  'label.analysis.group-by-currency': 'Group by {field}',
  'label.analysis.add-metric': 'Add metric',
  // The two metrics written rather than picked (D20 屏 B), and their cards.
  'label.analysis.add-formula': 'Formula',
  'label.analysis.add-derived': 'Derived metric',
  // A time between two moments (N3, DATE_DIFF): built on its card from the
  // two times and a unit, and cut into bands as a dimension from its menu.
  'label.analysis.add-duration': 'Time between two moments',
  'label.analysis.duration-from': 'From which time, for {name}',
  'label.analysis.duration-to': 'To which time, for {name}',
  'label.analysis.duration-unit': 'Unit of {name}',
  [wireKey('label.date-diff-unit', DateDiffUnits.SECOND)]: 'Seconds',
  [wireKey('label.date-diff-unit', DateDiffUnits.MINUTE)]: 'Minutes',
  [wireKey('label.date-diff-unit', DateDiffUnits.HOUR)]: 'Hours',
  [wireKey('label.date-diff-unit', DateDiffUnits.DAY)]: 'Days',
  'label.analysis.group-by-duration': 'Group by this duration',
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
  'label.analysis.top-first': 'Top few in each…',
  'label.analysis.top-title': 'Top few in each',
  'label.analysis.top-each': 'In each',
  'label.analysis.top-within': 'Dimension to rank within',
  'label.analysis.top-keep': 'keep the top',
  'label.analysis.top-count': 'How many to keep',
  'label.analysis.top-by': 'by',
  'label.analysis.top-metric': 'Metric to rank by',
  'label.analysis.top-direction': 'Ranking order',
  [wireKey('label.analysis.top-direction', SortDirections.DESC)]:
    'highest first',
  [wireKey('label.analysis.top-direction', SortDirections.ASC)]: 'lowest first',
  'label.analysis.top-invalid': 'A whole number from 1 to {max}.',
  'label.analysis.remove-top': 'Keep every group in each',
  'label.analysis.top-note':
    'Tied groups share a place, so a tie may keep more.',
  'label.analysis.reading-top':
    '{reading} · Top {count} in each {within} by {metric} ({order})',
  [wireKey('label.having.op', ComparisonOperators.GT)]: 'more than',
  [wireKey('label.having.op', ComparisonOperators.GTE)]: 'at least',
  [wireKey('label.having.op', ComparisonOperators.LT)]: 'less than',
  [wireKey('label.having.op', ComparisonOperators.LTE)]: 'at most',
  [wireKey('label.having.op', ComparisonOperators.EQ)]: 'equal to',
  [wireKey('label.having.op', ComparisonOperators.NE)]: 'not equal to',
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
  // An opening or closing value (FIRST / LAST) is the value on the group's
  // earliest or latest record: which time orders them is its second control.
  'label.analysis.first-last-note':
    'Opening and closing values are the values on the group’s earliest and latest record.',
  'label.analysis.order-by-of': 'Order of {name}',
  'label.analysis.order-by-default': 'By event time',
  'label.analysis.order-by': 'By {field}',
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
  // The five numbers a boxplot draws, added from one metric of a field.
  'label.analysis.five-numbers': 'Add the five numbers for a boxplot',
  'label.analysis.ohlc': 'Add the four numbers for a candlestick',
  'label.analysis.rename': 'Display name…',
  'label.analysis.display-name': 'Display name for {name}',
  'label.analysis.missing-bucket': 'Missing values as their own group',
  // The group of records with no value, as a table cell, an axis, a legend
  // and a file name it (the sentinel key itself never reaches the reader).
  'label.analysis.missing-group': '(empty)',
  'label.analysis.dense': 'Fill in empty periods',
  'label.analysis.dense-alone':
    'Fill in empty periods (needs to be the only dimension)',
  'label.analysis.dense-part': 'List every value, even with no records',
  'label.analysis.dense-part-alone':
    'List every value, even with no records (needs to be the only dimension)',
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
  // A chart the shape leaves no room for, said with its reason where the
  // surface can word both (`chart.as-table`, `analysisIssueNamer`).
  'label.analysis.as-table':
    'The {type} chart cannot draw this result ({reason}); it shows as a table.',

  // What a dimension cuts by, said as the cut rather than as Wow's enum.
  [wireKey('label.group.type', AggregationGroupTypes.TERMS)]: 'By value',
  [wireKey('label.group.type', AggregationGroupTypes.HISTOGRAM)]:
    'By number range',
  [wireKey('label.group.type', AggregationGroupTypes.DATE_HISTOGRAM)]:
    'By time unit',
  [wireKey('label.group.type', AggregationGroupTypes.DATE_PART)]:
    'By cycle (weekday, hour…)',

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
  'analysis.elementFilter.search':
    'A search cannot decide which items are expanded: take {field} out of this condition.',
  'analysis.elements.too-many': 'Too many expansions for this dataset.',
  'analysis.expression.divide-by-zero': 'This expression divides by zero.',
  'analysis.expression.date-operand':
    '{field} is a date and cannot be calculated with.',
  'analysis.expression.malformed': 'This metric has no usable expression.',
  'analysis.expression.operand-unsupported':
    'The source cannot calculate with {field}.',
  'analysis.metric.filter-field-unsupported':
    "The source cannot use {field} in a metric's own condition.",
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
  'analysis.group.dense-unsupported':
    'The source cannot fill the gaps of a time dimension.',
  'analysis.group.dense-not-alone':
    'A gap-filling time dimension must be the only dimension.',
  'analysis.group.interval-not-positive':
    'A number range must be wider than zero.',
  'analysis.group.missing-key-unsupported':
    '{field} cannot hold a bucket for missing values; only single-valued text fields can.',
  'analysis.group.unit-unsupported':
    'This dimension cannot group this way: {unit}.',
  'analysis.first-last.undeclared':
    '{field} offers no opening or closing value.',
  'analysis.first-last.order-by-required':
    'Inside expanded entries, an opening or closing value needs a time to order the entries by.',
  'analysis.date-diff.unit-unsupported':
    'A time between two moments cannot be measured in {unit} here.',
  'analysis.date-diff.not-time':
    '{field} holds no time a duration could run from or to.',
  'analysis.group.part-unsupported':
    'This dimension cannot group this way: {part}.',
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
  'analysis.having.metric-unsupported':
    'The source cannot keep groups by {metric}.',
  'analysis.having.unknown-metric':
    'The filter refers to {metric}, which is not a usable metric.',
  'analysis.top.unsupported':
    'This dataset cannot keep the first groups within each value of a dimension.',
  'analysis.top.requires-groups':
    'Keeping the first groups within a dimension needs at least two dimensions.',
  'analysis.top.unknown-within':
    'The first groups are kept within {within}, which is not one of the dimensions.',
  'analysis.top.unknown-metric':
    'The first groups are ranked by {metric}, which is not a metric that can rank them.',
  'analysis.top.count-out-of-range':
    'The number of groups kept within each value must be a whole number from 1 to {max}.',
  'analysis.top.malformed': 'This top N has no usable shape.',
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
  'analysis.metric.out-of-reach':
    'The span of {metric} lies outside the dates picked, so it does not apply and is left empty.',
  'analysis.result.more-groups':
    'Showing the first {limit} groups; there are more.',
  'analysis.result.at-limit':
    'Showing the first {limit} groups; there may be more.',
  // Money in each record's own currency: a group whose records hold
  // several has no total, and grouping by the currency field separates them.
  'analysis.result.mixed-currency':
    '{metric}: some groups hold records in several currencies, so their amounts are not added up; group by {field} to see each currency.',
  'analysis.metric.currency-unchecked':
    '{metric} is money in each record’s own currency, which the source cannot check, so its totals may add several currencies; group by {field} to see each currency.',
  // The split past the palette folds its rest into “Other” from a second
  // query; refused, every series is drawn and colours repeat (D42).
  'analysis.split.whole-failed':
    'Could not fold the smaller series into “Other”, so colours repeat: {reason}',
  'analysis.sort.duplicate': 'The sort already orders by {alias}.',
  'analysis.sort.root-group-first':
    'The sort orders by {alias} ahead of a dimension over the record itself; those dimensions must come first.',
  'analysis.sort.metric-unsupported':
    'The source orders groups by their dimensions only, not by {alias}.',
  'analysis.sort.requires-group': 'Sorting needs at least one dimension.',
  'analysis.sort.too-many':
    'A result sorts by at most {max} dimensions and metrics.',
  'analysis.sort.unknown-alias':
    'The sort orders by {alias}, which this result does not have.',
} as const satisfies Record<string, string>;
