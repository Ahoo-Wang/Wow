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

import type { ComponentType } from 'react';
import type { CellRendererProps } from '../recordReactTypes.js';
import { formatRecordValue } from '../recordValueFormat.js';
import { readRecordValue } from '../recordValidation.js';
import {
  assertObject,
  assertText,
  assertPath,
} from '../../contracts/validation/validationPrimitives.js';
import { TextCell } from './TextCell.js';
import { TagsCell } from './TagsCell.js';
import { isCellValue } from './cellValue.js';
import {
  StatusCell,
  type CellTone,
  type CellStatusTone,
} from './StatusCell.js';
import { LinkCell } from './LinkCell.js';
import { DateTimeCell } from './DateTimeCell.js';
import { NumberCell } from './NumberCell.js';

function options(
  props: CellRendererProps,
  allowed: readonly string[],
): Record<string, unknown> {
  const result = props.options ?? {};
  assertObject(result, '单元格选项');
  for (const key of Object.keys(result))
    if (!allowed.includes(key))
      throw new TypeError(`不支持的单元格选项：${key}`);
  return result;
}
function booleanOption(value: unknown, label: string): boolean | undefined {
  if (value !== undefined && typeof value !== 'boolean')
    throw new TypeError(`${label} 必须是布尔值`);
  return value;
}
function TextRenderer(props: CellRendererProps) {
  const config = options(props, ['ellipsis', 'copyable']);
  return (
    <TextCell
      value={props.value}
      text={formatRecordValue(
        props.value,
        props.field,
        props.definition.timeZone,
      )}
      ellipsis={booleanOption(config.ellipsis, 'ellipsis')}
      copyable={booleanOption(config.copyable, 'copyable')}
    />
  );
}
function TagsRenderer(props: CellRendererProps) {
  const config = options(props, ['maxVisible']);
  if (
    config.maxVisible !== undefined &&
    (typeof config.maxVisible !== 'number' ||
      !Number.isInteger(config.maxVisible) ||
      config.maxVisible < 1)
  )
    throw new TypeError('maxVisible 必须是正整数');
  const value =
    isCellValue(props.value) ||
    (Array.isArray(props.value) && props.value.every(isCellValue))
      ? props.value
      : null;
  return (
    <TagsCell
      value={value}
      options={props.field.options}
      maxVisible={config.maxVisible}
    />
  );
}
const tones: readonly CellTone[] = [
  'neutral',
  'success',
  'warning',
  'danger',
  'info',
];
function StatusRenderer(props: CellRendererProps) {
  const config = options(props, ['tones']);
  let mapping: CellStatusTone[] | undefined;
  if (config.tones !== undefined) {
    if (!Array.isArray(config.tones)) throw new TypeError('tones 必须是数组');
    const values = new Set<string>();
    mapping = config.tones.map(item => {
      assertObject(item, '状态色');
      if (!isCellValue(item.value) || !tones.includes(item.tone as CellTone))
        throw new TypeError('状态色 value/tone 无效');
      const key = JSON.stringify(item.value);
      if (values.has(key)) throw new TypeError('状态色 value 重复');
      values.add(key);
      return { value: item.value, tone: item.tone as CellTone };
    });
  }
  return (
    <StatusCell
      value={isCellValue(props.value) ? props.value : null}
      options={props.field.options}
      tones={mapping}
    />
  );
}
function LinkRenderer(props: CellRendererProps) {
  const config = options(props, ['hrefField', 'newTab']);
  let href: string | null | undefined;
  if (config.hrefField !== undefined) {
    assertPath(config.hrefField, '链接字段');
    const target = readRecordValue(props.record, config.hrefField as string);
    href = typeof target === 'string' ? target : null;
  }
  return (
    <LinkCell
      value={props.value}
      text={formatRecordValue(
        props.value,
        props.field,
        props.definition.timeZone,
      )}
      href={href}
      newTab={booleanOption(config.newTab, 'newTab')}
    />
  );
}
function DateTimeRenderer(props: CellRendererProps) {
  const config = options(props, ['locale', 'dateStyle', 'timeStyle']);
  if (config.locale !== undefined) assertText(config.locale, 'locale');
  for (const key of ['dateStyle', 'timeStyle'])
    if (
      config[key] !== undefined &&
      !['full', 'long', 'medium', 'short'].includes(config[key] as string)
    )
      throw new TypeError(`${key} 无效`);
  const value =
    typeof props.value === 'string' ||
    typeof props.value === 'number' ||
    props.value instanceof Date
      ? props.value
      : null;
  return (
    <DateTimeCell
      value={value}
      type={props.field.type === 'date' ? 'date' : 'datetime'}
      timeZone={props.definition.timeZone}
      locale={config.locale}
      dateStyle={config.dateStyle as Intl.DateTimeFormatOptions['dateStyle']}
      timeStyle={config.timeStyle as Intl.DateTimeFormatOptions['timeStyle']}
    />
  );
}
function NumberRenderer(props: CellRendererProps) {
  options(props, []);
  return (
    <NumberCell
      value={typeof props.value === 'number' ? props.value : null}
      format={props.field.numberFormat}
    />
  );
}
export const BUILTIN_CELL_RENDERERS: Readonly<
  Record<string, ComponentType<CellRendererProps>>
> = {
  text: TextRenderer,
  tags: TagsRenderer,
  status: StatusRenderer,
  link: LinkRenderer,
  'date-time': DateTimeRenderer,
  number: NumberRenderer,
};
