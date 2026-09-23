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

import type * as React from 'react';
import type { RecordRow } from '../../record/index.js';
import type { RecordTableController } from '../../react/index.js';
import { Checkbox } from '../components/checkbox.js';
import { useViewMessages } from '../MessagesProvider.js';

export interface RowCheckboxProps {
  table: RecordTableController;
  row: RecordRow;
  /** The surface's one {@link RangeHint}, which every row checkbox names. */
  hintId: string;
}

/**
 * One row's checkbox, table and cards alike: a press toggles the row, and
 * Shift extends the selection from the last plain toggle to it
 * (`RecordTableController.toggle`, `{ range }`).
 *
 * The keyboard needs nothing of its own. Base UI's checkbox turns a Space on
 * the root into a click carrying the modifiers held (`useButton`), and that
 * click reaches its hidden input — whose change event is what
 * `onCheckedChange` reports, native event and all. So Shift+Space and
 * Shift+click are read off the one event, by the one handler.
 */
export function RowCheckbox({ table, row, hintId }: RowCheckboxProps) {
  const messages = useViewMessages();
  return (
    <Checkbox
      aria-label={messages.label('label.record.select', {
        key: String(row.key),
      })}
      aria-describedby={hintId}
      checked={table.isSelected(row.key)}
      onCheckedChange={(_checked, details) =>
        table.toggle(row.key, { range: heldShift(details.event) })
      }
      onMouseDown={keepTextWhereItIs}
    />
  );
}

function heldShift(event: Event): boolean {
  return 'shiftKey' in event && event.shiftKey === true;
}

/**
 * A Shift+press is a range of rows, not a range of text. Left alone, the
 * browser reads the same press as "extend the text selection to here" and
 * paints every cell between the last press and this one blue. Holding the
 * press's default back keeps the text as it was — and takes the focus the
 * press would have given with it, so the checkbox is handed it directly.
 */
function keepTextWhereItIs(event: React.MouseEvent<HTMLElement>) {
  if (!event.shiftKey) return;
  event.preventDefault();
  event.currentTarget.focus();
}

/**
 * What Shift does to a row checkbox, said once per surface and drawn
 * nowhere; every row checkbox points at it (`aria-describedby`), the way
 * the rows point at the opening hint (`openRows.ts`).
 */
export function RangeHint({ id }: { id: string }) {
  const messages = useViewMessages();
  return (
    <span id={id} className="sr-only">
      {messages.label('label.record.select.hint')}
    </span>
  );
}
