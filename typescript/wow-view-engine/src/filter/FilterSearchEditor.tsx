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
import { SearchMode } from '@ahoo-wang/fetcher-wow';
import { XIcon } from 'lucide-react';
import type {
  FilterComponentConfig,
  FilterComponentProperties,
  FilterFieldDefinition,
} from './filterModel.js';
import { FilterSelect } from './FilterSelect.js';
import { Parameters } from './FilterValueParameters.js';
import { Button } from '../components/ui/button.js';
import {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupInput,
  InputGroupText,
} from '../components/ui/input-group.js';

export function FilterSearchEditor({
  node,
  fields,
  disabled,
  invalid,
  errorId,
  onChange,
}: {
  node: FilterComponentConfig;
  fields: readonly FilterFieldDefinition[];
  disabled?: boolean;
  invalid?: boolean;
  errorId?: string;
  onChange(node: FilterComponentConfig): void;
}) {
  const properties = node.props as BuiltinFilterProperties;
  const searchFields = Array.isArray(properties.fields)
    ? properties.fields
    : [];
  const update = (patch: FilterComponentProperties) =>
    onChange({ ...node, props: { ...node.props, ...patch } });
  return (
    <>
      <InputGroupInput
        aria-invalid={invalid || undefined}
        aria-describedby={invalid ? errorId : undefined}
        aria-label="搜索内容"
        value={properties.query ?? ''}
        placeholder="搜索内容"
        disabled={disabled}
        className="fve:w-40 fve:flex-none"
        onChange={event => update({ query: event.target.value || undefined })}
      />
      <Parameters label="搜索" disabled={disabled}>
        <FilterSelect
          invalid={invalid}
          errorId={errorId}
          label="搜索模式"
          placeholder="默认模式"
          value={properties.mode}
          disabled={disabled}
          options={[
            { value: SearchMode.TERMS, label: '词项' },
            { value: SearchMode.PHRASE, label: '短语' },
          ]}
          onClear={() => update({ mode: undefined })}
          onValueChange={mode => update({ mode })}
        />
        <InputGroupText>
          {properties.fields === undefined
            ? '默认搜索字段'
            : searchFields.length === 0
              ? '全部搜索字段'
              : '搜索字段'}
        </InputGroupText>
        {searchFields.map((path, index) => (
          <InputGroup key={index}>
            <InputGroupInput
              aria-invalid={invalid || undefined}
              aria-describedby={invalid ? errorId : undefined}
              aria-label={`搜索字段${index + 1}`}
              value={path}
              disabled={disabled}
              onChange={event =>
                update({
                  fields: searchFields.map((value, position) =>
                    position === index ? event.target.value : value,
                  ),
                })
              }
            />
            <InputGroupAddon align="inline-end">
              <InputGroupButton
                aria-label={`删除搜索字段${fields.find(item => item.field === path)?.label ?? path}`}
                disabled={disabled}
                size="icon-xs"
                onClick={() =>
                  update({
                    fields: searchFields.filter(
                      (_, position) => position !== index,
                    ),
                  })
                }
              >
                <XIcon aria-hidden="true" />
              </InputGroupButton>
            </InputGroupAddon>
          </InputGroup>
        ))}
        <FilterSelect
          invalid={invalid}
          errorId={errorId}
          label="添加搜索字段"
          placeholder="添加搜索字段"
          disabled={disabled}
          options={fields.map(item => ({
            value: item.field,
            label: item.label,
            disabled: searchFields.includes(item.field),
          }))}
          onValueChange={path => update({ fields: [...searchFields, path] })}
        />
        <Button
          variant="ghost"
          size="sm"
          disabled={disabled}
          onClick={() => update({ fields: undefined })}
        >
          使用默认搜索字段
        </Button>
        {properties.fields === undefined && (
          <Button
            variant="ghost"
            size="sm"
            disabled={disabled}
            onClick={() => update({ fields: [] })}
          >
            搜索全部字段
          </Button>
        )}
      </Parameters>
    </>
  );
}
