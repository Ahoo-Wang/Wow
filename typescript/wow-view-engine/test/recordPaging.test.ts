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
import {
  clampPage,
  cursorPaging,
  lastPageInWindow,
  pagedPaging,
  pageWindow,
  projectRecord,
} from '../src/record/index.js';
import { DEFAULT_RUNTIME_LIMITS } from '../src/model/index.js';
import { ordersDefinition, recordConfig } from './fixtures.js';

const DEFAULT_WINDOW = DEFAULT_RUNTIME_LIMITS.maxPageWindow;

/**
 * What the pager reads, worked out once by the kernel from what ran.
 *
 * The case behind it is a real one: Wow over Elasticsearch refuses a paged
 * query whose `index × size` passes 10 000, and a pager that divided 624 100
 * rows by 20 offered 31 205 pages and served 500 of them.
 */
describe('pagedPaging', () => {
  it('counts every page when the source declares no window', () => {
    expect(pagedPaging({ index: 1, size: 20, total: 624_100 })).toEqual({
      mode: 'paged',
      index: 1,
      size: 20,
      total: 624_100,
      pages: 31_205,
      hasNext: true,
    });
  });

  it('stops at the last page inside the window, and says how far that is', () => {
    const paging = pagedPaging({
      index: 1,
      size: 20,
      total: 624_100,
      maxWindow: 10_000,
    });

    expect(paging.pages).toBe(500);
    expect(paging.reachable).toBe(10_000);
    expect(paging.hasNext).toBe(true);
    // The last reachable page has nowhere further to go.
    expect(
      pagedPaging({ index: 500, size: 20, total: 624_100, maxWindow: 10_000 })
        .hasNext,
    ).toBe(false);
  });

  /**
   * A page is reachable when `index × size` stays inside the window, so the
   * count rounds down: at 30 a page, page 334 would reach row 10 020. A total
   * the window does cover can therefore still lose its last page to it.
   */
  it('rounds the window down to whole pages', () => {
    const paging = pagedPaging({
      index: 1,
      size: 30,
      total: 9_995,
      maxWindow: 10_000,
    });

    expect(paging.pages).toBe(333);
    expect(paging.reachable).toBe(9_990);
  });

  it('says nothing about a window that covers every page', () => {
    const exact = pagedPaging({
      index: 1,
      size: 20,
      total: 10_000,
      maxWindow: 10_000,
    });
    expect(exact.pages).toBe(500);
    expect(exact).not.toHaveProperty('reachable');

    const inside = pagedPaging({
      index: 1,
      size: 20,
      total: 4_321,
      maxWindow: 10_000,
    });
    expect(inside.pages).toBe(217);
    expect(inside).not.toHaveProperty('reachable');
  });

  it('counts an exact multiple without a trailing empty page', () => {
    const paging = pagedPaging({ index: 2, size: 20, total: 40 });

    expect(paging.pages).toBe(2);
    expect(paging.hasNext).toBe(false);
  });

  it('is one page when the total is less than a page', () => {
    const fewer = pagedPaging({ index: 1, size: 20, total: 7 });
    expect(fewer.pages).toBe(1);
    expect(fewer.hasNext).toBe(false);

    // Nothing matched is still one page to stand on, not zero.
    expect(pagedPaging({ index: 1, size: 20, total: 0 }).pages).toBe(1);
  });

  /**
   * Without a total nothing rules the next page out — except the window,
   * which bounds the request rather than the result.
   */
  it('takes a next page on trust without a total, up to the window', () => {
    const open = pagedPaging({ index: 3, size: 20 });
    expect(open).not.toHaveProperty('pages');
    expect(open.hasNext).toBe(true);

    expect(
      pagedPaging({ index: 499, size: 20, maxWindow: 10_000 }).hasNext,
    ).toBe(true);
    expect(
      pagedPaging({ index: 500, size: 20, maxWindow: 10_000 }).hasNext,
    ).toBe(false);
  });

  it('counts no pages against a size that means nothing', () => {
    const paging = pagedPaging({ index: 1, size: 0, total: 42 });
    expect(paging).not.toHaveProperty('pages');
    expect(paging.hasNext).toBe(true);
  });
});

describe('the pages a window allows', () => {
  it('is the last page whose rows stay inside it, and never below one', () => {
    expect(lastPageInWindow(20, 10_000)).toBe(500);
    expect(lastPageInWindow(30, 10_000)).toBe(333);
    // A page larger than the window still asks for the first page: the
    // source says whether it serves it, as a failed query.
    expect(lastPageInWindow(200, 50)).toBe(1);
    expect(lastPageInWindow(20)).toBeUndefined();
    expect(lastPageInWindow(0, 10_000)).toBeUndefined();
  });

  it('clamps a jump to the pages the pager can reach', () => {
    const paging = pagedPaging({
      index: 1,
      size: 20,
      total: 624_100,
      maxWindow: 10_000,
    });

    expect(clampPage(paging, 600)).toBe(500);
    expect(clampPage(paging, 0)).toBe(1);
    expect(clampPage(paging, 42)).toBe(42);
    // No total, no end to hold a jump to.
    expect(clampPage(pagedPaging({ index: 1, size: 20 }), 600)).toBe(600);
  });
});

describe('cursorPaging', () => {
  it('has a next page exactly when there is a cursor', () => {
    expect(cursorPaging('c-2').hasNext).toBe(true);
    expect(cursorPaging(null).hasNext).toBe(false);
  });
});

/**
 * The projection reads the size off the config that ran and the window off
 * the definition, so the draft's page size can never reach the pager.
 */
describe('projectRecord paging', () => {
  it('carries the executed size and the declared window', () => {
    const definition = ordersDefinition({
      record: {
        rowKey: 'id',
        paging: 'paged',
        layouts: ['table', 'card'],
        maxWindow: 4,
      },
    });
    const view = projectRecord(
      definition,
      recordConfig({ pageSize: 2 }),
      { list: [{ id: 'o-1' }, { id: 'o-2' }], total: 6 },
      2,
    );

    expect(view.paging).toEqual({
      mode: 'paged',
      index: 2,
      size: 2,
      total: 6,
      pages: 2,
      hasNext: false,
      reachable: 4,
    });
  });

  it('stops at the window a default Wow server serves when none is declared (D42)', () => {
    // A Wow server's HTTP guard refuses a page reaching past row 10,000
    // (`maxPageWindow`): 624 100 rows at 20 a page are 500 reachable pages.
    const view = projectRecord(
      ordersDefinition(),
      recordConfig({ pageSize: 20 }),
      { list: [{ id: 'o-1' }], total: 624_100 },
      1,
    );
    expect(view.paging).toMatchObject({ pages: 500, reachable: 10_000 });
    // A declared window below it wins; a host that raised the guard raises
    // the runtime's with it.
    expect(
      pageWindow({ maxWindow: 4 }, { maxPageWindow: DEFAULT_WINDOW }),
    ).toBe(4);
    expect(pageWindow(undefined, { maxPageWindow: 50_000 })).toBe(50_000);
    expect(
      pageWindow(undefined, { maxPageWindow: Number.POSITIVE_INFINITY }),
    ).toBeUndefined();
    // A cursor has a position, not a window.
    expect(
      pageWindow({ paging: 'cursor' }, { maxPageWindow: DEFAULT_WINDOW }),
    ).toBeUndefined();
  });

  /**
   * The card layout is projected beside the columns, from the same field
   * facts, rather than resolved a second time by the controller (A8).
   */
  it('projects the card layout the way it projects columns', () => {
    const view = projectRecord(
      ordersDefinition(),
      recordConfig({
        card: {
          title: 'id',
          fields: ['amount', 'removed', 'status'],
          image: 'id',
          perRow: 2,
        },
      }),
      { list: [], total: 0 },
    );

    expect(view.card.title).toBe('id');
    expect(view.card.titleField).toMatchObject({ field: 'id', cell: 'string' });
    // A field the definition dropped is left out rather than a blank row.
    expect(view.card.fields.map(field => field.field)).toEqual([
      'amount',
      'status',
    ]);
    expect(view.card.fields[0]).toMatchObject({ label: 'Amount' });
    expect(view.card).toMatchObject({ image: 'id', perRow: 2 });
  });
});
