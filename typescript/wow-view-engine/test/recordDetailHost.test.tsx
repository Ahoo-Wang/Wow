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
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import type { FilterPagedQuery } from '@ahoo-wang/wow-client';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type {
  RecordDetailSection,
  RecordDetailSectionContext,
} from '../src/react/index.js';
import { DataWorkbench, type RecordDetailOptions } from '../src/ui/index.js';
import { placeSections } from '../src/ui/record/detailPlacement.js';
import {
  MemoryViewStore,
  ViewEngine,
  type RecordData,
  type RecordKey,
} from '../src/index.js';
import {
  ROWS,
  deferred,
  mine,
  ordersDefinition,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

/** A record only a link can reach: it is on no page the view shows. */
const FAR_ID = 'o-9';
const FAR: RecordData = {
  id: FAR_ID,
  warehouse: 'US',
  amount: 90,
  status: 'PENDING',
  note: 'Held at customs',
};

/** What a read of one record answers, by the key it asked for. */
type Answer = RecordData | null | Error;

/**
 * A source whose page is the two fixture rows and whose one-record reads
 * answer from `records` — a whole record with a note, by default — or with
 * whatever `answer` says for a key.
 */
function detailSource(answer: (key: unknown) => Answer | Promise<Answer>) {
  return testSource({
    paged: vi.fn(async (query: FilterPagedQuery) => {
      if (query.pagination?.size !== 1) return { total: 2, list: [...ROWS] };
      const answered = await answer(keyOf(query));
      if (answered instanceof Error) throw answered;
      return { total: answered ? 1 : 0, list: answered ? [answered] : [] };
    }),
  });
}

function keyOf(query: FilterPagedQuery): unknown {
  const found = JSON.stringify(query.filter).match(/"value":"([^"]+)"/);
  return found?.[1];
}

const WHOLE = (key: unknown): Answer => {
  if (key === FAR_ID) return FAR;
  const row = ROWS.find(item => item.id === key);
  return row ? { ...row, note: `Note of ${String(key)}` } : null;
};

function definition() {
  return ordersDefinition({
    fields: [
      ...ordersDefinition().fields,
      { name: 'note', label: 'Note', kind: 'string', cell: 'text' },
    ],
    fieldGroups: [
      { id: 'order', label: 'The order', fields: ['id', 'status'] },
      { id: 'money', label: 'Money', fields: ['amount'] },
    ],
  });
}

function engineOver(source = detailSource(WHOLE)) {
  return new ViewEngine({
    definitions: [definition()],
    store: new MemoryViewStore({ instances: [mine] }),
    resolveSource: () => source,
  });
}

/** A host holding the open record the way it would hold `?id=`. */
function Host({
  engine,
  initial = null,
  onOpenChange,
  sections,
}: {
  engine: ViewEngine;
  initial?: RecordKey | null;
  onOpenChange?(key: RecordKey | null): void;
  sections?: RecordDetailOptions['sections'];
}) {
  const [id, setId] = useState<RecordKey | null>(initial);
  return (
    <>
      <button type="button" onClick={() => setId(FAR_ID)}>
        Follow the link
      </button>
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        record={{
          detail: {
            open: id,
            onOpenChange: key => {
              onOpenChange?.(key);
              setId(key);
            },
            ...(sections ? { sections } : {}),
          },
        }}
      />
    </>
  );
}

async function rowsDrawn() {
  await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
}

function dataRows(): HTMLElement[] {
  return screen.getAllByRole('row').slice(1);
}

function section(
  id: string,
  placement?: RecordDetailSection['placement'],
): RecordDetailSection {
  return {
    id,
    title: `Host ${id}`,
    ...(placement ? { placement } : {}),
    render: () => <p>Body of {id}</p>,
  };
}

describe('placeSections', () => {
  const fields = [
    { id: 'order', label: 'The order', fields: [] },
    { id: 'money', label: 'Money', fields: [] },
    { id: null, label: null, fields: [] },
  ];
  const order = (hosts: RecordDetailSection[]) =>
    placeSections(fields, hosts).map(placed =>
      placed.host ? `host:${placed.section.id}` : String(placed.section.id),
    );

  it('keeps the engine’s sections alone when the host adds none', () => {
    expect(order([])).toEqual(['order', 'money', 'null']);
  });

  it('puts a host section at the start, after a group, or at the end', () => {
    expect(
      order([
        section('a'),
        section('b', 'start'),
        section('c', { after: 'order' }),
        section('d', 'end'),
        section('e', { after: 'order' }),
      ]),
    ).toEqual([
      'host:b',
      'order',
      'host:c',
      'host:e',
      'money',
      'null',
      'host:a',
      'host:d',
    ]);
  });

  it('sends a section after a group the definition lacks to the end', () => {
    expect(order([section('x', { after: 'gone' }), section('y')])).toEqual([
      'order',
      'money',
      'null',
      'host:x',
      'host:y',
    ]);
  });
});

describe('the host’s sections in a record’s detail (G2)', () => {
  it('draws them among the engine’s, titled and labelled, and only once open', async () => {
    const render1 = vi.fn(() => <p>Execution context form</p>);
    const sections = vi.fn<
      (context: RecordDetailSectionContext) => RecordDetailSection[]
    >(() => [
      { id: 'context', title: 'Execution context', render: render1 },
      section('history', { after: 'order' }),
    ]);
    render(
      <DataWorkbench
        engine={engineOver()}
        definitionId="orders"
        instanceId="orders-1"
        record={{ detail: { sections } }}
      />,
    );
    await rowsDrawn();
    // Nothing of the host's is asked for before a record is opened.
    expect(sections).not.toHaveBeenCalled();
    expect(render1).not.toHaveBeenCalled();

    fireEvent.keyDown(dataRows()[0]!, { key: 'Enter' });
    const panel = await screen.findByRole('dialog');
    await within(panel).findByText('Note of o-1');
    expect(
      within(panel)
        .getAllByRole('heading', { level: 3 })
        .map(heading => heading.textContent),
    ).toEqual([
      'The order',
      'Host history',
      'Money',
      'Other',
      'Execution context',
    ]);
    // Each is a region named by its heading.
    const context = within(panel).getByRole('region', {
      name: 'Execution context',
    });
    expect(context.getAttribute('data-section')).toBe('context');
    expect(within(context).getByText('Execution context form')).toBeTruthy();
    // The context names the record, and says once it is whole.
    const last = sections.mock.lastCall![0];
    expect(last.row.key).toBe('o-1');
    expect(last.complete).toBe(true);
    expect(last.row.data.note).toBe('Note of o-1');
    expect(typeof last.refresh).toBe('function');
  });

  it('keeps a section that throws to itself, and tells the host', async () => {
    const onRenderFailure = vi.fn();
    render(
      <DataWorkbench
        engine={engineOver()}
        definitionId="orders"
        instanceId="orders-1"
        onRenderFailure={onRenderFailure}
        record={{
          detail: {
            sections: () => [
              {
                id: 'broken',
                title: 'Broken',
                render: () => {
                  throw new Error('host section failed');
                },
              },
              section('fine'),
            ],
          },
        }}
      />,
    );
    await rowsDrawn();
    vi.spyOn(console, 'error').mockImplementation(() => {});
    fireEvent.keyDown(dataRows()[0]!, { key: 'Enter' });
    const panel = await screen.findByRole('dialog');
    const broken = await within(panel).findByRole('region', { name: 'Broken' });
    expect(
      broken
        .querySelector('[data-slot="render-failed"]')
        ?.getAttribute('data-boundary'),
    ).toBe('detail');
    // The record and the other section stand.
    expect(within(panel).getByText('Body of fine')).toBeTruthy();
    expect(within(panel).getByRole('heading', { name: 'o-1' })).toBeTruthy();
    expect(onRenderFailure).toHaveBeenCalledWith(
      expect.objectContaining({ boundary: 'detail' }),
    );
  });
});

describe('a record opened by its key (G2)', () => {
  it('opens the record a host names, from the page’s row at once', async () => {
    render(<Host engine={engineOver()} initial="o-2" />);
    const panel = await screen.findByRole('dialog');
    expect(within(panel).getByRole('heading', { name: 'o-2' })).toBeTruthy();
    // Focus goes into the detail, onto the record's key.
    await waitFor(() =>
      expect(document.activeElement).toBe(
        within(panel).getByRole('heading', { name: 'o-2' }),
      ),
    );
    expect(await within(panel).findByText('Note of o-2')).toBeTruthy();
  });

  it('reads one the page does not hold, saying it is reading until it comes', async () => {
    const held = deferred<Answer>();
    const sections = vi.fn(() => [section('history')]);
    render(
      <Host
        engine={engineOver(
          detailSource(key => (key === FAR_ID ? held.promise : WHOLE(key))),
        )}
        initial={FAR_ID}
        sections={sections}
      />,
    );
    const panel = await screen.findByRole('dialog');
    expect(within(panel).getByRole('heading', { name: 'o-9' })).toBeTruthy();
    expect(panel.getAttribute('aria-busy')).toBe('true');
    expect(within(panel).getByRole('status').textContent).toMatch(
      /Reading the whole record/,
    );
    expect(
      panel.querySelector('[data-slot="record-detail-reading"]'),
    ).not.toBeNull();
    // No record in hand, so nothing of the host's yet.
    expect(sections).not.toHaveBeenCalled();

    await act(async () => held.resolve(FAR));
    expect(await within(panel).findByText('Held at customs')).toBeTruthy();
    expect(panel.getAttribute('aria-busy')).toBe('false');
    expect(
      panel.querySelector('[data-slot="record-detail-reading"]'),
    ).toBeNull();
    expect(within(panel).getByText('Body of history')).toBeTruthy();
  });

  it('says so when the linked record is not there, with nothing of the host’s', async () => {
    const sections = vi.fn(() => [section('history')]);
    render(<Host engine={engineOver()} initial="o-404" sections={sections} />);
    const panel = await screen.findByRole('dialog');
    expect(await within(panel).findByText(/no longer there/)).toBeTruthy();
    expect(sections).not.toHaveBeenCalled();
  });

  it('says the reader may not see a record the source refuses them', async () => {
    const refused = Object.assign(new Error('Forbidden'), {
      exchange: { response: { status: 403 } },
    });
    render(
      <Host
        engine={engineOver(detailSource(() => refused))}
        initial={FAR_ID}
      />,
    );
    const panel = await screen.findByRole('dialog');
    const line = await within(panel).findByRole('alert');
    expect(line.getAttribute('data-code')).toBe('record.detail.forbidden');
    expect(line.textContent).toMatch(/do not have permission/);
    // Trying again would be refused again; there is no button for it.
    expect(
      within(panel).queryByRole('button', { name: 'Try again' }),
    ).toBeNull();
  });

  it('says why a read failed, and reads again when asked', async () => {
    let fail = true;
    render(
      <Host
        engine={engineOver(
          detailSource(key =>
            fail ? new Error('connection reset') : WHOLE(key),
          ),
        )}
        initial={FAR_ID}
      />,
    );
    const panel = await screen.findByRole('dialog');
    const line = await within(panel).findByRole('alert');
    expect(line.getAttribute('data-code')).toBe('record.detail.failed');
    expect(line.textContent).toMatch(/connection reset/);
    // Nothing was ever in hand, so it does not claim to show what the list had.
    expect(line.textContent).not.toMatch(/fields the list had/);
    fail = false;
    fireEvent.click(within(panel).getByRole('button', { name: 'Try again' }));
    expect(await within(panel).findByText('Held at customs')).toBeTruthy();
    expect(within(panel).queryByRole('alert')).toBeNull();
  });

  it('asks the host to open and to close, and follows what it holds', async () => {
    const onOpenChange = vi.fn();
    render(<Host engine={engineOver()} onOpenChange={onOpenChange} />);
    await rowsDrawn();
    fireEvent.keyDown(dataRows()[1]!, { key: 'Enter' });
    expect(onOpenChange).toHaveBeenLastCalledWith('o-2');
    const panel = await screen.findByRole('dialog');
    expect(within(panel).getByRole('heading', { name: 'o-2' })).toBeTruthy();
    fireEvent.click(within(panel).getByRole('button', { name: 'Close' }));
    expect(onOpenChange).toHaveBeenLastCalledWith(null);
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    // Focus is back on the row it was opened from.
    await waitFor(() => expect(document.activeElement).toBe(dataRows()[1]));

    // A key the host sets later opens that record, on the page or not.
    fireEvent.click(screen.getByRole('button', { name: 'Follow the link' }));
    const far = await screen.findByRole('dialog');
    expect(within(far).getByRole('heading', { name: 'o-9' })).toBeTruthy();
  });

  it('stays open while the host holds the key, whatever the close asked', async () => {
    const onOpenChange = vi.fn();
    render(
      <DataWorkbench
        engine={engineOver()}
        definitionId="orders"
        instanceId="orders-1"
        record={{ detail: { open: 'o-1', onOpenChange } }}
      />,
    );
    const panel = await screen.findByRole('dialog');
    fireEvent.click(within(panel).getByRole('button', { name: 'Close' }));
    expect(onOpenChange).toHaveBeenCalledWith(null);
    expect(screen.getByRole('dialog')).toBe(panel);
  });

  it('tells a host that only watches which record is open', async () => {
    const onOpenChange = vi.fn();
    render(
      <DataWorkbench
        engine={engineOver()}
        definitionId="orders"
        instanceId="orders-1"
        record={{ detail: { onOpenChange } }}
      />,
    );
    await rowsDrawn();
    fireEvent.click(within(dataRows()[0]!).getAllByRole('cell')[2]!);
    expect(onOpenChange).toHaveBeenLastCalledWith('o-1');
    const panel = await screen.findByRole('dialog');
    fireEvent.click(within(panel).getByRole('button', { name: 'Close' }));
    expect(onOpenChange).toHaveBeenLastCalledWith(null);
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('hands focus to the record’s row when a linked record closes', async () => {
    render(<Host engine={engineOver()} initial="o-2" />);
    const panel = await screen.findByRole('dialog');
    await within(panel).findByText('Note of o-2');
    fireEvent.click(within(panel).getByRole('button', { name: 'Close' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(document.activeElement).toBe(dataRows()[1]));
  });
});
