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
import {
  seriesMark,
  valueLabelsOn,
  type CartesianData,
} from '../../analysis/index.js';
import type {
  AxisSpec,
  CartesianSeries,
  ChartSpec,
} from '../../model/index.js';
import { allWhole, axisId, categoryTick, formatValue } from './axis.js';
import type { ColumnTitle, ValueLabel } from './family.js';
import { measureText } from './measure.js';
import { colorOf } from './palette.js';
import type { ChartTheme } from './theme.js';
import { tooltipFrame, tooltipHtml } from './tooltip.js';

/** What a cartesian drawing reads besides its data. */
export interface CartesianContext {
  spec?: ChartSpec;
  label: ValueLabel;
  column: ColumnTitle;
  locale?: string;
  /** Whether the marks grow into place (`useChartMotion`). */
  animate: boolean;
  /** Whether a press on a mark opens the follow-up menu. */
  pickable: boolean;
  /**
   * The category ticks written short, one per point, where the axis is a
   * date bucket (`shortDateTicks`); a point with none is written as its
   * column reads it. The tooltip still names the whole bucket.
   */
  ticks?: readonly (string | undefined)[];
}

/** One series as drawn: the kernel's, and what the spec says about it. */
export interface DrawnSeries {
  key: string;
  metric: string;
  /** Its name in the legend and the tooltip. */
  name: string;
  /** A CSS colour: a theme slot or the one the spec pinned. */
  color: string;
  side: 'left' | 'right';
  configured?: CartesianSeries;
  /** The mark: the chart's own, or — in a combo — the one the spec names. */
  kind: 'bar' | 'line' | 'area';
}

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

/** The series as the legend, the tooltip and the marks all name them. */
export function drawnSeries(
  data: CartesianData,
  { spec, label, column }: Pick<CartesianContext, 'spec' | 'label' | 'column'>,
): DrawnSeries[] {
  const bySeries = new Map(
    (spec?.cartesian?.series ?? []).map(series => [series.metric, series]),
  );
  return data.series.map((series, index) => {
    const configured = bySeries.get(series.metric);
    return {
      key: series.key,
      metric: series.metric,
      // A pivoted series shows its split value as that field shows it; an
      // unpivoted one is its column's title — 「金额的合计」, never the
      // alias, which names the query.
      name:
        series.value === undefined
          ? (column(series.metric) ?? series.label)
          : label(spec?.cartesian?.splitBy, series.value),
      // The spec names a pivoted series by its split value as the kernel
      // labels it, and an unpivoted one by its metric alias.
      color: colorOf(spec, index, series.label, series.metric),
      side: axisId(configured?.axis),
      configured,
      kind: seriesMark(data.chart, configured),
    };
  });
}

/**
 * A bar, line, area or combo chart as the library draws it, from the
 * kernel's shape.
 *
 * What it adds to the data is only display: which axis carries the numbers,
 * how they are written (short on the ticks and over the bars, whole in the
 * tooltip — all through the column's own format), where a value label goes
 * and that one landing on another is hidden rather than drawn over it, the
 * totals over a stack, and the reference lines. Everything a mark stands
 * for was decided by `shapeChart`.
 */
export function cartesianOption(
  data: CartesianData,
  context: CartesianContext,
  theme: ChartTheme,
): EChartsCoreOption {
  const { spec, label, column, locale, animate, pickable, ticks } = context;
  const cartesian = spec?.cartesian;
  const horizontal = cartesian?.orientation === 'horizontal';
  const series = drawnSeries(data, context);
  const lines = cartesian?.referenceLines ?? [];
  const hasRight =
    series.some(entry => entry.side === 'right') ||
    lines.some(line => axisId(line.axis) === 'right');
  /**
   * Whether a series writes its values: a bar does unless the analyst said
   * not to, a line or an area only when asked (`valueLabelsOn`) — a number
   * on every point drowned the line it was on.
   */
  const labelled = (entry: DrawnSeries) => valueLabelsOn(spec, entry.kind);

  /**
   * A stack as the library draws it: the spec's name on one axis. Series
   * measured against two axes are two scales, and stacking a count on top
   * of an amount drew the count at the amount's height; each axis stacks
   * its own. A line never stacks: its point would stand at the running sum
   * while its label says its own value (「¥640」 at ¥1920), with no band
   * under it to show the part it adds — it draws its own values.
   */
  const stackOf = (entry: DrawnSeries) =>
    entry.configured?.stack === undefined || entry.kind === 'line'
      ? undefined
      : `${entry.side}:${entry.configured.stack}`;

  /** Every value one axis carries: its series' and its reference lines'. */
  const valuesOn = (side: 'left' | 'right') => [
    ...series
      .filter(entry => entry.side === side)
      .flatMap(entry =>
        data.points.map(point => point.values[entry.key] ?? null),
      )
      .filter((value): value is number => value !== null),
    ...lines.filter(line => axisId(line.axis) === side).map(line => line.value),
  ];
  /**
   * The highest and the lowest a mark reaches on one axis: a stack reaches
   * the sum of its parts on either side of zero, anything else its value.
   * The axis starts at zero, so neither is ever past it the wrong way.
   */
  const reachOn = (side: 'left' | 'right') => {
    let high = 0;
    let low = 0;
    for (const point of data.points) {
      const stacks = new Map<
        string | undefined,
        { up: number; down: number }
      >();
      for (const entry of series.filter(one => one.side === side)) {
        const value = point.values[entry.key];
        if (typeof value !== 'number') continue;
        const stack = stackOf(entry);
        if (stack === undefined) {
          high = Math.max(high, value);
          low = Math.min(low, value);
          continue;
        }
        const sum = stacks.get(stack) ?? { up: 0, down: 0 };
        if (value > 0) sum.up += value;
        else sum.down += value;
        stacks.set(stack, sum);
      }
      for (const { up, down } of stacks.values()) {
        high = Math.max(high, up);
        low = Math.min(low, down);
      }
    }
    return { high, low };
  };
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
  const valueAxis = (side: 'left' | 'right') => {
    const axis = cartesian?.yAxis?.[side];
    const values = valuesOn(side);
    const metric = series.find(entry => entry.side === side)?.metric;
    // Titled by what it measures when it measures one thing — a split draws
    // one metric many times, which is still one — as Metabase titles its
    // axes; two metrics on one axis are named by the legend instead.
    const measured = new Set(
      series.filter(entry => entry.side === side).map(entry => entry.metric),
    );
    const lineValues = lines
      .filter(line => axisId(line.axis) === side)
      .map(line => line.value);
    // A reference line is a threshold the reader set: one past the marks
    // stretches the axis to it rather than falling off the plot. One within
    // them leaves the axis to round its own ends — handed back as a bound,
    // the data's raw top read 「¥4882」 on the last tick.
    const reach = reachOn(side);
    const lineHigh = Math.max(-Infinity, ...lineValues);
    const lineLow = Math.min(Infinity, ...lineValues);
    return {
      type: 'value',
      position: horizontal ? (side === 'left' ? 'bottom' : 'top') : side,
      min: axis?.min ?? (lineLow < reach.low ? lineLow : undefined),
      max: axis?.max ?? (lineHigh > reach.high ? lineHigh : undefined),
      // A count between 0 and 2 otherwise took ticks at 0.5 and 1.5, which
      // a count's format rounds into a second 「1」 and 「2」.
      minInterval: allWhole(values) ? 1 : undefined,
      name:
        axis?.label ??
        (measured.size === 1 && metric !== undefined
          ? column(metric)
          : undefined),
      nameLocation: 'middle',
      nameGap: under(horizontal && side === 'left'),
      nameMoveOverlap: true,
      nameTextStyle: titleStyle,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: {
        color: theme.muted,
        hideOverlap: true,
        formatter: (value: number) => tick(axis, metric, value),
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
  ) =>
    axis?.format && axis.format !== 'auto'
      ? formatValue(value, axis.format, locale)
      : label(metric, value, true);

  const names = data.points.map(point => label(cartesian?.x, point.x));
  const categoryAxis = {
    type: 'category',
    data: names,
    // Laid on its side the first category is the top one, as a list reads.
    inverse: horizontal,
    // Titled by the dimension, as the value axis is by its metric.
    name: column(cartesian?.x),
    nameLocation: 'middle',
    nameGap: under(!horizontal),
    nameMoveOverlap: true,
    nameTextStyle: titleStyle,
    axisTick: { show: false },
    axisLine: { lineStyle: { color: theme.border } },
    axisLabel: {
      color: theme.muted,
      hideOverlap: true,
      formatter: (name: string, index: number) =>
        ticks?.[index] ?? categoryTick(name),
    },
  };
  const values = hasRight
    ? [valueAxis('left'), valueAxis('right')]
    : [valueAxis('left')];
  const axisIndex = (side: 'left' | 'right') =>
    horizontal
      ? { xAxisIndex: side === 'right' ? 1 : 0 }
      : { yAxisIndex: side === 'right' ? 1 : 0 };

  const stacks = new Map<string, DrawnSeries[]>();
  for (const entry of series) {
    const stack = stackOf(entry);
    if (stack !== undefined)
      stacks.set(stack, [...(stacks.get(stack) ?? []), entry]);
  }
  const stacked = (entry: DrawnSeries) =>
    (stacks.get(stackOf(entry) ?? '')?.length ?? 0) > 1;
  const hasBars = series.some(entry => entry.kind === 'bar');
  const valueLabel = (metric: string, position: string) => ({
    show: true,
    position,
    color: theme.foreground,
    fontSize: 11,
    // A halo of the ground under it keeps a label legible over a gridline
    // or the top of the bar beside it.
    textBorderColor: theme.ground,
    textBorderWidth: 2,
    formatter: ({ value }: { value: unknown }) =>
      typeof value === 'number' ? label(metric, value, true) : '',
  });
  const outside = horizontal ? 'right' : 'top';

  const color = (entry: DrawnSeries) => theme.resolve(entry.color);
  const marks = series.map((entry, index) => {
    const common = {
      id: `s${index}`,
      name: entry.name,
      ...axisIndex(entry.side),
      data: data.points.map(point => point.values[entry.key] ?? null),
      stack: stackOf(entry),
      cursor: pickable ? 'pointer' : 'default',
    };
    if (entry.kind === 'bar')
      return {
        ...common,
        type: 'bar',
        barMaxWidth: BAR_MAX_WIDTH,
        itemStyle: {
          color: color(entry),
          borderRadius: horizontal ? [0, 2, 2, 0] : [2, 2, 0, 0],
        },
        // Inside its segment when stacked — the total goes over the stack —
        // over the bar's end otherwise; either way a label that would land
        // on another is left out rather than drawn over it.
        ...(labelled(entry)
          ? {
              label: valueLabel(
                entry.metric,
                stacked(entry) ? 'inside' : outside,
              ),
              labelLayout: { hideOverlap: true },
            }
          : {}),
      };
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
      lineStyle: { color: color(entry), width: 2 },
      itemStyle: { color: color(entry) },
      ...(entry.kind === 'area'
        ? { areaStyle: { color: color(entry), opacity: 0.2 } }
        : {}),
      ...(labelled(entry)
        ? {
            label: valueLabel(entry.metric, outside),
            labelLayout: { hideOverlap: true },
          }
        : {}),
    };
  });

  /**
   * The total over each stack: a bar of nothing on top of it, labelled with
   * the stack's sum, so the number lands where the stack ends. It takes no
   * press and no tooltip row; the segments are the groups.
   */
  const totals = valueLabelsOn(spec, 'bar')
    ? [...stacks.values()]
        .map(members => members.filter(member => member.kind === 'bar'))
        .filter(members => members.length > 1)
        .map(members => ({
          type: 'bar',
          stack: stackOf(members[0]),
          ...axisIndex(members[0].side),
          data: data.points.map(() => 0),
          barMaxWidth: BAR_MAX_WIDTH,
          silent: true,
          tooltip: { show: false },
          itemStyle: { color: 'transparent' },
          label: {
            ...valueLabel(members[0].metric, outside),
            formatter: ({ dataIndex }: { dataIndex: number }) => {
              const point = data.points[dataIndex];
              const parts = members
                .map(member => point?.values[member.key])
                .filter((value): value is number => typeof value === 'number');
              return parts.length > 0
                ? label(
                    members[0].metric,
                    parts.reduce((sum, value) => sum + value, 0),
                    true,
                  )
                : '';
            },
          },
          labelLayout: { hideOverlap: true },
        }))
    : [];

  /**
   * The reference lines, one carrier per axis: a series with no points of
   * its own, so it takes no slot beside the bars in a category's band.
   */
  const references = (['left', 'right'] as const)
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

  /**
   * The value labels past the marks' ends, as text: what a bar or a point
   * writes beside itself, and each stack's total. The widest of them is the
   * room the value side keeps.
   */
  const outerTexts = [
    ...series
      // A stacked bar writes its part inside its segment.
      .filter(
        entry => labelled(entry) && !(entry.kind === 'bar' && stacked(entry)),
      )
      .flatMap(entry =>
        data.points.map(point => {
          const value = point.values[entry.key];
          return typeof value === 'number'
            ? label(entry.metric, value, true)
            : '';
        }),
      ),
    ...totals.flatMap(total =>
      data.points.map((_point, dataIndex) =>
        total.label.formatter({ dataIndex }),
      ),
    ),
  ];
  const widestLabel = Math.max(
    0,
    ...outerTexts.map(text => measureText(text, theme.fontFamily)),
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
    // text over the tallest.
    grid: {
      left: 4,
      right: horizontal
        ? Math.max(16, Math.ceil(widestLabel) + LABEL_DISTANCE + 4)
        : 16,
      top: !horizontal && outerTexts.some(text => text !== '') ? 24 : 16,
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
      // of a line, which a band would blur.
      axisPointer: hasBars
        ? { type: 'shadow', shadowStyle: { color: theme.border, opacity: 0.5 } }
        : { type: 'line', lineStyle: { color: theme.muted, width: 1 } },
      formatter: (params: { dataIndex: number }[] | { dataIndex: number }) => {
        const first = Array.isArray(params) ? params[0] : params;
        const point = data.points[first?.dataIndex ?? -1];
        if (!point) return '';
        return tooltipHtml(
          names[first.dataIndex] ?? '',
          series
            .filter(entry => typeof point.values[entry.key] === 'number')
            .map(entry => ({
              color: theme.resolve(entry.color),
              name: entry.name,
              value: label(entry.metric, point.values[entry.key]),
            })),
        );
      },
    },
    series: [...marks, ...totals, ...references],
  };
}

/**
 * How the category names on a bar chart's axis fit the width they have.
 *
 * Side by side while every name fits its band; at a slant once one does
 * not, each still under its own bar, cut at a length a slant can carry; and
 * only when a band is narrower than a line of text does the axis name every
 * few bars instead — the first and the last always among them. Metabase
 * turns its labels the same way; the library itself only thins them, which
 * showed every other name of 16 event types with room to spare.
 */
export function categoryFit(
  names: readonly string[],
  width: number,
  measure: (text: string) => number,
  horizontal: boolean,
  /**
   * The names are the days or months of a time axis, in order. Those are
   * never slanted: a reader carries a date across the gap between two
   * ticks, so the axis writes every few flat, the first always among them,
   * as Metabase's time axis does.
   */
  dated = false,
): EChartsCoreOption {
  if (horizontal)
    return {
      yAxis: {
        axisLabel: {
          width: Math.max(64, Math.round(width * 0.3)),
          overflow: 'truncate',
        },
      },
    };
  // What the value axis and the padding leave the categories.
  const band = Math.max(0, width - 72) / Math.max(1, names.length);
  const widest = Math.max(0, ...names.map(name => measure(categoryTick(name))));
  if (widest + 8 <= band)
    return { xAxis: { axisLabel: { rotate: 0, interval: 0 } } };
  if (dated)
    return {
      xAxis: {
        axisLabel: { rotate: 0, interval: 'auto', showMinLabel: true },
      },
    };
  // Slanted, the names reach further down than a line of text, and the
  // title moved clear of them (`nameMoveOverlap`) sat 4px under their ends:
  // the gap is set past the slant's own depth instead, a line of air below.
  const slant = (Math.min(widest, SLANT_MAX) + LINE_HEIGHT) * Math.SQRT1_2;
  return {
    xAxis: {
      nameGap: Math.ceil(LABEL_MARGIN + slant + TITLE_AIR),
      axisLabel: {
        rotate: 45,
        interval: band >= LINE_HEIGHT ? 0 : 'auto',
        width: SLANT_MAX,
        overflow: 'truncate',
        showMinLabel: true,
        showMaxLabel: true,
      },
    },
  };
}

/**
 * How far below the axis line the title under the plot sits: a line of
 * ticks (some 20px) and a clear line of air under it.
 */
export const TITLE_GAP_UNDER = 36;

/** The air between the lowest tick and the title under it. */
const TITLE_AIR = 14;

/** How far a tick stands off its axis line (the library's default). */
const LABEL_MARGIN = 8;

/** How far a value label stands off its mark's end (the library's default). */
const LABEL_DISTANCE = 5;

/** A line of tick text, and so the narrowest band a slanted name fits. */
const LINE_HEIGHT = 16;

/** The longest a slanted name is drawn before it is cut. */
const SLANT_MAX = 120;
