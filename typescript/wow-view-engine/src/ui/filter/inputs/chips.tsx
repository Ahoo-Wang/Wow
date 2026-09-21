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

import { useState } from 'react';
import { Combobox as ComboboxPrimitive } from '@base-ui/react';
import { XIcon } from 'lucide-react';
import type { FilterValue } from '../../../model/index.js';
import { Button } from '../../components/button.js';
import {
  Combobox,
  ComboboxChip,
  ComboboxChips,
  ComboboxChipsInput,
  ComboboxEmpty,
  ComboboxItem,
  ComboboxList,
  ComboboxValue,
} from '../../components/combobox.js';
import { IconTooltip } from '../../IconButton.js';
import { useViewMessages } from '../../MessagesProvider.js';
import { ComboboxContent } from '../../popups.js';
import { isPlainEnter } from '../enter.js';

/**
 * A list of typed values that grows, as chips on Base UI's `Combobox`.
 *
 * `IN` / `NOT_IN` take as many values as are entered, and this is the one
 * control that collects them, for numbers and for text alike. It stands on
 * the primitive's `multiple` mode with `Chips`: every value is a chip with a
 * remove button named after it, Backspace in an empty entry takes the last
 * one back, the arrow keys walk the chips, and what is typed is offered
 * back as the one item of the popup — «Add 12» — so Enter, a click on it,
 * and leaving the field all commit on the same terms. D16 before this it was
 * a row of badges with a hand-written add button and no keyboard model.
 *
 * Three rules are this file's rather than the primitive's:
 *
 * - **Leaving the field commits.** Apply is a button elsewhere on the panel,
 *   and reaching for it blurs the entry first; a value typed and not yet
 *   added would be thrown away by the very click meant to run the query with
 *   it. The primitive clears its input when the popup closes for any reason
 *   but an item press, which would empty the field a moment before that
 *   blur — so that clear is cancelled, and `onBlur` reads what is still
 *   there.
 * - **Nothing half-made is offered.** An empty entry, half a number, or a
 *   value already in the list is not an item: the popup says why instead,
 *   and Enter commits nothing. The same value twice asks nothing more of the
 *   query and would name two remove buttons alike.
 * - **What is being typed belongs to the list it is typed into.** A list
 *   replaced from outside — the panel's discard, a config reload, another
 *   view opened — is a different list, and a draft kept across that
 *   replacement would be written back by the next blur: the discard would
 *   not have discarded. The draft is therefore keyed on the list it was
 *   typed over rather than synchronised in an effect.
 */
export function ValueChips<T extends string | number>({
  value,
  values,
  onChange,
  label,
  disabled,
  invalid,
  parse,
  unparsable,
  inputMode,
}: {
  /** The list in force, as the host handed it over: the draft's key. */
  value: FilterValue;
  /** The same list, as the values this control shows. */
  values: readonly T[];
  onChange(values: T[]): void;
  /** The list's own name; the entry and the popup are named after it. */
  label: string;
  disabled?: boolean;
  invalid?: boolean;
  /** What the typed text is as a value, or `null` while it is not one yet. */
  parse(text: string): T | null;
  /** Why a non-empty entry is not a value, for the popup to say. */
  unparsable: string;
  inputMode?: 'decimal' | 'text';
}) {
  const messages = useViewMessages();
  const [draft, setDraft] = useState<{
    text: string;
    over: FilterValue;
  } | null>(null);
  const [wantsOpen, setWantsOpen] = useState(false);
  const text = draft !== null && Object.is(value, draft.over) ? draft.text : '';
  const typed = parse(text);
  const held = typed !== null && values.includes(typed);
  const items: T[] = typed !== null && !held ? [typed] : [];
  // The popup has something to say only once something is typed: an entry
  // clicked into stays a field, not a menu of nothing.
  const open = wantsOpen && text.trim().length > 0;

  function commit(): void {
    if (items.length === 0) return;
    setDraft(null);
    onChange([...values, ...items]);
  }

  return (
    <Combobox
      multiple
      items={items}
      value={values as T[]}
      onValueChange={next => {
        setDraft(null);
        onChange(next);
      }}
      inputValue={text}
      onInputValueChange={(next, details) => {
        if (details.reason === 'input-clear' && !details.isItemPress) {
          details.cancel();
          return;
        }
        setDraft(next.length === 0 ? null : { text: next, over: value });
      }}
      open={open}
      onOpenChange={setWantsOpen}
      // The one item is the entry itself; matching it against the entry
      // would only ever lose it to whitespace.
      filter={null}
      autoHighlight
      disabled={disabled}
    >
      <ComboboxValue>
        {(selected: T[]) => (
          <ComboboxChips aria-label={label}>
            {selected.map(item => (
              <ComboboxChip
                key={item}
                aria-label={String(item)}
                showRemove={false}
              >
                {String(item)}
                {/* The registry's chip draws its ✕ with no name; the
                    primitive's own remove part, named after the value and
                    said on hover like every icon button here (D12). */}
                <IconTooltip
                  label={messages.label('label.filter.remove-value', {
                    value: String(item),
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
              aria-label={messages.label('label.filter.new-value-of', {
                field: label,
              })}
              aria-invalid={invalid}
              inputMode={inputMode}
              // A list with nothing in it yet is a normal editing state
              // rather than a mistake, so the box says what is missing;
              // once it holds chips they say it themselves.
              placeholder={
                selected.length === 0
                  ? messages.label('label.filter.not-set')
                  : undefined
              }
              onBlur={commit}
              onKeyDown={event => {
                // A modified Enter is somebody else's shortcut — the host
                // page's, most likely — and `FilterPanel` lets it by for
                // exactly that reason; a control that swallowed it would be
                // the one place in the panel where the host's shortcut
                // stops working.
                if (!isPlainEnter(event)) return;
                // `FilterPanel` applies the draft on an Enter from anywhere
                // inside it, except on a control that answers Enter itself.
                // This is one: one keystroke, one meaning, so the panel
                // never sees this press. With the popup open the primitive
                // takes the highlighted item; with it closed — Escape shut
                // it, or nothing was typed — the entry answers on its own.
                event.preventDefault();
                event.stopPropagation();
                if (!open) commit();
              }}
            />
          </ComboboxChips>
        )}
      </ComboboxValue>
      <ComboboxContent>
        <ComboboxEmpty>
          {held ? messages.label('label.filter.already-listed') : unparsable}
        </ComboboxEmpty>
        <ComboboxList
          aria-label={messages.label('label.filter.add-value-of', {
            field: label,
          })}
        >
          {(item: T) => (
            <ComboboxItem key={item} value={item}>
              {messages.label('label.filter.add-value', {
                value: String(item),
              })}
            </ComboboxItem>
          )}
        </ComboboxList>
      </ComboboxContent>
    </Combobox>
  );
}
