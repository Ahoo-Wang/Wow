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
import { Accessibility } from '@dnd-kit/dom';
import { OptimisticSortingPlugin } from '@dnd-kit/dom/sortable';
import type { DragDropProvider } from '@dnd-kit/react';
import type { dragAccessibility } from './dragAnnounce.js';

/** The library's own plugins, as a provider's `plugins` callback gets them. */
type Plugins = Parameters<
  Extract<
    NonNullable<ComponentProps<typeof DragDropProvider>['plugins']>,
    (defaults: never) => unknown
  >
>[0];

/**
 * The plugins one sortable list's `DragDropProvider` runs (Q-11): the
 * library's defaults, its `Accessibility` plugin worded by the list
 * (`dragAccessibility`, through each list's own `…DragAccessibility`)
 * rather than in the library's English built from ids.
 */
export function announcedPlugins(
  accessibility: ReturnType<typeof dragAccessibility>,
): (defaults: Plugins) => Plugins {
  return defaults =>
    defaults.map(plugin =>
      plugin === Accessibility
        ? Accessibility.configure(accessibility)
        : plugin,
    );
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
