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

import { useState, type ReactNode } from 'react';
import { Combobox as ComboboxPrimitive } from '@base-ui/react';
import { XIcon } from 'lucide-react';
import type { FilterValue } from '../../../model/index.js';
import type { ValueCandidateSource } from '../../../runtime/index.js';
import { useValueCandidates } from '../../../react/index.js';
import { Button } from '../../components/button.js';
import {
  Combobox,
  ComboboxChip,
  ComboboxChipsInput,
  ComboboxEmpty,
  ComboboxItem,
  ComboboxList,
  ComboboxValue,
  useComboboxAnchor,
} from '../../components/combobox.js';
import { Spinner } from '../../components/spinner.js';
import { formatNumber } from '../../display.js';
import { IconTooltip } from '../../IconButton.js';
import { useViewMessages } from '../../MessagesProvider.js';
import { ComboboxContent } from '../../popups.js';
import { PillChips, PillComboboxInput } from '../../variants.js';
import { useSurfaceDisplay } from '../../ViewSurface.js';
import { isPlainEnter } from '../enter.js';
import { scalarText, type ValueProps } from './shared.js';

/**
 * A text value typed, or picked from the values the data holds.
 *
 * The field's values come from its `ValueCandidateSource` — the records
 * counted by value, the most frequent first — and each is listed with how
 * many records hold it, so the choice is made knowing what it will find.
 * The list is a help and not a fence: what is typed stays a value whether or
 * not the list holds it, because the list is the most frequent values and
 * not necessarily all of them, and the one wanted may be rare.
 *
 * - **One value** (`EQ`, `NE`): the box holds the value itself, as a plain
 *   text box does — every keystroke is the value, Apply takes it — and the
 *   list under it offers what to type instead. Picking one writes it.
 * - **Several** (`IN`, `NOT_IN`): a chip per value, as `ValueChips` draws
 *   them; the list holds the field's values, and what is typed is offered
 *   after them to be added as it is. Leaving the box adds what was typed, for
 *   `ValueChips`'s reason: reaching for Apply must not throw it away.
 *
 * Nothing is asked for until the list opens (`useValueCandidates`); what is
 * typed narrows what is already listed at once, and the answer for it
 * follows once typing pauses.
 */
export function SuggestedValue({
  multiple,
  source,
  ...props
}: ValueProps & { multiple: boolean; source: ValueCandidateSource }) {
  return multiple ? (
    <SuggestedList {...props} source={source} />
  ) : (
    <SuggestedText {...props} source={source} />
  );
}

/** The candidates for `typed`, and the popup's lines around them. */
function useSuggestions(
  source: ValueCandidateSource,
  typed: string,
  open: boolean,
) {
  const messages = useViewMessages();
  const { locale } = useSurfaceDisplay();
  const candidates = useValueCandidates(source, typed, open);
  const text = typed.trim();
  // An answer for other text is narrowed here until the one for this text
  // arrives, so the list follows the keyboard rather than the network.
  const values =
    candidates.query === text
      ? candidates.values
      : candidates.values.filter(({ value }) =>
          value.toLowerCase().includes(text.toLowerCase()),
        );
  const counts = new Map(values.map(({ value, count }) => [value, count]));

  const item = (value: string): ReactNode => {
    const count = counts.get(value);
    return (
      <ComboboxItem
        key={value}
        value={value}
        aria-label={
          count === undefined
            ? messages.label('label.filter.add-value', { value })
            : messages.label('label.filter.value-count', { value, count })
        }
      >
        {count === undefined ? (
          messages.label('label.filter.add-value', { value })
        ) : (
          <>
            <span className="min-w-0 flex-1 truncate">{value}</span>
            <span
              data-slot="candidate-count"
              className="text-muted-foreground shrink-0 text-xs tabular-nums"
            >
              {formatNumber(count, undefined, locale)}
            </span>
          </>
        )}
      </ComboboxItem>
    );
  };

  const loading = candidates.status === 'loading';
  const footer = (
    <>
      {loading && (
        <div
          role="status"
          data-slot="candidate-status"
          className="text-muted-foreground flex items-center gap-2 px-2 py-1.5 text-xs"
        >
          <Spinner />
          {messages.label('label.filter.values-loading')}
        </div>
      )}
      {candidates.status === 'error' && (
        <div
          role="alert"
          data-slot="candidate-status"
          data-failed=""
          className="text-destructive flex items-center justify-between gap-2 px-2 py-1.5 text-xs"
        >
          <span className="min-w-0">
            {messages.label('label.filter.values-failed', {
              reason: candidates.reason ?? '',
            })}
          </span>
          <Button
            type="button"
            variant="ghost"
            size="xs"
            onClick={candidates.retry}
          >
            {messages.label('label.filter.candidates-retry')}
          </Button>
        </div>
      )}
      {candidates.status === 'success' &&
        !candidates.complete &&
        values.length > 0 && (
          <div
            data-slot="candidate-status"
            className="text-muted-foreground border-t px-2 py-1.5 text-xs"
          >
            {messages.label('label.filter.values-top')}
          </div>
        )}
    </>
  );
  // «No match» is an answer, not a pause: said once one came back empty.
  const empty = candidates.status === 'success' && (
    <ComboboxEmpty>{messages.label('label.filter.no-candidate')}</ComboboxEmpty>
  );
  return { values: values.map(({ value }) => value), item, footer, empty };
}

type SourcedProps = ValueProps & { source: ValueCandidateSource };

function SuggestedText({
  value,
  onChange,
  label,
  disabled,
  invalid,
  source,
}: SourcedProps) {
  const messages = useViewMessages();
  const [open, setOpen] = useState(false);
  // What was typed since the list opened, which is what narrows it: the value
  // already in the box is the answer so far, not a question, and opening on
  // it must still show every value to change it to.
  const [typed, setTyped] = useState('');
  const text = scalarText(value);
  const { values, item, footer, empty } = useSuggestions(source, typed, open);

  return (
    <Combobox
      items={values}
      value={values.includes(text) ? text : null}
      onValueChange={next => {
        if (typeof next === 'string') onChange(next);
      }}
      inputValue={text}
      onInputValueChange={(next, details) => {
        // Only a keystroke or a pick is the user's; anything else is the
        // primitive putting the box back to the selection, and the box holds
        // the value itself.
        if (details.reason !== 'input-change' && !details.isItemPress) {
          details.cancel();
          return;
        }
        if (details.reason === 'input-change') setTyped(next);
        onChange(next);
      }}
      open={open}
      onOpenChange={next => {
        setOpen(next);
        setTyped('');
      }}
      filter={null}
      autoHighlight
      disabled={disabled}
    >
      <PillComboboxInput
        aria-label={label}
        aria-invalid={invalid}
        placeholder={messages.label('label.filter.pick-or-type')}
        className="w-full"
      />
      <ComboboxContent className={VALUE_LIST}>
        {empty}
        <ComboboxList aria-label={label}>{item}</ComboboxList>
        {footer}
      </ComboboxContent>
    </Combobox>
  );
}

function SuggestedList({
  value,
  onChange,
  label,
  disabled,
  invalid,
  source,
}: SourcedProps) {
  const messages = useViewMessages();
  const anchor = useComboboxAnchor();
  const [open, setOpen] = useState(false);
  // What is being typed belongs to the list it is typed over, as in
  // `ValueChips`: a list replaced from outside forgets it.
  const [draft, setDraft] = useState<{
    text: string;
    over: FilterValue;
  } | null>(null);
  const text = draft !== null && Object.is(value, draft.over) ? draft.text : '';
  const picked = Array.isArray(value) ? value.map(scalarText) : [];
  const { values, item, footer, empty } = useSuggestions(source, text, open);
  const typed = text.trim();
  const fresh = typed.length > 0 && !picked.includes(typed);
  // The field's values first, what was typed last: Enter takes the first
  // value holding it, and a value no record holds yet is one arrow away.
  const items = fresh && !values.includes(typed) ? [...values, typed] : values;

  const write = (next: string[]) => {
    setDraft(null);
    onChange(next);
  };
  const commit = () => {
    if (fresh) write([...picked, typed]);
  };

  return (
    <Combobox
      multiple
      items={items}
      value={picked}
      onValueChange={write}
      inputValue={text}
      onInputValueChange={(next, details) => {
        if (details.reason === 'input-clear' && !details.isItemPress) {
          details.cancel();
          return;
        }
        setDraft(next.length === 0 ? null : { text: next, over: value });
      }}
      open={open}
      onOpenChange={setOpen}
      filter={null}
      autoHighlight
      disabled={disabled}
    >
      <PillChips ref={anchor} aria-invalid={invalid} data-slot="filter-chips">
        <ComboboxValue>
          {(selected: string[]) => (
            <>
              {selected.map(entry => (
                <ComboboxChip key={entry} aria-label={entry} showRemove={false}>
                  {entry}
                  <IconTooltip
                    label={messages.label('label.filter.remove-value', {
                      value: entry,
                    })}
                    render={
                      <ComboboxPrimitive.ChipRemove
                        data-slot="combobox-chip-remove"
                        className="-ml-1 opacity-50 hover:opacity-100"
                        render={<Button variant="ghost" size="icon-xs" />}
                      />
                    }
                  >
                    <XIcon className="pointer-events-none" />
                  </IconTooltip>
                </ComboboxChip>
              ))}
              <ComboboxChipsInput
                aria-label={label}
                aria-invalid={invalid}
                placeholder={
                  selected.length === 0
                    ? messages.label('label.filter.pick-or-type')
                    : undefined
                }
                onBlur={commit}
                onKeyDown={event => {
                  // With the list shut, Enter adds what was typed, and the
                  // panel never sees it; see `ValueChips`.
                  if (!isPlainEnter(event) || open) return;
                  event.preventDefault();
                  event.stopPropagation();
                  commit();
                }}
              />
            </>
          )}
        </ComboboxValue>
      </PillChips>
      <ComboboxContent anchor={anchor} className={VALUE_LIST}>
        {empty}
        <ComboboxList aria-label={label}>{item}</ComboboxList>
        {footer}
      </ComboboxContent>
    </Combobox>
  );
}

/**
 * The list is as wide as its longest value, at least as wide as the box it
 * drops from and at most a readable line: a condition's value box is
 * narrow, and a list held to its width cut every processor name to its
 * first three letters.
 */
const VALUE_LIST =
  'w-max min-w-(--anchor-width) max-w-[min(28rem,var(--available-width))]';
