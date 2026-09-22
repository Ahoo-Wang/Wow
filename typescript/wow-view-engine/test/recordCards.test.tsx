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

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { RecordCards } from '../src/ui/index.js';
import { ViewSurface } from '../src/ui/ViewSurface.js';
import { recordTableController } from './fixtures/ui.js';

afterEach(cleanup);

/** Cards over one settled page of two rows, the amount as title and body. */
function cards(
  overrides: Parameters<typeof recordTableController>[0] = {},
  props: Partial<Parameters<typeof RecordCards>[0]> = {},
) {
  const table = recordTableController({
    card: {
      title: 'amount',
      titleField: { field: 'amount', label: 'Amount', kind: 'number' },
      fields: [{ field: 'amount', label: 'Amount', kind: 'number' }],
    },
    ...overrides,
  });
  render(
    <ViewSurface>
      <RecordCards table={table} {...props} />
    </ViewSurface>,
  );
  return table;
}

/**
 * The cards answer the four states the table answers (D18 V): nothing
 * before a first result, skeletons while the first is on its way, the empty
 * result when nothing matched, and the summaries whenever the table would
 * have drawn its footer. Before this the card layout was a grid of nothing
 * for every one of them.
 */
describe('RecordCards outside the rows', () => {
  it('draws nothing before a result and nothing is running', () => {
    cards({ rows: [], hasResult: false, status: 'idle' });
    expect(document.querySelector('[data-slot="record-cards"]')).toBeNull();
    expect(
      document.querySelector('[data-slot="record-cards-skeleton"]'),
    ).toBeNull();
  });

  it('draws skeleton cards while the first result is on its way', () => {
    cards({ rows: [], hasResult: false, status: 'loading', loading: true });
    expect(
      document.querySelector('[data-slot="record-cards-skeleton"]'),
    ).not.toBeNull();
    expect(document.querySelector('[data-slot="record-cards"]')).toBeNull();
  });

  it('says a result matched nothing, with the way out', () => {
    const onEmptyAction = vi.fn();
    cards({ rows: [] }, { hasConditions: true, onEmptyAction });
    expect(screen.getByText('Nothing to show')).toBeDefined();
    fireEvent.click(
      screen.getByRole('button', { name: 'Clear the conditions' }),
    );
    expect(onEmptyAction).toHaveBeenCalledTimes(1);
  });

  it('keeps the summaries under the cards, both scopes', () => {
    cards({
      summaries: {
        scope: 'total',
        cells: [{ field: 'amount', label: 'Amount', fn: 'SUM', value: 42 }],
      },
    });
    const summaries = document.querySelector(
      '[data-slot="record-summaries"][data-layout="card"]',
    );
    expect(summaries).not.toBeNull();
    expect(summaries?.querySelectorAll('[data-scope]')).toHaveLength(2);
    // The page row is added up from the rows on screen; the total is the
    // query's own answer.
    const values = [
      ...(summaries?.querySelectorAll('[data-slot="summary-value"]') ?? []),
    ].map(node => node.textContent);
    expect(values).toEqual(['3', '42']);
  });
});

describe('RecordCards read a value as the table does', () => {
  it("hands a host's renderer the same cell the table would", () => {
    const seen: string[] = [];
    cards(
      {},
      {
        renderCell: cell => {
          seen.push(
            `${cell.column.field}:${String(cell.value)}:${String(cell.column.sortable)}`,
          );
          return <em>{String(cell.value)}</em>;
        },
      },
    );
    // The title and the body field both go through it, with the column in
    // the table's shape — `sortable` said, so one renderer serves both.
    expect(seen).toContain('amount:1:false');
    expect(screen.getAllByText('1').length).toBeGreaterThan(0);
  });

  it('titles a card through the same reading as its column', () => {
    cards({
      card: {
        title: 'amount',
        titleField: {
          field: 'amount',
          label: 'Amount',
          kind: 'number',
          numberFormat: { style: 'currency', currency: 'CNY' },
        },
        fields: [],
      },
    });
    // A number formats as its field says rather than as a bare digit.
    expect(screen.getByText(/1\.00/)).toBeDefined();
  });

  it('falls back to the key when the title field is gone', () => {
    cards({ card: { title: 'removed', fields: [] } });
    expect(screen.getByText('o-1')).toBeDefined();
  });
});
