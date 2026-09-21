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

import type { FieldOption } from '../../../model/index.js';
import {
  Select,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/select.js';
import { SelectContent } from '../../popups.js';
import { DELETION_STATES, isDeletionState } from '../../../filter/index.js';
import { deletionLabel } from '../../display.js';
import { useViewMessages } from '../../MessagesProvider.js';
import { ChoiceValue, scalarText, type ValueProps } from './shared.js';

/**
 * Yes or no, and neither.
 *
 * A blank leaf used to show "False", so a row the user had only just added
 * read as a condition already narrowing the list — and picking False, which
 * is what it looked like they had, changed nothing and fired no event. It
 * says `Not set` like every other blank value, rather than a word of its own.
 */
export function BooleanValue({
  value,
  onChange,
  label,
  disabled,
  invalid,
}: ValueProps) {
  const messages = useViewMessages();
  return (
    <ChoiceValue
      label={label}
      disabled={disabled}
      invalid={invalid}
      value={typeof value === 'boolean' ? String(value) : null}
      placeholder={messages.label('label.filter.not-set')}
      items={[
        { label: messages.label('label.boolean.true'), value: 'true' },
        { label: messages.label('label.boolean.false'), value: 'false' },
      ]}
      onChange={next => onChange(next === 'true')}
    />
  );
}

/**
 * Which records a soft-deleting source shows: the three readings of Wow's
 * `DELETION` filter, worded by the catalogue. Blank is the source's own
 * default, not deleted, which the applied bar says on its own (D17-2).
 */
export function DeletionValue({
  value,
  onChange,
  label,
  disabled,
  invalid,
}: ValueProps) {
  const messages = useViewMessages();
  return (
    <ChoiceValue
      label={label}
      disabled={disabled}
      invalid={invalid}
      value={isDeletionState(value) ? value : null}
      placeholder={messages.label('label.filter.not-set')}
      items={DELETION_STATES.map(state => ({
        label: messages.label(deletionLabel(state)),
        value: state,
      }))}
      onChange={next => onChange(next)}
    />
  );
}

/**
 * One or several of a declared list of options. The option's own value is
 * what is stored: the control speaks in text, and the text is mapped back.
 */
export function OptionValue({
  label,
  disabled,
  invalid,
  value,
  multiple,
  options,
  onChange,
}: ValueProps & { multiple: boolean; options: readonly FieldOption[] }) {
  const messages = useViewMessages();
  const items = options.map(option => ({
    label: option.label,
    value: String(option.value),
  }));
  const byText = new Map(options.map(option => [String(option.value), option]));
  const selected = (Array.isArray(value) ? value : [value])
    .map(scalarText)
    .filter(entry => entry.length > 0);

  return (
    <Select
      items={items}
      multiple={multiple}
      disabled={disabled}
      value={multiple ? selected : (selected[0] ?? null)}
      onValueChange={next => {
        const chosen = (Array.isArray(next) ? next : [next])
          .filter((entry): entry is string => typeof entry === 'string')
          .map(entry => byText.get(entry)?.value ?? entry);
        onChange(multiple ? chosen : (chosen[0] ?? null));
      }}
    >
      <SelectTrigger
        aria-label={label}
        aria-invalid={invalid}
        size="sm"
        className="min-w-40"
      >
        {/*
         * The placeholder belongs to the value, not to the root: `Select`
         * takes no such prop, so passing it there left a blank enum as an
         * empty `data-placeholder` span — a box saying nothing, beside a
         * text and a number field that both said `Not set`.
         */}
        <SelectValue placeholder={messages.label('label.filter.not-set')} />
      </SelectTrigger>
      <SelectContent>
        <SelectGroup>
          {items.map(item => (
            <SelectItem key={item.value} value={item.value}>
              {item.label}
            </SelectItem>
          ))}
        </SelectGroup>
      </SelectContent>
    </Select>
  );
}
