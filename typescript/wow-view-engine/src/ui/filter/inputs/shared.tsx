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

import type { ReactNode } from 'react';
import type { FilterValue } from '../../../model/index.js';
import {
  Select,
  SelectGroup,
  SelectItem,
  SelectValue,
} from '../../components/select.js';
import { SelectContent } from '../../popups.js';
import { useViewMessages } from '../../MessagesProvider.js';
import { PillSelectTrigger, type ControlChromeProps } from '../../variants.js';

/** What every value control is given: the value in force, and where to put it. */
export interface ValueProps {
  value: FilterValue;
  onChange(value: FilterValue): void;
  label: string;
  disabled?: boolean;
  /**
   * Whether the condition holding this value is refused.
   *
   * The pill draws itself invalid from its own `data-invalid`, which is a
   * border and nothing else: a reader who never sees the border hears a
   * perfectly ordinary control. Every control a value is typed or picked in
   * carries the mark, because the value is one answer however many boxes it
   * takes to say — a range is refused for being inverted, not for either end.
   */
  invalid?: boolean;
}

/**
 * One of a short, fixed list of answers.
 *
 * Inside a condition pill, which is where all but one of these stand, the
 * pill is the field and the select draws no chrome of its own (D12). The
 * one outside is the group block's and/or, which has nothing around it to
 * be that edge and asks for it back with `chrome="box"`.
 */
export function ChoiceValue({
  label,
  disabled,
  invalid,
  value,
  items,
  placeholder,
  chrome,
  onChange,
}: ControlChromeProps & {
  label: string;
  disabled?: boolean;
  invalid?: boolean;
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
      <PillSelectTrigger
        aria-label={label}
        aria-invalid={invalid}
        size="sm"
        chrome={chrome}
      >
        <SelectValue placeholder={placeholder} />
      </PillSelectTrigger>
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

/**
 * The two ends of a range, with the word that joins them between.
 *
 * Two boxes side by side are two answers; a range is one, and nothing on the
 * row said so — «金额 介于 100 ———— 5,000» read as two numbers the user had
 * been asked for separately. The separator is the same one the applied bar
 * prints between the ends of a range (`label.filter.range-join`), so the
 * condition is punctuated the same way wherever it is read. It is
 * `aria-hidden`: each box is already named for the end it holds
 * (`label.filter.range-from` / `range-to`), and a reader that also spoke the
 * tilde would hear punctuation in the middle of a form.
 *
 * Both ends are `flex-1` against it, so they share whatever the pill has left
 * and neither is the one that gives.
 */
export function RangeRow({ from, to }: { from: ReactNode; to: ReactNode }) {
  const messages = useViewMessages();
  return (
    <div className="flex min-w-0 items-center gap-2">
      {from}
      <span aria-hidden="true" className="text-muted-foreground shrink-0">
        {messages.label('label.filter.range-join')}
      </span>
      {to}
    </div>
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
