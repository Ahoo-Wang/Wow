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

import {
  filter,
  FilterOperator,
  SortDirection,
  StringComparison,
  type AggregationQuery,
  type CursorPage,
  type CursorQuery,
  type FilterExpression,
  type PagedList,
  type PagedQueryRequest,
} from '@ahoo-wang/fetcher-wow';
import {
  readRecordValue,
  type RecordData,
  type RecordQuerySource,
} from '@ahoo-wang/fetcher-view-engine';
export type DemoQuery = PagedQueryRequest | CursorQuery;
export interface QueryOptions {
  failFirstQuery?: boolean;
  failFirstSummary?: boolean;
  onQuery?(method: 'paged' | 'cursor', query: DemoQuery): void;
  onSummary?(query: AggregationQuery): void;
}
const pause = () => new Promise(resolve => setTimeout(resolve, 30));
function matches(record: RecordData, expression: FilterExpression): boolean {
  switch (expression.op) {
    case FilterOperator.MATCH_ALL:
      return true;
    case FilterOperator.AND:
      return expression.operands.every(child => matches(record, child));
    case FilterOperator.OR:
      return expression.operands.some(child => matches(record, child));
    case FilterOperator.ELEMENT_MATCH: {
      const items = readRecordValue(record, expression.field);
      return (
        Array.isArray(items) &&
        items.some(
          item =>
            item !== null &&
            typeof item === 'object' &&
            !Array.isArray(item) &&
            matches(item, expression.predicate),
        )
      );
    }
    case FilterOperator.CONTAINS: {
      const actual = readRecordValue(record, expression.field);
      if (typeof actual !== 'string') return false;
      return expression.stringComparison === StringComparison.CASE_INSENSITIVE
        ? actual.toLowerCase().includes(expression.value.toLowerCase())
        : actual.includes(expression.value);
    }
    case FilterOperator.EQ:
      return readRecordValue(record, expression.field) === expression.value;
    case FilterOperator.NE:
      return readRecordValue(record, expression.field) !== expression.value;
    case FilterOperator.IN:
      return expression.values.some(
        value => value === readRecordValue(record, expression.field),
      );
    case FilterOperator.NOT_IN:
      return !expression.values.some(
        value => value === readRecordValue(record, expression.field),
      );
    case FilterOperator.BETWEEN: {
      const actual = readRecordValue(record, expression.field);
      if (actual === null || actual === undefined) return false;
      if (
        typeof actual !== 'number' ||
        typeof expression.lowerBound !== 'number' ||
        typeof expression.upperBound !== 'number'
      )
        throw new Error('演示服务的范围比较仅支持数值或时间戳。');
      return actual >= expression.lowerBound && actual <= expression.upperBound;
    }
    case FilterOperator.GT:
    case FilterOperator.GTE:
    case FilterOperator.LT:
    case FilterOperator.LTE: {
      const actual = readRecordValue(record, expression.field);
      if (typeof actual !== 'number' || typeof expression.value !== 'number')
        throw new Error('演示服务的大小比较仅支持数值字段。');
      if (expression.op === FilterOperator.GT) return actual > expression.value;
      if (expression.op === FilterOperator.GTE)
        return actual >= expression.value;
      if (expression.op === FilterOperator.LT) return actual < expression.value;
      return actual <= expression.value;
    }
    default:
      throw new Error(`演示服务未实现操作 ${expression.op}。`);
  }
}

function compare(
  left: unknown,
  right: unknown,
  direction: SortDirection,
): number {
  // Unpaid timestamps stay last for either direction.
  if (left === null || left === undefined)
    return right === null || right === undefined ? 0 : 1;
  if (right === null || right === undefined) return -1;
  let result: number;
  if (typeof left === 'number' && typeof right === 'number')
    result = left - right;
  else if (typeof left === 'string' && typeof right === 'string')
    result = left.localeCompare(right, 'zh-CN');
  else throw new Error('演示服务仅支持字符串和数值排序。');
  return direction === SortDirection.ASC ? result : -result;
}

export function createOrderSource(
  read: () => RecordData[],
  options: QueryOptions = {},
) {
  let failNext = options.failFirstQuery ?? false;
  let failNextSummary = options.failFirstSummary ?? false;
  const onQuery = options.onQuery ?? (() => {});
  const onSummary = options.onSummary ?? (() => {});
  // ponytail: this small in-memory server supports only the operators advertised above; use a real QueryApi for production data.
  async function queryRecords(
    method: 'paged' | 'cursor',
    query: DemoQuery,
    abortController?: AbortController,
  ) {
    abortController?.signal.throwIfAborted();
    onQuery(method, structuredClone(query));
    await pause();
    abortController?.signal.throwIfAborted();
    if (failNext) {
      failNext = false;
      throw new Error('订单服务暂时不可用，请重试查询。');
    }
    if (!('filter' in query))
      throw new Error('演示服务只接收 Wow Filter 查询。');
    return read()
      .filter(record => matches(record, query.filter))
      .sort((left, right) => {
        for (const sort of query.sort ?? []) {
          const result = compare(
            readRecordValue(left, sort.field),
            readRecordValue(right, sort.field),
            sort.direction,
          );
          if (result) return result;
        }
        return 0;
      });
  }

  const source = {
    async aggregate<
      Row extends RecordData = RecordData,
      Fields extends string = string,
    >(
      query: AggregationQuery<string, Fields>,
      _attributes?: Record<string, unknown>,
      abortController?: AbortController,
    ): Promise<Row[]> {
      abortController?.signal.throwIfAborted();
      onSummary(structuredClone(query));
      await pause();
      abortController?.signal.throwIfAborted();
      if (failNextSummary) {
        failNextSummary = false;
        throw new Error('汇总服务暂时不可用，请重试汇总。');
      }
      if (query.groupBy?.length || query.elements?.length || query.sort?.length)
        throw new Error('演示服务仅支持无分组字段汇总');
      const matched = read().filter(record =>
        matches(record, query.filter ?? filter.matchAll()),
      );
      const result: RecordData = {};
      for (const metric of query.metrics) {
        if (metric.type !== 'NUMERIC' || metric.expression.type !== 'FIELD')
          throw new Error('演示服务仅支持数值字段汇总');
        const field = metric.expression.field;
        const values = matched
          .map(record => readRecordValue(record, field))
          .filter(
            (value): value is number =>
              typeof value === 'number' && Number.isFinite(value),
          );
        if (!values.length) {
          result[metric.alias] = null;
          continue;
        }
        const sum = values.reduce((sum, value) => sum + value, 0);
        result[metric.alias] =
          metric.function === 'SUM'
            ? sum
            : metric.function === 'AVG'
              ? sum / values.length
              : metric.function === 'MIN'
                ? Math.min(...values)
                : Math.max(...values);
      }
      return [result as Row];
    },
    async paged<T extends Partial<RecordData> = RecordData>(
      query: PagedQueryRequest,
      _attributes?: Record<string, unknown>,
      abortController?: AbortController,
    ): Promise<PagedList<T>> {
      const result = await queryRecords('paged', query, abortController);
      const { index = 1, size = 5 } = query.pagination ?? {};
      return {
        total: result.length,
        list: structuredClone(
          result.slice((index - 1) * size, index * size),
        ) as T[],
      };
    },
    async cursor<T extends Partial<RecordData> = RecordData>(
      query: CursorQuery,
      _attributes?: Record<string, unknown>,
      abortController?: AbortController,
    ): Promise<CursorPage<T>> {
      const result = await queryRecords('cursor', query, abortController);
      const token = query.cursor?.match(/^orders:(\d+)$/);
      if (query.cursor && !token) throw new Error('无效的订单游标。');
      const offset = token ? Number(token[1]) : 0;
      const end = offset + (query.size ?? 5);
      return {
        list: structuredClone(result.slice(offset, end)) as T[],
        nextCursor: end < result.length ? `orders:${end}` : null,
      };
    },
  } satisfies RecordQuerySource;
  return {
    ...source,
    failNextRead() {
      failNext = true;
    },
  };
}
