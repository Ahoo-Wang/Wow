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

import { useId } from 'react';
import { InfoIcon } from 'lucide-react';
import { Button } from '../components/button.js';
import { Tooltip, TooltipTrigger } from '../components/tooltip.js';
import { PASSED_OVER } from '../focus.js';
import { TooltipContent } from '../popups.js';

/**
 * The ⓘ at a term's top-right (D71): one sentence saying what the term
 * means, on hover and on focus, for a reader who does not know the word.
 *
 * It is a **button of its own, beside the term** — never inside the heading
 * or another control, so a reader jumping by heading hears the term alone
 * and the keyboard reaches the ⓘ as one stop. Its name says which term it
 * explains (「指标说明」); the sentence is its **description** as well as
 * the tooltip, the way `BadgeTooltip` pairs them, so a reader hears it
 * without the tooltip having to open.
 *
 * It looks small — the 12px glyph `icon-xs` draws, with no frame, pulled
 * up against the term — and is not: the button is the 24px `icon-xs` every
 * icon button here is (WCAG 2.5.8), its negative margin taking the room
 * back from the row rather than from the target. The one rule set aside is
 * the colour: the glyph wears the muted ink, knowingly, because a full-ink
 * ⓘ beside every term out-shouted the terms it is there to explain.
 */
export function TermTip({
  label,
  tip,
  slot = 'term-tip',
  describedBy,
}: {
  /** The button's name: which term it explains. */
  label: string;
  /** The sentence it says. */
  tip: string;
  /** Its `data-slot`, where one row carries more than one. */
  slot?: string;
  /**
   * The id the sentence is kept under, where a control beside the ⓘ is
   * described by it too — auto-run's switch.
   */
  describedBy?: string;
}) {
  const own = useId();
  const described = describedBy ?? own;
  return (
    <Tooltip>
      <TooltipTrigger
        aria-label={label}
        aria-describedby={described}
        render={
          <Button
            variant="ghost"
            size="icon-xs"
            data-slot={slot}
            // A landing passes it over (`focusableIn`): opening the tray
            // must not open the first term's tooltip.
            {...{ [PASSED_OVER]: '' }}
            className="text-muted-foreground -my-1.5 -ml-1 self-start"
          />
        }
      >
        <InfoIcon />
      </TooltipTrigger>
      <TooltipContent>{tip}</TooltipContent>
      <span id={described} hidden>
        {tip}
      </span>
    </Tooltip>
  );
}
