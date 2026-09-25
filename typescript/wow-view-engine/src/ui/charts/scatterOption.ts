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
import type { ScatterData } from '../../analysis/index.js';
import type { AxisSpec, ChartSpec } from '../../model/index.js';
import { logScaleFits } from '../../analysis/logScale.js';
import { allWhole, formatValue, logBounds, sideTitle } from './axis.js';
import type { ColumnTitle, ValueLabel } from './family.js';
import { color } from './palette.js';
import { emphasized, type ChartTheme, chartText } from './theme.js';
import { tooltipFrame, tooltipHtml } from './tooltip.js';

/** What a scatter reads besides its points. */
export interface ScatterContext {
  spec?: ChartSpec;
  label: ValueLabel;
  column: ColumnTitle;
  /** What an axis is called when no column titles it: 「X 轴」, 「Y 轴」. */
  fallback: { x: string; y: string };
  animate: boolean;
  pickable: boolean;
  /** The surface's language, for an axis's pinned number format. */
  locale?: string;
}

/**
 * Up to this many points, each is named where it is drawn; past it, names
 * would run into one another, and the tooltip names the one pointed at.
 * A few points are a few named things — 「华东」 and 「华南」 — and a
 * scatter that only says "a dot at (3, ¥1,200)" makes the reader hover each.
 */
export const NAMED_POINTS = 8;

/** An axis's data extent, as the library hands it to a bound. */
interface Extent {
  min: number;
  max: number;
}

/** A share of the span, or of the value when every point is at one. */
function room(min: number, max: number, share: number): number {
  return (max - min || Math.abs(max) || 1) * share;
}

/** A point's diameter: one size, or from the smallest to the largest third metric. */
const POINT = 10;
const POINT_RANGE: [number, number] = [8, 28];

/**
 * A scatter as the library draws it: two metrics per group, each axis
 * titled as the table heads its column, its ticks read — shortly — as
 * that column reads and whole where every value is, and a third metric,
 * when there is one, as the points' size. The axes leave room past the
 * extremes, so the point at the largest value is not cut in half by the
 * plot's edge (the 2026-09-23 audit).
 */
export function scatterOption(
  data: ScatterData,
  context: ScatterContext,
  theme: ChartTheme,
): EChartsCoreOption {
  const { spec, label, column, fallback, animate, pickable, locale } = context;
  const measured = spec?.scatter;
  const sizes = data.points
    .map(point => point.size)
    .filter((size): size is number => typeof size === 'number');
  const low = Math.min(...sizes);
  const high = Math.max(...sizes);
  const diameter = (size: number | undefined) =>
    size === undefined || sizes.length === 0
      ? POINT
      : high === low
        ? (POINT_RANGE[0] + POINT_RANGE[1]) / 2
        : POINT_RANGE[0] +
          ((size - low) / (high - low)) * (POINT_RANGE[1] - POINT_RANGE[0]);
  const named = data.points.length <= NAMED_POINTS;
  const names = data.points.map(point =>
    label(measured?.category, point.category),
  );
  const axis = (which: 'x' | 'y') => {
    const alias = measured?.[which];
    const set = scatterAxis(spec, which);
    const name = set?.label ?? column(alias) ?? fallback[which];
    const style = { color: theme.axis.color, fontWeight: 500 };
    const log = scatterLogOn(data, spec, which);
    const text = (value: number) =>
      set?.format && set.format !== 'auto'
        ? formatValue(value, set.format, locale)
        : label(alias, value, true);
    return {
      type: log ? 'log' : 'value',
      // The vertical one set flat at its head where it is Chinese.
      ...(which === 'x'
        ? {
            name,
            nameLocation: 'middle',
            nameGap: 24,
            nameMoveOverlap: true,
            nameTextStyle: style,
          }
        : sideTitle(name, 'left', 'end', style, 16)),
      // Room past the extremes — a point is a circle centred on its value —
      // as a share of the span rather than the library's gap, which rounds
      // the ends out to whole ticks: counts from 0 to 2 read -1 … 3. The
      // two edges are then no tick anyone chose, and write nothing.
      // A bound the analyst set is theirs; a log axis pads by a factor,
      // since a span has no meaning on it.
      ...(log
        ? {
            ...logBounds(set),
            ...(set?.min === undefined
              ? { min: ({ min }: Extent) => min / 1.5 }
              : {}),
            ...(set?.max === undefined
              ? { max: ({ max }: Extent) => max * (named ? 2 : 1.5) }
              : {}),
          }
        : {
            min:
              set?.min ??
              (({ min, max }: Extent) => min - room(min, max, 0.06)),
            max:
              set?.max ??
              (({ min, max }: Extent) =>
                max + room(min, max, which === 'y' && named ? 0.14 : 0.06)),
            minInterval: allWhole(data.points.map(point => point[which]))
              ? 1
              : undefined,
          }),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: {
        color: theme.axis.color,
        hideOverlap: true,
        showMinLabel: false,
        showMaxLabel: false,
        formatter: text,
      },
      splitLine: { lineStyle: { ...theme.grid } },
      // A crosshair: both axes read where the pointer stands, the number
      // written on each (analysis-echarts.md 2.2). Its own, not the
      // tooltip's, which names the point under the pointer.
      axisPointer: {
        show: true,
        type: 'line',
        snap: false,
        triggerTooltip: false,
        lineStyle: {
          color: theme.muted,
          type: 'dashed',
          width: theme.grid.width,
        },
        label: {
          show: true,
          formatter: ({ value }: { value: number }) => text(value),
          color: theme.ground,
          backgroundColor: theme.foreground,
          padding: [2, 4],
          fontSize: theme.text.labelSize,
        },
      },
    };
  };
  const fill = theme.resolve(color(0));
  return {
    animation: animate,
    animationDuration: 300,
    textStyle: chartText(theme),
    grid: {
      left: 4,
      right: 16,
      top: 16,
      bottom: 4,
      outerBoundsMode: 'same',
      outerBoundsContain: 'all',
    },
    xAxis: axis('x'),
    yAxis: axis('y'),
    tooltip: {
      ...tooltipFrame(theme),
      trigger: 'item',
      formatter: ({ dataIndex }: { dataIndex: number }) => {
        const point = data.points[dataIndex];
        if (!point) return '';
        const row = (
          alias: string | undefined,
          which: string,
          value: number,
        ) => ({
          color: fill,
          name: column(alias) ?? which,
          value: label(alias, value),
        });
        return tooltipHtml(names[dataIndex] ?? '', [
          row(measured?.x, fallback.x, point.x),
          row(measured?.y, fallback.y, point.y),
          ...(point.size === undefined || measured?.size === undefined
            ? []
            : [row(measured.size, measured.size, point.size)]),
        ]);
      },
    },
    series: [
      {
        type: 'scatter',
        cursor: pickable ? 'pointer' : 'default',
        data: data.points.map(point => ({
          value: [point.x, point.y],
          symbolSize: diameter(point.size),
        })),
        itemStyle: { color: fill, opacity: 0.85 },
        // The point under the pointer steps toward the ink, as every mark
        // does, and grows a little; no ring, which read as a second point.
        emphasis: {
          scale: 1.2,
          itemStyle: { color: emphasized(theme, fill), opacity: 1 },
        },
        ...(named
          ? {
              label: {
                show: true,
                position: 'top',
                formatter: ({ dataIndex }: { dataIndex: number }) =>
                  names[dataIndex] ?? '',
                color: theme.foreground,
                fontSize: theme.text.labelSize,
                textBorderColor: theme.ground,
                textBorderWidth: 2,
              },
              labelLayout: { hideOverlap: true },
            }
          : {}),
      },
    ],
  };
}

/** What the analyst set of one of a scatter's two axes. */
function scatterAxis(
  spec: ChartSpec | undefined,
  which: 'x' | 'y',
): AxisSpec | undefined {
  return which === 'x' ? spec?.scatter?.xAxis : spec?.scatter?.yAxis;
}

/**
 * Whether a scatter's axis is stepped by powers of ten: the spec asks and
 * every point's number on it is above zero (`logScaleFits`).
 */
export function scatterLogOn(
  data: ScatterData,
  spec: ChartSpec | undefined,
  which: 'x' | 'y',
): boolean {
  return (
    scatterAxis(spec, which)?.scale === 'log' &&
    logScaleFits(data.points.map(point => point[which]))
  );
}

/** The axes whose log scale the points refuse, drawn linear instead. */
export function scatterLogRefused(
  data: ScatterData,
  spec: ChartSpec | undefined,
): ('x' | 'y')[] {
  return (['x', 'y'] as const).filter(
    which =>
      scatterAxis(spec, which)?.scale === 'log' &&
      !scatterLogOn(data, spec, which),
  );
}
