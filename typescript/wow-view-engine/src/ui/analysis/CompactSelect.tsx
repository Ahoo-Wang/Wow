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

import {
  Select,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../components/select.js';
import { SelectContent } from '../popups.js';

/**
 * The one select a tray card carries: a named choice among a few words —
 * a dimension's type or granularity, a metric's summary. One shape for all
 * of them, so a card reads as a card wherever it is.
 *
 * Named one of two ways: by `label`, where no word on the screen names it
 * — the visible word is the chosen item — or by the `FieldLabel` that
 * stands over it, through `id`, where one does: the word on the screen is
 * then the name, said once (U-16).
 */
export function CompactSelect<V extends string>({
  label,
  id,
  items,
  value,
  disabled,
  onChange,
}: (
  | {
      /** The control's accessible name, where nothing on screen says it. */
      label: string;
      id?: never;
    }
  | {
      /** The trigger's id, which a `FieldLabel`'s `htmlFor` names it by. */
      id: string;
      label?: never;
    }
) & {
  items: readonly { value: V; label: string }[];
  value: V;
  disabled?: boolean;
  onChange(value: V): void;
}) {
  return (
    <Select
      items={items}
      value={value}
      disabled={disabled}
      onValueChange={next => {
        if (typeof next === 'string') onChange(next);
      }}
    >
      <SelectTrigger id={id} aria-label={label} size="sm">
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
