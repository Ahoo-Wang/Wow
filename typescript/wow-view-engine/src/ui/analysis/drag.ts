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

/**
 * What the visualization panel's ordered lists make of a drag — the series,
 * a funnel's stages, a hierarchy's levels: which drop each will take, and
 * what a screen reader hears while one is under way.
 *
 * Both are here rather than inside the lists because neither needs a DOM to
 * be true, and the drop is the one decision a pointer makes that jsdom
 * cannot reach — `@dnd-kit/dom` picks its target by measuring boxes, and
 * every box there is 0×0 at the origin.
 */

import { dragAccessibility } from '../dragAnnounce.js';
import { dragWording, type DragWordingKeys } from '../dragWording.js';
import { dropped, type DropOperation } from '../dragDrop.js';
import type { MessageFormatters } from '../MessagesProvider.js';

/** The two places one drop on a list is between. */
export interface ListDrop {
  from: number;
  to: number;
}

/**
 * The move a finished drag on one of the panel's lists is, or null.
 *
 * Each list carries its rows under a key of its own — a series under the
 * alias of the metric it draws, a stage under its metric or its value, a
 * level under its dimension's alias — so the two ids the drop reports are
 * two of those keys, and no separate identity has to be invented for the
 * library to hold. Whether there was a drop at all is the guard every
 * sortable list here shares ({@link dropped}); what is added on top is that
 * both ids name a row of *this* list, since the panel is not the only
 * draggable thing the page holds.
 */
export function listDrop(
  keys: readonly string[],
  operation: DropOperation,
  canceled: boolean | undefined,
): ListDrop | null {
  const drop = dropped(operation, canceled);
  if (!drop) return null;
  const from = keys.indexOf(drop.source);
  const to = keys.indexOf(drop.target);
  if (from < 0 || to < 0 || from === to) return null;
  return { from, to };
}

/** Where the panel's lists' drag sentences live in the catalogue. */
export const CHART_DRAG_WORDING: DragWordingKeys = {
  picked: 'label.chart.picked',
  cancelled: 'label.chart.cancelled',
  placeholder: 'name',
};

/**
 * What a screen reader hears while a row of the panel is being carried: the
 * shared wording of a drag ({@link dragAccessibility}) said in the panel's
 * own words, with the keys the library is holding turned back into the
 * names on screen.
 */
export function chartDragAccessibility(
  messages: MessageFormatters,
  nameOf: (key: string) => string,
) {
  return dragAccessibility(dragWording(messages, CHART_DRAG_WORDING), nameOf);
}
