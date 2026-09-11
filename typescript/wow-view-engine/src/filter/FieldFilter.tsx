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
import type { FilterField, FilterOption } from './filterTypes.js';
import { XIcon } from 'lucide-react';
import { FilterSelect } from './FilterSelect.js';
import {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupText,
} from '../components/ui/input-group.js';

export interface FieldFilterProps<Operator extends string = string> {
  field: FilterField;
  operator: Operator;
  operators: readonly FilterOption<Operator>[];
  onOperatorChange: (operator: Operator) => void;
  children?: ReactNode;
  onRemove?: () => void;
  disabled?: boolean;
}

export function FieldFilter<Operator extends string>({
  field,
  operator,
  operators,
  onOperatorChange,
  children,
  onRemove,
  disabled = false,
}: FieldFilterProps<Operator>) {
  return (
    <fieldset
      disabled={disabled}
      className="fve-root fve:m-0 fve:min-w-0 fve:w-fit fve:max-w-full fve:border-0 fve:p-0 fve:in-data-[slot=filter-cell]:w-full"
    >
      <InputGroup
        aria-label={`${field.label}筛选`}
        className="fve:h-auto fve:min-h-8 fve:w-fit fve:max-w-full fve:flex-wrap fve:in-data-[slot=filter-cell]:w-full"
      >
        <InputGroupAddon>
          <InputGroupText>{field.label}</InputGroupText>
        </InputGroupAddon>
        {operators.length === 1 && operators[0].value === operator ? (
          <InputGroupText>{operators[0].label}</InputGroupText>
        ) : (
          <FilterSelect
            options={operators}
            value={operator}
            label={`${field.label}操作`}
            onValueChange={onOperatorChange}
            inline
            disabled={disabled}
          />
        )}
        {children}
        {onRemove && (
          <InputGroupAddon
            align="inline-end"
            className="fve:ml-auto fve:has-[>button]:mr-0"
          >
            <InputGroupButton
              size="icon-xs"
              aria-label={`删除${field.label}条件`}
              onClick={onRemove}
              disabled={disabled}
            >
              <XIcon aria-hidden="true" />
            </InputGroupButton>
          </InputGroupAddon>
        )}
      </InputGroup>
    </fieldset>
  );
}
