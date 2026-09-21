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

import { ChevronDownIcon, RefreshCwIcon } from 'lucide-react';
import type { RefreshController } from '../react/index.js';
import { Button } from './components/button.js';
import { ButtonGroup } from './components/button-group.js';
import {
  DropdownMenu,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from './components/dropdown-menu.js';
import { Spinner } from './components/spinner.js';
import { useViewMessages, type MessageFormatters } from './MessagesProvider.js';
import { DropdownMenuContent } from './popups.js';

/** The value the radio group carries for "no automatic refresh". */
const OFF = 'off';

export interface RefreshControlProps {
  refresh: RefreshController;
  /**
   * `ghost` above a result, where a toolbar must not compete with the rows
   * under it; `outline` in a title bar, among controls that all wear one.
   */
  variant?: 'ghost' | 'outline';
  /**
   * Whether something other than this view's own query makes a refresh
   * pointless right now — a dashboard still resolving its references. The
   * controller's own `loading` is added to it.
   */
  busy?: boolean;
  /**
   * One line at the top of the menu, for a surface whose timer is not the
   * open view's alone: a dashboard holds one timer for every panel, and the
   * interval saved in a referenced view is ignored inside it.
   */
  note?: string;
}

/**
 * Refreshing the view: one press for now, the `▾` for how often by itself.
 *
 * The two halves are one control because they answer one question — how the
 * numbers on screen get renewed — and two separate buttons would give the
 * rare choice the weight of the daily one. The primary half is exactly
 * the one-shot refresh it has always been.
 *
 * What the menu offers is cut to what the limits admit: an interval the
 * kernel would refuse is **absent** rather than disabled (D4), and a view
 * with no interval to choose gets no `▾` at all rather than a chevron that
 * opens on the state it is already in.
 *
 * Choosing writes `refresh.interval` into the draft and applies it, which is
 * the edit-then-apply path sorting and column changes take (D3's exception):
 * the runtime's timer reads `applied`, so an edit that stopped short of
 * applying would change the config and nothing else. While an interval is in
 * force the button wears the cadence — it says *this view refreshes itself
 * every 30s*, a fact about the view rather than a fourth credential
 * competing with the three of D2, which say whether what is on screen has
 * been applied, run, or saved.
 *
 * The two halves therefore answer to two different ages of the same member,
 * and deliberately: the menu marks the **picked** interval, because that is
 * the editor's value and what a save would write, while the cadence says the
 * one **in force**, because it is a claim about what is happening. They
 * differ only while a draft the kernel refuses holds `apply` back — the
 * state the strip above the result is already explaining — and in it the
 * older number is the true one. Reading the draft in both places would put a
 * cadence on the button that nothing is running to.
 *
 * The UI invents no reason of its own for the timer to stop: the four the
 * runtime holds it for (`docs/design/runtime.md`) are the whole list.
 */
export function RefreshControl({
  refresh,
  variant = 'ghost',
  busy = false,
  note,
}: RefreshControlProps) {
  const messages = useViewMessages();
  const { interval, chosen, intervals, unsound } = refresh;
  // The credential answers to the interval in force, never to the picked
  // one: while a refused draft holds `apply` back the two differ, and what
  // the button claims is happening had better be what is happening.
  const cadence =
    interval === null ? null : refreshIntervalLabel(interval, messages);
  // Nothing on offer, nothing running and nothing to mend: a chevron here
  // would open a menu whose only item is the state the view is already in.
  // The last two matter when the limits leave no rung at all — one keeps the
  // way out of an interval that is running, the other the way out of a
  // `refresh` member admission refuses, which `Off` is what repairs.
  const choosable = intervals.length > 0 || interval !== null || unsound;

  return (
    <ButtonGroup
      data-slot="refresh-control"
      aria-label={messages.label('label.toolbar.freshness')}
    >
      <Button
        variant={variant}
        size="sm"
        data-slot="refresh-now"
        aria-label={messages.label('label.toolbar.refresh')}
        onClick={refresh.now}
        disabled={busy || refresh.loading}
        // Said as well as drawn: the cadence beside the word is a fragment,
        // and a screen reader reaching the button gets the sentence it
        // stands for rather than two words in a row.
        aria-description={
          cadence === null
            ? undefined
            : messages.label('label.refresh.on', { interval: cadence })
        }
      >
        {/* The vendored spinner announces itself as `status "Loading"` in
            English whatever the host's catalogue says, so the name is handed
            to it here, where the component is used. */}
        {refresh.loading ? (
          <Spinner aria-label={messages.label('label.status.loading')} />
        ) : (
          <RefreshCwIcon />
        )}
        {/* No word on the button (D12): a function is its icon, and the
            only text a control here carries is the state it reports — the
            cadence, when an interval is running. */}
        {cadence !== null && (
          <span
            data-slot="refresh-cadence"
            // Not a dot: the dot is "edited, not applied", and this is not
            // that. A number says which cadence as well as that there is
            // one, which a mark alone never could.
            className="text-muted-foreground text-xs tabular-nums"
          >
            {cadence}
          </span>
        )}
      </Button>

      {choosable && (
        <DropdownMenu>
          <DropdownMenuTrigger
            render={
              <Button
                variant={variant}
                size="icon-sm"
                data-slot="refresh-interval"
                // Not disabled while a query runs: an interval is a decision
                // about the next hour, and the runtime already replaces an
                // in-flight request when the applied config moves under it.
                aria-label={messages.label('label.refresh.auto')}
              />
            }
          >
            <ChevronDownIcon />
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            <DropdownMenuRadioGroup
              // The picked one, which is the editor's value — like the
              // layout switch or the page size, both of which read the
              // draft. What is marked is what a save would write.
              value={chosen === null ? OFF : String(chosen)}
              // Base UI types a radio group's value as `any`; naming the
              // parameter's type keeps that `any` out of this file, and
              // every value in the group is written by the two lines below.
              onValueChange={(next: string) =>
                refresh.setInterval(next === OFF ? null : Number(next))
              }
            >
              <DropdownMenuLabel>
                {messages.label('label.refresh.auto')}
              </DropdownMenuLabel>
              {note !== undefined && (
                <DropdownMenuLabel
                  data-slot="refresh-note"
                  className="text-muted-foreground max-w-56 font-normal whitespace-normal"
                >
                  {note}
                </DropdownMenuLabel>
              )}
              <DropdownMenuRadioItem value={OFF} closeOnClick>
                {messages.label('label.refresh.off')}
              </DropdownMenuRadioItem>
              {intervals.map(seconds => (
                <DropdownMenuRadioItem
                  key={seconds}
                  value={String(seconds)}
                  closeOnClick
                >
                  {refreshIntervalLabel(seconds, messages)}
                </DropdownMenuRadioItem>
              ))}
            </DropdownMenuRadioGroup>
          </DropdownMenuContent>
        </DropdownMenu>
      )}
    </ButtonGroup>
  );
}

/**
 * An interval as a person says it: whole hours as hours, whole minutes as
 * minutes, anything else in seconds.
 *
 * The units are three keys rather than one sentence with a unit word in it,
 * because a language that writes "1 分钟" where English writes "1 min" is
 * not translating a word but the whole phrase.
 */
export function refreshIntervalLabel(
  seconds: number,
  messages: MessageFormatters,
): string {
  if (seconds >= 3600 && seconds % 3600 === 0)
    return messages.label('label.refresh.hours', { count: seconds / 3600 });
  if (seconds >= 60 && seconds % 60 === 0)
    return messages.label('label.refresh.minutes', { count: seconds / 60 });
  return messages.label('label.refresh.seconds', { count: seconds });
}
