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
import { FilterOperator } from '@ahoo-wang/wow-client/dsl';
import type { FilterTree, RecordViewConfig } from '../src/index.js';
import { answeringAnew, outOfDate } from '../src/runtime/autoApply.js';
import { withScopeFilter } from '../src/runtime/scope.js';
import { RecordTable } from '../src/ui/index.js';
import { dashboardConfig, recordConfig } from './fixtures.js';
import { twoColumnTable } from './fixtures/ui.js';

afterEach(cleanup);

/**
 * A result whose rows answer an older question than the one on its way is
 * kept and faded on every surface (`ui/kit/stale.ts`). The analysis
 * workbench's own fade is pinned in `test/autoRun.test.tsx`; this suite is
 * the rule read with the scope injected beside a view — a board's filter
 * under a panel — and the mark a record table says it with.
 */

const EAST: FilterTree = {
  op: 'and',
  children: [{ field: 'warehouse', operator: FilterOperator.EQ, value: 'CN' }],
};

const WEST: FilterTree = {
  op: 'and',
  children: [{ field: 'warehouse', operator: FilterOperator.EQ, value: 'US' }],
};

/** A record view's state: `applied` asked under `ran`, the answer landed or not. */
function asked(
  status: 'loading' | 'success',
  ran: FilterTree | null,
  applied: RecordViewConfig = recordConfig(),
) {
  return {
    draft: applied,
    applied,
    issues: [],
    autoApply: false,
    query: { status },
    result: { own: applied, config: withScopeFilter(applied, ran) },
  };
}

describe('the rows on screen answer an older question', () => {
  it('when the scope beside the view moved and the answer is on its way', () => {
    expect(answeringAnew(asked('loading', EAST), WEST)).toBe(true);
    expect(outOfDate(asked('loading', EAST), WEST)).toBe(true);
  });

  it('when a scope came into force, or went out of it', () => {
    expect(answeringAnew(asked('loading', null), EAST)).toBe(true);
    expect(answeringAnew(asked('loading', EAST), null)).toBe(true);
  });

  it('not through a refresh under the same scope', () => {
    expect(answeringAnew(asked('loading', EAST), EAST)).toBe(false);
    expect(answeringAnew(asked('loading', null), null)).toBe(false);
  });

  it('not once the answer has landed', () => {
    expect(answeringAnew(asked('success', EAST), WEST)).toBe(false);
  });

  it('reads the view’s own config alone where no scope is given', () => {
    expect(answeringAnew(asked('loading', EAST))).toBe(false);
    const resized = recordConfig({ pageSize: 50 });
    expect(answeringAnew({ ...asked('loading', null), applied: resized })).toBe(
      true,
    );
  });

  it('never of a dashboard, which runs no conditions of its own', () => {
    const board = dashboardConfig();
    expect(
      answeringAnew(
        {
          applied: board,
          query: { status: 'loading' },
          result: { own: board, config: board },
        },
        EAST,
      ),
    ).toBe(false);
  });
});

describe('a record table whose rows are out of date', () => {
  it('says so on the table, and that it is busy', () => {
    render(<RecordTable table={twoColumnTable()} stale />);
    const table = screen.getByRole('table');
    expect(table.hasAttribute('data-stale')).toBe(true);
    expect(table.getAttribute('aria-busy')).toBe('true');
  });

  it('says nothing of the kind while its rows are the answer', () => {
    render(<RecordTable table={twoColumnTable()} />);
    const table = screen.getByRole('table');
    expect(table.hasAttribute('data-stale')).toBe(false);
    expect(table.hasAttribute('aria-busy')).toBe(false);
  });
});
