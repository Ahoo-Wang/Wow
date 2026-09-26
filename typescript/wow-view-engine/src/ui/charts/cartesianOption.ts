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

import type { EChartsCoreOption } from 'echarts/core';
import type { CartesianData } from '../../analysis/index.js';
import type { AxisSpec } from '../../model/index.js';
import { formatValue, logBounds, measuredTitle, sideTitle } from './axis.js';
import {
  cartesianPlan,
  type CartesianContext,
  type CartesianPlan,
  type DrawnSeries,
} from './cartesianPlan.js';
import { LABEL_DISTANCE, TITLE_GAP_UNDER } from './cartesianFit.js';
import {
  derivedSeries,
  extremeIndexes,
  extremeMarks,
  referenceSeries,
} from './cartesianMarks.js';
import { measureText } from './measure.js';
import { cartesianTooltip } from './cartesianTooltip.js';
import { LARGE_FROM, SLIDER_ROOM, zoomOption } from './cartesianZoom.js';
import { brushOption } from './cartesianBrush.js';
import { chartText, emphasized, inkOn, type ChartTheme } from './theme.js';

export {
  drawnSeries,
  type CartesianContext,
  type DrawnSeries,
} from './cartesianPlan.js';

/**
 * Past this many points a line draws no dot on each: a dot per day of a year
 * is a smear, and the tooltip names the point under the pointer anyway.
 */
export const DOTS_UP_TO = 60;

/**
 * A bar, line, area or combo chart as the library draws it, from the
 * kernel's shape.
 *
 * What it adds to the data is only display: which axis carries the numbers
 * and on what scale (`sharedScales`, so two axes share their gridlines),
 * how they are written (short on the ticks and over the bars, whole in the
 * tooltip — all through the column's own format), where a value label goes,
 * the totals over a stack, and the reference lines. Which labels the plot
 * has room for is decided by its size (`cartesianFit`), never by the
 * library dropping whichever one it met second. Everything a mark stands
 * for was decided by `shapeChart`.
 */
export function cartesianOption(
  data: CartesianData,
  context: CartesianContext,
  theme: ChartTheme,
): EChartsCoreOption {
  return optionOf(cartesianPlan(data, context), theme);
}

/** The option for a plan already made — `Cartesian` makes it once. */
export function optionOf(
  plan: CartesianPlan,
  theme: ChartTheme,
): EChartsCoreOption {
  const { data, context, horizontal, series, sides, names } = plan;
  const { spec, label, column, locale, animate, pickable } = context;
  const cartesian = spec?.cartesian;
  const two = sides.length > 1;
  /**
   * An axis title, a weight above its ticks. Its gap is measured from the
   * axis line, not from the tick names, and a line of ticks under the plot
   * takes some 20px of it: at 24 the title sat 4px under the middle tick,
   * so with an odd count of categories 「已成功」 over 「状态」 read as one
   * name on two lines (2026-09-23 audit P1-2). At 36 a clear line of air
   * parts them. Names that slant down past that push the title below them
   * (`nameMoveOverlap`), still a gap apart.
   */
  const titleStyle = { color: theme.axis.color, fontWeight: 500 };
  const under = (bottom: boolean) => (bottom ? TITLE_GAP_UNDER : 16);
  /** Of two axes the library scales, the one following the other's lines. */
  const follower: 'left' | 'right' =
    plan.wholeOn('right') && !plan.wholeOn('left') ? 'left' : 'right';

  /** What an axis is titled: what the analyst typed, else what it measures. */
  const titleOf = (side: 'left' | 'right') =>
    cartesian?.yAxis?.[side]?.label ??
    // An axis only a running total stands on is titled by it.
    (series.some(entry => entry.side === side)
      ? undefined
      : plan.derived
          .filter(line => line.side === side)
          .map(line => line.name)
          .join(context.join ?? ', ') || undefined) ??
    measuredTitle(
      series.filter(entry => entry.side === side).map(entry => entry.metric),
      two,
      column,
      context.join ?? ', ',
    );

  const valueAxis = (side: 'left' | 'right') => {
    const axis = cartesian?.yAxis?.[side];
    const metric =
      series.find(entry => entry.side === side)?.metric ??
      plan.derived.find(line => line.side === side)?.metric;
    const shares = plan.sharesOn(side);
    const owned = plan.scales[side];
    const marks = plan.reach(side, false);
    const reach = plan.reach(side, true);
    const name = titleOf(side);
    const log = plan.logOn(side);
    return {
      type: log ? 'log' : 'value',
      position: horizontal ? (side === 'left' ? 'bottom' : 'top') : side,
      ...(log
        ? logBounds(axis, plan.valuesOn(side))
        : owned
          ? { min: owned.min, max: owned.max, interval: owned.interval }
          : {
              // Beside a bound the analyst set, the library rounds the other
              // end; a reference line past the marks still stretches it.
              min:
                axis?.min ??
                (shares ? 0 : reach.low < marks.low ? reach.low : undefined),
              max:
                axis?.max ??
                (shares ? 1 : reach.high > marks.high ? reach.high : undefined),
              // A count between 0 and 2 otherwise took ticks at 0.5 and
              // 1.5, which a count's format rounds into a second 「1」.
              minInterval: plan.wholeOn(side) ? 1 : undefined,
              // Not onto a log axis's powers of ten, which a linear one
              // cannot share.
              ...(two && side === follower && !sides.some(plan.logOn)
                ? { alignTicks: true }
                : {}),
            }),
      ...(horizontal
        ? {
            name,
            nameLocation: 'middle',
            nameGap: under(side === 'left'),
            nameMoveOverlap: true,
            nameTextStyle: titleStyle,
          }
        : sideTitle(name, side, 'end', titleStyle, 16)),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: {
        color: theme.axis.color,
        hideOverlap: true,
        formatter: (value: number) => tick(axis, metric, value, shares),
      },
      // One set of gridlines, the left axis's: a second set from the right
      // axis would rule the plot twice at two unrelated steps.
      splitLine: {
        show: side === 'left',
        lineStyle: { ...theme.grid },
      },
    };
  };
  const tick = (
    axis: AxisSpec | undefined,
    metric: string | undefined,
    value: number,
    shares = false,
  ) =>
    axis?.format && axis.format !== 'auto'
      ? formatValue(value, axis.format, locale)
      : shares
        ? formatValue(value, 'percent', locale)
        : label(metric, value, true);

  const dimension = column(cartesian?.x);
  const categoryAxis = {
    type: 'category',
    data: names,
    // Laid on its side the first category is the top one, as a list reads.
    inverse: horizontal,
    // Titled by the dimension, as the value axis is by its metric.
    ...(horizontal
      ? sideTitle(dimension, 'left', 'start', titleStyle, 16)
      : {
          name: dimension,
          nameLocation: 'middle',
          nameGap: TITLE_GAP_UNDER,
          nameMoveOverlap: true,
          nameTextStyle: titleStyle,
        }),
    axisTick: { show: false },
    axisLine: { lineStyle: { ...theme.grid } },
    axisLabel: {
      color: theme.axis.color,
      hideOverlap: true,
      // By the name, not the index the library hands over: zoomed, it
      // counts from the window's first category, and a year narrowed to its
      // last week was written as its first.
      formatter: (name: string) => plan.tickOf(name),
    },
  };
  const values = sides.map(valueAxis);
  const axisIndex = (side: 'left' | 'right') =>
    horizontal
      ? { xAxisIndex: side === 'right' ? 1 : 0 }
      : { yAxisIndex: side === 'right' ? 1 : 0 };
  const large = data.points.length > LARGE_FROM;
  const zoom = zoomOption(plan, theme, context.zoomGestures === true);
  const brush = brushOption(plan, theme);

  /**
   * A value label past a mark's end. A halo of the ground under it keeps it
   * legible over a gridline or the top of the bar beside it.
   */
  const outerLabel = (formatter: (params: never) => string) => ({
    show: true,
    position: horizontal ? 'right' : 'top',
    distance: LABEL_DISTANCE,
    color: theme.foreground,
    fontSize: theme.text.labelSize,
    textBorderColor: theme.ground,
    textBorderWidth: 2,
    formatter,
  });
  const color = (entry: DrawnSeries) => theme.resolve(entry.color);
  /**
   * How wide a bar may be (`chart-bar-min-width`, `chart-bar-max-width`).
   * A cap, because one group on a wide plot was a single slab the width of
   * the chart (定价「按状态分布」, found on the real backend 2026-09-23): a
   * bar is a length to compare, and its width says nothing. 80px by
   * default, as wide as a pair of bars stands: at 48 one series' bars were
   * slivers in a wide band (the 2026-09-25 visual review).
   */
  const barWidths = barBounds(theme);
  /** A bar's free end rounded (`chart-bar-radius`), its base square. */
  const { radius } = theme.bar;
  const barEnd = horizontal ? [0, radius, radius, 0] : [radius, radius, 0, 0];

  const marks = series.map((entry, index) => {
    const fill = color(entry);
    const common = {
      id: `s${index}`,
      name: entry.name,
      ...axisIndex(entry.side),
      data: data.points.map((_point, at) => plan.drawnAt(entry, at)),
      stack: plan.stackOf(entry),
      cursor: pickable ? 'pointer' : 'default',
    };
    // A filled-in 0 is drawn and not written (D23, Q14), and neither is a
    // number the highest or the lowest point's mark writes already.
    const marked = extremeIndexes(plan, entry);
    const written = ({
      value,
      dataIndex,
    }: {
      value: unknown;
      dataIndex: number;
    }) =>
      typeof value === 'number' &&
      !plan.filledAt(entry, dataIndex) &&
      !marked.has(dataIndex)
        ? plan.drawnText(entry, value)
        : '';
    if (entry.kind === 'bar') {
      // Inside its segment when stacked — the total goes over the stack —
      // in the ink that stands off the segment's own colour, with no halo
      // to blur it; over the bar's end otherwise.
      const inside = plan.stacked(entry);
      const ink = inkOn(theme, fill);
      const label = inside
        ? {
            show: true,
            position: 'inside',
            color: ink,
            fontSize: theme.text.labelSize,
            formatter: ({ dataIndex }: { dataIndex: number }) =>
              plan.insideText(entry, dataIndex),
          }
        : outerLabel(written);
      // Past `LARGE_FROM` bars, one path draws them all and no bar is
      // labelled: a thousand numbers a pixel apart are no reading.
      if (large)
        return {
          ...common,
          type: 'bar',
          large: true,
          largeThreshold: LARGE_FROM,
          progressive: 0,
          ...barWidths,
          itemStyle: { color: fill },
        };
      return {
        ...common,
        type: 'bar',
        ...barWidths,
        itemStyle: { color: fill, borderRadius: barEnd },
        // The bar under the pointer a step toward the ink, its label as it
        // was: the library's own hover paled both, and the one bar being
        // read looked like the one switched off (2026-09-23 audit).
        emphasis: {
          itemStyle: { color: emphasized(theme, fill) },
          label: {
            color: inside ? inkOn(theme, emphasized(theme, fill)) : label.color,
          },
        },
        // A long row of bars writes its peak and trough as marks, not a
        // number over each (`peaksOnly`); zoomed in, `cartesianFit` writes
        // the numbers on screen again.
        ...(plan.labelled(entry)
          ? { label: plan.peaksOnly(entry) ? { ...label, show: false } : label }
          : {}),
        ...extremeMarks(plan, entry, fill, theme),
      };
    }
    // A line or an area: a dot on each point while there are few enough to
    // tell apart, a gap where a value is missing rather than a line drawn
    // through it, and an area filled faintly under its own line.
    return {
      ...common,
      type: 'line',
      smooth: entry.configured?.smooth === true,
      symbol: 'circle',
      symbolSize: 6,
      showSymbol: data.points.length <= DOTS_UP_TO,
      // More points than the plot has pixels across are thinned to the ones
      // that keep the line's shape (largest-triangle); the tooltip and the
      // reading table still say every point, and a zoom thins no further
      // than the window needs. A no-op while every point has a pixel.
      sampling: 'lttb',
      connectNulls: false,
      lineStyle: { color: fill, width: theme.line.width },
      itemStyle: { color: fill },
      emphasis: {
        itemStyle: { color: emphasized(theme, fill) },
        lineStyle: { width: theme.line.width },
        label: { color: theme.foreground },
      },
      ...(entry.kind === 'area'
        ? { areaStyle: { color: fill, opacity: theme.line.areaOpacity } }
        : {}),
      ...(plan.labelled(entry)
        ? {
            label: outerLabel(written),
            // Two lines' numbers at one height are moved apart rather than
            // one of them dropped: a missing number reads as a missing value.
            labelLayout: { moveOverlap: 'shiftY' },
          }
        : {}),
      ...extremeMarks(plan, entry, fill, theme),
    };
  });

  /**
   * The total over each stack: a bar of nothing on top of it, labelled with
   * the stack's sum, so the number lands where the stack ends. It takes no
   * press and no tooltip row; the segments are the groups. A 100% stack has
   * none: every one of them would say 100%.
   */
  const totals = plan.totals.map(({ members, texts }) => ({
    type: 'bar',
    stack: plan.stackOf(members[0]),
    ...axisIndex(members[0].side),
    data: data.points.map(() => 0),
    ...barWidths,
    silent: true,
    tooltip: { show: false },
    itemStyle: { color: 'transparent' },
    label: outerLabel(
      ({ dataIndex }: { dataIndex: number }) => texts[dataIndex] ?? '',
    ),
  }));

  // What is drawn over the marks: reference lines and target bands, and
  // the derived lines (`cartesianMarks`).
  const references = referenceSeries(plan, theme, axisIndex);
  const derived = derivedSeries(plan, theme, axisIndex);

  // The widest value label past a mark's end is the room the value side
  // keeps on a chart lying on its side.
  const widestLabel = Math.max(
    0,
    // Measured at the chart's text size, a step over the label's: the room
    // keeps a little to spare.
    ...plan.outerTexts.map(text =>
      measureText(text, theme.text.family, theme.text.size),
    ),
  );

  return {
    // A long axis is read, not watched: zoomed, every refit of its names
    // replayed a line's growing in from the left, and a drag of the slider
    // flickered the lines empty at each step. It moves at once instead.
    animation: animate && !zoom,
    animationDuration: 300,
    textStyle: chartText(theme),
    // The labels and the axis titles stay inside the chart's own box: a
    // tick is centred on its mark, and the last one used to hang half its
    // width past the edge (「2026年9月22E」). Neither does a value label
    // cross it: the library keeps only the axes' text in the box, so the
    // value side keeps room for the widest label beyond the longest mark —
    // on its side, 「59.6万」 over the longest bar lost its 「万」 to the
    // frame and read 「59.6」 (2026-09-23 audit P0-5); upright, a line of
    // text over the tallest (and more, `cartesianFit`, where they stand).
    grid: {
      left: 4,
      right: horizontal
        ? Math.max(16, Math.ceil(widestLabel) + LABEL_DISTANCE + 4)
        : 16,
      // A line of text over the tallest mark: a value label, or the word by
      // the highest point.
      top:
        !horizontal &&
        (plan.outerTexts.some(text => text !== '') ||
          plan.series.some(entry => plan.extremesOf(entry) !== undefined))
          ? 24
          : 16,
      // Under the plot, room for the zoom's slider where there is one.
      bottom: zoom ? 4 + SLIDER_ROOM : 4,
      outerBoundsMode: 'same',
      outerBoundsContain: 'all',
    },
    xAxis: horizontal ? values : categoryAxis,
    yAxis: horizontal ? categoryAxis : values,
    tooltip: cartesianTooltip(plan, theme),
    ...(zoom ? { dataZoom: zoom } : {}),
    ...(brush ? { brush } : {}),
    series: [...marks, ...totals, ...references, ...derived],
  };
}

/** The library's bounds on a bar's width, from the theme's. */
export function barBounds(theme: ChartTheme): {
  barMaxWidth: number;
  barMinWidth?: number;
} {
  return {
    barMaxWidth: theme.bar.maxWidth,
    ...(theme.bar.minWidth === undefined
      ? {}
      : { barMinWidth: theme.bar.minWidth }),
  };
}
