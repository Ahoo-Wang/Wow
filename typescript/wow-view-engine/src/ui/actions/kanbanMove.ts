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

import type { RecordActionsController } from '../../react/index.js';
import {
  UNAVAILABLE,
  actionState,
  moveActionFor,
  moveInput,
  type RecordActions,
} from '../../runtime/actions.js';
import type { KanbanMove } from '../record/kanbanDrag.js';

/**
 * A kanban's drag bound to the host's declared move: the action that
 * sets the lane field (`RecordAction.moves`), asked for the lane a card is
 * held over with that lane's value as its input, and run through the
 * surface's controller — the same path a press on the card's menu takes, so
 * it asks first where the action says so, says how it went and reads the
 * view again. `undefined` when the host declared no move for this field.
 */
export function kanbanMove(
  controller: RecordActionsController,
  list: RecordActions | undefined,
  field: string | undefined,
): KanbanMove | undefined {
  const action = moveActionFor(list, field);
  if (!action) return undefined;
  return {
    can: (row, value) => {
      const state = actionState(action, row, {
        now: controller.now,
        input: moveInput(action, value),
      });
      if (state.hidden) return UNAVAILABLE;
      return state.available ? true : (state.reason ?? UNAVAILABLE);
    },
    run: (row, value) =>
      controller.start(action.id, 'row', row, moveInput(action, value)),
    busy: controller.busy || controller.pending !== null,
  };
}
