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
 * The catalogue's side of what a drag says: the three sentences one list's
 * drag is worded by.
 *
 * Every sortable list says the same three things — how to carry a row, that
 * it was picked up, that the move was given up. How to carry a row is one
 * sentence for all of them (`label.reorder.instructions`), because the
 * handle is one component and works one way everywhere; the other two name
 * the row, so they live in each list's own family under the placeholder
 * that family had already chosen for its other sentences (`{field}` for a
 * column or a sort entry, `{title}` for a view, a tab or a panel, `{name}`
 * for a series, a stage or a level, `{filter}` for a board filter).
 */

import type { MessageFormatters } from './MessagesProvider.js';
import type { DragWording } from './dragAnnounce.js';
import type { MessageKey } from './messages.js';

/** Where one list's drag sentences live in the catalogue. */
export interface DragWordingKeys {
  /** The row was picked up. */
  picked: MessageKey;
  /** The move was given up and the row stayed where it was. */
  cancelled: MessageKey;
  /** The placeholder `picked` and `cancelled` name the carried row by. */
  placeholder: string;
}

/** One list's drag sentences, in the wording in force. */
export function dragWording(
  messages: MessageFormatters,
  keys: DragWordingKeys,
): DragWording {
  return {
    instructions: messages.label('label.reorder.instructions'),
    picked: name => messages.label(keys.picked, { [keys.placeholder]: name }),
    cancelled: name =>
      messages.label(keys.cancelled, { [keys.placeholder]: name }),
  };
}
