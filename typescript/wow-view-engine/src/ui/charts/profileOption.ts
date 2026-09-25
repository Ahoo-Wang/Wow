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
import type { ParallelData, RadarData } from '../../analysis/index.js';
import { roundUp } from '../../analysis/gauge.js';
import { CHART_COLOR_SLOTS, type ChartSpec } from '../../model/index.js';
import type { RecordData } from '../../model/index.js';
import type { ColumnTitle, SeriesName, ValueLabel } from './family.js';
import { FADED_OPACITY } from './highlight.js';
import { colorOf } from './palette.js';
import { groupKeyText } from '../../analysis/index.js';
import { emphasized, type ChartTheme } from './theme.js';
import { tooltipFrame, tooltipHtml } from './tooltip.js';

/** What a radar or parallel axes read besides their profiles. */
export interface ProfileContext {
  spec?: ChartSpec;
  label: ValueLabel;
  column: ColumnTitle;
  seriesName?: SeriesName;
  animate: boolean;
  pickable: boolean;
  /** The group a press set the board's filter to (D22 I): the rest faint. */
  highlight?: (row: RecordData) => boolean;
}

/** One drawn shape or line: its group, name and colour. */
export interface DrawnProfile {
  row: RecordData;
  key: string;
  name: string;
  color: string;
}

/**
 * Every shape or line in the order drawn, each with the group it stands
 * for, its name as the legend says it and its colour: a slot of its own up
 * to the palette's size, the spec's pinned colour first; past it, parallel
 * axes' lines wear the first slot alone, since a line is read by where it
 * runs and the tooltip names it.
 */
export function drawnProfiles(
  data: RadarData | ParallelData,
  {
    spec,
    label,
    seriesName,
  }: Pick<ProfileContext, 'spec' | 'label' | 'seriesName'>,
): DrawnProfile[] {
  const category =
    data.type === 'radar' ? spec?.radar?.category : spec?.parallel?.category;
  const many = data.profiles.length > CHART_COLOR_SLOTS;
  return data.profiles.map((profile, index) => {
    const key = groupKeyText(profile.group);
    return {
      row: category === undefined ? {} : { [category]: profile.group },
      key,
      name: (seriesName ?? label)(category, profile.group),
      color: colorOf(spec, many ? 0 : index, ...(many ? [] : [key])),
    };
  });
}

/** How strong a radar shape's fill is: the outline reads through them all. */
const AREA = 0.12;

/**
 * The top of each metric's axis: a round number at or past the largest
 * value drawn on it (`roundUp`), so every shape stays inside the web and a
 * tick reads at a glance. Each axis is its own scale — the metrics measure
 * different things.
 */
function axisTops(data: RadarData | ParallelData): number[] {
  return data.metrics.map((_metric, at) =>
    roundUp(Math.max(0, ...data.profiles.map(profile => profile.values[at]))),
  );
}

/** The bottom of each axis: 0, or a round number under a negative value. */
function axisBottoms(data: RadarData | ParallelData): number[] {
  return data.metrics.map((_metric, at) => {
    const low = Math.min(
      0,
      ...data.profiles.map(profile => profile.values[at]),
    );
    return low < 0 ? -roundUp(-low) : 0;
  });
}

/** The tooltip of one group: its number on every axis, as each column reads. */
function profileTooltip(
  data: RadarData | ParallelData,
  drawn: readonly DrawnProfile[],
  index: number,
  { label, column }: Pick<ProfileContext, 'label' | 'column'>,
  theme: ChartTheme,
): string {
  const profile = data.profiles[index];
  const shape = drawn[index];
  if (!profile || !shape) return '';
  const fill = theme.resolve(shape.color);
  return tooltipHtml(
    shape.name,
    data.metrics.map((metric, at) => ({
      color: fill,
      name: column(metric) ?? metric,
      value: label(metric, profile.values[at]),
    })),
  );
}

/**
 * A radar as the library draws it: an axis per metric going round, each
 * titled by its column and on its own scale from 0 to a round number past
 * its largest value, the web in the border's colour; a closed shape per
 * group in its slot, faintly filled so every outline reads through the
 * others.
 */
export function radarOption(
  data: RadarData,
  context: ProfileContext,
  theme: ChartTheme,
): EChartsCoreOption {
  const { label, column, animate, pickable, highlight } = context;
  const drawn = drawnProfiles(data, context);
  const tops = axisTops(data);
  const bottoms = axisBottoms(data);
  const anyLit = highlight ? drawn.some(shape => highlight(shape.row)) : false;
  return {
    animation: animate,
    animationDuration: 300,
    textStyle: { fontFamily: theme.fontFamily, fontSize: 12 },
    radar: {
      radius: '66%',
      center: ['50%', '52%'],
      splitNumber: 4,
      shape: 'polygon',
      indicator: data.metrics.map((metric, at) => ({
        name: column(metric) ?? metric,
        min: bottoms[at],
        max: tops[at],
      })),
      axisName: { color: theme.muted, fontSize: 12 },
      axisLine: { lineStyle: { color: theme.border } },
      splitLine: { lineStyle: { color: theme.border } },
      splitArea: { show: false },
    },
    tooltip: {
      ...tooltipFrame(theme),
      trigger: 'item',
      formatter: ({ dataIndex }: { dataIndex: number }) =>
        profileTooltip(data, drawn, dataIndex, { label, column }, theme),
    },
    series: [
      {
        type: 'radar',
        cursor: pickable ? 'pointer' : 'default',
        symbolSize: 5,
        data: data.profiles.map((profile, index) => {
          const fill = theme.resolve(drawn[index].color);
          const faint = anyLit && !highlight?.(drawn[index].row);
          return {
            value: profile.values,
            name: drawn[index].name,
            itemStyle: {
              color: fill,
              ...(faint ? { opacity: FADED_OPACITY } : {}),
            },
            lineStyle: {
              color: fill,
              width: 2,
              ...(faint ? { opacity: FADED_OPACITY } : {}),
            },
            areaStyle: { color: fill, opacity: faint ? AREA / 2 : AREA },
            emphasis: {
              lineStyle: { color: emphasized(theme, fill), width: 3 },
              areaStyle: { color: fill, opacity: AREA * 2 },
            },
          };
        }),
      },
    ],
  };
}

/** How opaque a line is when many share one colour: the crossings read. */
const CROWD = 0.45;

/**
 * Parallel axes as the library draws them: an upright axis per metric,
 * titled by its column and on its own scale, and a line per group across
 * them — a slot each up to the palette's size, one colour past it, faint
 * enough that where the lines crowd reads as crowding.
 */
export function parallelOption(
  data: ParallelData,
  context: ProfileContext,
  theme: ChartTheme,
): EChartsCoreOption {
  const { label, column, animate, pickable, highlight } = context;
  const drawn = drawnProfiles(data, context);
  const many = data.profiles.length > CHART_COLOR_SLOTS;
  const tops = axisTops(data);
  const bottoms = axisBottoms(data);
  const anyLit = highlight ? drawn.some(line => highlight(line.row)) : false;
  return {
    animation: animate,
    animationDuration: 300,
    textStyle: { fontFamily: theme.fontFamily, fontSize: 12 },
    parallel: {
      left: 48,
      right: 64,
      top: 40,
      bottom: 24,
      parallelAxisDefault: {
        type: 'value',
        nameLocation: 'end',
        nameGap: 12,
        nameTextStyle: { color: theme.muted, fontWeight: 500, fontSize: 12 },
        axisLine: { lineStyle: { color: theme.border } },
        axisTick: { show: false },
        axisLabel: { color: theme.muted, fontSize: 11, hideOverlap: true },
        splitLine: { show: false },
      },
    },
    parallelAxis: data.metrics.map((metric, at) => ({
      dim: at,
      name: column(metric) ?? metric,
      min: bottoms[at],
      max: tops[at],
      axisLabel: {
        formatter: (value: number) => label(metric, value, true),
      },
    })),
    tooltip: {
      ...tooltipFrame(theme),
      trigger: 'item',
      formatter: ({ dataIndex }: { dataIndex: number }) =>
        profileTooltip(data, drawn, dataIndex, { label, column }, theme),
    },
    series: [
      {
        type: 'parallel',
        cursor: pickable ? 'pointer' : 'default',
        smooth: false,
        data: data.profiles.map((profile, index) => {
          const fill = theme.resolve(drawn[index].color);
          const faint = anyLit && !highlight?.(drawn[index].row);
          return {
            value: profile.values,
            name: drawn[index].name,
            lineStyle: {
              color: fill,
              width: many ? 1 : 2,
              opacity: faint ? FADED_OPACITY / 2 : many ? CROWD : 1,
            },
          };
        }),
        emphasis: { lineStyle: { width: 3, opacity: 1 } },
      },
    ],
  };
}
