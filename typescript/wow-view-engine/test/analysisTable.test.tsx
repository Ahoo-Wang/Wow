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

import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { AnalysisView } from '../src/index.js';
import { AnalysisTable, ViewSurface, zhCN } from '../src/ui/index.js';

afterEach(cleanup);

describe('AnalysisTable', () => {
  const view: AnalysisView = {
    columns: [
      { alias: 'warehouse', label: 'Warehouse', role: 'group' },
      {
        alias: 'orders',
        label: 'Orders',
        role: 'metric',
        numberFormat: { style: 'decimal' },
        width: 120,
      },
    ],
    rows: [
      { warehouse: 'CN', orders: 2 },
      { warehouse: 'JP', orders: null },
    ],
  };

  it('renders the rows and formats the numbers', () => {
    render(<AnalysisTable view={view} />);

    expect(screen.getByText('CN')).toBeDefined();
    expect(screen.getByText('2')).toBeDefined();
  });

  it('puts the totals row in the footer', () => {
    render(<AnalysisTable view={{ ...view, totals: { orders: 3 } }} />);

    expect(screen.getByText('Total')).toBeDefined();
    expect(screen.getByText('3')).toBeDefined();
  });

  it('renders every value shape a row can hold', () => {
    render(
      <AnalysisTable
        view={{
          columns: [
            { alias: 'warehouse', label: 'Warehouse', role: 'group' },
            { alias: 'plain', label: 'Plain', role: 'metric' },
            { alias: 'flag', label: 'Flag', role: 'metric' },
            { alias: 'blob', label: 'Blob', role: 'metric' },
          ],
          rows: [
            {
              warehouse: 'CN',
              plain: 7,
              flag: false,
              blob: { nested: true },
            },
          ],
        }}
      />,
    );

    expect(screen.getByText('7')).toBeDefined();
    expect(screen.getByText('No')).toBeDefined();
    expect(screen.getByText('{"nested":true}')).toBeDefined();
  });

  it('shows a date bucket as the day it starts, and an enum key by its label', () => {
    const day = Date.UTC(2026, 8, 18);
    render(
      <ViewSurface locale="en-GB" timeZone="UTC">
        <AnalysisTable
          view={{
            columns: [
              {
                alias: 'day',
                label: 'Day',
                role: 'group',
                kind: 'datetime',
                cell: 'datetime',
                dateUnit: 'DAY',
              },
              {
                alias: 'status',
                label: 'Status',
                role: 'group',
                kind: 'enum',
                cell: 'enum',
                options: [{ value: 'FAILED', label: 'Failed' }],
              },
              { alias: 'orders', label: 'Orders', role: 'metric' },
            ],
            rows: [{ day, status: 'FAILED', orders: 2 }],
          }}
        />
      </ViewSurface>,
    );

    expect(
      screen.getByText(
        new Intl.DateTimeFormat('en-GB', {
          dateStyle: 'medium',
          timeZone: 'UTC',
        }).format(day),
      ),
    ).toBeDefined();
    expect(screen.getByText('Failed')).toBeDefined();
    expect(screen.getByText('2')).toBeDefined();
  });

  /**
   * The sentence is about the range, not about the analysis: "nothing to
   * aggregate" read as "this analysis computes nothing", which is never what
   * happened — the metrics are fine and no group matched.
   */
  it('says that no group matched', () => {
    render(<AnalysisTable view={{ ...view, rows: [] }} />);

    expect(screen.getByText('No groups match')).toBeDefined();
  });
});

/**
 * A metric column is titled by two parts the kernel hands over separately —
 * the field and the summary — because only a catalogue knows their order.
 * The alias used to be the only thing that told two summaries of one field
 * apart, and the alias is not a word anybody chose.
 */
describe('an analysis column header', () => {
  const view: AnalysisView = {
    columns: [
      { alias: 'wh', label: 'Warehouse', role: 'group' },
      { alias: 'm1', label: 'Amount', role: 'metric', fn: 'SUM' },
      { alias: 'm2', label: 'Amount', role: 'metric', fn: 'AVG' },
      { alias: 'm3', label: 'orders', role: 'metric', fn: 'COUNT' },
    ],
    rows: [{ wh: 'CN', m1: 10, m2: 5, m3: 2 }],
  };

  it('says the summary of the field, and tells two summaries apart', () => {
    render(<AnalysisTable view={view} />);

    const headers = screen
      .getAllByRole('columnheader')
      .map(cell => cell.textContent);
    expect(headers).toEqual([
      'Warehouse',
      'Sum of Amount',
      'Average of Amount',
      // A count counts records rather than summarising a field, so it is one
      // word and never the alias the query carried.
      'Record count',
    ]);
  });

  it('says the same two parts in the other language, in its own order', () => {
    render(
      <ViewSurface messages={zhCN} locale="zh-CN">
        <AnalysisTable view={view} />
      </ViewSurface>,
    );

    expect(
      screen.getAllByRole('columnheader').map(cell => cell.textContent),
    ).toEqual(['Warehouse', 'Amount 的 合计', 'Amount 的 平均', '记录数']);
  });
});

/**
 * What an aggregate is decides how it prints, not what it was computed from
 * (K5) — and it prints in the surface's language, not the machine's.
 */
describe('an analysis number', () => {
  const money = { style: 'currency', currency: 'CNY' } as const;

  it('reads a metric in its own format and the surface language', () => {
    render(
      <ViewSurface locale="zh-CN">
        <AnalysisTable
          view={{
            columns: [
              { alias: 'wh', label: 'Warehouse', role: 'group' },
              {
                alias: 'total',
                label: 'Amount',
                role: 'metric',
                fn: 'SUM',
                numberFormat: money,
              },
              {
                alias: 'orders',
                label: 'orders',
                role: 'metric',
                fn: 'COUNT',
                numberFormat: { maximumFractionDigits: 0 },
              },
              { alias: 'plain', label: 'Plain', role: 'metric', fn: 'AVG' },
            ],
            rows: [{ wh: 'CN', total: 1234, orders: 1200, plain: 1234.5 }],
          }}
        />
      </ViewSurface>,
    );

    // `zh-CN` writes the yuan sign; the machine's own language writes CN¥.
    expect(screen.getByText('¥1,234.00')).toBeDefined();
    // A count is grouped and whole, in nobody's currency.
    expect(screen.getByText('1,200')).toBeDefined();
    // A number with no declared format is still grouped.
    expect(screen.getByText('1,234.5')).toBeDefined();
  });
});
