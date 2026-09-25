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

import { useSyncExternalStore } from 'react';
import type { CartesianPlan } from './cartesianPlan.js';
import { FADED_OPACITY } from './highlight.js';
import type { ChartTheme } from './theme.js';

/**
 * Brushing a stretch of a time axis (D33 batch C, Q52): a drag along the
 * plot selects the buckets under it, and the follow-up menu a press on one
 * group opens is asked about all of them at once — 「这几天怎么了」. The
 * brush only chooses; what the stretch means as a condition is the
 * kernel's (`drillSpan`), and nothing here edits the view.
 *
 * Only along the axis (`lineX`), only one stretch at a time, and never
 * saved: the cover goes when the menu over it closes.
 */

/** Whether a chart of this plan is brushed: upright, over time, pressable. */
export function brushes(plan: CartesianPlan): boolean {
  return (
    !plan.horizontal &&
    plan.context.brushes === true &&
    plan.context.pickable &&
    plan.data.points.length > 1
  );
}

/**
 * The id the brush component of this package's charts wears, by which the
 * toolbox the library injects for it is recognised (`withoutBrushToolbox`).
 */
export const BRUSH_ID = 'fve-brush';

/**
 * The brush component a brushed chart carries, or nothing.
 *
 * Its cover is a quiet band in the muted ink, and the marks outside it fade as a pressed
 * group's neighbours do on a board (`FADED_OPACITY`), so what the stretch
 * holds reads while the drag is under way. No toolbox: the drag is always
 * on (`BRUSH_CURSOR`), and a press still presses — a drag shorter than the
 * library's threshold draws no cover, and a click is only a click when the
 * pointer barely moved.
 */
export function brushOption(
  plan: CartesianPlan,
  theme: ChartTheme,
): object | undefined {
  if (!brushes(plan)) return undefined;
  return {
    id: BRUSH_ID,
    xAxisIndex: 0,
    brushLink: 'none',
    brushType: 'lineX',
    brushMode: 'single',
    transformable: false,
    removeOnClick: true,
    toolbox: [],
    brushStyle: {
      color: theme.muted,
      borderColor: theme.muted,
      borderWidth: 1,
      opacity: 0.18,
    },
    inBrush: {},
    outOfBrush: { colorAlpha: FADED_OPACITY },
  };
}

/**
 * The option without the toolbox the library's brush preprocessor put into
 * it for this package's brush — an option preprocessor, registered after the
 * brush (`echarts.ts`), so it runs before the library checks what the option
 * uses. The preprocessor adds a `toolbox` for the buttons it would draw; the
 * toolbox is not registered (analysis-echarts.md 2.4: +15.1KB for buttons
 * this package draws itself), so nothing was drawn, but a development build
 * logged 「Component toolbox is used but not imported」 in every host's
 * console. Only an option carrying this package's brush (`BRUSH_ID`) is
 * touched, and only while no toolbox is registered: a host drawing its own
 * charts with the same library, toolbox and all, keeps its toolbox.
 */
export function withoutBrushToolbox(
  option: Record<string, unknown> | undefined,
  toolboxRegistered: boolean,
): void {
  if (!option || toolboxRegistered || !('toolbox' in option)) return;
  const brushes = option.brush;
  const list: unknown[] = Array.isArray(brushes) ? brushes : [brushes];
  if (list.some(entry => (entry as { id?: unknown } | null)?.id === BRUSH_ID))
    delete option.toolbox;
}

/**
 * What turns the brush on without a toolbox button: taken when a mouse or a
 * pen goes down on the plot, before the library sees the press, and again
 * after a whole new option has reset the component (`EChart`).
 */
export const BRUSH_CURSOR = {
  type: 'takeGlobalCursor',
  key: 'brush',
  brushOption: { brushType: 'lineX', brushMode: 'single' },
} as const;

/** What clears the cover once the menu over it has gone. */
export const BRUSH_CLEAR = { type: 'brush', areas: [] } as const;

/** The first and the last category a brush covers, by index. */
export interface BrushedSpan {
  from: number;
  to: number;
}

/**
 * The categories a finished brush covers, read off the library's `brushEnd`
 * event — its one area's range along the category axis, which it gives in
 * category indexes — clamped to the `count` there are; `null` for a brush
 * that covers none (one taken away by a click, or dragged off the plot).
 */
export function brushedSpan(said: unknown, count: number): BrushedSpan | null {
  const areas = (said as { areas?: unknown } | null)?.areas;
  const area: unknown = Array.isArray(areas) ? areas[0] : undefined;
  const range = (area as { coordRange?: unknown } | undefined)?.coordRange;
  if (!Array.isArray(range) || range.length !== 2 || count === 0) return null;
  const [a, b] = range as unknown[];
  if (typeof a !== 'number' || typeof b !== 'number') return null;
  if (!Number.isFinite(a) || !Number.isFinite(b)) return null;
  // The library gives whole indexes on a category axis; were one to come
  // between two, it belongs to the nearer category.
  const low = Math.max(0, Math.round(Math.min(a, b)));
  const high = Math.min(count - 1, Math.round(Math.max(a, b)));
  return low <= high ? { from: low, to: high } : null;
}

/**
 * Whether the reader's main pointer is a finger (`pointer: coarse`): a
 * phone or a tablet, where a chart is brushed not at all. The library takes
 * a touch on the plot as the start of a drag and holds the page still under
 * it, so a finger that landed on a chart to scroll past it would brush it
 * instead; there the first tap reads a mark and the second opens its menu
 * (`EChart`), and the table layout picks a span with Shift where a keyboard
 * is. Read live; where the platform cannot say, a desktop is assumed.
 */
export function useCoarsePointer(): boolean {
  return useSyncExternalStore(subscribe, coarse, () => false);
}

const COARSE = '(pointer: coarse)';

function media(): MediaQueryList | null {
  return typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function'
    ? window.matchMedia(COARSE)
    : null;
}

function coarse(): boolean {
  return media()?.matches ?? false;
}

function subscribe(change: () => void): () => void {
  const list = media();
  list?.addEventListener('change', change);
  return () => list?.removeEventListener('change', change);
}
