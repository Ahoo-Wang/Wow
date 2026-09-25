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

import { useViewMessages } from '../../MessagesProvider.js';
import { PillInput } from '../../variants.js';
import { ValueChips } from './chips.js';
import { scalarText, type ValueProps } from './shared.js';

/** Typed text, as one value or as a list of them. */
export function TextValue({
  value,
  onChange,
  label,
  disabled,
  invalid,
  multiple,
  placeholder,
}: ValueProps & {
  multiple: boolean;
  /** What the empty box says instead of 「未设置」: a board's search, 「搜索…」. */
  placeholder?: string;
}) {
  const messages = useViewMessages();
  if (multiple)
    return (
      <ValueChips
        value={value}
        values={Array.isArray(value) ? value.map(scalarText) : []}
        onChange={onChange}
        label={label}
        disabled={disabled}
        invalid={invalid}
        // Text is a value as soon as it is not blank; the surrounding
        // whitespace is what a chip could never show and a query would ask
        // for literally.
        parse={text => (text.trim().length === 0 ? null : text.trim())}
        // Never said: every non-blank entry is a value.
        unparsable={messages.label('label.filter.not-set')}
      />
    );

  return (
    <PillInput
      aria-label={label}
      aria-invalid={invalid}
      disabled={disabled}
      value={scalarText(value)}
      // A condition with no value yet is a normal editing state rather than a
      // mistake, so the box says what is missing instead of standing empty
      // and looking finished.
      placeholder={placeholder ?? messages.label('label.filter.not-set')}
      onChange={event => onChange(event.target.value)}
    />
  );
}
