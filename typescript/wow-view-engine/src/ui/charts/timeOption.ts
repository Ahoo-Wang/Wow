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
import type { CalendarData, ThemeRiverData } from '../../analysis/index.js';
import type { ChartSpec, RecordData } from '../../model/index.js';
import type { ColumnTitle, SeriesName, ValueLabel } from './family.js';
import { FADED_OPACITY } from './highlight.js';
import { color, colorOf, OTHER_COLOR } from './palette.js';
import { mixColor, type ChartTheme, chartText } from './theme.js';
import { tooltipFrame, tooltipHtml } from './tooltip.js';

/** What a calendar or a theme river reads besides its data. */
export interface TimeContext {
  spec?: ChartSpec;
  label: ValueLabel;
  column: ColumnTitle;
  seriesName?: SeriesName;
  /** The surface's language: the weekday and month names are its. */
  locale?: string;
  animate: boolean;
  pickable: boolean;
  /** The group a press set the board's filter to (D22 I): the rest faint. */
  highlight?: (row: RecordData) => boolean;
  /** 「其他」, as the chart says the folded rest. */
  other: string;
}

/** How strong the palest day is against the ground, as a heatmap's cell. */
const PALEST = 0.2;

/** Whether the surface reads Chinese: the calendar's own names follow it. */
const chinese = (locale: string | undefined) =>
  (locale ?? '').toLowerCase().startsWith('zh');

/**
 * A calendar heatmap as the library draws it: a block a year, weeks across
 * and weekdays down from Monday, a day's cell shaded from the palest step
 * of the first slot to the full slot by its number, the scale under the
 * blocks. A day the rows lack is left blank — no cell, as a heatmap does.
 */
/**
 * A calendar's day and month names, a step under the chart's text: 10 over
 * 12, small enough for a week's column.
 */
const SMALL = 5 / 6;

export function calendarOption(
  data: CalendarData,
  context: TimeContext,
  theme: ChartTheme,
): EChartsCoreOption {
  const { spec, label, column, locale, animate, pickable, highlight } = context;
  const calendar = spec?.calendar;
  const fill = theme.resolve(color(0));
  const palest = mixColor(theme.ground, fill, PALEST);
  const zh = chinese(locale);
  const anyLit = highlight
    ? data.days.some(day => highlight({ [calendar?.date ?? '']: day.at }))
    : false;
  const high = data.high === data.low ? data.low + 1 : data.high;
  return {
    animation: animate,
    animationDuration: 300,
    textStyle: chartText(theme),
    tooltip: {
      ...tooltipFrame(theme),
      trigger: 'item',
      // A series is a year, so a datum is found by its id, not its place.
      formatter: ({ data: datum }: { data?: { id?: unknown } }) => {
        const at = dayOf(datum);
        const day = at === undefined ? undefined : data.days[at];
        return day
          ? tooltipHtml(label(calendar?.date, day.at), [
              {
                color: mixColor(
                  palest,
                  fill,
                  (day.value - data.low) / (high - data.low),
                ),
                name: column(calendar?.value) ?? '',
                value: label(calendar?.value, day.value),
              },
            ])
          : '';
      },
    },
    visualMap: {
      type: 'continuous',
      min: data.low,
      max: high,
      calculable: false,
      orient: 'horizontal',
      left: 'center',
      bottom: 0,
      itemHeight: 120,
      itemWidth: 10,
      inRange: { color: [palest, fill] },
      text: [
        label(calendar?.value, data.high, true),
        label(calendar?.value, data.low, true),
      ],
      textStyle: { color: theme.axis.color, fontSize: theme.text.labelSize },
    },
    // The frame's height shared among the years, the scale under them: a
    // year's block fits whatever the panel's size, its cells as big as
    // that leaves them.
    calendar: data.years.map((year, index) => ({
      top: `${6 + index * (78 / data.years.length)}%`,
      height: `${78 / data.years.length - 6}%`,
      left: 40,
      right: 8,
      cellSize: ['auto', 'auto'],
      range: String(year),
      orient: 'horizontal',
      splitLine: { lineStyle: { ...theme.grid } },
      itemStyle: {
        color: theme.ground,
        borderColor: theme.grid.color,
        borderWidth: 0.5,
      },
      yearLabel: {
        show: data.years.length > 1,
        color: theme.axis.color,
        fontSize: theme.text.size,
      },
      dayLabel: {
        firstDay: 1,
        nameMap: zh ? 'ZH' : 'EN',
        color: theme.axis.color,
        fontSize: theme.text.size * SMALL,
      },
      monthLabel: {
        nameMap: zh ? 'ZH' : 'EN',
        color: theme.axis.color,
        fontSize: theme.text.size * SMALL,
      },
    })),
    series: data.years.map((year, index) => ({
      type: 'heatmap',
      id: `y${year}`,
      coordinateSystem: 'calendar',
      calendarIndex: index,
      cursor: pickable ? 'pointer' : 'default',
      data: data.days
        .map((day, at) => ({ day, at }))
        .filter(({ day }) => day.date.startsWith(`${year}-`))
        .map(({ day, at }) => ({
          value: [day.date, day.value],
          id: `d${at}`,
          ...(anyLit && !highlight?.({ [calendar?.date ?? '']: day.at })
            ? { itemStyle: { opacity: FADED_OPACITY } }
            : {}),
        })),
    })),
  };
}

/** The day a datum is (`d{n}`), by its place in `CalendarData.days`. */
export function dayOf(datum: { id?: unknown } | undefined): number | undefined {
  return typeof datum?.id === 'string' && datum.id.startsWith('d')
    ? Number(datum.id.slice(1))
    : undefined;
}

/** One drawn stream: its key, its name as the legend says it, its colour. */
export interface DrawnStream {
  key: string;
  name: string;
  color: string;
  other: boolean;
}

/** Every stream of a theme river, as the legend and the tooltip name them. */
export function drawnStreams(
  data: ThemeRiverData,
  {
    spec,
    label,
    seriesName,
    other,
  }: Pick<TimeContext, 'spec' | 'label' | 'seriesName' | 'other'>,
): DrawnStream[] {
  const split = spec?.themeRiver?.splitBy;
  return data.streams.map((stream, index) => ({
    key: stream.key,
    name: stream.other ? other : (seriesName ?? label)(split, stream.value),
    color: stream.other ? OTHER_COLOR : colorOf(spec, index, stream.key),
    other: stream.other === true,
  }));
}

/**
 * A theme river as the library draws it: the time axis along the bottom,
 * each stream a band as wide as its number at every bucket, stacked round a
 * centre line so the whole reads as one river and each stream's swell as
 * its own. The streams are in the kernel's order, largest first, the folded
 * 「其他」 grey and last. A point is drawn at every bucket (a river cannot
 * break); the tooltip reads every stream at the bucket under the pointer.
 */
export function themeRiverOption(
  data: ThemeRiverData,
  context: TimeContext,
  theme: ChartTheme,
): EChartsCoreOption {
  const { spec, label, animate, pickable, highlight } = context;
  const river = spec?.themeRiver;
  const streams = drawnStreams(data, context);
  const fills = streams.map(stream => theme.resolve(stream.color));
  const lit = (at: number) =>
    !highlight ||
    streams[at]?.other === true ||
    data.times.some(time =>
      highlight({
        [river?.x ?? '']: time,
        [river?.splitBy ?? '']: data.streams[at]?.value,
      }),
    );
  const anyLit = highlight
    ? streams.some((_stream, at) => !streams[at]?.other && lit(at))
    : false;
  return {
    animation: animate,
    animationDuration: 300,
    textStyle: chartText(theme),
    color: fills.map((fill, at) =>
      anyLit && !lit(at) ? mixColor(theme.ground, fill, FADED_OPACITY) : fill,
    ),
    tooltip: {
      ...tooltipFrame(theme),
      trigger: 'axis',
      axisPointer: {
        type: 'line',
        lineStyle: {
          color: theme.muted,
          type: 'dashed',
          width: theme.grid.width,
        },
      },
      formatter: (params: { value?: [number, number, string] }[]) => {
        const at = params[0]?.value?.[0];
        if (at === undefined) return '';
        return tooltipHtml(
          label(river?.x, data.times[at]),
          streams.map((stream, index) => ({
            color: fills[index] ?? '',
            name: stream.name,
            value: label(river?.value, data.values[at]?.[index] ?? 0),
          })),
        );
      },
    },
    singleAxis: {
      type: 'value',
      min: 0,
      max: Math.max(0, data.times.length - 1),
      minInterval: 1,
      left: 24,
      right: 24,
      top: 16,
      bottom: 32,
      axisLine: { lineStyle: { ...theme.grid } },
      axisTick: { show: false },
      splitLine: { show: false },
      axisLabel: {
        color: theme.axis.color,
        hideOverlap: true,
        formatter: (at: number) =>
          Number.isInteger(at) ? label(river?.x, data.times[at]) : '',
      },
      axisPointer: { label: { show: false } },
    },
    series: [
      {
        type: 'themeRiver',
        cursor: pickable ? 'pointer' : 'default',
        emphasis: { focus: 'self' },
        label: { show: false },
        data: data.times.flatMap((_time, at) =>
          streams.map((stream, index) => [
            at,
            data.values[at]?.[index] ?? 0,
            stream.key,
          ]),
        ),
      },
    ],
  };
}
