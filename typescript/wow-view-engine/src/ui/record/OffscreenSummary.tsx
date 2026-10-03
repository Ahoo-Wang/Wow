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
import { summaryFunctionKey } from '../kit/display.js';
import { useViewMessages } from '../kit/MessagesProvider.js';
import { useSurfaceDisplay } from '../kit/ViewSurface.js';
import { Button } from '../components/button.js';
import { Tooltip, TooltipTrigger } from '../components/tooltip.js';
import { TooltipContent } from '../kit/popups.js';
import type { HintCaption, OffscreenHint } from './offscreenSummaries.js';
import { summaryText } from './summaryText.js';

interface OffscreenSummaryProps {
  hint: OffscreenHint;
  scope: SummaryRow['scope'];
  /**
   * The hint shares its cell with the scope label and so says the scope
   * itself: 「全部 · 实付 总和 ¥1,401.49 →」.
   */
  withScope: boolean;
  /**
   * Whether this row words whose number it is, or shares that line with the
   * row beside it that names the same column's same function
   * (`hintCaptions`, R2-79). Its own by default.
   */
  caption?: HintCaption;
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
 *
 * Two rows naming the same number say whose it is once (`caption`,
 * R2-79): over the page's number, and the total's row keeps to the one
 * line its scope and number take.
 */
export function OffscreenSummary({
  hint,
  scope,
  withScope,
  caption = 'own',
  onReveal,
}: OffscreenSummaryProps) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  const fn = messages.label(summaryFunctionKey(hint.cell.fn, hint.cell.cell));
  const value = summaryText(hint.cell, messages, display);
  const field = messages.say(hint.column.label);
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
  // The row's scope, where the hint shares the scope's cell: still the
  // scope label, in its own quiet colour, ahead of whichever line opens
  // with the row's own words.
  const scopeLabel = (
    <>
      <span
        data-slot="summary-scope"
        className="fve:text-quiet-foreground fve:font-normal"
      >
        {scopeWord}
      </span>
      {' · '}
    </>
  );

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
            // As wide as the cell and never wider, flush with the cell's
            // own padding, two lines stacked to the start. At least 24px
            // tall, the floor for a target (WCAG 2.5.8). In the row's own
            // ink rather than the link's: a total is read, not followed, and
            // in the primary colour it was the loudest thing in the table
            // (2026-09-27 review). The underline under the pointer still
            // says it can be pressed.
            className="fve:text-foreground fve:-my-0.5 fve:h-auto fve:min-h-6 fve:w-full fve:min-w-0 fve:flex-col fve:items-start fve:gap-0 fve:px-0 fve:py-0.5 fve:[contain:inline-size]"
          />
        }
      >
        {/* The label wraps at its words, two lines at most, before it is
            cut: a narrow key column (82px in the compensation console) cut
            「Retries Sum」 to 「Retries …」 on one line. A wide cell keeps
            it on one. Shared with the row below (`leads`), it is that
            row's caption too and leaves the scope to the number's line;
            shared with the row above (`follows`), that line says it and
            this row keeps to one line — a reader still hears it, after the
            number. */}
        {caption !== 'follows' && (
          <span
            data-slot="summary-offscreen-label"
            className="fve:text-quiet-foreground fve:line-clamp-2 fve:w-full fve:text-left fve:font-normal fve:break-words fve:whitespace-normal"
          >
            {withScope && caption === 'own' && scopeLabel}
            {label}
            {/* A caption two rows share counts the columns out of view for
                both, and their numbers keep their lines to themselves. */}
            {caption === 'leads' && more && ` ${more}`}
          </span>
        )}
        {/* The two lines are two spans, and a name computed from them runs
            them together (「总和¥1,401.49」) without a space between; in a
            flex column the space takes no room on screen. */}{' '}
        <span className="fve:flex fve:w-full fve:min-w-0 fve:items-center fve:gap-1">
          {hint.side === 'left' && (
            <Arrow
              data-icon="inline-start"
              className="fve:text-quiet-foreground"
            />
          )}
          {withScope && caption !== 'own' && (
            <>
              <span className="fve:shrink-0">{scopeLabel}</span>{' '}
            </>
          )}
          {/* The number keeps its room and 「等 N 项」 gives way first: a
              count of more columns is worth less than the number itself. */}
          <span
            data-slot="summary-offscreen-value"
            className="fve:max-w-full fve:shrink-0 fve:truncate fve:tabular-nums"
          >
            {value}
          </span>
          {/* A word apart from the number where there is a word to add;
              the tail's own punctuation needs none. */}
          {(caption === 'follows' || (caption === 'own' && more)) && ' '}
          <span className="fve:min-w-0 fve:truncate">
            {caption === 'own' && more}
            {/* Inside the words it follows, so a reader hears one
                sentence; out of the flow, so it never takes their room. */}
            <span className="fve:sr-only">
              {caption === 'follows' && [label, more].filter(Boolean).join(' ')}
              {tail}
            </span>
          </span>
          {hint.side === 'right' && (
            <Arrow
              data-icon="inline-end"
              className="fve:text-quiet-foreground"
            />
          )}
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
