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

import { useEffect, useRef, useState } from 'react';
import { CheckIcon, CopyIcon } from 'lucide-react';
import { cn } from 'cn';
import { IconButton } from './IconButton.js';
import { useViewMessages } from './MessagesProvider.js';

/**
 * How long a settled copy stands before the button offers again.
 *
 * Long enough to be read, short enough that the row does not keep a stale
 * "Copied" on it: the tick is an answer to a press, not a state of the
 * record.
 */
const SETTLE_MS = 1500;

/**
 * How the button comes and goes.
 *
 * Revealed on hover and on its own focus, because one of these per row would
 * otherwise put a column of grey icons down every table — the value is what
 * the column is for, and the means to take it away is not. Two rules keep
 * that from becoming a control nobody can reach:
 *
 * - **opacity, never `display: none`** — a hidden button is out of the tab
 *   order, and the keyboard is the one way in that has no hover at all. It
 *   is always focusable, and focusing it shows it (`focus-visible`, which the
 *   pointer does not trigger);
 * - **`(hover: hover)` guards the hiding rather than the showing** — on a
 *   touch screen there is no hover to reveal anything with, so the button is
 *   simply always there. Tailwind wraps `group-hover` in that same query, so
 *   hiding unconditionally and revealing under it would hide the button on a
 *   phone for good.
 *
 * Either group answers: the row (`group/row`, what the table and a card's
 * field row carry) and the cell itself (`group/copyable`, which `cellValue`
 * always draws — the readings are exported, and a host drawing one in its
 * own markup has no row of ours to hover).
 */
const REVEAL = [
  'transition-opacity',
  '[@media(hover:hover)]:opacity-0',
  'group-hover/row:opacity-100',
  'group-hover/copyable:opacity-100',
  'focus-visible:opacity-100',
];

/** What the last press settled as; `null` while the button is just offering. */
type Outcome = 'copied' | 'failed' | null;

export interface CopyButtonProps {
  /**
   * The text the clipboard is given — and the text the button is named
   * after, so a reader hears which of a page full of these it has landed on.
   * It is what the cell shows, not what the record holds: a copy that came
   * back as thirteen digits where the screen said a date would be a second,
   * quieter reading of the value.
   */
  value: string;
  /** Layout only; the button's own look is `ghost` / `icon-xs`. */
  className?: string;
}

/**
 * A value's own copy button.
 *
 * There is no `Copy` in the registry, so this is the one wrapper over
 * `IconButton` that pairs the clipboard with the two words it can come back
 * with. Everything else about it is already decided elsewhere: `IconButton`
 * gives it a name in one string said twice (tooltip and `aria-label`), and
 * the catalogue gives the wording.
 *
 * **The outcome is said, not only shown.** The word appears on the control
 * the user just pressed, and a screen reader does not re-read a button it is
 * already sitting on; a live region mounted with the settled sentence is how
 * `SaveActions` says the same kind of thing. It is mounted per outcome rather
 * than kept empty on every row — `DashboardGrid` gives the reason: a dozen
 * empty regions is a dozen things for a reader to walk past, and only the
 * button that was pressed ever has anything to say.
 */
export function CopyButton({ value, className }: CopyButtonProps) {
  const messages = useViewMessages();
  const [outcome, setOutcome] = useState<Outcome>(null);
  // One timer, and it is cleared when the row unmounts under it — a page
  // turn while the tick is up would otherwise set state on a gone button.
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(
    () => () => {
      if (timer.current !== null) clearTimeout(timer.current);
    },
    [],
  );

  const settle = (next: Exclude<Outcome, null>) => {
    setOutcome(next);
    if (timer.current !== null) clearTimeout(timer.current);
    timer.current = setTimeout(() => setOutcome(null), SETTLE_MS);
  };

  const copy = async () => {
    try {
      // Not every context has one: the API is secure-context only, so an
      // application served over plain HTTP has no `clipboard` at all, and a
      // permission the user refused rejects the write.
      await navigator.clipboard.writeText(value);
      settle('copied');
    } catch {
      settle('failed');
    }
  };

  const label =
    outcome === 'copied'
      ? messages.label('label.copied')
      : outcome === 'failed'
        ? messages.label('label.copy-failed')
        : messages.label('label.copy-of', { value });

  return (
    <>
      <IconButton
        label={label}
        variant="ghost"
        size="icon-xs"
        data-slot="cell-copy"
        data-outcome={outcome ?? undefined}
        className={cn(REVEAL, className)}
        onClick={() => void copy()}
      >
        {outcome === 'copied' ? <CheckIcon /> : <CopyIcon />}
      </IconButton>
      {outcome !== null && (
        <span
          data-slot="cell-copy-announcement"
          role="status"
          className="sr-only"
        >
          {label}
        </span>
      )}
    </>
  );
}
