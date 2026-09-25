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

import {
  CalendarDaysIcon,
  ChartAreaIcon,
  ChartCandlestickIcon,
  ChartColumnIcon,
  ChartGanttIcon,
  ChartLineIcon,
  ChartNoAxesCombinedIcon,
  ChartPieIcon,
  ChartScatterIcon,
  FunnelIcon,
  GaugeIcon,
  Grid3x3Icon,
  LayoutDashboardIcon,
  MapIcon,
  NetworkIcon,
  PentagonIcon,
  SlidersVerticalIcon,
  SquareSigmaIcon,
  SunIcon,
  WavesIcon,
  WaypointsIcon,
  TableIcon,
  type LucideIcon,
} from 'lucide-react';
import type { ChartType } from '../../model/index.js';

/**
 * One glyph per way of drawing a result, drawn wherever that way is named:
 * the visualization panel's tiles (`ChartPicker`) and the result toolbar's
 * 「图表」 segment (`AnalysisToolbar`), which shows the type the chart layout
 * will draw — so the two never disagree about what a pie looks like.
 */
export const CHART_ICON: Record<ChartType | 'table', LucideIcon> = {
  bar: ChartColumnIcon,
  line: ChartLineIcon,
  area: ChartAreaIcon,
  // Bands swelling along time: a theme river's streams.
  themeRiver: WavesIcon,
  combo: ChartNoAxesCombinedIcon,
  // Bars floating one after another: a waterfall's steps.
  waterfall: ChartGanttIcon,
  pie: ChartPieIcon,
  // Blocks of unequal size tiling a square: a treemap's parts.
  treemap: LayoutDashboardIcon,
  // Rings round a centre: a sunburst's levels.
  sunburst: SunIcon,
  // A root branching into its parts: a tree.
  tree: NetworkIcon,
  // Paths running from one column of stops to the next: a sankey's flow.
  sankey: WaypointsIcon,
  heatmap: Grid3x3Icon,
  // A month's days in rows of weeks: a calendar heatmap.
  calendar: CalendarDaysIcon,
  // A folded map: regions shaded by their numbers.
  map: MapIcon,
  scatter: ChartScatterIcon,
  // Boxes on whiskers: a box's five numbers.
  boxplot: ChartCandlestickIcon,
  // A closed outline across axes that go round: a radar's shape.
  radar: PentagonIcon,
  // Upright axes side by side: parallel coordinates.
  parallel: SlidersVerticalIcon,
  funnel: FunnelIcon,
  metric: SquareSigmaIcon,
  // A dial and its needle: one number on a scale.
  gauge: GaugeIcon,
  table: TableIcon,
};

/**
 * The chart type whose glyph stands for `type`: the type itself, or the
 * bar — the generic chart — where it is not one this package draws; a config
 * arrives from a store, and a type a later version wrote is refused by the
 * kernel, not drawn as a blank. A key rather than the icon, so a caller
 * looks the component up in `CHART_ICON` as the picker does, and never makes
 * one during render.
 */
export function glyphType(type: string | undefined): ChartType {
  return type !== undefined &&
    type !== 'table' &&
    Object.prototype.hasOwnProperty.call(CHART_ICON, type)
    ? (type as ChartType)
    : 'bar';
}
