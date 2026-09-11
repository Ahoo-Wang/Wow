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

import type { FilterComponentProperties } from './filterModel.js';
import { FilterOperator as Op } from '@ahoo-wang/fetcher-wow';
import type {
  FilterEditorProps,
  FilterRegistration,
} from './filterReactTypes.js';
import { FilterChoiceSelect } from './FilterChoiceSelect.js';
import { FilterRemoteSelect } from './FilterRemoteSelect.js';
import { FilterTextValues } from './FilterTextValues.js';
import { FilterDateTimeRange } from './FilterDateTimeRange.js';
import {
  isFilterOptionValue,
  readFilterOptions,
} from './filterOptionSource.js';
import { getBuiltinFilterCompiler } from './builtinFilterCompilers.js';

function choices(props: FilterEditorProps) {
  return readFilterOptions(props.options?.items ?? props.field?.options ?? []);
}
function selected(props: FilterEditorProps) {
  return readFilterOptions(props.props.selectedOptions ?? []);
}
function values(props: FilterEditorProps) {
  const values = props.props.values ?? [];
  if (!Array.isArray(values) || !values.every(isFilterOptionValue))
    throw new TypeError('多选值无效');
  return [...new Set(values)];
}
function single(props: FilterEditorProps) {
  const value = props.props.value;
  if (value === undefined) return null;
  if (!isFilterOptionValue(value)) throw new TypeError('单选值无效');
  return value;
}
function LocalSingle(props: FilterEditorProps) {
  const value = single(props);
  return (
    <FilterChoiceSelect
      label={props.field?.label ?? '选择'}
      options={choices(props)}
      selectedOptions={selected(props)}
      values={value === null ? [] : [value]}
      inline
      disabled={props.disabled}
      invalid={!!props.errors?.length}
      errorId={props.errorId}
      onValuesChange={(values, items) =>
        props.onChange({
          ...props.props,
          value: values[0],
          selectedOptions: items.map(({ value, label }) => ({ value, label })),
        })
      }
    />
  );
}
function LocalMulti(props: FilterEditorProps) {
  return (
    <FilterChoiceSelect
      label={props.field?.label ?? '选择'}
      options={choices(props)}
      selectedOptions={selected(props)}
      values={values(props)}
      multiple
      inline
      disabled={props.disabled}
      invalid={!!props.errors?.length}
      errorId={props.errorId}
      onValuesChange={(values, items) =>
        props.onChange({
          ...props.props,
          values,
          selectedOptions: items.map(({ value, label }) => ({ value, label })),
        })
      }
    />
  );
}

function Remote({
  multiple,
  ...props
}: FilterEditorProps & { multiple: boolean }) {
  const name = props.options?.source;
  const source =
    typeof name === 'string' &&
    props.optionSources &&
    Object.prototype.hasOwnProperty.call(props.optionSources, name)
      ? props.optionSources[name]
      : undefined;
  if (!source) throw new TypeError('未注册候选数据源');
  const pageSize = props.options?.pageSize,
    debounceMs = props.options?.debounceMs;
  if (
    (pageSize !== undefined && typeof pageSize !== 'number') ||
    (debounceMs !== undefined && typeof debounceMs !== 'number')
  )
    throw new TypeError('候选配置必须使用数字分页大小与防抖时间');
  const common = {
    source,
    selectedOptions: selected(props),
    label: props.field?.label ?? '选择',
    disabled: props.disabled,
    invalid: !!props.errors?.length,
    errorId: props.errorId,
    inline: true,
    pageSize,
    debounceMs,
  };
  return multiple ? (
    <FilterRemoteSelect
      {...common}
      multiple
      values={values(props)}
      onValueChange={(values, items) =>
        props.onChange({
          ...props.props,
          values,
          selectedOptions: items.map(({ value, label }) => ({ value, label })),
        })
      }
    />
  ) : (
    <FilterRemoteSelect
      {...common}
      value={single(props)}
      onValueChange={(value, items) =>
        props.onChange({
          ...props.props,
          value: value ?? undefined,
          selectedOptions: items.map(({ value, label }) => ({ value, label })),
        })
      }
    />
  );
}

function RemoteSingle(props: FilterEditorProps) {
  return <Remote {...props} multiple={false} />;
}
function RemoteMulti(props: FilterEditorProps) {
  return <Remote {...props} multiple />;
}
function TextValues(props: FilterEditorProps) {
  const list = values(props);
  if (!list.every(value => typeof value === 'string'))
    throw new TypeError('多值文本只接受字符串');
  const rawText = props.props.rawText ?? '';
  if (typeof rawText !== 'string') throw new TypeError('文本缓冲必须是字符串');
  function change(values: string[], rawText: string) {
    const next: FilterComponentProperties = { ...props.props, values };
    delete next.rawText;
    props.onChange(rawText ? { ...next, rawText } : next);
  }
  return (
    <FilterTextValues
      label={props.field?.label ?? '文本'}
      value={list}
      disabled={props.disabled}
      invalid={!!props.errors?.length}
      errorId={props.errorId}
      rawText={rawText}
      onRawTextChange={text => change(list, text)}
      onValueChange={change}
    />
  );
}
function Range(props: FilterEditorProps) {
  if (!props.field) throw new TypeError('区间缺少字段');
  return (
    <FilterDateTimeRange
      field={props.field}
      showTime={props.options?.showTime === true}
      timeZone={props.timeZone}
      value={props.props}
      disabled={props.disabled}
      invalid={!!props.errors?.length}
      errorId={props.errorId}
      onValueChange={value => props.onChange({ ...props.props, ...value })}
    />
  );
}
const registrations: Readonly<Record<string, FilterRegistration>> = {
  select: {
    ...getBuiltinFilterCompiler('select')!,
    component: LocalSingle,
    modes: ['simple', 'advanced'],
    supports: (_, c) => [Op.EQ, Op.NE].includes(c.operator),
  },
  'multi-select': {
    ...getBuiltinFilterCompiler('multi-select')!,
    component: LocalMulti,
    modes: ['simple', 'advanced'],
    supports: (_, c) => [Op.IN, Op.NOT_IN].includes(c.operator),
  },
  'remote-select': {
    ...getBuiltinFilterCompiler('remote-select')!,
    component: RemoteSingle,
    modes: ['simple', 'advanced'],
    supports: (_, c) => [Op.EQ, Op.NE].includes(c.operator),
  },
  'remote-multi-select': {
    ...getBuiltinFilterCompiler('remote-multi-select')!,
    component: RemoteMulti,
    modes: ['simple', 'advanced'],
    supports: (_, c) => [Op.IN, Op.NOT_IN].includes(c.operator),
  },
  'text-values': {
    ...getBuiltinFilterCompiler('text-values')!,
    component: TextValues,
    modes: ['simple', 'advanced'],
    supports: (_, c) => [Op.IN, Op.NOT_IN].includes(c.operator),
  },
  'datetime-range': {
    ...getBuiltinFilterCompiler('datetime-range')!,
    component: Range,
    modes: ['simple', 'advanced'],
    supports: (_, c) =>
      c.operator === Op.BETWEEN &&
      ['date', 'datetime'].includes(c.field?.type ?? ''),
  },
};
export function getBuiltinFilterRegistration(name: string) {
  return Object.prototype.hasOwnProperty.call(registrations, name)
    ? registrations[name]
    : undefined;
}
