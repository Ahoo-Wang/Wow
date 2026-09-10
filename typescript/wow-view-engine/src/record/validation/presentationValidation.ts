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

import type { DeepReadonly } from '../../lib/types.js';
import type {
  RecordTableConfig,
  RecordCardConfig,
  RecordPresentation,
  RecordPresentationDefaults,
  RecordSummaryFunction,
  ViewDefinition,
  ViewFieldDefinition,
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

export function validateRecordTableConfig(
  value: unknown,
  definition: DeepReadonly<ViewDefinition>,
): asserts value is RecordTableConfig {
  assertObject(value, '表格配置');
  const columns = value.columns;
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
export function validateRecordCardConfig(
  value: unknown,
  definition: DeepReadonly<ViewDefinition>,
): asserts value is RecordCardConfig {
  assertObject(value, '卡片配置');
  function field(config: unknown, title = false) {
    assertObject(config, '卡片字段');
    assertText(config.id, '卡片字段 ID');
    assertText(config.field, '卡片字段路径');
    if (
      !(title && config.field === definition.rowKey) &&
      !definition.fields.some(field => field.field === config.field)
    )
      throw new Error(`卡片引用了未知字段：${config.field}`);
    if (config.title !== undefined) assertText(config.title, '卡片字段名称');
    validateReference(config.renderer);
  }
  field(value.title, true);
  if (!Array.isArray(value.fields)) throw new Error('卡片摘要字段必须为数组');
  const ids = new Set<string>();
  for (const item of value.fields) {
    field(item);
    if (ids.has(item.id)) throw new Error(`卡片字段 ID 重复：${item.id}`);
    ids.add(item.id);
  }
  if (value.cover !== undefined) {
    assertObject(value.cover, '卡片封面');
    const coverField = value.cover.field;
    if (
      !definition.fields.some(
        field => field.field === coverField && field.type === 'string',
      )
    )
      throw new Error('卡片封面必须引用 string 字段');
  }
  if (value.actions !== undefined) {
    assertObject(value.actions, '卡片操作');
    if (
      value.actions.visible !== undefined &&
      typeof value.actions.visible !== 'boolean'
    )
      throw new Error('卡片操作 visible 必须为布尔值');
    validateReference(value.actions.renderer);
    if (!value.actions.renderer && !definition.recordActions?.row)
      throw new Error('卡片操作缺少行操作扩展');
  }
}
export function validateRecordPresentationDefaults(
  value: unknown,
  definition: DeepReadonly<ViewDefinition>,
): asserts value is RecordPresentationDefaults {
  assertObject(value, '展示配置');
  if (value.table !== undefined)
    validateRecordTableConfig(value.table, definition);
  if (value.card !== undefined)
    validateRecordCardConfig(value.card, definition);
}
export function validateRecordPresentation(
  value: unknown,
  definition: DeepReadonly<ViewDefinition>,
): asserts value is RecordPresentation {
  assertObject(value, '展示配置');
  if (value.layout !== 'table' && value.layout !== 'card')
    throw new Error('展示布局必须为 table 或 card');
  if (!definition.allowedLayouts.includes(value.layout))
    throw new Error(`视图定义不允许展示布局：${value.layout}`);
  validateRecordPresentationDefaults(value, definition);
  if (value.layout === 'table')
    validateRecordTableConfig(value.table, definition);
  else if (value.layout === 'card')
    validateRecordCardConfig(value.card, definition);
  else throw new Error('展示布局必须为 table 或 card');
}
