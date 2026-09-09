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

import { encodeViewResourceId } from '../viewServiceContract.js';
import { SortDirection } from '@ahoo-wang/fetcher-wow';
import { validateFilterConfiguration } from '../../filter/filterConfiguration.js';
import {
  type RecordSummaryFunction,
  type ViewDefinition,
  type ViewInstance,
  type ViewFieldDefinition,
} from '../recordModel.js';
import {
  RECORD_COLUMN_MAX_WIDTH,
  RECORD_COLUMN_MIN_WIDTH,
} from '../recordColumns.js';
import { getRecordSummaryFunctions } from '../recordPresentation.js';
import {
  assertObject,
  assertText,
  validateReference,
} from './validationPrimitives.js';

export function validateViewInstance(
  value: unknown,
  definition: ViewDefinition,
  expectedId?: string,
): asserts value is ViewInstance {
  assertObject(value, '视图实例');
  assertText(value.id, '实例 ID');
  encodeViewResourceId(value.id);
  if (expectedId !== undefined && value.id !== expectedId)
    throw new Error('返回的实例 ID 不匹配');
  if (value.definitionId !== definition.id)
    throw new Error('实例不属于当前视图定义');
  assertText(value.title, '实例名称');
  if (value.kind !== 'record') throw new Error('当前仅支持 record 视图');
  assertObject(value.scope, '实例范围');
  if (
    value.scope.type !== 'personal' &&
    !(
      value.scope.type === 'public' &&
      ['system', 'shared'].includes(String(value.scope.source))
    )
  )
    throw new Error('实例范围无效');
  if (value.revision !== undefined) assertText(value.revision, '实例 revision');
  assertObject(value.config, '实例配置');
  const config = value.config;
  if ('filter' in config) throw new Error('视图配置必须保存 filters 组件配置');
  validateFilterConfiguration(
    config.filters,
    definition.fields,
    definition.allowedOperators,
  );
  if (!Array.isArray(config.sort) || config.sort.length > 32)
    throw new Error('排序必须为最多 32 项的数组');
  const sorted = new Set<string>();
  for (const sort of config.sort) {
    assertObject(sort, '排序');
    assertText(sort.field, '排序字段');
    if (
      !definition.fields.some(
        field => field.field === sort.field && field.sortable === true,
      )
    )
      throw new Error(`字段不支持排序：${sort.field}`);
    if (
      ![SortDirection.ASC, SortDirection.DESC].includes(
        sort.direction as SortDirection,
      ) ||
      sorted.has(sort.field)
    )
      throw new Error('排序方向无效或字段重复');
    sorted.add(sort.field);
  }
  assertObject(config.pagination, '分页配置');
  if (
    !['paged', 'cursor'].includes(String(config.pagination.mode)) ||
    !Number.isSafeInteger(config.pagination.size) ||
    Number(config.pagination.size) <= 0
  )
    throw new Error('分页方式或每页数量无效');
  assertObject(config.presentation, '展示配置');
  if (config.presentation.layout !== 'table')
    throw new Error('当前仅支持 table 布局');
  assertObject(config.presentation.table, '表格配置');
  const columns = config.presentation.table.columns;
  if (!Array.isArray(columns) || !columns.length)
    throw new Error('请至少配置一列');
  const columnIds = new Set<string>();
  let visible = false;
  let summaryCount = 0;
  for (const column of columns) {
    assertObject(column, '列');
    assertText(column.id, '列 ID');
    if (columnIds.has(column.id)) throw new Error(`列 ID 重复：${column.id}`);
    columnIds.add(column.id);
    if (column.title !== undefined) assertText(column.title, '列名称');
    if (column.visible !== undefined && typeof column.visible !== 'boolean')
      throw new Error('列 visible 必须为布尔值');
    if (
      column.pinned !== undefined &&
      column.pinned !== false &&
      column.pinned !== 'left' &&
      column.pinned !== 'right'
    )
      throw new Error('列固定位置必须为 left、right 或 false');
    visible ||= column.visible !== false;
    if (
      column.width !== undefined &&
      (typeof column.width !== 'number' ||
        !Number.isFinite(column.width) ||
        column.width < RECORD_COLUMN_MIN_WIDTH ||
        column.width > RECORD_COLUMN_MAX_WIDTH)
    )
      throw new Error(
        `列宽必须在 ${RECORD_COLUMN_MIN_WIDTH}–${RECORD_COLUMN_MAX_WIDTH} 之间`,
      );
    validateReference(column.renderer);
    if (column.summary !== undefined) {
      const field = definition.fields.find(
        field => field.field === column.field,
      );
      if (
        column.kind !== 'field' ||
        !field ||
        !Array.isArray(column.summary) ||
        new Set(column.summary).size !== column.summary.length ||
        column.summary.some(
          summary =>
            !getRecordSummaryFunctions(field as ViewFieldDefinition).includes(
              summary as RecordSummaryFunction,
            ),
        )
      )
        throw new Error('列汇总函数无效或字段不支持');
      summaryCount += column.summary.length;
    }
    if (column.kind === 'field') {
      if (!definition.fields.some(field => field.field === column.field))
        throw new Error(`列引用了未知字段：${String(column.field)}`);
    } else if (column.kind === 'actions') {
      if (!column.renderer && !definition.recordActions?.row)
        throw new Error('操作列缺少行操作扩展');
    } else throw new Error('列类型不支持');
  }
  if (!visible) throw new Error('请至少显示一列');
  if (summaryCount > 64) throw new Error('最多配置 64 个汇总指标');
}

/** Shared list trust boundary for initial loading and uncertain-write reconciliation. */
export function readInstanceList(
  value: unknown,
  definition: ViewDefinition,
): ViewInstance[] {
  if (
    !value ||
    typeof value !== 'object' ||
    !('instances' in value) ||
    !Array.isArray(value.instances)
  )
    throw new Error('实例列表必须包含 instances 数组');
  const seen = new Set<string>();
  for (const instance of value.instances) {
    validateViewInstance(instance, definition);
    if (seen.has(instance.id)) throw new Error(`实例 ID 重复：${instance.id}`);
    seen.add(instance.id);
  }
  if (
    !('defaultInstanceId' in value) ||
    (value.defaultInstanceId !== null &&
      (typeof value.defaultInstanceId !== 'string' ||
        !seen.has(value.defaultInstanceId)))
  )
    throw new Error('默认视图必须为当前列表中的实例 ID 或 null');
  return value.instances;
}
