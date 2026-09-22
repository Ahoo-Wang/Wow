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
 * The cell renderer family: what a definition can say about how a column
 * reads, and what each reading draws.
 *
 * The readings live in `ui/record/cells.tsx` and both the table and the cards
 * come through it, so the last suite here is the one that keeps them from
 * drifting apart.
 */

import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { FieldOption, RecordData } from '../src/index.js';
import type { RecordColumnView } from '../src/record/index.js';
import { RecordCards, RecordTable } from '../src/ui/index.js';
import { recordTableController } from './fixtures/ui.js';

afterEach(cleanup);

/** One column over one row, which is all a cell reading needs to show. */
function oneCell(
  column: Partial<RecordColumnView>,
  value: unknown,
  data: RecordData = { value },
) {
  return recordTableController({
    columns: [
      {
        field: 'value',
        label: 'Value',
        kind: 'string',
        cell: 'string',
        sortable: false,
        ...column,
      },
    ],
    rows: [{ key: 'r-1', data }],
  });
}

const badges = (container: HTMLElement) => [
  ...container.querySelectorAll<HTMLElement>('[data-slot="badge"]'),
];

const STATUSES: FieldOption[] = [
  { value: 'PENDING', label: 'Pending', tone: 'warning' },
  { value: 'SHIPPED', label: 'Shipped', tone: 'success' },
  { value: 'CANCELLED', label: 'Cancelled', tone: 'danger' },
];

/**
 * A status was asked for by name, so it wears its pill whether or not the
 * definition has a word for the code — which is where it parts company with
 * an `enum`, whose badge is inferred from the choices being named.
 */
describe('a status cell', () => {
  it('wears one badge, in the tone its option declares', () => {
    const { container } = render(
      <RecordTable
        table={oneCell({ cell: 'status', options: STATUSES }, 'SHIPPED')}
      />,
    );

    expect(badges(container).map(node => node.textContent)).toEqual([
      'Shipped',
    ]);
    expect(badges(container)[0].getAttribute('data-tone')).toBe('success');
  });

  it('stays neutral when the option names no tone', () => {
    const { container } = render(
      <RecordTable
        table={oneCell(
          { cell: 'status', options: [{ value: 'OPEN', label: 'Open' }] },
          'OPEN',
        )}
      />,
    );

    expect(badges(container)[0].getAttribute('data-tone')).toBe('neutral');
    expect(badges(container)[0].getAttribute('data-variant')).toBe('secondary');
  });

  /** Danger is the one tone the registry itself has a variant for. */
  it('draws danger with the destructive variant', () => {
    const { container } = render(
      <RecordTable
        table={oneCell({ cell: 'status', options: STATUSES }, 'CANCELLED')}
      />,
    );

    expect(badges(container)[0].getAttribute('data-variant')).toBe(
      'destructive',
    );
  });

  /**
   * The definition said this column is a status; a code it has since stopped
   * naming is still one, and showing it bare would say the opposite.
   */
  it('keeps the pill around a code no option names', () => {
    const { container } = render(
      <RecordTable
        table={oneCell({ cell: 'status', options: STATUSES }, 'HELD')}
      />,
    );

    expect(badges(container).map(node => node.textContent)).toEqual(['HELD']);
  });
});

describe('a tags cell', () => {
  const tagged = (value: unknown) =>
    oneCell(
      {
        kind: 'array',
        cell: 'tags',
        options: [
          { value: 'rush', label: 'Rush' },
          { value: 'gift', label: 'Gift', tone: 'success' },
        ],
      },
      value,
    );

  it('gives every entry its own badge, by its label', () => {
    const { container } = render(
      <RecordTable table={tagged(['rush', 'gift'])} />,
    );

    expect(badges(container).map(node => node.textContent)).toEqual([
      'Rush',
      'Gift',
    ]);
    expect(
      badges(container).map(node => node.getAttribute('data-tone')),
    ).toEqual(['neutral', 'success']);
  });

  /** An entry nobody named is shown as it came, rather than dropped. */
  it('shows an entry the options do not name', () => {
    const { container } = render(<RecordTable table={tagged(['rush', 'x'])} />);

    expect(badges(container).map(node => node.textContent)).toEqual([
      'Rush',
      'x',
    ]);
  });

  it('takes a lone value as one tag', () => {
    const { container } = render(<RecordTable table={tagged('gift')} />);

    expect(badges(container).map(node => node.textContent)).toEqual(['Gift']);
  });

  it('draws nothing at all for an empty list', () => {
    const { container } = render(<RecordTable table={tagged([])} />);

    expect(badges(container)).toEqual([]);
    expect(container.querySelector('tbody td')?.textContent).toBe('');
  });
});

describe('a link cell', () => {
  const linked = (value: unknown) => oneCell({ cell: 'link' }, value);

  it('opens in a new document that cannot reach back', () => {
    render(<RecordTable table={linked('https://example.com/o/1')} />);

    // The text is the URL, so the link has an accessible name of its own.
    const link = screen.getByRole('link', {
      name: 'https://example.com/o/1',
    });
    expect(link.getAttribute('href')).toBe('https://example.com/o/1');
    expect(link.getAttribute('target')).toBe('_blank');
    expect(link.getAttribute('rel')).toBe('noopener noreferrer');
  });

  /**
   * A record's URLs are data, and data is not trusted: a scheme the content
   * rules refuse falls back to text, which is the same answer the dashboard's
   * markdown links give. A live `javascript:` link would run in this origin.
   */
  it('refuses a scheme the content rules do not allow', () => {
    const { container } = render(
      <RecordTable table={linked('javascript:alert(1)')} />,
    );

    expect(container.querySelector('a')).toBeNull();
    expect(screen.getByText('javascript:alert(1)')).toBeDefined();
  });

  it('refuses a value that is not a string', () => {
    const { container } = render(<RecordTable table={linked(42)} />);

    expect(container.querySelector('a')).toBeNull();
    expect(screen.getByText('42')).toBeDefined();
  });
});

describe('a text cell', () => {
  const NOTE = 'First line\nSecond line\nThird line\nFourth line';

  it('clamps the lines, keeps the newlines, and holds the whole of it', () => {
    const { container } = render(
      <RecordTable table={oneCell({ cell: 'text' }, NOTE)} />,
    );

    const cell = container.querySelector<HTMLElement>(
      '[data-slot="cell-text"]',
    )!;
    expect(cell.className).toContain('line-clamp-3');
    expect(cell.className).toContain('whitespace-pre-wrap');
    // Clamped on screen, whole on hover: nothing is lost, only folded.
    expect(cell.getAttribute('title')).toBe(NOTE);
    expect(cell.textContent).toBe(NOTE);
  });

  it('leaves a value that is not text to the default rendering', () => {
    const { container } = render(
      <RecordTable table={oneCell({ cell: 'text' }, 7)} />,
    );

    expect(container.querySelector('[data-slot="cell-text"]')).toBeNull();
    expect(screen.getByText('7')).toBeDefined();
  });
});

/**
 * The pin on the family: a field that declares no reading renders exactly as
 * it did before there was one to declare. Each of these is a path the new
 * readings sit in front of.
 */
describe('a cell no definition spoke for', () => {
  it('joins an array of named values rather than badging them', () => {
    render(
      <RecordTable
        table={oneCell(
          {
            kind: 'array',
            cell: 'array',
            options: [
              { value: 'rush', label: 'Rush' },
              { value: 'gift', label: 'Gift' },
            ],
          },
          ['rush', 'gift'],
        )}
      />,
    );

    expect(screen.getByText('Rush, Gift')).toBeDefined();
  });

  it('keeps a string a string and a URL plain text', () => {
    const { container } = render(
      <RecordTable table={oneCell({}, 'https://example.com/o/1')} />,
    );

    expect(container.querySelector('a')).toBeNull();
    expect(screen.getByText('https://example.com/o/1')).toBeDefined();
  });

  it('keeps a number in its format and a boolean in its word', () => {
    render(
      <RecordTable
        table={recordTableController({
          columns: [
            {
              field: 'amount',
              label: 'Amount',
              kind: 'number',
              cell: 'number',
              sortable: false,
              numberFormat: { style: 'currency', currency: 'CNY' },
            },
            {
              field: 'paid',
              label: 'Paid',
              kind: 'boolean',
              cell: 'boolean',
              sortable: false,
            },
          ],
          rows: [{ key: 'r-1', data: { amount: 10, paid: true } }],
        })}
      />,
    );

    expect(screen.getByText('CN¥10.00')).toBeDefined();
    expect(screen.getByText('Yes')).toBeDefined();
  });

  it('writes an object the way JSON does', () => {
    render(<RecordTable table={oneCell({}, { a: 1 })} />);

    expect(screen.getByText('{"a":1}')).toBeDefined();
  });
});

/**
 * A card is the same result folded, so it reads a value the same way: the
 * two used to share only `displayValue`, and a status that was a badge in
 * the table was a bare code on the card beside it.
 */
describe('cards read a value as the table does', () => {
  it('badges a status and links a link', () => {
    const { container } = render(
      <RecordCards
        table={recordTableController({
          card: {
            title: 'id',
            fields: [
              {
                field: 'status',
                label: 'Status',
                kind: 'enum',
                cell: 'status',
                options: STATUSES,
              },
              {
                field: 'track',
                label: 'Tracking',
                kind: 'string',
                cell: 'link',
              },
            ],
          },
          rows: [
            {
              key: 'r-1',
              data: {
                id: 'r-1',
                status: 'CANCELLED',
                track: 'https://example.com/t/1',
              },
            },
          ],
        })}
      />,
    );

    expect(badges(container).map(node => node.textContent)).toEqual([
      'Cancelled',
    ]);
    expect(badges(container)[0].getAttribute('data-tone')).toBe('danger');
    expect(
      screen.getByRole('link', { name: 'https://example.com/t/1' }),
    ).toBeDefined();
  });

  it("still lets a host's own renderer decide", () => {
    render(
      <RecordCards
        table={recordTableController({
          card: {
            title: 'id',
            fields: [
              {
                field: 'status',
                label: 'Status',
                kind: 'enum',
                cell: 'status',
                options: STATUSES,
              },
            ],
          },
          rows: [{ key: 'r-1', data: { id: 'r-1', status: 'SHIPPED' } }],
        })}
        renderCell={cell => <b>{String(cell.value)}</b>}
      />,
    );

    expect(screen.getByText('SHIPPED')).toBeDefined();
  });
});
