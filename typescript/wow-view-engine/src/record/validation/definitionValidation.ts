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

import { validateRecordPresentationDefaults } from './presentationValidation.js';
import { encodeViewResourceId } from '../viewServiceContract.js';
import { validateTimeZone } from '../../lib/timeZone.js';
import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import {
  type ViewDefinition,
  type ViewFieldDefinition,
} from '../recordModel.js';
import { RECORD_SUMMARY_LABELS } from '../recordPresentation.js';
import { formatRecordNumber } from '../recordValueFormat.js';
import {
  assertObject,
  assertText,
  assertPath,
  validateReference,
} from './validationPrimitives.js';

function validateFields(value: unknown) {
  if (!Array.isArray(value)) throw new Error('字段定义必须是数组');
  const names = new Set<string>();
  for (const field of value) {
    assertObject(field, '字段');
    assertText(field.field, '字段路径');
    assertPath(field.field, '字段路径');
    assertText(field.label, '字段名称');
    if (field.group !== undefined) assertText(field.group, '字段分组');
    if (names.has(field.field)) throw new Error(`字段重复：${field.field}`);
    names.add(field.field);
    if (
      field.type !== undefined &&
      !['string', 'number', 'boolean', 'date', 'datetime', 'array'].includes(
        String(field.type),
      )
    )
      throw new Error('字段类型不支持');
    if (field.sortable !== undefined && typeof field.sortable !== 'boolean')
      throw new Error('sortable 必须是布尔值');
    if (
      field.operators !== undefined &&
      (!Array.isArray(field.operators) ||
        field.operators.some(op => !Object.values(FilterOperator).includes(op)))
    )
      throw new Error('字段操作符不支持');
    if (field.options !== undefined) {
      if (!Array.isArray(field.options)) throw new Error('枚举选项必须是数组');
      const values = new Set<string>();
      for (const option of field.options) {
        assertObject(option, '枚举选项');
        assertText(option.label, '枚举选项名称');
        if (option.group !== undefined)
          assertText(option.group, '枚举选项分组');
        if (
          !['string', 'number', 'boolean'].includes(typeof option.value) ||
          (typeof option.value === 'number' && !Number.isFinite(option.value))
        )
          throw new Error('枚举选项值无效');
        const key = JSON.stringify(option.value);
        if (values.has(key)) throw new Error('枚举选项值重复');
        values.add(key);
        if (
          option.disabled !== undefined &&
          typeof option.disabled !== 'boolean'
        )
          throw new Error('枚举 disabled 必须是布尔值');
      }
    }
    if (field.numberFormat !== undefined) {
      assertObject(field.numberFormat, '数值格式');
      if (field.type !== 'number') throw new Error('只有数值字段支持数值格式');
      if (field.numberFormat.locale !== undefined)
        assertText(field.numberFormat.locale, '数值区域设置');
      formatRecordNumber(0, field as unknown as ViewFieldDefinition);
    }
    if (field.summaryFunctions !== undefined) {
      if (
        !Array.isArray(field.summaryFunctions) ||
        new Set(field.summaryFunctions).size !==
          field.summaryFunctions.length ||
        field.summaryFunctions.some(
          fn =>
            typeof fn !== 'string' ||
            !Object.prototype.hasOwnProperty.call(RECORD_SUMMARY_LABELS, fn) ||
            field.type !== 'number',
        )
      )
        throw new Error('字段汇总函数无效或不支持');
    }
    validateReference(field.editor);
    validateReference(field.cellRenderer);
    if (field.fields !== undefined) validateFields(field.fields);
  }
}

export function validateViewDefinition(
  value: unknown,
): asserts value is ViewDefinition {
  assertObject(value, '视图定义');
  assertText(value.id, '定义 ID');
  encodeViewResourceId(value.id);
  assertText(value.title, '定义名称');
  assertText(value.sourceId, '数据源 ID');
  assertPath(value.rowKey, '记录主键');
  if (value.timeZone !== undefined) {
    assertText(value.timeZone, '时区');
    validateTimeZone(value.timeZone);
  }
  if (
    !Array.isArray(value.allowedLayouts) ||
    !value.allowedLayouts.length ||
    new Set(value.allowedLayouts).size !== value.allowedLayouts.length ||
    value.allowedLayouts.some(layout => layout !== 'table' && layout !== 'card')
  )
    throw new Error('allowedLayouts 必须为非空且不重复的 table/card 数组');
  validateFields(value.fields);
  if (
    value.allowedOperators !== undefined &&
    (!Array.isArray(value.allowedOperators) ||
      value.allowedOperators.some(
        op => !Object.values(FilterOperator).includes(op),
      ))
  )
    throw new Error('定义操作符不支持');
  if (value.filterEditors !== undefined) {
    assertObject(value.filterEditors, '筛选扩展');
    for (const [op, editor] of Object.entries(value.filterEditors)) {
      if (!Object.values(FilterOperator).includes(op as FilterOperator))
        throw new Error('筛选扩展操作符不支持');
      validateReference(editor);
    }
  }
  if (value.recordActions !== undefined) {
    assertObject(value.recordActions, '业务操作');
    validateReference(value.recordActions.global);
    validateReference(value.recordActions.toolbar);
    validateReference(value.recordActions.row);
  }
  if (value.defaultPresentation !== undefined)
    validateRecordPresentationDefaults(
      value.defaultPresentation,
      value as unknown as ViewDefinition,
    );
}
