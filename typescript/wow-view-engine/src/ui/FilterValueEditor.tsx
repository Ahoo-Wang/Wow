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

import type { FieldKindId, FieldOption, FilterValue } from '../model/index.js';
import type { EditorDescriptor } from '../filter/index.js';
import { UnsupportedValue } from './filter/inputs/unsupported.js';
import { DateValue } from './filter/inputs/date.js';
import { NumberValue } from './filter/inputs/number.js';
import { RemoteValue } from './filter/inputs/remote.js';
import {
  BooleanValue,
  DeletionValue,
  OptionValue,
} from './filter/inputs/select.js';
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
  /**
   * The kind that produced the descriptor. Only the fallback reads it, to
   * name the kind in the sentence saying why nothing here can be edited.
   */
  kind: FieldKindId;
  value: FilterValue;
  onChange(value: FilterValue): void;
  label: string;
  disabled?: boolean;
  /**
   * Whether the condition this value belongs to has been refused, which the
   * controls carry as `aria-invalid`: the pill's own `data-invalid` is a
   * border, and a border is not something a screen reader reads out.
   */
  invalid?: boolean;
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
 * cut along (docs/design/extension.md); until there is one, a kind picks one
 * of the members, and a shape outside the union has no control at all. It
 * used to fall through to a plain text input, which was the one answer the
 * extension point promises not to give: a kind that invented a value shape
 * had it quietly overwritten with a string by an editor that did not
 * understand it. The fallback now reads the value out and says nothing can
 * edit it, and admission refuses the condition for the same reason
 * (`filter.kind.unknown-editor`), so Apply does not run it either.
 */
export function FilterValueEditor({
  editor,
  kind,
  value,
  onChange,
  label,
  disabled,
  invalid,
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
          invalid={invalid}
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
          invalid={invalid}
        />
      );

    case 'deletion':
      return (
        <DeletionValue
          value={value}
          onChange={onChange}
          label={label}
          disabled={disabled}
          invalid={invalid}
        />
      );

    case 'select':
      return (
        <OptionValue
          label={label}
          disabled={disabled}
          invalid={invalid}
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
          invalid={invalid}
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
          invalid={invalid}
          range={editor.input === 'dateRange'}
          withTime={editor.withTime === true}
        />
      );

    case 'text':
      return (
        <TextValue
          value={value}
          onChange={onChange}
          label={label}
          disabled={disabled}
          invalid={invalid}
          multiple={editor.multiple === true}
        />
      );

    case 'predicate':
      // A condition, not a value: `ConditionPill` draws it as a block over
      // the same group editor the outer filter uses, and never reaches this
      // switch with one. There is no value control to give it here, which is
      // why this is `null` and not the fallback below — the shape is
      // supported, just not by a value editor.
      return null;

    default:
      // Unreachable through the union, and reachable at runtime: the
      // descriptor comes from a kind an application wrote. See above.
      return <UnsupportedValue kind={kind} value={value} />;
  }
}
