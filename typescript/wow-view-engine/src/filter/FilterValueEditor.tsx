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
import { DeletionState, FilterOperator } from '@ahoo-wang/fetcher-wow';
import { PlusIcon, XIcon } from 'lucide-react';
import type {
  FilterComponentConfig,
  FilterComponentProperties,
  FilterJsonValue,
  FilterFieldDefinition,
} from './filterModel.js';
import { FILTER_OPERATORS, stringOperators } from './filterOperators.js';
import { ScalarEditor } from './FilterScalarEditor.js';
import {
  FilterDateTimeRange,
  type FilterDateTimeRangeProps,
} from './FilterDateTimeRange.js';
import { FilterSearchEditor } from './FilterSearchEditor.js';
import { FilterValueParameters } from './FilterValueParameters.js';
import { FilterSelect } from './FilterSelect.js';
import { FilterTimeInput } from './FilterTimeInput.js';
import {
  InputGroupButton,
  InputGroupInput,
  InputGroupText,
} from '../components/ui/input-group.js';

export interface FilterValueEditorProps {
  node: FilterComponentConfig;
  field?: FilterFieldDefinition;
  fields: readonly FilterFieldDefinition[];
  disabled?: boolean;
  showTime?: boolean;
  timeZone?: string;
  errors?: readonly string[];
  errorId?: string;
  onChange(node: FilterComponentConfig): void;
}

export function FilterValueEditor({
  node,
  field,
  fields,
  disabled,
  showTime = false,
  timeZone,
  errors = [],
  errorId,
  onChange,
}: FilterValueEditorProps) {
  const invalid = errors.length > 0;
  const descriptor = FILTER_OPERATORS[node.operator];
  if (!descriptor)
    return <InputGroupText role="alert">未知操作</InputGroupText>;
  const label = field?.label ?? descriptor.label;
  const stringOperation = stringOperators.includes(node.operator);
  const valueField =
    descriptor.category === 'root' || stringOperation
      ? { field: '', label, type: 'string' as const }
      : field;
  const properties = node.props as BuiltinFilterProperties;
  const values = Array.isArray(properties.values) ? properties.values : [];
  const update = (patch: FilterComponentProperties) =>
    onChange({ ...node, props: { ...node.props, ...patch } });
  let input: ReactNode;
  switch (descriptor.input) {
    case 'value':
      input = (
        <ScalarEditor
          showTime={showTime}
          timeZone={timeZone}
          invalid={invalid}
          errorId={errorId}
          label={`${label}值`}
          dateLabel={label}
          value={properties.value}
          field={valueField}
          nullable={
            node.operator === FilterOperator.EQ ||
            node.operator === FilterOperator.NE
          }
          disabled={disabled}
          onChange={value =>
            update({ value: value as FilterJsonValue | undefined })
          }
        />
      );
      break;
    case 'values':
      input = (
        <span className="fve:inline-flex fve:max-w-full fve:flex-wrap fve:items-center">
          {values.map((value, index) => (
            <span
              key={index}
              className="fve:inline-flex fve:max-w-full fve:flex-wrap fve:items-center"
            >
              <ScalarEditor
                showTime={showTime}
                timeZone={timeZone}
                invalid={invalid}
                errorId={errorId}
                label={`${label}值${index + 1}`}
                value={value}
                field={valueField}
                disabled={disabled}
                onChange={value =>
                  update({
                    values: values.map((current, position) =>
                      position === index ? (value as FilterJsonValue) : current,
                    ),
                  })
                }
              />
              <InputGroupButton
                aria-label={`删除${label}值${index + 1}`}
                disabled={disabled}
                size="icon-xs"
                onClick={() =>
                  update({
                    values: values.filter((_, position) => position !== index),
                  })
                }
              >
                <XIcon aria-hidden="true" />
              </InputGroupButton>
            </span>
          ))}
          <InputGroupButton
            aria-label={`添加${label}值`}
            disabled={disabled}
            onClick={() =>
              update({
                values: [
                  ...values,
                  {
                    type:
                      valueField?.type === 'number'
                        ? 'number'
                        : valueField?.type === 'boolean'
                          ? 'boolean'
                          : 'string',
                  },
                ],
              })
            }
          >
            <PlusIcon data-icon="inline-start" aria-hidden="true" />
            添加值
          </InputGroupButton>
        </span>
      );
      break;
    case 'between':
      if (field?.type === 'date' || field?.type === 'datetime') {
        input = (
          <FilterDateTimeRange
            showTime={showTime}
            timeZone={timeZone}
            invalid={invalid}
            errorId={errorId}
            field={field}
            value={
              {
                lowerBound: properties.lowerBound,
                upperBound: properties.upperBound,
              } as FilterDateTimeRangeProps['value']
            }
            disabled={disabled}
            onValueChange={update}
          />
        );
        break;
      }
      input = (
        <>
          <ScalarEditor
            showTime={showTime}
            timeZone={timeZone}
            invalid={invalid}
            errorId={errorId}
            label={`${label}下限`}
            value={properties.lowerBound}
            field={field}
            disabled={disabled}
            onChange={lowerBound =>
              update({ lowerBound: lowerBound as FilterJsonValue | undefined })
            }
          />
          <InputGroupText>至</InputGroupText>
          <ScalarEditor
            showTime={showTime}
            timeZone={timeZone}
            invalid={invalid}
            errorId={errorId}
            label={`${label}上限`}
            value={properties.upperBound}
            field={field}
            disabled={disabled}
            onChange={upperBound =>
              update({ upperBound: upperBound as FilterJsonValue | undefined })
            }
          />
        </>
      );
      break;
    case 'search':
      input = (
        <FilterSearchEditor
          invalid={invalid}
          errorId={errorId}
          node={node}
          fields={fields}
          disabled={disabled}
          onChange={onChange}
        />
      );
      break;
    case 'deletion':
      input = (
        <FilterSelect
          invalid={invalid}
          errorId={errorId}
          label="删除状态"
          placeholder="未设置"
          value={properties.state}
          inline
          disabled={disabled}
          options={[
            { value: DeletionState.ACTIVE, label: '未删除' },
            { value: DeletionState.DELETED, label: '已删除' },
            { value: DeletionState.ALL, label: '全部' },
          ]}
          onClear={() => update({ state: undefined })}
          onValueChange={state => update({ state })}
        />
      );
      break;
    case 'time':
      input = (
        <FilterTimeInput
          invalid={invalid}
          errorId={errorId}
          label={`${label}时间`}
          value={
            properties.time === undefined ? undefined : String(properties.time)
          }
          disabled={disabled}
          inline
          onValueChange={time => update({ time: time || undefined })}
        />
      );
      break;
    case 'days':
      input = (
        <>
          <InputGroupInput
            aria-label={`${label}天数`}
            aria-invalid={invalid || undefined}
            aria-describedby={invalid ? errorId : undefined}
            value={properties.days ?? ''}
            placeholder="天数"
            inputMode="numeric"
            disabled={disabled}
            className="fve:w-20 fve:flex-none"
            onChange={event =>
              update({ days: event.target.value || undefined })
            }
          />
          <InputGroupText>天</InputGroupText>
        </>
      );
      break;
  }
  return (
    <>
      {input}
      <FilterValueParameters
        invalid={invalid}
        errorId={errorId}
        node={node}
        label={label}
        disabled={disabled}
        onChange={onChange}
      />
    </>
  );
}
