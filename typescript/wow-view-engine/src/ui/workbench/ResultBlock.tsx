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

import type { ReactNode } from 'react';
import { cn } from 'cn';
import { SPACE } from '../layout.js';

/**
 * The result and its caption, on no card of their own (D12).
 *
 * The card used to be here for every kind but the dashboard, and it was a
 * frame around a frame in all of them: a table draws its own header layer,
 * its own hairlines between rows and its own edges on the held columns, so a
 * border and 12px of padding around that put the first row of data behind
 * five layers of chrome. Without it the table runs to the block's edge and
 * the lines on screen are the table's own, which is the only set of lines
 * that means anything. A dashboard still opts out (`resultFramed={false}`
 * on the shell): its result is already a grid of cards, and a frame round
 * that would be the card of cards the rule forbids.
 */
export function ResultBlock({
  framed,
  children,
}: {
  framed: boolean;
  children: ReactNode;
}) {
  return (
    <section
      data-slot="result-block"
      data-framed={framed || undefined}
      className={cn(
        'flex min-w-0 flex-col',
        framed
          ? // One frame round the result and nothing else (D12): the toolbar
            // is its top row and the pagination its bottom row, ruled off;
            // the rows run to its edge; what else lands in it — a query
            // strip, an empty state, cards — keeps a margin of its own.
            'border-border overflow-hidden rounded-lg border ' +
              '[&>[data-slot=result-toolbar]]:border-border [&>[data-slot=result-toolbar]]:border-b [&>[data-slot=result-toolbar]]:px-3 [&>[data-slot=result-toolbar]]:py-2 ' +
              '[&>[data-slot=record-pagination]]:border-border [&>[data-slot=record-pagination]]:bg-muted/40 [&>[data-slot=record-pagination]]:border-t [&>[data-slot=record-pagination]]:px-3 [&>[data-slot=record-pagination]]:py-2 ' +
              '[&>[data-slot=status-strip]]:m-3 [&>[data-slot=record-empty]]:my-6 [&>[data-slot=record-cards]]:p-3'
          : SPACE.ROWS,
      )}
    >
      {children}
    </section>
  );
}
