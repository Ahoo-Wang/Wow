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
 * What the manager makes of a drag: which drop it will take, and what a
 * screen reader hears while one is under way.
 *
 * Both are here rather than inside the dialog because neither needs a DOM to
 * be true, and the drop is the one decision a pointer makes that jsdom cannot
 * reach — `@dnd-kit/dom` picks its target by measuring boxes, and every box
 * there is 0×0 at the origin.
 */

import type { ViewAudience } from '../../model/index.js';
import { dragAccessibility } from '../dragAnnounce.js';
import { dropped, type Drop, type DropOperation } from '../dragDrop.js';
import type { MessageFormatters } from '../MessagesProvider.js';

/**
 * The drop this list will take, or null.
 *
 * What makes a drop a drop at all is the one guard every list here shares
 * ({@link dropped}). What is added on top is the audiences, checked here as
 * well as held apart by the library's groups: both lists draw personal views
 * above shared ones whatever order is stored, so a row carried across that
 * line would store a new order, spend a revision and leave the screen exactly
 * as it was — and there is no index inside a group that could even express
 * it.
 */
export function managerDrop(
  operation: DropOperation,
  canceled: boolean | undefined,
  audienceOf: (id: string) => ViewAudience | undefined,
): Drop | null {
  const drop = dropped(operation, canceled);
  if (!drop) return null;
  const audience = audienceOf(drop.source);
  if (audience === undefined || audience !== audienceOf(drop.target))
    return null;
  return drop;
}

/**
 * What a screen reader hears while a view is being carried: the shared
 * wording of a drag said in the manager's own words, with the instance ids
 * the library is holding turned back into the titles on screen.
 */
export function manageDragAccessibility(
  messages: MessageFormatters,
  titleFor: (id: string) => string,
) {
  return dragAccessibility(
    messages.label('label.manage.instructions'),
    {
      picked: title => messages.label('label.manage.picked', { title }),
      cancelled: title => messages.label('label.manage.cancelled', { title }),
    },
    titleFor,
  );
}
