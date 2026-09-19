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

import type { FieldOption, FilterValue } from '../model/index.js';
import type { EditorDescriptor } from '../filter/index.js';
import { DateValue } from './filter/inputs/date.js';
import { NumberValue } from './filter/inputs/number.js';
import { RemoteValue } from './filter/inputs/remote.js';
import { BooleanValue, OptionValue } from './filter/inputs/select.js';
import { TextValue } from './filter/inputs/text.js';

/**
 * A number field that lets a half-typed number stay half typed. Lives with
 * the number input; re-exported here because the analysis editor uses it for
 * its own limits, which are numbers without being filter values.
 */
export { NumberInput } from './filter/inputs/number.js';

export interface FilterValueEditorProps {
  /** What the field's kind says this operator needs. */
  editor: EditorDescriptor;
  value: FilterValue;
  onChange(value: FilterValue): void;
  label: string;
  disabled?: boolean;
  /** Candidates for a `remote` editor; typed entry without one. */
  options?: FieldOption[];
}

/**
 * One value control, chosen by the descriptor the field kind produced.
 *
 * The kind decides the shape of a value and the editor it implies; this
 * component only renders that decision, which is why a custom kind needs no
 * change here beyond a renderer for a shape it invents.
 *
 * This switch is the one place that knows `EditorDescriptor.input` is a
 * closed union — every control behind it takes plain props and knows nothing
 * of the descriptor. That is the seam a per-kind renderer registry would be
 * cut along (docs/design/extension.md); until there is one, an unrecognised
 * shape falls through to a plain text input.
 */
export function FilterValueEditor({
  editor,
  value,
  onChange,
  label,
  disabled,
  options,
}: FilterValueEditorProps) {
  switch (editor.input) {
    case 'none':
      return null;

    case 'number':
      return (
        <NumberValue
          value={value}
          onChange={onChange}
          label={label}
          disabled={disabled}
          range={editor.range === true}
          multiple={editor.multiple === true}
        />
      );

    case 'boolean':
      return (
        <BooleanValue
          value={value}
          onChange={onChange}
          label={label}
          disabled={disabled}
        />
      );

    case 'select':
      return (
        <OptionValue
          label={label}
          disabled={disabled}
          value={value}
          multiple={editor.multiple === true}
          options={editor.options ?? []}
          onChange={onChange}
        />
      );

    case 'remote':
      return (
        <RemoteValue
          label={label}
          disabled={disabled}
          value={value}
          multiple={editor.multiple === true}
          options={options}
          onChange={onChange}
        />
      );

    case 'date':
    case 'dateRange':
    case 'relativeDate':
      return (
        <DateValue
          value={value}
          onChange={onChange}
          label={label}
          disabled={disabled}
          range={editor.input === 'dateRange'}
          withTime={editor.withTime === true}
        />
      );

    default:
      return (
        <TextValue
          value={value}
          onChange={onChange}
          label={label}
          disabled={disabled}
          multiple={editor.multiple === true}
        />
      );
  }
}
