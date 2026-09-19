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
import type { FilterValue } from '../../../model/index.js';
import { Input } from '../../components/input.js';
import { useViewMessages } from '../../MessagesProvider.js';
import { scalarText, type ValueProps } from './shared.js';

/** Typed text, as one value or as a comma-separated list of them. */
export function TextValue({
  value,
  onChange,
  label,
  disabled,
  multiple,
}: ValueProps & { multiple: boolean }) {
  const messages = useViewMessages();
  // A list is parsed on the way out, but the raw text stays on screen while
  // it is typed: re-deriving it from the parsed list would eat the comma
  // separating the values, and a second value could never be entered. The
  // draft lives only while the value in force is the very list it produced —
  // the reference a host feeds back. A replacement, equal or not, wins.
  const [draft, setDraft] = useState<{
    text: string;
    parsed: FilterValue;
  } | null>(null);
  const text =
    draft !== null && draft.parsed === value
      ? draft.text
      : Array.isArray(value)
        ? value.map(scalarText).join(', ')
        : scalarText(value);

  return (
    <Input
      aria-label={label}
      disabled={disabled}
      value={text}
      placeholder={
        multiple ? messages.label('label.filter.comma-separated') : undefined
      }
      onChange={event => {
        const raw = event.target.value;
        if (!multiple) {
          onChange(raw);
          return;
        }
        const parsed = raw
          .split(',')
          .map(part => part.trim())
          .filter(part => part.length > 0);
        setDraft({ text: raw, parsed });
        onChange(parsed);
      }}
    />
  );
}
