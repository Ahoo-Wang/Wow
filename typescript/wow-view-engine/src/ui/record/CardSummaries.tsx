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

import { cn } from 'cn';
import type { SummaryRow } from '../../record/index.js';
import { TEXT_UI } from '../layout.js';
import { useViewMessages } from '../MessagesProvider.js';
import { SummaryValue } from './SummaryRows.js';

/**
 * The summary rows under the cards (D18 V).
 *
 * A card has no column to stand a number under, so the two scopes are two
 * lines instead of two rows: the scope's word, then each summary as the
 * function's word and the value — the same `SummaryValue` the table's
 * footer draws, so a number reads the same whichever layout it is under.
 * Switching to cards used to drop the summaries without a word, which
 * turned "This page / All records" into something the table alone had.
 */
export function CardSummaries({ rows }: { rows: readonly SummaryRow[] }) {
  const messages = useViewMessages();
  if (rows.length === 0 || rows.every(row => row.cells.length === 0))
    return null;
  return (
    <div
      data-slot="record-summaries"
      data-layout="card"
      // The same muted layer the table's footer is: the numbers are about
      // the rows, not one more of them.
      className="bg-muted border-border flex flex-col gap-1 border-t px-3 py-2"
    >
      {rows.map(row => (
        <div
          key={row.scope}
          data-scope={row.scope}
          className="flex flex-wrap items-baseline gap-x-4 gap-y-1"
        >
          <span
            data-slot="summary-scope"
            className={cn('text-quiet-foreground font-normal', TEXT_UI)}
          >
            {messages.label(`label.summary.scope.${row.scope}`)}
          </span>
          {row.cells.map(cell => (
            <span
              key={`${cell.field}-${cell.fn}`}
              className="flex items-baseline gap-1 text-sm font-medium"
            >
              {/* The field's name first: under a column the heading says
                  it, on a line it has to be said here. */}
              <span
                className={cn('text-quiet-foreground font-normal', TEXT_UI)}
              >
                {cell.label}
              </span>
              <SummaryValue cell={cell} />
            </span>
          ))}
        </div>
      ))}
    </div>
  );
}
