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
import {
  axisId,
  categoryTick,
  formatShare,
  formatValue,
  measuredTitle,
  sideTitle,
} from './axis.js';
import {
  cartesianPlan,
  type CartesianContext,
  type CartesianPlan,
  type DrawnSeries,
} from './cartesianPlan.js';
import { LABEL_DISTANCE, TITLE_GAP_UNDER } from './cartesianFit.js';
import { measureText } from './measure.js';
import { emphasized, inkOn, type ChartTheme } from './theme.js';
import { tooltipFrame, tooltipHtml } from './tooltip.js';

export {
  drawnSeries,
  type CartesianContext,
  type DrawnSeries,
} from './cartesianPlan.js';

/**
 * The widest a bar grows. One group on a wide plot was a single slab the
 * width of the chart (定价「按状态分布」, found on the real backend
 * 2026-09-23); a bar is a length to compare, and its width says nothing.
 */
export const BAR_MAX_WIDTH = 48;

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
  const { spec, label, column, locale, animate, pickable, ticks } = context;
  const cartesian = spec?.cartesian;
  const lines = cartesian?.referenceLines ?? [];
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
  const titleStyle = { color: theme.muted, fontWeight: 500 };
  const under = (bottom: boolean) => (bottom ? TITLE_GAP_UNDER : 16);
  /** Of two axes the library scales, the one following the other's lines. */
  const follower: 'left' | 'right' =
    plan.wholeOn('right') && !plan.wholeOn('left') ? 'left' : 'right';

  /** What an axis is titled: what the analyst typed, else what it measures. */
  const titleOf = (side: 'left' | 'right') =>
    cartesian?.yAxis?.[side]?.label ??
    measuredTitle(
      series.filter(entry => entry.side === side).map(entry => entry.metric),
      two,
      column,
      context.join ?? ', ',
    );

  const valueAxis = (side: 'left' | 'right') => {
    const axis = cartesian?.yAxis?.[side];
    const metric = series.find(entry => entry.side === side)?.metric;
    const shares = plan.sharesOn(side);
    const owned = plan.scales[side];
    const marks = plan.reach(side, false);
    const reach = plan.reach(side, true);
    const name = titleOf(side);
    return {
      type: 'value',
      position: horizontal ? (side === 'left' ? 'bottom' : 'top') : side,
      ...(owned
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
            ...(two && side === follower ? { alignTicks: true } : {}),
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
        color: theme.muted,
        hideOverlap: true,
        formatter: (value: number) => tick(axis, metric, value, shares),
      },
      // One set of gridlines, the left axis's: a second set from the right
      // axis would rule the plot twice at two unrelated steps.
      splitLine: {
        show: side === 'left',
        lineStyle: { color: theme.border, width: 1 },
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
    axisLine: { lineStyle: { color: theme.border } },
    axisLabel: {
      color: theme.muted,
      hideOverlap: true,
      formatter: (name: string, index: number) =>
        ticks?.[index] ?? categoryTick(name),
    },
  };
  const values = sides.map(valueAxis);
  const axisIndex = (side: 'left' | 'right') =>
    horizontal
      ? { xAxisIndex: side === 'right' ? 1 : 0 }
      : { yAxisIndex: side === 'right' ? 1 : 0 };
  const hasBars = series.some(entry => entry.kind === 'bar');

  /**
   * A value label past a mark's end. A halo of the ground under it keeps it
   * legible over a gridline or the top of the bar beside it.
   */
  const outerLabel = (formatter: (params: never) => string) => ({
    show: true,
    position: horizontal ? 'right' : 'top',
    distance: LABEL_DISTANCE,
    color: theme.foreground,
    fontSize: 11,
    textBorderColor: theme.ground,
    textBorderWidth: 2,
    formatter,
  });
  const color = (entry: DrawnSeries) => theme.resolve(entry.color);

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
    // A filled-in 0 is drawn and not written (D23, Q14).
    const written = ({
      value,
      dataIndex,
    }: {
      value: unknown;
      dataIndex: number;
    }) =>
      typeof value === 'number' && !plan.filledAt(entry, dataIndex)
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
            fontSize: 11,
            formatter: ({ dataIndex }: { dataIndex: number }) =>
              plan.insideText(entry, dataIndex),
          }
        : outerLabel(written);
      return {
        ...common,
        type: 'bar',
        barMaxWidth: BAR_MAX_WIDTH,
        itemStyle: {
          color: fill,
          borderRadius: horizontal ? [0, 2, 2, 0] : [2, 2, 0, 0],
        },
        // The bar under the pointer a step toward the ink, its label as it
        // was: the library's own hover paled both, and the one bar being
        // read looked like the one switched off (2026-09-23 audit).
        emphasis: {
          itemStyle: { color: emphasized(theme, fill) },
          label: {
            color: inside ? inkOn(theme, emphasized(theme, fill)) : label.color,
          },
        },
        ...(plan.labelled(entry) ? { label } : {}),
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
      connectNulls: false,
      lineStyle: { color: fill, width: 2 },
      itemStyle: { color: fill },
      emphasis: {
        itemStyle: { color: emphasized(theme, fill) },
        lineStyle: { width: 2 },
        label: { color: theme.foreground },
      },
      ...(entry.kind === 'area'
        ? { areaStyle: { color: fill, opacity: 0.2 } }
        : {}),
      ...(plan.labelled(entry)
        ? {
            label: outerLabel(written),
            // Two lines' numbers at one height are moved apart rather than
            // one of them dropped: a missing number reads as a missing value.
            labelLayout: { moveOverlap: 'shiftY' },
          }
        : {}),
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
    barMaxWidth: BAR_MAX_WIDTH,
    silent: true,
    tooltip: { show: false },
    itemStyle: { color: 'transparent' },
    label: outerLabel(
      ({ dataIndex }: { dataIndex: number }) => texts[dataIndex] ?? '',
    ),
  }));

  /**
   * The reference lines, one carrier per axis: a series with no points of
   * its own, so it takes no slot beside the bars in a category's band.
   */
  const references = sides
    .map(side => ({
      side,
      drawn: lines.filter(line => axisId(line.axis) === side),
    }))
    .filter(({ drawn }) => drawn.length > 0)
    .map(({ side, drawn }) => ({
      type: 'line',
      ...axisIndex(side),
      data: [],
      silent: true,
      tooltip: { show: false },
      markLine: {
        symbol: 'none',
        silent: true,
        animation: false,
        lineStyle: { color: theme.muted, type: [4, 4], width: 1 },
        label: {
          position: 'insideEndTop',
          color: theme.muted,
          formatter: (params: { dataIndex: number }) =>
            drawn[params.dataIndex]?.label ?? '',
        },
        data: drawn.map(line =>
          horizontal ? { xAxis: line.value } : { yAxis: line.value },
        ),
      },
    }));

  // The widest value label past a mark's end is the room the value side
  // keeps on a chart lying on its side.
  const widestLabel = Math.max(
    0,
    ...plan.outerTexts.map(text => measureText(text, theme.fontFamily)),
  );

  return {
    animation: animate,
    animationDuration: 300,
    textStyle: { fontFamily: theme.fontFamily, fontSize: 12 },
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
      top: !horizontal && plan.outerTexts.some(text => text !== '') ? 24 : 16,
      bottom: 4,
      outerBoundsMode: 'same',
      outerBoundsContain: 'all',
    },
    xAxis: horizontal ? values : categoryAxis,
    yAxis: horizontal ? categoryAxis : values,
    tooltip: {
      ...tooltipFrame(theme),
      trigger: 'axis',
      // A band behind the bars of one category; a rule through the points
      // of a line, which a band would blur. Behind them, not over them: the
      // library draws its pointer above the series, and a half-grey band
      // laid over the one bar being read paled it and its number — the
      // hovered bar read as the disabled one (2026-09-23 audit).
      axisPointer: hasBars
        ? {
            type: 'shadow',
            z: 0,
            shadowStyle: { color: theme.border, opacity: 0.5 },
          }
        : { type: 'line', lineStyle: { color: theme.muted, width: 1 } },
      formatter: (params: { dataIndex: number }[] | { dataIndex: number }) => {
        const first = Array.isArray(params) ? params[0] : params;
        const point = data.points[first?.dataIndex ?? -1];
        if (!point) return '';
        return tooltipHtml(
          names[first.dataIndex] ?? '',
          series
            .filter(entry => typeof point.values[entry.key] === 'number')
            .map(entry => {
              const read = label(entry.metric, point.values[entry.key]);
              // A filled-in 0 says it is one: no records there, not a
              // count of none that came back (D23, Q14).
              const value =
                plan.filledAt(entry, first.dataIndex) && context.filled
                  ? context.filled(cartesian?.x, read)
                  : read;
              // Stacked to 100%, the mark is a share and the tooltip says
              // both: what the part is, and what part of its stack.
              const share = plan.asShares(entry)
                ? plan.shareAt(entry, first.dataIndex)
                : undefined;
              return {
                color: theme.resolve(entry.color),
                name: entry.name,
                value:
                  share === undefined
                    ? value
                    : `${value} · ${formatShare(share, locale)}`,
              };
            }),
        );
      },
    },
    series: [...marks, ...totals, ...references],
  };
}
