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
  FilterOperator,
  SortDirection,
  type FieldSort,
  type FilterPagedQuery,
} from '@ahoo-wang/fetcher-wow';
import { describe, expect, it, vi } from 'vitest';
import {
  builtinFieldKinds,
  compileRecord,
  RecordDataViewRuntime,
  DEFAULT_RUNTIME_LIMITS,
  FIRST_PAGE,
  RequestRunner,
  type DataViewDefinition,
  type ProjectedRecord,
  type RecordData,
  type RecordPageTarget,
  type RecordSort,
  type RecordViewRuntime,
  type ViewSource,
} from '../src/index.js';
import {
  nextTask,
  ordersDefinition,
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';

/**
 * A record page never repeats a row. Measured against a Wow compensation
 * service: paging by index over `eventTime DESC`, where many failed
 * executions share the millisecond, showed the same row on two pages — and
 * so left others off every page — in eleven scans out of twelve. The same
 * scan with the row key appended found none. These suites page through a
 * backend that behaves the way that one did.
 */

const context = { now: new Date('2026-09-16T10:30:00.000Z'), timeZone: 'UTC' };

const cursorDefinition = () =>
  ordersDefinition({
    record: { rowKey: 'id', paging: 'cursor', layouts: ['table'] },
  });

/** The sort `compileRecord` sends for this user sort. */
function sortOf(
  definition: DataViewDefinition,
  sort: RecordSort[],
  page: RecordPageTarget,
) {
  return compileRecord(
    definition,
    recordConfig({ sort }),
    builtinFieldKinds,
    context,
    page,
  ).sort;
}

/**
 * The row key is unique, so a sort that ends on it is a total order, and
 * the engine adds it because not every backend will: Wow adds a unique key
 * to cursor queries only.
 */
describe('the row key breaks ties', () => {
  it('ends every paged sort on the row key, ascending', () => {
    expect(sortOf(ordersDefinition(), [], FIRST_PAGE.paged)).toEqual([
      { field: 'id', direction: SortDirection.ASC },
    ]);
    expect(
      sortOf(ordersDefinition(), [{ field: 'amount', direction: 'DESC' }], {
        index: 3,
      }),
    ).toEqual([
      { field: 'amount', direction: SortDirection.DESC },
      { field: 'id', direction: SortDirection.ASC },
    ]);
  });

  it('ends a cursor sort on it too — one rule for both modes', () => {
    expect(
      sortOf(
        cursorDefinition(),
        [{ field: 'amount', direction: 'DESC' }],
        FIRST_PAGE.cursor,
      ),
    ).toEqual([
      { field: 'amount', direction: SortDirection.DESC },
      { field: 'id', direction: SortDirection.ASC },
    ]);
  });

  it('adds nothing when the sort names the row key already, either way round', () => {
    for (const direction of ['ASC', 'DESC'] as const) {
      const sort: RecordSort[] = [
        { field: 'id', direction },
        { field: 'amount', direction: 'DESC' },
      ];
      const expected = [
        { field: 'id', direction: SortDirection[direction] },
        { field: 'amount', direction: SortDirection.DESC },
      ];
      expect(sortOf(ordersDefinition(), sort, FIRST_PAGE.paged)).toEqual(
        expected,
      );
      expect(sortOf(cursorDefinition(), sort, FIRST_PAGE.cursor)).toEqual(
        expected,
      );
    }
  });

  it('breaks ties on a nested row key by its path', () => {
    const definition = ordersDefinition({
      fields: [
        ...ordersDefinition().fields,
        { name: 'state.id', label: 'ID', kind: 'string', sortable: true },
      ],
      record: { rowKey: 'state.id', paging: 'paged', layouts: ['table'] },
    });
    expect(
      sortOf(definition, [{ field: 'amount', direction: 'DESC' }], {
        index: 1,
      }),
    ).toEqual([
      { field: 'amount', direction: SortDirection.DESC },
      { field: 'state.id', direction: SortDirection.ASC },
    ]);
  });

  it('leaves the config it compiled from as the user wrote it', () => {
    const applied = recordConfig({
      sort: [{ field: 'amount', direction: 'DESC' }],
    });
    compileRecord(
      ordersDefinition(),
      applied,
      builtinFieldKinds,
      context,
      FIRST_PAGE.paged,
    );
    expect(applied.sort).toEqual([{ field: 'amount', direction: 'DESC' }]);
  });
});

/** Ten orders in two runs of equal amounts: the sort by amount ties. */
const ROWS: RecordData[] = Array.from({ length: 10 }, (_row, index) => ({
  id: `o-${index + 1}`,
  amount: index < 5 ? 20 : 10,
}));

const PAGE_SIZE = 3;

function compare(a: RecordData, b: RecordData, sort: FieldSort[]): number {
  for (const { field, direction } of sort) {
    const left = a[field] as string | number;
    const right = b[field] as string | number;
    if (left === right) continue;
    const order = left < right ? -1 : 1;
    return direction === SortDirection.DESC ? -order : order;
  }
  return 0;
}

/**
 * A paged backend that honours the sort it is given and promises nothing
 * about ties: each request meets the rows in another order before a stable
 * sort, so rows equal on every sort key land wherever they happen to fall —
 * which is what a document store does without a unique key in the sort.
 */
function unstableSource(): ViewSource {
  let calls = 0;
  return testSource({
    paged: vi.fn((query: FilterPagedQuery) => {
      calls += 1;
      const turn = (calls * 3) % ROWS.length;
      const met = [...ROWS.slice(turn), ...ROWS.slice(0, turn)];
      if (calls % 2 === 0) met.reverse();
      const sorted = met.sort((a, b) => compare(a, b, query.sort ?? []));
      const { index, size } = query.pagination ?? { index: 1, size: 20 };
      const start = (index - 1) * size;
      return Promise.resolve({
        total: ROWS.length,
        list: sorted.slice(start, start + size),
      });
    }),
  });
}

function open(source: ViewSource): RecordViewRuntime<'paged'> {
  return new RecordDataViewRuntime({
    id: 'runtime-1',
    definition: ordersDefinition(),
    config: recordConfig({
      pageSize: PAGE_SIZE,
      sort: [{ field: 'amount', direction: 'DESC' }],
    }),
    title: 'Mine',
    scope: 'personal',
    kinds: builtinFieldKinds,
    limits: { ...DEFAULT_RUNTIME_LIMITS, maxPageSize: PAGE_SIZE },
    environment: testEnvironment().environment,
    source,
    runner: new RequestRunner(),
  }) as unknown as RecordViewRuntime<'paged'>;
}

function keysOnScreen(runtime: RecordViewRuntime): unknown[] {
  const data = runtime.getSnapshot().result?.data as
    ProjectedRecord | undefined;
  return data?.view.rows.map(row => row.key) ?? [];
}

const PAGES = Math.ceil(ROWS.length / PAGE_SIZE);
const EVERY_ID = ROWS.map(row => row.id).sort();

describe('a sort that ties, paged by index', () => {
  /**
   * The fixture has to be able to fail: asked by amount alone, as the
   * engine used to ask, it hands one row out twice and another not at all.
   */
  it('repeats and skips rows when the query stops at the tied key', async () => {
    const source = unstableSource();
    const seen: unknown[] = [];
    for (let index = 1; index <= PAGES; index += 1) {
      const page = await source.paged({
        filter: { op: FilterOperator.MATCH_ALL },
        sort: [{ field: 'amount', direction: SortDirection.DESC }],
        pagination: { index, size: PAGE_SIZE },
      });
      seen.push(...page.list.map(row => row.id));
    }
    expect(new Set(seen).size).toBeLessThan(ROWS.length);
  });

  it('shows every row on exactly one page as the user pages through', async () => {
    const source = unstableSource();
    const runtime = open(source);
    const seen: unknown[] = [];

    runtime.apply();
    await nextTask();
    seen.push(...keysOnScreen(runtime));
    for (let index = 2; index <= PAGES; index += 1) {
      runtime.page({ index });
      await nextTask();
      seen.push(...keysOnScreen(runtime));
    }

    expect(seen).toHaveLength(ROWS.length);
    expect([...seen].sort()).toEqual(EVERY_ID);
    // The order the user asked for still leads; the key only breaks ties.
    expect(seen.slice(0, 5).every(id => Number(String(id).slice(2)) <= 5)).toBe(
      true,
    );
    runtime.dispose();
  });

  it('exports every row once, however the backend meets the ties', async () => {
    const runtime = open(unstableSource());

    const exported = await runtime.exportRows();

    expect(exported.rows.map(row => row.id).sort()).toEqual(EVERY_ID);
    runtime.dispose();
  });
});
