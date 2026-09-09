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

import type { BuiltinFilterProperties } from './filterReactTypes.js';
import type { ReactNode } from 'react';
import { StringComparison, TimeUnit } from '@ahoo-wang/fetcher-wow';
import { Settings2Icon } from 'lucide-react';
import type {
  FilterComponentConfig,
  FilterComponentProperties,
} from './filterModel.js';
import { FILTER_OPERATORS, stringOperators } from './filterOperators.js';
import { FilterSelect } from './FilterSelect.js';
import {
  InputGroup,
  InputGroupButton,
  InputGroupInput,
} from '../components/ui/input-group.js';
import {
  Popover,
  PopoverContent,
  PopoverTitle,
  PopoverTrigger,
} from '../components/ui/popover.js';

const stringOptions = [
  { value: StringComparison.CASE_SENSITIVE, label: '区分大小写' },
  { value: StringComparison.CASE_INSENSITIVE, label: '忽略大小写' },
];
const timeUnits = [
  { value: TimeUnit.NANOSECONDS, label: '纳秒' },
  { value: TimeUnit.MICROSECONDS, label: '微秒' },
  { value: TimeUnit.MILLISECONDS, label: '毫秒' },
  { value: TimeUnit.SECONDS, label: '秒' },
  { value: TimeUnit.MINUTES, label: '分钟' },
  { value: TimeUnit.HOURS, label: '小时' },
  { value: TimeUnit.DAYS, label: '天' },
];

export function Parameters({
  label,
  disabled,
  children,
}: {
  label: string;
  disabled?: boolean;
  children: ReactNode;
}) {
  return (
    <Popover>
      <PopoverTrigger
        render={<InputGroupButton size="icon-xs" />}
        aria-label={`${label}参数`}
        disabled={disabled}
      >
        <Settings2Icon aria-hidden="true" />
      </PopoverTrigger>
      <PopoverContent align="start">
        <PopoverTitle>{label}参数</PopoverTitle>
        {children}
      </PopoverContent>
    </Popover>
  );
}

export function FilterValueParameters({
  node,
  label,
  disabled,
  invalid,
  errorId,
  onChange,
}: {
  node: FilterComponentConfig;
  label: string;
  disabled?: boolean;
  invalid?: boolean;
  errorId?: string;
  onChange(node: FilterComponentConfig): void;
}) {
  const descriptor = FILTER_OPERATORS[node.operator];
  const stringOperation = stringOperators.includes(node.operator);
  const properties = node.props as BuiltinFilterProperties;
  const update = (patch: FilterComponentProperties) =>
    onChange({ ...node, props: { ...node.props, ...patch } });
  return (
    <>
      {(descriptor.relativeTime || stringOperation) && (
        <Parameters label={label} disabled={disabled}>
          {stringOperation && (
            <FilterSelect
              invalid={invalid}
              errorId={errorId}
              label="大小写比较"
              placeholder="默认比较方式"
              value={properties.stringComparison}
              options={stringOptions}
              disabled={disabled}
              onClear={() => update({ stringComparison: undefined })}
              onValueChange={stringComparison => update({ stringComparison })}
            />
          )}
          {descriptor.relativeTime && (
            <>
              <InputGroup>
                <InputGroupInput
                  aria-invalid={invalid || undefined}
                  aria-describedby={invalid ? errorId : undefined}
                  aria-label="日期格式"
                  placeholder="日期格式（未指定）"
                  value={properties.datePattern ?? ''}
                  disabled={disabled}
                  onChange={event =>
                    update({ datePattern: event.target.value || undefined })
                  }
                />
              </InputGroup>
              <FilterSelect
                invalid={invalid}
                errorId={errorId}
                label="时间单位"
                placeholder="默认时间单位"
                value={properties.timeUnit}
                options={timeUnits}
                disabled={disabled}
                onClear={() => update({ timeUnit: undefined })}
                onValueChange={timeUnit => update({ timeUnit })}
              />
            </>
          )}
        </Parameters>
      )}
    </>
  );
}
