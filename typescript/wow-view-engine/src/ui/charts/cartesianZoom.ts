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

import type { CartesianPlan } from './cartesianPlan.js';
import { mixColor, type ChartTheme } from './theme.js';

/**
 * Past this many categories an upright cartesian chart can be zoomed: a
 * slider under the plot, and — where the host allows gestures — a pinch or
 * Ctrl + wheel over it. Two months of days fit a plot side by side; a year
 * of them is a smear of 365 marks whose ticks name every fortnight, and the
 * reader who wants last week had to narrow the range and run again. The
 * same count past which a line stops dotting every point (`DOTS_UP_TO`).
 */
export const ZOOM_FROM = 60;

/**
 * The fewest categories a zoom narrows to, less one (the library's
 * `minValueSpan` counts the steps between them): a week of days, each one
 * named and wide enough to read.
 */
const ZOOM_SPAN = 6;

/**
 * Past this many points a bar or scatter series is drawn in the library's
 * large mode — one path for all the marks rather than an element each —
 * and writes no value labels, which could not be read there anyway.
 * Measured on the SVG renderer (docs/design/analysis-echarts.md 2.4): a
 * thousand bars an element each is where a redraw starts to be felt.
 */
export const LARGE_FROM = 1000;

/** The slider's height: a WCAG 2.5.8 target for its handles. */
const SLIDER_HEIGHT = 24;

/** The room the slider takes under the plot: itself and a line of air. */
export const SLIDER_ROOM = SLIDER_HEIGHT + 12;

/**
 * Which part of the categories is on screen, in percent of the whole — as
 * the library reports a zoom. Transient: never saved with the view (Q51),
 * and gone when the result changes.
 */
export interface ZoomWindow {
  start: number;
  end: number;
}

/** Whether a chart of this plan is zoomable: upright, and long enough. */
export function zooms(plan: CartesianPlan): boolean {
  return !plan.horizontal && plan.data.points.length > ZOOM_FROM;
}

/**
 * How many categories a window shows, of `count`: all of them without one.
 */
export function visibleCount(count: number, window?: ZoomWindow): number {
  if (!window) return count;
  const share = Math.max(0, Math.min(100, window.end - window.start)) / 100;
  return Math.max(1, Math.min(count, Math.ceil(count * share)));
}

/**
 * The zoom a long category axis carries, or nothing for a short one.
 *
 * Always a slider under the plot: it is the one control that reads the same
 * on a phone, in a dashboard panel and in a workbench, and it pans as well
 * as narrows. With `gestures` also the library's `inside` zoom, narrowed to
 * what does not take the page's own gestures away:
 *
 * - **the wheel only with Ctrl held** (a trackpad's pinch arrives as one):
 *   a plain wheel over a chart scrolls the page, as it does over any
 *   picture. `EChart` keeps a plain wheel from reaching the library at all,
 *   because its roam handler swallows every wheel over the grid before
 *   asking whether the zoom wanted it;
 * - **no drag to pan**, and nothing held back on a drag: a finger that lands
 *   on the chart still scrolls the page on a phone — the slider pans;
 * - **a two-finger pinch** on a touch screen, which the page does not scroll
 *   by.
 *
 * A dashboard and a read-only embedding pass no gestures: a board is read
 * by scrolling past many charts, and a page hosting an embedded one is the
 * host's to scroll (docs/design/analysis-echarts.md 2.2).
 */
export function zoomOption(
  plan: CartesianPlan,
  theme: ChartTheme,
  gestures: boolean,
): object[] | undefined {
  if (!zooms(plan)) return undefined;
  const faint = (strength: number) =>
    mixColor(theme.ground, theme.muted, strength);
  const slider = {
    type: 'slider',
    xAxisIndex: 0,
    minValueSpan: ZOOM_SPAN,
    height: SLIDER_HEIGHT,
    bottom: 4,
    left: 8,
    right: 16,
    // The window's ends are the axis's own ticks; a second pair of labels
    // at the handles said the same dates in the library's own format.
    showDetail: false,
    brushSelect: false,
    borderColor: theme.border,
    borderRadius: 4,
    backgroundColor: 'transparent',
    fillerColor: faint(0.12),
    dataBackground: {
      lineStyle: { color: faint(0.6), width: 1 },
      areaStyle: { color: faint(0.2) },
    },
    selectedDataBackground: {
      lineStyle: { color: theme.muted, width: 1 },
      areaStyle: { color: faint(0.35) },
    },
    handleStyle: { color: theme.ground, borderColor: theme.muted },
    moveHandleSize: 6,
    moveHandleStyle: { color: faint(0.4) },
    emphasis: {
      handleStyle: { borderColor: theme.foreground },
      moveHandleStyle: { color: theme.muted },
    },
  };
  if (!gestures) return [slider];
  return [
    slider,
    {
      type: 'inside',
      xAxisIndex: 0,
      minValueSpan: ZOOM_SPAN,
      zoomOnMouseWheel: 'ctrl',
      moveOnMouseWheel: false,
      moveOnMouseMove: false,
      preventDefaultMouseMove: false,
    },
  ];
}
