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

import { act, cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { VirtualItem } from '@tanstack/react-virtual';
import type { AnalysisView } from '../src/index.js';
import { AnalysisTable } from '../src/ui/index.js';
import {
  VIRTUAL_ROWS_AFTER,
  bodySegments,
} from '../src/ui/analysis/virtualRows.js';

afterEach(cleanup);

/*
 * The browser half — scrolling, the keyboard reaching a row not drawn, the
 * sort's time, how few rows are drawn — is the story 「分析视图/长表/回归」;
 * jsdom lays nothing out, so here the page is the scroller at its default
 * size and every drawn row measures nothing tall.
 */

function longView(count: number, totals = true): AnalysisView {
  return {
    columns: [
      { alias: 'customer', label: 'Customer', role: 'group' },
      { alias: 'orders', label: 'Orders', role: 'metric' },
    ],
    rows: Array.from({ length: count }, (_, index) => ({
      customer: `C${index}`,
      orders: index,
    })),
    ...(totals ? { totals: { orders: count } } : {}),
    truncated: false,
  };
}

const item = (index: number, size = 10, margin = 0): VirtualItem => ({
  key: index,
  index,
  start: margin + index * size,
  end: margin + (index + 1) * size,
  size,
  lane: 0,
});

describe('bodySegments', () => {
  it('puts the room of the rows not drawn before and after the ones drawn', () => {
    expect(bodySegments([item(3, 10, 40), item(4, 10, 40)], 100, 40)).toEqual([
      { kind: 'gap', key: 'gap-3', height: 30 },
      { kind: 'row', index: 3, key: 3 },
      { kind: 'row', index: 4, key: 4 },
      { kind: 'gap', key: 'gap-end', height: 50 },
    ]);
  });

  it('keeps a row drawn away from the others where it stands', () => {
    // The row holding the Tab stop, scrolled far from the rows in view.
    expect(bodySegments([item(8), item(0), item(7)], 100, 0)).toEqual([
      { kind: 'row', index: 0, key: 0 },
      { kind: 'gap', key: 'gap-7', height: 60 },
      { kind: 'row', index: 7, key: 7 },
      { kind: 'row', index: 8, key: 8 },
      { kind: 'gap', key: 'gap-end', height: 10 },
    ]);
  });

  it('adds no room where the drawn rows reach the ends', () => {
    expect(bodySegments([item(0), item(1)], 20, 0)).toEqual([
      { kind: 'row', index: 0, key: 0 },
      { kind: 'row', index: 1, key: 1 },
    ]);
  });
});

describe('AnalysisTable, long', () => {
  const rows = (container: HTMLElement) =>
    container.querySelectorAll('tbody tr[data-index]');

  it(`draws every row of a result of ${VIRTUAL_ROWS_AFTER} groups`, () => {
    const { container } = render(
      <AnalysisTable view={longView(VIRTUAL_ROWS_AFTER)} />,
    );
    expect(rows(container)).toHaveLength(VIRTUAL_ROWS_AFTER);
    expect(
      container.querySelector('table')?.getAttribute('aria-rowcount'),
    ).toBeNull();
    expect(container.querySelector('[data-slot="row-gap"]')).toBeNull();
  });

  it('draws a longer result virtually and says how many rows it has', () => {
    const count = VIRTUAL_ROWS_AFTER + 1;
    const { container } = render(<AnalysisTable view={longView(count)} />);
    const drawn = rows(container);
    // Fewer than all: jsdom measures every drawn row as nothing tall, so
    // how few is the browser story's to say.
    expect(drawn.length).toBeGreaterThan(0);
    expect(drawn.length).toBeLessThan(count);
    // The header, the groups and the totals.
    expect(
      container.querySelector('table')?.getAttribute('aria-rowcount'),
    ).toBe(String(count + 2));
    expect(
      container.querySelector('thead tr')?.getAttribute('aria-rowindex'),
    ).toBe('1');
    expect(drawn[0]?.getAttribute('aria-rowindex')).toBe('2');
    expect(
      container
        .querySelector('[data-slot="totals-row"]')
        ?.getAttribute('aria-rowindex'),
    ).toBe(String(count + 2));
  });

  it('counts no totals row where there is none', () => {
    const count = VIRTUAL_ROWS_AFTER + 1;
    const { container } = render(
      <AnalysisTable view={longView(count, false)} />,
    );
    expect(
      container.querySelector('table')?.getAttribute('aria-rowcount'),
    ).toBe(String(count + 1));
  });

  it('draws every row while the page prints', () => {
    const count = VIRTUAL_ROWS_AFTER + 500;
    const { container } = render(<AnalysisTable view={longView(count)} />);
    act(() => {
      window.dispatchEvent(new Event('beforeprint'));
    });
    expect(rows(container)).toHaveLength(count);
    expect(
      container.querySelector('table')?.getAttribute('aria-rowcount'),
    ).toBeNull();
    act(() => {
      window.dispatchEvent(new Event('afterprint'));
    });
    expect(rows(container).length).toBeLessThan(count);
  });
});
