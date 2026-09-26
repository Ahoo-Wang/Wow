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

import type { ComponentProps } from 'react';
import {
  Accessibility,
  PointerActivationConstraints,
  PointerSensor,
} from '@dnd-kit/dom';
import { OptimisticSortingPlugin } from '@dnd-kit/dom/sortable';
import type { DragDropProvider } from '@dnd-kit/react';
import type { dragAccessibility } from './dragAnnounce.js';

type ProviderProps = ComponentProps<typeof DragDropProvider>;

/** The library's own defaults, as a provider's customising callback gets them. */
type Defaults<K extends 'plugins' | 'sensors'> = Parameters<
  Extract<NonNullable<ProviderProps[K]>, (defaults: never) => unknown>
>[0];

/**
 * A press on a handle is not yet a drag: the pointer has to travel a few
 * pixels first, or — on a touch screen — rest a moment, as the library
 * already asks of a finger. Without it a mouse picked the row up on the
 * press itself, and the click that followed was swallowed as the end of a
 * drag, so the handle could never be *clicked* — and a click on it is the
 * one-press way to move a row for a pointer that cannot drag (WCAG 2.5.7,
 * the handle's menu in `DragHandle`).
 */
const PRESS_OR_DRAG = PointerSensor.configure({
  activationConstraints: (event: PointerEvent) =>
    event.pointerType === 'touch'
      ? [new PointerActivationConstraints.Delay({ value: 250, tolerance: 5 })]
      : [new PointerActivationConstraints.Distance({ value: 4 })],
});

/**
 * What every sortable list's `DragDropProvider` runs (Q-11), spread onto it
 * whole so no list sets one half and forgets the other:
 *
 * - `plugins` — the library's defaults, its `Accessibility` plugin worded
 *   by the list (`dragAccessibility`, through each list's own
 *   `…DragAccessibility`) rather than in the library's English built from
 *   ids;
 * - `sensors` — the library's defaults, the pointer's waiting for a drag to
 *   be one ({@link PRESS_OR_DRAG}) so the handle can still be clicked.
 */
export function sortableList(
  accessibility: ReturnType<typeof dragAccessibility>,
): {
  plugins(defaults: Defaults<'plugins'>): Defaults<'plugins'>;
  sensors(defaults: Defaults<'sensors'>): Defaults<'sensors'>;
} {
  return {
    plugins: defaults =>
      defaults.map(plugin =>
        plugin === Accessibility
          ? Accessibility.configure(accessibility)
          : plugin,
      ),
    sensors: defaults =>
      defaults.map(sensor =>
        sensor === PointerSensor ? PRESS_OR_DRAG : sensor,
      ),
  };
}

/**
 * The plugins one sortable row runs: the library's defaults less the
 * optimistic sorting. That plugin reorders the DOM while the pointer moves,
 * which makes the indexes a row is rendered from stale exactly when the drop
 * is read; without it the library still draws the drag preview, and every
 * list here computes the committed order from what the drop reports.
 */
export function withoutOptimisticSorting<P>(defaults: P[]): P[] {
  return defaults.filter(plugin => plugin !== OptimisticSortingPlugin);
}
