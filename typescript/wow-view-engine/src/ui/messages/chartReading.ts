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

import { AggregationDateUnits } from '../../model/index.js';
import { wireKey } from './wireKey.js';

/**
 * What a drawn chart and a metric card say about the data: the chart in one
 * sentence and as a table, a period still under way, a value filled in or a
 * gap left, a card's period and its change, and the words a series or a
 * slice is named by when the data names none.
 */
export const chartReadingMessages = {
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
  'label.chart.gap.unsupported':
    'the data source does not compute earlier periods (or a year back over weeks).',
  'label.chart.gap.shares':
    'the chart draws shares of 100%, and a computed line of shares means nothing.',
  'label.chart.gap.statistic-split':
    'a split draws a line per value, and an average or median line is drawn on an unsplit chart only.',
  'label.chart.gap.none-measured': 'there is no measured value to take it of.',
  // Said over a chart whose saved log scale its values cannot take.
  'label.chart.log-refused':
    '{axis} is drawn on a linear scale: it holds 0 or a negative number, which a log scale has no place for.',
  // A split past the palette whose metric does not add up (D33 Q56).
  'label.chart.crowded':
    'More than {slots} series, so colours repeat. A heatmap or the table reads them better.',

  // Series names the chart writes itself, for legends and tooltips.
  'label.chart.other': 'Other',
  // A series or a slice standing for a yes or a no, named with its field.
  'label.chart.series.of-field': '{field}: {value}',
  'label.chart.share-basis': 'Shares of the groups shown',
  // A 0 the chart filled in rather than measured (decisions.md D23, Q14):
  // a bucket or a group the rows lack, said in the tooltip and the reading
  // table, never written on the mark.
  [wireKey('label.chart.filled', AggregationDateUnits.YEAR)]:
    '{value} (no records this year)',
  [wireKey('label.chart.filled', AggregationDateUnits.QUARTER)]:
    '{value} (no records this quarter)',
  [wireKey('label.chart.filled', AggregationDateUnits.MONTH)]:
    '{value} (no records this month)',
  [wireKey('label.chart.filled', AggregationDateUnits.WEEK)]:
    '{value} (no records this week)',
  [wireKey('label.chart.filled', AggregationDateUnits.DAY)]:
    '{value} (no records this day)',
  [wireKey('label.chart.filled', AggregationDateUnits.HOUR)]:
    '{value} (no records this hour)',
  [wireKey('label.chart.filled', AggregationDateUnits.MINUTE)]:
    '{value} (no records this minute)',
  [wireKey('label.chart.filled', AggregationDateUnits.SECOND)]:
    '{value} (no records this second)',
  'label.chart.filled.group': '{value} (no records in this group)',
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
  'label.chart.sentence-one':
    '1 group; highest {high}, {highValue}; lowest {low}, {lowValue}.',
  'label.chart.sentence.time':
    '{count} periods from {first} to {last}, {trend}; highest {high}, {highValue}; lowest {low}, {lowValue}.',
  'label.chart.sentence.up': 'rising overall',
  'label.chart.sentence.down': 'falling overall',
  'label.chart.sentence.flat': 'about level overall',
  'label.chart.ongoing': '{period} (in progress)',
  'label.chart.ongoing.band': 'In progress',
  'label.chart.sentence.unfinished':
    ' {period} is not over yet and is not counted.',
  'label.chart.sentence.trend-of': '{measure} {trend}',
  'label.chart.sentence.other-axis':
    ' On the other axis, {measures}: highest {high}, {highValue}; lowest {low}, {lowValue}.',
  'label.chart.sentence.scatter':
    '{count} points; {x} from {xLow} to {xHigh}, {y} from {yLow} to {yHigh}.',
  'label.chart.sentence.scatter-one':
    '1 point; {x} from {xLow} to {xHigh}, {y} from {yLow} to {yHigh}.',
  'label.chart.sentence.boxplot':
    '{count} groups; highest median {high}, {highValue}; lowest median {low}, {lowValue}.',
  'label.chart.sentence.boxplot-one':
    '1 group; highest median {high}, {highValue}; lowest median {low}, {lowValue}.',
  'label.chart.sentence.gauge': '{value} on a scale from {min} to {max}.',
  'label.chart.sentence.funnel':
    '{count} stages from {first}, {firstValue}, to {last}, {lastValue}; {overall} overall.',
  // Run on after the sentence above, so it carries the space between.
  'label.chart.sentence.funnel-drop':
    ' The largest drop is from {from} to {to}: {drop} ({rate}).',
  'label.chart.sentence.candlestick':
    '{count} periods, from {first} to {last}; opened at {open}, closed at {close}; highest {high}, lowest {low}.',
  'label.chart.sentence.gauge-target':
    '{value}, {share} of the target {target}.',
  'label.chart.sentence.profile-metric':
    '{metric}: highest {high}, {highValue}; lowest {low}, {lowValue}',
  'label.chart.sentence.profile-join': '. ',
  'label.chart.sentence.profile-readings': ' {readings}.',
  'label.chart.sentence.profiles': '{count} groups across {metrics} metrics.',
  'label.chart.sentence.sankey':
    '{count} flows; the largest {high}, {highValue}.',
  'label.chart.sentence.sankey-one': '1 flow; the largest {high}, {highValue}.',
  'label.chart.sentence.themeRiver':
    '{streams} streams over {count} periods from {first} to {last}, {trend}.',
  'label.chart.column.category': 'Category',
  'label.chart.column.value': 'Value',
  'label.chart.column.direction': 'Direction',
  'label.chart.column.x': 'X',
  'label.chart.column.y': 'Y',
  'label.chart.column.stage': 'Stage',
  // Which stage a conversion is relative to, said where the percentages are.
  'label.chart.column.conversion.previous': 'Conversion from previous stage',
  'label.chart.column.conversion.first': 'Conversion from first stage',
  'label.chart.column.drop': 'Drop from previous stage',
  // A cumulative funnel's numbers are not the table's, said over the drawing
  // and over its reading table's value column.
  'label.chart.column.cumulative': 'Cumulative: reached at least this stage',
  'label.chart.column.compare': 'Compared with',
  'label.chart.column.target': 'Target',
  'label.chart.column.change': 'Change',
  'label.chart.column.running': 'Running total',
  'label.chart.column.share': 'Share',
  'label.chart.column.from': 'From',
  'label.chart.column.to': 'To',
  'label.chart.column.scale-min': 'Scale start',
  'label.chart.column.scale-max': 'Scale end',
  'label.chart.column.reached': 'Of the target',

  // Which span a trend card's big number covers, said over it, and how it
  // moved from the period before.
  'label.chart.period.week': 'Week of {start}',
  'label.chart.period.so-far': '{period} so far',
  'label.chart.period.whole': 'All in range',
  'label.chart.period.skipped': '{period} is not over yet and is not counted',
  'label.chart.change.against': 'vs previous period',
  // A metric card's change, against the period right before by its unit.
  [wireKey('label.chart.change.against', AggregationDateUnits.YEAR)]:
    'vs the year before',
  [wireKey('label.chart.change.against', AggregationDateUnits.QUARTER)]:
    'vs the quarter before',
  [wireKey('label.chart.change.against', AggregationDateUnits.MONTH)]:
    'vs the month before',
  [wireKey('label.chart.change.against', AggregationDateUnits.WEEK)]:
    'vs the week before',
  [wireKey('label.chart.change.against', AggregationDateUnits.DAY)]:
    'vs the day before',
  [wireKey('label.chart.change.against', AggregationDateUnits.HOUR)]:
    'vs the hour before',
  [wireKey('label.chart.change.against', AggregationDateUnits.MINUTE)]:
    'vs the minute before',
  [wireKey('label.chart.change.against', AggregationDateUnits.SECOND)]:
    'vs the second before',
  'label.chart.change.against.previous-year': 'vs the same period last year',
  // A change of a percentage, in percentage points.
  'label.chart.change.points': '{amount} pp',
  'label.chart.change.none': 'No previous period to compare with',
  'label.chart.change.none.previous-year':
    'No same period last year to compare with',
  'label.chart.change.unknown.previous-year':
    'No number in the same period last year to compare with',
  'label.chart.change.unknown':
    'No number in the previous period to compare with',
  'label.chart.change.unmatched':
    'The dates picked cover this period and the one before unequally; not compared',
  // The part of a period a trend card's dates hold: days of one year, or
  // any two dates.
  'label.chart.period.range.in-year': '{start} – {end}, {year}',
  'label.chart.period.range': '{from} – {to}',
  'label.chart.period.cut':
    'More than {limit} periods: the latest is not among them, so there is no number to show. Narrow the dates or read by a coarser period.',
} as const satisfies Record<string, string>;
