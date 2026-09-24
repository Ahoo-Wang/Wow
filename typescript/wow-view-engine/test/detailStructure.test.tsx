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

import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { cardField } from '../src/record/index.js';
import type { FieldDefinition } from '../src/index.js';
import {
  ViewSurface,
  cellValue,
  useSurfaceDisplay,
  useViewMessages,
  zhCN,
  type CellField,
  type CellSurface,
} from '../src/ui/index.js';

afterEach(cleanup);

/** An event stream's `body`, declared the way the event console does. */
const events: FieldDefinition = {
  name: 'body',
  label: '事件',
  kind: 'elementMatch',
  elementTitle: 'bodyType',
  elements: [
    {
      name: 'bodyType',
      label: '事件类型',
      kind: 'enum',
      options: [
        { value: 'Created', label: '首次失败', tone: 'danger' },
        { value: 'Prepared', label: '准备重试' },
      ],
    },
    { name: 'name', label: '事件名', kind: 'string' },
    { name: 'search', label: '全文', kind: 'search' },
  ],
};

const TRACE = 'Inventory refused.\n\tat Warehouse.reserve(Warehouse.kt:42)';

const stream = [
  {
    bodyType: 'Created',
    name: 'execution_failed_created',
    body: { error: { errorCode: 'BadRequest', stackTrace: TRACE } },
  },
  { bodyType: 'Prepared', name: 'compensation_prepared', body: {} },
];

function Read({
  value,
  field,
  surface = 'detail',
}: {
  value: unknown;
  field: CellField;
  surface?: CellSurface;
}) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  return (
    <div data-testid="read">
      {cellValue(value, field, messages, display, surface)}
    </div>
  );
}

function read(value: unknown, field: CellField, surface?: CellSurface) {
  render(
    <ViewSurface messages={zhCN} locale="zh-CN">
      <Read value={value} field={field} surface={surface} />
    </ViewSurface>,
  );
  return screen.getByTestId('read');
}

describe('a structure in a record detail, read whole', () => {
  it('lays an array of objects out element by element', () => {
    const shown = read(stream, cardField(events));
    const elements = shown.querySelectorAll('[data-slot="detail-element"]');
    expect(elements).toHaveLength(2);

    const [first] = elements as unknown as HTMLElement[];
    // Numbered, and titled by its type in the type's own words.
    expect(within(first!).getByText('第 1 项')).toBeTruthy();
    expect(within(first!).getByText('首次失败')).toBeTruthy();
    // The declared fields by their labels — the title not said twice, the
    // search handle not at all.
    const terms = [...first!.querySelectorAll(':scope > dl > div > dt')].map(
      term => term.textContent,
    );
    expect(terms).toEqual(['事件名', 'body']);
    expect(within(first!).getByText('execution_failed_created')).toBeTruthy();
    // A structure or a paragraph sits under its name, across the width; a
    // plain value beside it.
    const valueOf = (term: string) =>
      [...first!.querySelectorAll('dt')].find(dt => dt.textContent === term)!
        .nextElementSibling as HTMLElement;
    expect(valueOf('事件名').hasAttribute('data-block')).toBe(false);
    expect(valueOf('body').hasAttribute('data-block')).toBe(true);
    expect(valueOf('stackTrace').hasAttribute('data-block')).toBe(true);
    // What nothing declares, key by key, as it is written.
    expect(within(first!).getByText('errorCode')).toBeTruthy();
    expect(within(first!).getByText('BadRequest')).toBeTruthy();
    // The stack trace inside it whole, kept as written, copyable.
    const trace = within(first!).getByText(/Inventory refused/);
    expect(trace.closest('[data-slot="cell-long"]')).not.toBeNull();
    expect(trace.textContent).toContain('\tat Warehouse.reserve');
  });

  it('lays an object nothing declares out key by key', () => {
    const shown = read(
      { retries: 3, owner: { team: 'payments' }, tags: ['a', 'b'] },
      { kind: 'object' },
    );
    expect(within(shown).getByText('retries')).toBeTruthy();
    expect(within(shown).getByText('3')).toBeTruthy();
    expect(within(shown).getByText('team')).toBeTruthy();
    expect(within(shown).getByText('payments')).toBeTruthy();
    // A list of plain values is one line, in the catalogue's separator.
    expect(within(shown).getByText('a、b')).toBeTruthy();
    // Never the JSON of it.
    expect(shown.textContent).not.toContain('{');
  });

  it('writes what nests past its depth whole, rather than key under key', () => {
    let deep: unknown = { bottom: true };
    for (let level = 0; level < 8; level++) deep = { level: deep };
    const shown = read(deep, { kind: 'object' });
    const block = shown.querySelector('[data-slot="cell-long"]');
    expect(block?.textContent).toContain('"bottom": true');
  });

  it('keeps the one-line reading in a table', () => {
    const shown = read(stream, cardField(events), 'table');
    expect(shown.querySelector('[data-slot="detail-element"]')).toBeNull();
    expect(
      shown.querySelectorAll('[data-slot="cell-elements"] [data-slot="badge"]'),
    ).toHaveLength(2);
  });
});

describe('cardField, for a detail', () => {
  it('resolves the element fields that hold a value', () => {
    expect(cardField(events).elements?.map(field => field.field)).toEqual([
      'bodyType',
      'name',
    ]);
  });
});
