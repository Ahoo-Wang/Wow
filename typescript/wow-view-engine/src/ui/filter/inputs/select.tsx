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
import { useViewMessages } from '../../MessagesProvider.js';
import { ChoiceValue, scalarText, type ValueProps } from './shared.js';

/**
 * Yes or no, and neither.
 *
 * A blank leaf used to show "False", so a row the user had only just added
 * read as a condition already narrowing the list — and picking False, which
 * is what it looked like they had, changed nothing and fired no event.
 */
export function BooleanValue({ value, onChange, label, disabled }: ValueProps) {
  const messages = useViewMessages();
  return (
    <ChoiceValue
      label={label}
      disabled={disabled}
      value={typeof value === 'boolean' ? String(value) : null}
      placeholder={messages.label('label.filter.choose')}
      items={[
        { label: messages.label('label.boolean.true'), value: 'true' },
        { label: messages.label('label.boolean.false'), value: 'false' },
      ]}
      onChange={next => onChange(next === 'true')}
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
  value,
  multiple,
  options,
  onChange,
}: ValueProps & { multiple: boolean; options: readonly FieldOption[] }) {
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
      <SelectTrigger aria-label={label} size="sm" className="min-w-40">
        <SelectValue />
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
