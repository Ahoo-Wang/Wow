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

import { dragAccessibility } from '../dragAnnounce.js';
import type { MessageFormatters } from '../MessagesProvider.js';

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
    messages.label('label.columns.instructions'),
    {
      picked: field => messages.label('label.columns.picked', { field }),
      cancelled: field => messages.label('label.columns.cancelled', { field }),
    },
    labelFor,
  );
}
