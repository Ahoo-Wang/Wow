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

import type { FilterValue } from '../../../model/index.js';
import {
  Select,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/select.js';
import { SelectContent } from '../../popups.js';

/** What every value control is given: the value in force, and where to put it. */
export interface ValueProps {
  value: FilterValue;
  onChange(value: FilterValue): void;
  label: string;
  disabled?: boolean;
}

/** One of a short, fixed list of answers. */
export function ChoiceValue({
  label,
  disabled,
  value,
  items,
  placeholder,
  onChange,
}: {
  label: string;
  disabled?: boolean;
  /** `null` shows the placeholder: nothing has been chosen yet. */
  value: string | null;
  items: { label: string; value: string }[];
  placeholder?: string;
  onChange(value: string): void;
}) {
  return (
    <Select
      items={items}
      value={value}
      disabled={disabled}
      onValueChange={next => onChange(String(next))}
    >
      <SelectTrigger aria-label={label} size="sm">
        <SelectValue placeholder={placeholder} />
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

/** Only scalars are shown as text; an object value has its own editor. */
export function scalarText(value: unknown): string {
  switch (typeof value) {
    case 'string':
      return value;
    case 'number':
    case 'boolean':
    case 'bigint':
      return value.toString();
    default:
      return '';
  }
}
