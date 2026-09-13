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

import { ANALYSIS_LIMITS } from '../../analysis/analysisCapabilities.js';
import { MAX_ANALYSIS_ELEMENTS } from '../../analysis/analysisModel.js';
import { validateFilterJson } from '../../filter/filterConfigurationValidation.js';
import { validateRecordPresentationDefaults } from '../../record/validation/presentationValidation.js';
import { encodeViewResourceId } from '../viewServiceContract.js';
import { validateTimeZone } from '../../lib/timeZone.js';
import {
  FilterOperator,
  AggregationGroupType,
  AggregationFunction,
  AggregationDateUnit,
} from '@ahoo-wang/fetcher-wow';
import { type ViewDefinition } from '../viewModel.js';
import { RECORD_SUMMARY_LABELS } from '../../record/recordPresentation.js';
import { formatRecordNumber } from '../../record/recordValueFormat.js';
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
        // 校验器故意对未知类型值做 ToString 以校验其字符串形态。
        // eslint-disable-next-line @typescript-eslint/no-base-to-string
        String(field.type),
      )
    )
      throw new Error('字段类型不支持');
    if (field.sortable !== undefined && typeof field.sortable !== 'boolean')
      throw new Error('sortable 必须是布尔值');
    if (
      field.operators !== undefined &&
      (!Array.isArray(field.operators) ||
        field.operators.some(
          // 校验器故意逐个检查原始值是否为合法操作符，断言仅为类型表达。
          op => !Object.values(FilterOperator).includes(op as FilterOperator),
        ))
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
      formatRecordNumber(0, field);
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

function enumList(value: unknown, allowed: readonly string[], label: string) {
  if (
    !Array.isArray(value) ||
    new Set(value).size !== value.length ||
    value.some(item => typeof item !== 'string' || !allowed.includes(item))
  )
    throw new Error(`${label}无效`);
}
function validateAnalysisCapability(value: unknown, scoped = false) {
  assertObject(value, '分析能力');
  if (typeof value.count !== 'boolean' || !Array.isArray(value.fields))
    throw new Error('分析能力无效');
  if (value.expressions !== undefined && typeof value.expressions !== 'boolean')
    throw new Error('表达式能力必须为布尔值');
  const paths = new Set<string>();
  for (const field of value.fields) {
    assertObject(field, '分析字段');
    assertPath(field.field, '分析字段路径');
    if (paths.has(field.field as string)) throw new Error('分析字段重复');
    paths.add(field.field as string);
    enumList(field.groups, Object.values(AggregationGroupType), '分组能力');
    enumList(
      field.functions,
      Object.values(AggregationFunction),
      '数值函数能力',
    );
    if (field.dateUnits !== undefined)
      enumList(field.dateUnits, Object.values(AggregationDateUnit), '时间粒度');
    if (field.any !== undefined && typeof field.any !== 'boolean')
      throw new Error('代表值能力必须为布尔值');
    if (field.unit !== undefined) assertText(field.unit, '指标单位');
    if (field.numberFormat !== undefined) {
      assertObject(field.numberFormat, '分析数值格式');
      if (field.numberFormat.locale !== undefined)
        assertText(field.numberFormat.locale, '分析数值区域设置');
      formatRecordNumber(0, { numberFormat: field.numberFormat });
    }
  }
  if (value.limits !== undefined) {
    assertObject(value.limits, '分析限制');
    const maxima: Record<string, number> = ANALYSIS_LIMITS;
    for (const [name, limit] of Object.entries(value.limits)) {
      if (
        limit !== undefined &&
        (typeof limit !== 'number' ||
          !Number.isSafeInteger(limit) ||
          limit <= 0 ||
          !Object.prototype.hasOwnProperty.call(maxima, name) ||
          limit > maxima[name])
      )
        throw new Error('分析限制无效');
    }
  }
  if (value.scopes !== undefined) {
    if (scoped || !Array.isArray(value.scopes)) throw new Error('分析范围无效');
    const ids = new Set<string>();
    for (const scope of value.scopes) {
      assertObject(scope, '分析范围');
      assertText(scope.id, '分析范围 ID');
      assertText(scope.label, '分析范围名称');
      if (ids.has(scope.id)) throw new Error('分析范围重复');
      ids.add(scope.id);
      if (
        !Array.isArray(scope.elements) ||
        !scope.elements.length ||
        scope.elements.length > MAX_ANALYSIS_ELEMENTS
      )
        throw new Error('分析范围元素链无效');
      for (const element of scope.elements) {
        assertObject(element, '范围元素');
        assertPath(element.path, '元素路径');
        validateFields(element.fields);
      }
      validateFields(scope.fields);
      validateAnalysisCapability(scope.capability, true);
    }
  }
}

export function validateViewDefinition(
  value: unknown,
): asserts value is ViewDefinition {
  assertObject(value, '视图定义');
  assertText(value.id, '定义 ID');
  encodeViewResourceId(value.id);
  assertText(value.title, '定义名称');
  if (value.record || value.analysis || value.sourceId !== undefined)
    assertText(value.sourceId, '数据源 ID');
  if (value.dashboard !== undefined && value.dashboard !== true)
    throw new Error('dashboard 能力必须为 true');
  if (!value.record && !value.analysis && !value.dashboard)
    throw new Error('定义至少需要 record 或 analysis 或 dashboard 能力');
  if (value.record !== undefined) {
    assertObject(value.record, '记录能力');
    assertPath(value.record.rowKey, '记录主键');
  }
  if (value.analysis !== undefined) {
    validateFilterJson(value.analysis);
    validateAnalysisCapability(value.analysis);
  }
  if (value.timeZone !== undefined) {
    assertText(value.timeZone, '时区');
    validateTimeZone(value.timeZone);
  }
  if (
    value.record !== undefined &&
    (!Array.isArray(value.record.allowedLayouts) ||
      !value.record.allowedLayouts.length ||
      new Set(value.record.allowedLayouts).size !==
        value.record.allowedLayouts.length ||
      value.record.allowedLayouts.some(
        layout => layout !== 'table' && layout !== 'card',
      ))
  )
    throw new Error('allowedLayouts 必须为非空且不重复的 table/card 数组');
  validateFields(value.fields);
  if (
    value.allowedOperators !== undefined &&
    (!Array.isArray(value.allowedOperators) ||
      value.allowedOperators.some(
        // 校验器故意逐个检查原始值是否为合法操作符，断言仅为类型表达。
        op => !Object.values(FilterOperator).includes(op as FilterOperator),
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
  if (value.record?.recordActions !== undefined) {
    assertObject(value.record?.recordActions, '业务操作');
    validateReference(value.record.recordActions.global);
    validateReference(value.record.recordActions.toolbar);
    validateReference(value.record.recordActions.row);
  }
  if (value.record?.defaultPresentation !== undefined)
    validateRecordPresentationDefaults(
      value.record?.defaultPresentation,
      value as unknown as ViewDefinition,
    );
}
