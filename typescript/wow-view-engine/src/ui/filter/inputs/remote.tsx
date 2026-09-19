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
import { OptionValue } from './select.js';
import type { ValueProps } from './shared.js';
import { TextValue } from './text.js';

/**
 * A value the host looks up rather than the kind declaring it.
 *
 * The candidates arrive from outside, so until they do — a host that wires no
 * lookup for this key, or one that has not answered yet — the field is typed
 * rather than picked. Nothing is lost by typing an id that is already valid.
 */
export function RemoteValue({
  value,
  onChange,
  label,
  disabled,
  multiple,
  options,
}: ValueProps & { multiple: boolean; options?: FieldOption[] }) {
  return options ? (
    <OptionValue
      label={label}
      disabled={disabled}
      value={value}
      multiple={multiple}
      options={options}
      onChange={onChange}
    />
  ) : (
    <TextValue
      value={value}
      onChange={onChange}
      label={label}
      disabled={disabled}
      multiple={multiple}
    />
  );
}
