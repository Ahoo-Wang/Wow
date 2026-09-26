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

import type { SummaryCell } from '../../record/index.js';
import { inCurrency } from '../../model/currency.js';
import { cellText, type DisplayContext } from '../display.js';
import type { MessageFormatters } from '../MessagesProvider.js';

/**
 * How one summary reads: under its column, in a card, and named by the
 * summary row when its column is out of view — one path, so the three never
 * disagree.
 */
export function summaryText(
  cell: SummaryCell,
  messages: MessageFormatters,
  display: DisplayContext,
): string {
  // Unlike amounts have no total: said, never summed.
  if (cell.currency?.type === 'mixed')
    return messages.label('label.value.mixed-currencies');
  if (cell.value === null) return messages.label('label.summary.unavailable');
  // The same reading the column's cells get, and deliberately the same code
  // path: a date in the surface's language and zone, a number in its field's
  // format. `cell.cell` is set only where the value is not a number
  // (`SummaryCell`), so a count under a date column is not formatted as a
  // date; the field's kind is left out here for exactly that reason.
  return cellText(
    cell.value,
    {
      cell: cell.cell,
      // In the one currency the total's records are in.
      numberFormat:
        cell.currency?.type === 'one'
          ? inCurrency(cell.numberFormat, cell.currency.code)
          : cell.numberFormat,
      timeUnit: cell.timeUnit,
    },
    messages,
    display,
  );
}
