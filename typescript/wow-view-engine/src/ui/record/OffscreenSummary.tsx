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
 * under it. What does not fit is cut, and the whole sentence stays the
 * button's name and its tooltip.
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
  const name = messages.label(
    hint.count > 1
      ? 'label.summary.offscreen.several'
      : 'label.summary.offscreen.one',
    {
      scope: scopeWord,
      summary: messages.label('label.summary.of', { fn, field }),
      value,
      field,
      count: hint.count,
    },
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
            aria-label={name}
            onClick={onReveal}
            // Layout only: as wide as the cell and never wider, flush with
            // the cell's own padding, one line.
            className="h-auto w-full min-w-0 justify-start px-0 [contain:inline-size]"
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
        </span>
        {hint.side === 'right' && <Arrow data-icon="inline-end" />}
      </TooltipTrigger>
      <TooltipContent>{name}</TooltipContent>
    </Tooltip>
  );
}
