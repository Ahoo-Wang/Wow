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
 * What the column settings make of a drag: which drop they will take, and
 * what a screen reader hears while one is under way.
 *
 * Both are here rather than inside the panel because neither needs a DOM to
 * be true, and the drop is the one decision a pointer makes that jsdom
 * cannot reach — `@dnd-kit/dom` picks its target by measuring boxes, and
 * every box there is 0×0 at the origin.
 */

import { dragAccessibility } from '../dragAnnounce.js';
import { dragWording, type DragWordingKeys } from '../dragWording.js';
import { dropped, type Drop, type DropOperation } from '../dragDrop.js';
import type { MessageFormatters } from '../MessagesProvider.js';
import type { ColumnRegion } from './rows.js';

/**
 * The drop this panel will take, or null.
 *
 * What makes a drop a drop at all is the one guard every list here shares
 * ({@link dropped}). What is added on top is the two areas, checked here as
 * well as held apart by the library's groups: an area *is* a pinning, so a
 * column joins the other one by being pinned and never by being carried
 * there. A drop across that line has no index that could express it — the
 * place it names is a place in the *other* area's order — and committed as
 * one it would move the column somewhere inside its own area that nobody
 * pointed at.
 */
export function columnDrop(
  operation: DropOperation,
  canceled: boolean | undefined,
  regionOf: (field: string) => ColumnRegion | null,
): Drop | null {
  const drop = dropped(operation, canceled);
  if (!drop) return null;
  const region = regionOf(drop.source);
  if (region === null || region !== regionOf(drop.target)) return null;
  return drop;
}

/** Where this list's drag sentences live in the catalogue. */
export const COLUMN_DRAG_WORDING: DragWordingKeys = {
  instructions: 'label.columns.instructions',
  picked: 'label.columns.picked',
  cancelled: 'label.columns.cancelled',
  placeholder: 'field',
};

/**
 * What a screen reader hears while a column is being dragged: the shared
 * wording of a drag ({@link dragAccessibility}) said in this panel's own
 * words, with the field ids turned back into the labels on screen.
 */
export function columnDragAccessibility(
  messages: MessageFormatters,
  labelFor: (field: string) => string,
) {
  return dragAccessibility(
    dragWording(messages, COLUMN_DRAG_WORDING),
    labelFor,
  );
}
