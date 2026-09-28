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

import { categoryTick, sideTitle } from './axis.js';
import { chartText, type ChartTheme } from './theme.js';
import { tooltipFrame } from './tooltip.js';

/** What a chart of one mark per category tells the frame around it. */
export interface CategoryFrameInput {
  theme: ChartTheme;
  animate: boolean;
  /** The names along the bottom, one a mark, in the order drawn. */
  categories: readonly string[];
  /** The category axis's title, as its column is titled. */
  categoryTitle: string | undefined;
  /** The value axis's title, as its column is titled. */
  valueTitle: string | undefined;
  /** A value axis tick, written short. */
  valueTick: (value: number) => string;
  /**
   * Whether the value axis is read against itself rather than from 0 — a
   * spread, a price — and then keeps no line or ticks of its own. A
   * waterfall's axis stands on 0, where its total does.
   */
  scaled: boolean;
  /** The room under the plot; a waterfall's writes a fall's value there. */
  bottom?: number;
}

/**
 * The frame of a chart that draws one mark per category against a value
 * axis — a box, a candle, a waterfall's step: the grid, the category axis
 * along the bottom with its names cut for it, the value axis at the left
 * titled at its head (`sideTitle`), and the tooltip's frame, one mark at a
 * time. The three built it apiece, key for key (2026-09-27 quality
 * review); the marks, the tooltip's words and the series are each one's own.
 */
export function categoryFrame({
  theme,
  animate,
  categories,
  categoryTitle,
  valueTitle,
  valueTick,
  scaled,
  bottom = 4,
}: CategoryFrameInput) {
  const titleStyle = { color: theme.axis.color, fontWeight: 500 };
  return {
    animation: animate,
    animationDuration: 300,
    textStyle: chartText(theme),
    grid: {
      left: 4,
      right: 16,
      top: 24,
      bottom,
      outerBoundsMode: 'same',
      outerBoundsContain: 'all',
    },
    xAxis: {
      type: 'category',
      data: [...categories],
      name: categoryTitle,
      nameLocation: 'middle',
      nameGap: 28,
      nameMoveOverlap: true,
      nameTextStyle: titleStyle,
      axisTick: { show: false },
      axisLine: { lineStyle: { ...theme.grid } },
      axisLabel: {
        color: theme.axis.color,
        hideOverlap: true,
        formatter: (name: string) => categoryTick(name),
      },
    },
    yAxis: {
      type: 'value',
      ...(scaled ? { scale: true } : {}),
      ...sideTitle(valueTitle, 'left', 'end', titleStyle, 16),
      ...(scaled
        ? { axisLine: { show: false }, axisTick: { show: false } }
        : {}),
      axisLabel: {
        color: theme.axis.color,
        // A tick label that would overlap its neighbour is dropped, on
        // every axis of the three: a waterfall's too (2026-09-27, it wrote
        // them over each other on a short plot).
        hideOverlap: true,
        formatter: valueTick,
      },
      splitLine: { lineStyle: { ...theme.grid } },
    },
    tooltip: { ...tooltipFrame(theme), trigger: 'item' },
  };
}
