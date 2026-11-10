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
 * A chart's settings in the analysis editor: which chart, what goes in its
 * slots, and how it is drawn — the legend, the labels, the axes and their
 * scale, the number format, reference lines and bands, the derived lines,
 * and each family's own options. What the drawn chart says about its data is
 * `chartReading.ts`; what the chart kernels report is `chartIssues.ts`.
 */
export const chartMessages = {
  'label.chart.picker': 'Visualization',
  'label.chart.picker-back': 'Back to the views',
  'label.chart.recommended': 'Recommended',
  // The picker's two groups (D33 Q54): the types that can draw the result,
  // the table among them, and the rest, greyed, each saying what it lacks.
  'label.chart.group.suits': 'Fits this result',
  'label.chart.group.others': 'Other charts',
  // What the two one-number tiles answer, under their names in the picker:
  // the card says the number and how it moved, the gauge where it stands
  // on a scale — two tiles that look alike, told apart in words.
  'label.chart.hint.metric': 'The number and its change',
  'label.chart.hint.gauge': 'Where it stands on a scale',
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
  // The panel's three lists in an order of their own — the series (read off
  // a stack from the bottom up and off a legend from the left), a funnel's
  // stages and a hierarchy's levels — carried by one handle each and said
  // in one set of words; how the handle is worked is `label.reorder.*`.
  'label.chart.reorder': 'Reorder {name}',
  'label.chart.moved': '{name} moved to position {index} of {total}',
  'label.chart.picked': '{name} picked up',
  'label.chart.cancelled': 'Move cancelled; {name} stayed where it was',
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
  // A boxplot draws a box per value of one dimension from one field's
  // five numbers; a gauge places one number between two ends; a radar's
  // shapes and parallel axes' lines are the groups of one dimension, an
  // axis per metric.
  'label.chart.slot.box': 'One box per',
  'label.chart.slot.spread': 'Spread of',
  'label.chart.slot.candle': 'One candle per',
  'label.chart.slot.moves': 'Moves of',
  'label.chart.slot.shape': 'One shape per',
  'label.chart.slot.line': 'One line per',
  'label.chart.slot.axes': 'Axes',
  // A hierarchy's levels, outermost first; a sankey's stops, left to right.
  'label.chart.slot.levels': 'Levels, outermost first',
  'label.chart.slot.flow': 'Flow, left to right',
  'label.chart.slot.day': 'Days',
  'label.chart.slot.region': 'Regions',
  'label.chart.slot.map': 'Map',
  'label.chart.slot.streams': 'One stream per',
  'label.chart.axis-on': 'Draw {name} as an axis',
  'label.chart.gauge.min': 'Scale starts at',
  'label.chart.gauge.max': 'Scale ends at',
  'label.chart.gauge.auto': 'Automatic',
  // The two numbers a metric card carries beside its headline. The
  // reading table heads the same two with `label.chart.column.*`; a
  // control is asked for, a heading is read, so each has its own words.
  'label.chart.compare-with': 'Compared with',
  'label.chart.target-value': 'Target value',
  'label.chart.slot.stage-order': 'Stage order',
  'label.chart.remove-stage': 'Remove stage {name}',
  'label.chart.compare-mode': 'Comparison',
  'label.chart.compare.delta': 'Difference',
  'label.chart.compare.percent': 'Percent',
  // What a trend card's big number is: the last period that has ended, or
  // every record in the range. Each hint says what the comparison with
  // another metric and the target are measured over in that reading.
  'label.chart.against': 'Compare with',
  'label.chart.against.previous': 'Period before',
  'label.chart.against.previous-year': 'Same period last year',
  'label.chart.against.previous.hint':
    'The last period against the one right before it.',
  'label.chart.against.previous-year.hint':
    'The last period against the same days a year earlier.',
  'label.chart.against.gap.unsupported':
    'This source cannot read the same period last year.',
  'label.chart.against.gap.week':
    'Weeks have no same week last year; group by day or month to compare with last year.',
  'label.chart.against.gap.not-measured':
    'Only a count or a summary of a field can be compared with last year.',
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
  'label.chart.labels.auto': 'Auto',
  'label.chart.labels.all': 'Every value',
  'label.chart.labels.none': 'None',
  'label.chart.labels.share': 'Share only',
  'label.chart.labels.value-share': 'Value and share',
  'label.chart.labels.auto.peaks':
    '{count} bars: only the highest and the lowest are labelled',
  'label.chart.labels.auto.peaks.lines':
    '{count} bars: only the highest and the lowest are labelled; lines and areas are not',
  'label.chart.labels.auto.bars':
    'Under {from} bars, every bar is labelled; from {from} on, only the highest and the lowest',
  'label.chart.labels.auto.bars.lines':
    'Under {from} bars, every bar is labelled; from {from} on, only the highest and the lowest; lines and areas are not',
  'label.chart.labels.auto.totals':
    '{count} stacks: each part is labelled, and only the highest and the lowest total',
  'label.chart.labels.auto.totals.lines':
    '{count} stacks: each part is labelled, and only the highest and the lowest total; lines and areas are not',
  'label.chart.labels.auto.stacks':
    'Each part is labelled; under {from} stacks every total, from {from} on only the highest and the lowest',
  'label.chart.labels.auto.stacks.lines':
    'Each part is labelled; under {from} stacks every total, from {from} on only the highest and the lowest; lines and areas are not',
  'label.chart.stacked': 'Stacked',
  'label.chart.stacked-alone': 'Stacking needs two or more bar or area series',
  'label.chart.percent-stack': 'Stacked to 100%',
  'label.chart.percent-stack.hint':
    'Each stack reads as shares of its total; the tooltip keeps the values',
  'label.chart.horizontal': 'Horizontal',
  'label.chart.line': 'Lines',
  'label.chart.line.straight': 'Straight',
  'label.chart.line.smooth': 'Smooth',
  'label.chart.line.step': 'Step',
  'label.chart.line.step.hint':
    'Each value holds until the next point, as a balance does',
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
  'label.chart.derived.add.previous': 'Previous period',
  'label.chart.derived.add.previous-year': 'Same period last year',
  'label.chart.derived.window': 'Periods',
  'label.chart.derived.window.hint': 'Left empty: {count}.',
  'label.chart.derived.metric': 'Computed from',
  'label.chart.derived.trend': 'Trend',
  'label.chart.derived.moving-average': '{window}-period moving average',
  'label.chart.derived.cumulative': 'Running total',
  'label.chart.derived.cumulative-share': 'Running share',
  'label.chart.derived.previous': 'Previous period',
  'label.chart.derived.previous-year': 'Same period last year',
  'label.chart.derived.change': 'vs {name}: {change}',
  'label.chart.derived.computed': '{name} (computed)',
  'label.chart.derived.of': '{name} · {series}',
  'label.chart.statistic.average': 'Average',
  'label.chart.statistic.median': 'Median',
  'label.chart.statistic.caption': '{statistic} {value}',
  'label.chart.extreme.high': 'High',
  'label.chart.extreme.low': 'Low',
  'label.chart.donut': 'Donut',
  'label.chart.max-slices': 'Slices at most',
  // The ceiling is the palette's size: a slice past it would repeat a colour.
  'label.chart.max-slices.hint':
    'The rest merge into “Other”. At most {count}, one colour each — and {count} when left empty.',
  'label.chart.scale': 'Colour scale',
  'label.chart.scale.linear': 'Linear',
  'label.chart.scale.log': 'Logarithmic',
  'label.chart.scale.not-positive':
    'A log scale has no place for 0 or a negative number, and a cell here holds one: the cells are shaded linearly.',
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
  'label.chart.type.boxplot': 'boxplot',
  'label.chart.type.candlestick': 'candlestick',
  'label.chart.type.gauge': 'gauge',
  'label.chart.type.radar': 'radar',
  'label.chart.type.parallel': 'parallel coordinates',
  'label.chart.type.sunburst': 'sunburst',
  'label.chart.type.tree': 'tree',
  'label.chart.type.sankey': 'sankey',
  'label.chart.type.calendar': 'calendar',
  'label.chart.type.themeRiver': 'theme river',
  'label.chart.type.map': 'map',
  // A waterfall's closing bar adds up the steps drawn: over rows cut short,
  // those are the groups shown and not every group.
  'label.chart.waterfall.total-basis': 'The total is of the groups shown',
  'label.chart.waterfall.increase': 'Increase',
  'label.chart.waterfall.decrease': 'Decrease',
  // Rows whose number is not above zero have no area a tile could take.
  'label.chart.treemap.omitted': '{count} groups not above zero are not drawn',
  'label.chart.treemap.omitted-one': '1 group not above zero is not drawn',
  // Wow computes a percentile approximately: a box's quartiles and median
  // are, and the chart says so over itself, as the table's 「≈」 does.
  'label.chart.boxplot.approximate': 'Quartiles and median are approximate',
  'label.chart.boxplot.omitted':
    '{count} groups without all five numbers are not drawn',
  'label.chart.boxplot.omitted-one':
    '1 group without all five numbers is not drawn',
  'label.chart.candlestick.omitted':
    '{count} periods without all four numbers are not drawn',
  'label.chart.candlestick.omitted-one':
    '1 period without all four numbers is not drawn',
  // A candle's direction in words, beside its colour: a colour-blind eye
  // reads red and green as one.
  'label.chart.candlestick.rise': 'Closed higher',
  'label.chart.candlestick.fall': 'Closed lower',
  'label.chart.candlestick.flat': 'Closed where it opened',
  // Past the palette a shape would wear another's colour: the first ones
  // are drawn, the rest are the table's.
  'label.chart.radar.omitted': '{count} more groups are only in the table',
  'label.chart.radar.omitted-one': '1 more group is only in the table',
  'label.chart.parallel.omitted':
    '{count} groups missing a number are not drawn',
  'label.chart.parallel.omitted-one': '1 group missing a number is not drawn',
  'label.chart.gauge.above': 'The value is past the end of the scale',
  'label.chart.gauge.below': 'The value is below the start of the scale',
  'label.chart.gauge.reached': '{share} of the target',
  'label.chart.legend.more': '{count} more',
  'label.chart.legend.less': 'Show less',
  'label.chart.legend.toggle': 'Series shown — press one to hide or show it',
  // A river cannot break: a point the rows lack is drawn as 0, and where
  // nothing says the group was empty the chart says so.
  'label.chart.map.missing': 'No map is available to draw on',
  'label.chart.map.failed': 'The map could not be loaded',
  'label.chart.map.unplaced': '{count} regions are not on this map',
  'label.chart.map.unplaced-one': '1 region is not on this map',
  'label.chart.map.omitted': '{count} regions without a number are not drawn',
  'label.chart.map.omitted-one': '1 region without a number is not drawn',
  'label.chart.themeRiver.uncertain':
    '{count} points have no row and are drawn as 0; the rows may be cut short',
  'label.chart.themeRiver.uncertain-one':
    '1 point has no row and is drawn as 0; the rows may be cut short',
  // A funnel's key, over the drawing: what its percentages are, what the
  // words between two stages are, and the whole funnel's conversion.
  'label.chart.funnel.key':
    'Percentages are of the first stage; between stages, the drop from the one before',
  'label.chart.funnel.overall': '{share} overall ({last} / {first})',
  // The step that loses the largest share of what it had, said in words.
  'label.chart.funnel.largest-drop': 'Largest drop',

  // The metric card's target bar. The bar is a progressbar, so it says its
  // own name and reads its position as the two numbers behind it rather than
  // as the percentage the role would otherwise announce.
  'label.chart.target': 'Toward target',
  'label.chart.target.reached': '{value} of {target}',
  'label.chart.target.of': 'Target {target}',
  // A metric card's comparison, against the metric it is compared with.
  'label.chart.compare.against': 'vs {metric}',
  'label.chart.compare.unmatched':
    'The span of {metric} does not match the dates picked; not compared',
} as const satisfies Record<string, string>;
