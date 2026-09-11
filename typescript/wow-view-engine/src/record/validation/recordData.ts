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

import type { RecordData, RecordKey } from '../../contracts/viewModel.js';
import { assertObject } from './validationPrimitives.js';

/** Dot paths use exact own-property segments, preserving null and falsey values. */
export function readRecordValue(record: RecordData, field: string): unknown {
  return field
    .split('.')
    .reduce<unknown>(
      (value, segment) =>
        value !== null &&
        typeof value === 'object' &&
        Object.prototype.hasOwnProperty.call(value, segment)
          ? (value as Record<string, unknown>)[segment]
          : undefined,
      record,
    );
}

export function getRecordKey(record: RecordData, field: string): RecordKey {
  const key = readRecordValue(record, field);
  if (
    typeof key !== 'string' &&
    !(typeof key === 'number' && Number.isFinite(key))
  )
    throw new Error(`记录缺少有效主键：${field}`);
  return key;
}

export function validateRecordRows(
  rows: unknown,
  rowKey: string,
): asserts rows is RecordData[] {
  if (!Array.isArray(rows)) throw new Error('查询结果 list 必须是数组');
  const keys = new Set<RecordKey>();
  for (const row of rows) {
    assertObject(row, '记录');
    const key = getRecordKey(row, rowKey);
    if (keys.has(key)) throw new Error(`查询结果包含重复主键：${String(key)}`);
    keys.add(key);
  }
}
