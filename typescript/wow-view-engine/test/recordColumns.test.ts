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

import { describe, expect, it } from 'vitest';
import type {
  RecordColumn,
  RecordViewConfig,
  ViewInstance,
} from '../src/index.js';
import {
  recordColumn,
  reordered,
  repinned,
  resized,
  shown,
  withColumnsShown,
} from '../src/react/recordColumns.js';
import {
  cycledSort,
  offeredPageSizes,
  repairing,
  summariesOf,
} from '../src/react/recordEdits.js';
import type { ViewRuntimeState } from '../src/index.js';
import { recordConfig } from './fixtures.js';

/**
 * What the record controller's commands work out before they write. They
 * were callbacks inside `useRecordTable` and could only be reached through a
 * rendered hook and a runtime; read here on their own, the rules they carry
 * are one assertion each (A4).
 */
describe('recordColumn', () => {
  it('leaves out every member that says nothing', () => {
    expect(recordColumn('total', {})).toEqual({ field: 'total' });
    expect(
      recordColumn('total', { width: null, pinned: false, hidden: false }),
    ).toEqual({ field: 'total' });
  });

  it('writes the members that do', () => {
    expect(
      recordColumn('total', { width: 120, pinned: true, hidden: true }),
    ).toEqual({ field: 'total', width: 120, pinned: true, hidden: true });
  });
});

describe('repinned / resized / shown', () => {
  const column: RecordColumn = {
    field: 'total',
    width: 120,
    pinned: true,
    hidden: true,
  };

  it('changes one member and keeps the rest', () => {
    expect(repinned(column, false)).toEqual({
      field: 'total',
      width: 120,
      hidden: true,
    });
    expect(resized(column, null)).toEqual({
      field: 'total',
      pinned: true,
      hidden: true,
    });
    expect(shown(column, true)).toEqual({
      field: 'total',
      width: 120,
      pinned: true,
    });
  });
});

describe('withColumnsShown', () => {
  const offered = new Set(['total', 'warehouse']);

  it('switches a known column off in place, keeping its width and pin', () => {
    expect(
      withColumnsShown(
        [{ field: 'total', width: 120, pinned: true }, { field: 'warehouse' }],
        ['warehouse'],
        offered,
      ),
    ).toEqual([
      { field: 'total', width: 120, pinned: true, hidden: true },
      { field: 'warehouse' },
    ]);
  });

  it('drops the two entries that have nowhere to come back to', () => {
    expect(
      withColumnsShown(
        [{ field: 'gone' }, { field: 'total' }, { field: 'total' }],
        ['total'],
        offered,
      ),
    ).toEqual([{ field: 'total' }]);
  });

  it('adds a named field the config does not know yet, once, at the end', () => {
    expect(
      withColumnsShown([{ field: 'total' }], ['total', 'warehouse'], offered),
    ).toEqual([{ field: 'total' }, { field: 'warehouse' }]);
  });
});

describe('reordered', () => {
  const columns: RecordColumn[] = [
    { field: 'total' },
    { field: 'warehouse', hidden: true },
    { field: 'placedAt' },
  ];

  it('puts the named columns first, in the order given', () => {
    expect(reordered(columns, ['placedAt', 'warehouse'])).toEqual([
      { field: 'placedAt' },
      { field: 'warehouse', hidden: true },
      { field: 'total' },
    ]);
  });

  it('ignores a name that is not a column, and a name said twice', () => {
    expect(reordered(columns, ['nobody', 'placedAt', 'placedAt'])).toEqual([
      { field: 'placedAt' },
      { field: 'total' },
      { field: 'warehouse', hidden: true },
    ]);
  });
});

describe('cycledSort', () => {
  it('cycles one column off, ascending, descending, off', () => {
    const first = cycledSort([], 'total', false);
    expect(first).toEqual([{ field: 'total', direction: 'ASC' }]);
    const second = cycledSort(first, 'total', false);
    expect(second).toEqual([{ field: 'total', direction: 'DESC' }]);
    expect(cycledSort(second, 'total', false)).toEqual([]);
  });

  it('joins the sort at the end and keeps an existing place', () => {
    const two = cycledSort(
      [{ field: 'placedAt', direction: 'ASC' }],
      'total',
      false,
    );
    expect(two).toEqual([
      { field: 'placedAt', direction: 'ASC' },
      { field: 'total', direction: 'ASC' },
    ]);
    expect(cycledSort(two, 'placedAt', false)).toEqual([
      { field: 'placedAt', direction: 'DESC' },
      { field: 'total', direction: 'ASC' },
    ]);
  });

  it('makes the cycled field the whole sort when exclusive', () => {
    expect(
      cycledSort([{ field: 'placedAt', direction: 'ASC' }], 'total', true),
    ).toEqual([{ field: 'total', direction: 'ASC' }]);
    expect(
      cycledSort([{ field: 'total', direction: 'DESC' }], 'total', true),
    ).toEqual([]);
  });
});

describe('offeredPageSizes', () => {
  it('cuts the ladder to the ceiling and folds the current size in', () => {
    expect(offeredPageSizes([20, 50, 100], 50, 20)).toEqual([20, 50]);
    expect(offeredPageSizes([20, 50], undefined, 500)).toEqual([20, 50, 500]);
  });

  it('offers the whole ladder without a ceiling, and no size for none', () => {
    expect(offeredPageSizes([20, 50], undefined, 0)).toEqual([20, 50]);
    expect(offeredPageSizes([], undefined, 0)).toEqual([]);
  });
});

describe('summariesOf', () => {
  const saved = (config: RecordViewConfig): ViewInstance => ({
    id: 'orders-1',
    definitionId: 'orders',
    title: 'Mine',
    scope: 'personal',
    revision: 'r1',
    config,
  });

  it('answers with the saved config’s own spelling of "none"', () => {
    expect(summariesOf([], saved(recordConfig({ summaries: [] })))).toEqual([]);
    expect(summariesOf([], saved(recordConfig()))).toBeUndefined();
    expect(summariesOf([], null)).toBeUndefined();
  });

  it('answers with the list itself when there is one', () => {
    const next = [{ field: 'total', fn: 'SUM' as const }];
    expect(summariesOf(next, null)).toBe(next);
  });
});

describe('repairing', () => {
  function state(
    draft: Partial<RecordViewConfig>,
  ): ViewRuntimeState<RecordViewConfig> {
    return {
      saved: null,
      draft: recordConfig(draft),
    } as ViewRuntimeState<RecordViewConfig>;
  }

  it('carries the sound form of a list the patch does not replace', () => {
    const unreadable = state({
      sort: [{ direction: 'ASC' }, { field: 'total', direction: 'ASC' }],
    } as Partial<RecordViewConfig>);
    expect(repairing({ pageSize: 50 }, unreadable)).toEqual({
      pageSize: 50,
      sort: [{ field: 'total', direction: 'ASC' }],
    });
  });

  it('leaves a list the patch already replaces to the patch', () => {
    const unreadable = state({
      sort: [{ direction: 'ASC' }],
    } as Partial<RecordViewConfig>);
    expect(repairing({ sort: [] }, unreadable)).toEqual({ sort: [] });
  });

  it('repairs the table and the summaries by the same rule', () => {
    const unreadable = state({
      summaries: [{ fn: 'SUM' }, { field: 'total', fn: 'SUM' }],
      table: { columns: [{ width: 1 }, { field: 'total' }] },
    } as Partial<RecordViewConfig>);
    expect(repairing({}, unreadable)).toEqual({
      summaries: [{ field: 'total', fn: 'SUM' }],
      table: { columns: [{ field: 'total' }] },
    });
  });

  it('says nothing about a list that read soundly', () => {
    expect(repairing({ pageSize: 20 }, state({}))).toEqual({ pageSize: 20 });
  });
});
