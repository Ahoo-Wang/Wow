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

/**
 * A reference read as names sorts by the stored id (D354 ⑤), and the
 * screen says so by itself: the header's 「按 ID」 whether or not it is
 * sorted, its reason one hover away and in the button's description, and
 * the sort editor's list.
 */

import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { SortDirection } from '@ahoo-wang/wow-client/dsl';
import type { FieldDefinition, RecordSort } from '../src/model/index.js';
import type { RecordColumnView } from '../src/record/index.js';
import { SortableHeader } from '../src/ui/record/SortableHeader.js';
import { SortSettings } from '../src/ui/sort/SortSettings.js';
import { MessagesProvider } from '../src/ui/kit/MessagesProvider.js';
import { zhCN } from '../src/ui/messages/zh-CN.js';
import { tableController } from './fixtures/columns.js';
import { describedText } from './fixtures/ui.js';

afterEach(cleanup);

const CUSTOMER: RecordColumnView = {
  field: 'customerId',
  label: 'Customer',
  kind: 'reference',
  cell: 'reference',
  sortable: true,
  sortsById: true,
  remote: 'customers',
};

function header(column: RecordColumnView, sort: RecordSort[] = []) {
  render(
    <table>
      <thead>
        <tr>
          <SortableHeader
            column={column}
            sort={sort}
            onToggle={() => {}}
            additiveId="additive"
          />
        </tr>
      </thead>
    </table>,
  );
  return screen.getByRole('columnheader');
}

const mark = () => document.querySelector('[data-slot="sort-by-id"]');
const tip = () => document.querySelector('[data-slot="tooltip-content"]');

describe('the header of a reference sorted by id', () => {
  it('says "by id" before it is sorted, and in its name', () => {
    const head = header(CUSTOMER);
    expect(head.getAttribute('data-sorts-by')).toBe('id');
    expect(mark()?.textContent).toBe('by id');
    const button = screen.getByRole('button');
    expect(button.getAttribute('aria-label')).toBe(
      'Sort by Customer, ascending · by id',
    );
    expect(describedText(button)).toContain(
      'Sorts by the stored id, not the name shown.',
    );
  });

  it('keeps saying it while sorted', () => {
    header(CUSTOMER, [{ field: 'customerId', direction: SortDirection.ASC }]);
    expect(mark()?.textContent).toBe('by id');
    expect(
      document.querySelector('[data-slot="sort-direction"]'),
    ).not.toBeNull();
  });

  it('gives the reason one hover away', async () => {
    header(CUSTOMER);
    await userEvent.hover(mark()!);
    await waitFor(() =>
      expect(tip()?.textContent).toBe(
        'Sorts by the stored id, not the name shown.',
      ),
    );
  });

  it('says it in the language in force', () => {
    render(
      <MessagesProvider messages={zhCN}>
        <table>
          <thead>
            <tr>
              <SortableHeader
                column={CUSTOMER}
                sort={[]}
                onToggle={() => {}}
                additiveId="additive"
              />
            </tr>
          </thead>
        </table>
      </MessagesProvider>,
    );
    expect(mark()?.textContent).toBe('按 ID');
  });

  it('says nothing on a column that sorts by what it shows', () => {
    const head = header({ ...CUSTOMER, sortsById: undefined });
    expect(head.hasAttribute('data-sorts-by')).toBe(false);
    expect(mark()).toBeNull();
  });
});

describe('the sort editor on a reference sorted by id', () => {
  const FIELDS: FieldDefinition[] = [
    { name: 'id', label: 'Order', kind: 'string', sortable: true },
    {
      name: 'customerId',
      label: 'Customer',
      kind: 'reference',
      remote: 'customers',
      sortable: true,
    },
  ];

  it('offers it as sorted by id', async () => {
    const user = userEvent.setup();
    render(<SortSettings table={tableController()} fields={FIELDS} />);
    await user.click(
      document.querySelector<HTMLElement>('[data-control="sort"]')!,
    );
    await user.click(screen.getByRole('button', { name: /Sort by a field/ }));
    expect(
      await screen.findByRole('menuitem', { name: 'Customer (by id)' }),
    ).toBeTruthy();
    expect(screen.getByRole('menuitem', { name: 'Order' })).toBeTruthy();
  });

  it('lists a sort on it as sorted by id', async () => {
    const user = userEvent.setup();
    render(
      <SortSettings
        table={tableController({
          sort: [{ field: 'customerId', direction: SortDirection.DESC }],
        })}
        fields={FIELDS}
      />,
    );
    const trigger = document.querySelector<HTMLElement>(
      '[data-control="sort"]',
    )!;
    expect(trigger.textContent).toBe('Customer (by id)');
    await user.click(trigger);
    expect(
      screen.getByRole('button', {
        name: 'Stop sorting by Customer (by id)',
      }),
    ).toBeTruthy();
  });
});
