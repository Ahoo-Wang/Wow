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
 * the rows share, the selection column at its narrowest, and a hint that
 * appears and disappears as the table scrolls must not move the columns
 * under it. What does not fit is cut; the whole sentence is the button's
 * name and its tooltip.
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
  const shown = [
    field,
    fn,
    value,
    hint.count > 1
      ? messages.label('label.summary.offscreen.more', { count: hint.count })
      : '',
  ]
    .filter(Boolean)
    .join(' ');

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
            // the cell's own padding, one line. At least 24px tall, the
            // floor for a target (WCAG 2.5.8), with the 4px it has over the
            // line taken back by the margins, so the row is as tall with it
            // as without it at every density.
            className="-my-0.5 h-auto min-h-6 w-full min-w-0 justify-start px-0 [contain:inline-size]"
          />
        }
      >
        {hint.side === 'left' && <Arrow data-icon="inline-start" />}
        <span className="min-w-0 truncate">
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
          {shown}
          {/* Inside the words it follows, so a reader hears one sentence;
              out of the flow, so it never takes their room. */}
          <span className="sr-only">{tail}</span>
        </span>
        {hint.side === 'right' && <Arrow data-icon="inline-end" />}
      </TooltipTrigger>
      <TooltipContent>
        {withScope ? `${scopeWord} · ` : ''}
        {shown}
        {tail}
      </TooltipContent>
    </Tooltip>
  );
}
