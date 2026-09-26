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

import { ArrowLeftIcon, ArrowRightIcon } from 'lucide-react';
import type { SummaryRow } from '../../record/index.js';
import { summaryFunctionKey } from '../display.js';
import { useViewMessages } from '../MessagesProvider.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { Button } from '../components/button.js';
import { Tooltip, TooltipTrigger } from '../components/tooltip.js';
import { TooltipContent } from '../popups.js';
import type { OffscreenHint } from './offscreenSummaries.js';
import { summaryText } from './summaryText.js';

export interface OffscreenSummaryProps {
  hint: OffscreenHint;
  scope: SummaryRow['scope'];
  /**
   * The hint shares its cell with the scope label and so says the scope
   * itself: 「全部 · 实付 总和 ¥1,401.49 →」.
   */
  withScope: boolean;
  onReveal: () => void;
}

/**
 * The summary row naming what it holds out of view (D51): the first such
 * column's summary, 「等 N 项」 when there are more, and an arrow the way
 * they lie. A button, because what it offers is to go there.
 *
 * It never widens its cell (`contain: inline-size`): the cell is a column
 * the rows share, the row key at its usual widest, and a hint that appears
 * and disappears as the table scrolls must not move the columns under it.
 *
 * **Two lines, the number on its own** (second review, R1-P1-2 / R3-P1-2).
 * On one line the sentence did not fit the frozen cell it is given — 142px
 * of button in a workbench, less in a narrow key column — and what was cut
 * was its end, the number: 「实付 总和 ¥3,875,…」, 「Retrie… →」. The number
 * is the one thing the hint exists to say, so it gets a line of its own
 * with the arrow beside it, and the line above says whose it is (the
 * scope, the column and the function), wrapping to a second line in a
 * narrow cell before it is cut. 「等 N 项」 follows the number and gives
 * way before it; whatever is cut, the whole sentence is still the button's
 * name and its tooltip. A number is short and is cut last. The cell stays as wide as
 * before; the row is one line taller while the hint shows, which is the
 * room the reading needs and costs no column its place.
 */
export function OffscreenSummary({
  hint,
  scope,
  withScope,
  onReveal,
}: OffscreenSummaryProps) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  const fn = messages.label(summaryFunctionKey(hint.cell.fn, hint.cell.cell));
  const value = summaryText(hint.cell, messages, display);
  const field = hint.column.label;
  const scopeWord = messages.label(`label.summary.scope.${scope}`);
  // What the words on the button leave unsaid, for a screen reader: the
  // button's name starts with the words it shows (WCAG 2.5.3), and this
  // follows them — the row's scope where the button does not show it, that
  // it is out of view, and where the press goes.
  const tail = messages.label(
    withScope
      ? 'label.summary.offscreen.go'
      : 'label.summary.offscreen.go-in-scope',
    { scope: scopeWord, field },
  );
  const Arrow = hint.side === 'left' ? ArrowLeftIcon : ArrowRightIcon;
  const more =
    hint.count > 1
      ? messages.label('label.summary.offscreen.more', { count: hint.count })
      : '';
  // Whose number it is, then the number and how many more there are: the
  // order the two lines show them in, and so the order the name and the
  // tooltip say them in.
  const label = `${field} ${fn}`;
  const shown = [label, value, more].filter(Boolean).join(' ');

  return (
    <Tooltip>
      <TooltipTrigger
        render={
          <Button
            variant="link"
            size="xs"
            data-slot="summary-offscreen"
            data-side={hint.side}
            onClick={onReveal}
            // Layout only: as wide as the cell and never wider, flush with
            // the cell's own padding, two lines stacked to the start. At
            // least 24px tall, the floor for a target (WCAG 2.5.8).
            className="-my-0.5 h-auto min-h-6 w-full min-w-0 flex-col items-start gap-0 px-0 py-0.5 [contain:inline-size]"
          />
        }
      >
        {/* The label wraps at its words, two lines at most, before it is
            cut: a narrow key column (82px in the compensation console) cut
            「Retries Sum」 to 「Retries …」 on one line. A wide cell keeps
            it on one. */}
        <span
          data-slot="summary-offscreen-label"
          className="line-clamp-2 w-full text-left break-words whitespace-normal"
        >
          {withScope && (
            <>
              {/* Still the row's scope label, in its own quiet colour. */}
              <span
                data-slot="summary-scope"
                className="text-quiet-foreground font-normal"
              >
                {scopeWord}
              </span>
              {' · '}
            </>
          )}
          {label}
        </span>
        {/* The two lines are two spans, and a name computed from them runs
            them together (「总和¥1,401.49」) without a space between; in a
            flex column the space takes no room on screen. */}{' '}
        <span className="flex w-full min-w-0 items-center gap-1">
          {hint.side === 'left' && <Arrow data-icon="inline-start" />}
          {/* The number keeps its room and 「等 N 项」 gives way first: a
              count of more columns is worth less than the number itself. */}
          <span
            data-slot="summary-offscreen-value"
            className="max-w-full shrink-0 truncate tabular-nums"
          >
            {value}
          </span>
          {/* A word apart from the number where there is a word to add;
              the tail's own punctuation needs none. */}
          {more && ' '}
          <span className="min-w-0 truncate">
            {more}
            {/* Inside the words it follows, so a reader hears one
                sentence; out of the flow, so it never takes their room. */}
            <span className="sr-only">{tail}</span>
          </span>
          {hint.side === 'right' && <Arrow data-icon="inline-end" />}
        </span>
      </TooltipTrigger>
      <TooltipContent>
        {withScope ? `${scopeWord} · ` : ''}
        {shown}
        {tail}
      </TooltipContent>
    </Tooltip>
  );
}
