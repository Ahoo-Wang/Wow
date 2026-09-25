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
import type { MapData } from '../../analysis/index.js';
import type { ChartSpec, RecordData } from '../../model/index.js';
import type { ColumnTitle, ValueLabel } from './family.js';
import { FADED_OPACITY } from './highlight.js';
import { color } from './palette.js';
import { emphasized, mixColor, type ChartTheme, chartText } from './theme.js';
import { tooltipFrame, tooltipHtml } from './tooltip.js';

/** What a map reads besides its regions. */
export interface MapContext {
  spec?: ChartSpec;
  label: ValueLabel;
  column: ColumnTitle;
  animate: boolean;
  pickable: boolean;
  /** The group a press set the board's filter to (D22 I): the rest faint. */
  highlight?: (row: RecordData) => boolean;
}

/** How strong the palest measured region is: still a shade, not the ground. */
const PALEST = 0.2;

/** How strong an area with no number is: the land, told apart from the sea. */
const LAND = 0.08;

/** One measured region as drawn: its group, its name on the map, its number. */
export interface DrawnRegion {
  row: RecordData;
  /** The region as its column shows it: the name the map's area carries. */
  name: string;
  value: number;
  /** Whether the map has an area of that name. */
  placed: boolean;
}

/**
 * Every measured region, largest first, each with the name the map is
 * matched by — the region's value as its column shows it — and whether the
 * map has an area of that name; `regions` left out, every one is placed.
 */
export function drawnRegions(
  data: MapData,
  { spec, label }: Pick<MapContext, 'spec' | 'label'>,
  regions?: ReadonlySet<string>,
): DrawnRegion[] {
  const alias = spec?.map?.region;
  return data.regions.map(region => {
    const name = label(alias, region.group);
    return {
      row: alias === undefined ? {} : { [alias]: region.group },
      name,
      value: region.value,
      placed: regions === undefined || regions.has(name),
    };
  });
}

/**
 * A map as the library draws it: the host's geography named `mapName`,
 * each region with a number shaded from the palest step of the first slot
 * to the full slot by it (the scale under the map), every other area the
 * land's faint grey, borders in the ground. It holds still — no roam: a
 * press opens the follow-up menu on the region, as a press on a bar does.
 */
export function mapOption(
  data: MapData,
  mapName: string,
  context: MapContext,
  theme: ChartTheme,
): EChartsCoreOption {
  const { spec, label, column, animate, pickable, highlight } = context;
  const drawn = drawnRegions(data, context);
  const fill = theme.resolve(color(0));
  const palest = mixColor(theme.ground, fill, PALEST);
  const high = data.high === data.low ? data.low + 1 : data.high;
  const shade = (value: number) =>
    mixColor(palest, fill, (value - data.low) / (high - data.low));
  const anyLit = highlight
    ? drawn.some(region => highlight(region.row))
    : false;
  const measured = column(spec?.map?.value) ?? '';
  return {
    animation: animate,
    animationDuration: 300,
    textStyle: chartText(theme),
    tooltip: {
      ...tooltipFrame(theme),
      trigger: 'item',
      formatter: ({ name }: { name?: string }) => {
        const region = drawn.find(entry => entry.name === name);
        return region
          ? tooltipHtml(region.name, [
              {
                color: shade(region.value),
                name: measured,
                value: label(spec?.map?.value, region.value),
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
        label(spec?.map?.value, data.high, true),
        label(spec?.map?.value, data.low, true),
      ],
      textStyle: { color: theme.axis.color, fontSize: theme.text.labelSize },
    },
    series: [
      {
        type: 'map',
        map: mapName,
        roam: false,
        cursor: pickable ? 'pointer' : 'default',
        // Centred and as large as the frame's shorter side allows: a box
        // given by its four edges stretched the world to the frame's shape.
        layoutCenter: ['50%', '46%'],
        layoutSize: '88%',
        selectedMode: false,
        label: { show: false },
        itemStyle: {
          areaColor: mixColor(theme.ground, theme.muted, LAND),
          borderColor: theme.ground,
          borderWidth: 0.5,
        },
        emphasis: {
          label: { show: false },
          itemStyle: { areaColor: emphasized(theme, fill) },
        },
        data: drawn.map(region => ({
          name: region.name,
          value: region.value,
          ...(anyLit && !highlight?.(region.row)
            ? { itemStyle: { opacity: FADED_OPACITY } }
            : {}),
        })),
      },
    ],
  };
}
