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
 * A record page asks its source for the fields its view shows, and for the
 * ones the host's code reads besides — never the whole document. Measured
 * against the compensation service, a page of 100 failed executions was
 * 808 KB whole and 46 KB projected, the difference being mostly stack traces
 * nobody had on screen.
 */

import type { CursorQuery, FilterPagedQuery } from '@ahoo-wang/fetcher-wow';
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  FIRST_PAGE,
  MemoryViewStore,
  ViewEngine,
  builtinFieldKinds,
  compileRecord,
  recordProjection,
  validateDefinition,
  without,
  type DataViewDefinition,
  type RecordViewConfig,
  type RecordViewRuntime,
  type ViewSource,
} from '../src/index.js';
import { useOpenView, useRecordTable } from '../src/react/index.js';
import { NOW, recordConfig, testSource } from './fixtures.js';

afterEach(cleanup);

/**
 * Failed executions in miniature: a search box that is no path, nested
 * paths under `state`, and a stack trace no view here shows.
 */
function definition(
  record: Partial<NonNullable<DataViewDefinition['record']>> = {},
): DataViewDefinition {
  return {
    id: 'failures',
    title: 'Failures',
    kind: 'data',
    source: 'failures',
    fields: [
      { name: 'keyword', label: 'Search', kind: 'search' },
      { name: 'state.id', label: 'ID', kind: 'string', sortable: true },
      { name: 'state.status', label: 'Status', kind: 'string' },
      { name: 'state.isRetryable', label: 'Retryable', kind: 'boolean' },
      { name: 'state.error', label: 'Error', kind: 'string' },
      { name: 'state.error.errorCode', label: 'Code', kind: 'string' },
      { name: 'state.error.stackTrace', label: 'Stack', kind: 'string' },
      {
        name: 'state.retries',
        label: 'Retries',
        kind: 'number',
        sortable: true,
        summary: ['SUM', 'COUNT'],
      },
      { name: 'eventTime', label: 'Updated', kind: 'datetime', sortable: true },
    ],
    record: {
      rowKey: 'state.id',
      paging: 'paged',
      layouts: ['table', 'card'],
      ...record,
    },
  };
}

function config(overrides: Partial<RecordViewConfig> = {}): RecordViewConfig {
  return recordConfig({
    table: {
      columns: [{ field: 'state.status' }, { field: 'state.error.errorCode' }],
    },
    card: { title: 'state.status', fields: ['eventTime'] },
    ...overrides,
  });
}

const include = (
  found: DataViewDefinition,
  cfg: RecordViewConfig = config(),
): string[] | undefined => recordProjection(found, cfg).include;

describe('recordProjection', () => {
  it('asks for the row key, the visible columns and the card, nothing else', () => {
    expect(include(definition())).toEqual([
      'state.id',
      'state.status',
      'state.error.errorCode',
      'eventTime',
    ]);
  });

  it('leaves a hidden column out, and the card too where cards are not offered', () => {
    const cfg = config({
      table: {
        columns: [
          { field: 'state.status' },
          { field: 'state.error.stackTrace', hidden: true },
        ],
      },
      card: {
        title: 'state.error.errorCode',
        image: 'state.isRetryable',
        fields: ['eventTime'],
      },
    });
    expect(include(definition({ layouts: ['table'] }), cfg)).toEqual([
      'state.id',
      'state.status',
    ]);
    // Cards offered, cards asked for — whichever layout is current, because
    // switching layout runs no query (`RECORD_PRESENTATION_MEMBERS`).
    expect(include(definition(), { ...cfg, layout: 'table' })).toEqual([
      'state.id',
      'state.status',
      'state.error.errorCode',
      'state.isRetryable',
      'eventTime',
    ]);
  });

  it('asks for the sort, and for what the page summaries add up', () => {
    const cfg = config({
      table: { columns: [] },
      card: { title: 'state.id', fields: [] },
      sort: [{ field: 'eventTime', direction: 'DESC' }],
      summaries: [{ field: 'state.retries', fn: 'SUM' }],
    });
    expect(include(definition(), cfg)).toEqual([
      'state.id',
      'eventTime',
      'state.retries',
    ]);
    // A count reads no value: it counts the rows.
    expect(
      include(definition(), {
        ...cfg,
        sort: [],
        summaries: [{ field: 'state.retries', fn: 'COUNT' }],
      }),
    ).toEqual(['state.id']);
  });

  it('asks for the row fields the host declared, whatever the view shows', () => {
    expect(
      include(definition({ rowFields: ['state.isRetryable', 'state.status'] })),
    ).toEqual([
      'state.id',
      'state.status',
      'state.error.errorCode',
      'eventTime',
      'state.isRetryable',
    ]);
  });

  it('drops what is no path, and a path its ancestor already brings', () => {
    const cfg = config({
      table: {
        columns: [
          { field: 'keyword' },
          { field: 'gone' },
          { field: 'state.error.errorCode' },
          { field: 'state.error' },
        ],
      },
      card: { title: 'state.id', fields: [] },
    });
    expect(include(definition(), cfg)).toEqual(['state.id', 'state.error']);
  });

  /**
   * A column over an array of objects reads each element's title and
   * nothing else of it, so the page asks for the titles alone — an event's
   * payload and stack trace stay on the server. An array with no title reads
   * as how many it holds, which takes the elements; a host that reads the
   * array itself names it whole in `rowFields`. The array of a hidden column
   * is not fetched at all.
   */
  it('asks for a shown array of objects by its element titles', () => {
    const events: DataViewDefinition = {
      id: 'events',
      title: 'Events',
      kind: 'data',
      source: 'events',
      fields: [
        { name: 'aggregateId', label: 'Aggregate', kind: 'string' },
        { name: 'version', label: 'Version', kind: 'number' },
        {
          name: 'body',
          label: 'Events',
          kind: 'elementMatch',
          elementTitle: 'name',
          elements: [
            { name: 'name', label: 'Event', kind: 'string' },
            { name: 'body', label: 'Payload', kind: 'string' },
          ],
        },
      ],
      record: { rowKey: 'aggregateId', paging: 'paged', layouts: ['table'] },
    };
    const shown = (hidden?: true, card: string[] = []) =>
      recordConfig({
        table: {
          columns: [
            { field: 'version' },
            { field: 'body', ...(hidden ? { hidden } : {}) },
          ],
        },
        card: { title: 'aggregateId', fields: card },
      });

    expect(recordProjection(events, shown()).include).toEqual([
      'aggregateId',
      'version',
      'body.name',
    ]);
    expect(recordProjection(events, shown(true)).include).toEqual([
      'aggregateId',
      'version',
    ]);
    // A card reads it the same way.
    const carded: DataViewDefinition = {
      ...events,
      record: { ...events.record!, layouts: ['table', 'card'] },
    };
    expect(recordProjection(carded, shown(true, ['body'])).include).toEqual([
      'aggregateId',
      'version',
      'body.name',
    ]);
    // A host that reads the array names it whole, and whole it comes.
    const read: DataViewDefinition = {
      ...events,
      record: { ...events.record!, rowFields: ['body'] },
    };
    expect(recordProjection(read, shown()).include).toEqual([
      'aggregateId',
      'version',
      'body',
    ]);
    // Without a title the cell counts the elements, which takes them all.
    const untitled: DataViewDefinition = {
      ...events,
      fields: events.fields.map(field => without(field, 'elementTitle')),
    };
    expect(recordProjection(untitled, shown()).include).toEqual([
      'aggregateId',
      'version',
      'body',
    ]);
  });

  it('refuses a definition that declares no record capability', () => {
    expect(() =>
      recordProjection({ ...definition(), record: undefined }, config()),
    ).toThrow(/no record capability/);
  });
});

describe('compileRecord carries the projection', () => {
  const context = { now: NOW, timeZone: 'UTC' };

  it('into a paged query and into a cursor query alike', () => {
    const paged = compileRecord(
      definition(),
      config(),
      builtinFieldKinds,
      context,
      FIRST_PAGE.paged,
    );
    const cursor = compileRecord(
      definition({ paging: 'cursor' }),
      config(),
      builtinFieldKinds,
      context,
      FIRST_PAGE.cursor,
    );
    const expected = {
      include: [
        'state.id',
        'state.status',
        'state.error.errorCode',
        'eventTime',
      ],
    };
    expect(paged.projection).toEqual(expected);
    expect(cursor.projection).toEqual(expected);
  });
});

describe('RecordCapability.rowFields admission', () => {
  const codes = (rowFields: string[]) =>
    validateDefinition(definition({ rowFields }), builtinFieldKinds).map(
      found => [found.code, found.path],
    );

  it('admits declared paths', () => {
    expect(codes(['state.isRetryable'])).toEqual([]);
  });

  it('refuses a field nobody declared, and a handle that is no path', () => {
    expect(codes(['state.recoverable', 'keyword'])).toEqual([
      ['definition.record.row-field-unknown', ['record', 'rowFields', 0]],
      ['definition.record.row-field-not-a-path', ['record', 'rowFields', 1]],
    ]);
  });
});

describe('the query a view runs', () => {
  function open(source: ViewSource) {
    const engine = new ViewEngine({
      definitions: [definition()],
      store: new MemoryViewStore({
        instances: [
          {
            id: 'mine',
            definitionId: 'failures',
            title: 'Mine',
            scope: 'personal',
            revision: '1',
            config: config({
              // On both ladders' terms: a size the card ladder also holds,
              // so a layout switch is a layout switch and nothing more.
              pageSize: 24,
              table: {
                columns: [
                  { field: 'state.status' },
                  { field: 'state.error.stackTrace', hidden: true },
                ],
              },
            }),
          },
        ],
      }),
      resolveSource: () => source,
    });
    return renderHook(() => {
      const opened = useOpenView(engine, 'mine');
      const runtime = opened.runtime as RecordViewRuntime | null;
      return { runtime, table: useRecordTable(runtime) };
    });
  }

  const asked = (paged: ReturnType<typeof vi.fn>): unknown =>
    (paged.mock.lastCall?.[0] as FilterPagedQuery | CursorQuery).projection;

  it('fetches a column switched back on, in the query that shows it', async () => {
    const source = testSource();
    const { result } = open(source);
    await waitFor(() => expect(result.current.table.status).toBe('success'));
    const paged = vi.mocked(source.paged);
    expect(asked(paged)).toEqual({
      include: ['state.id', 'state.status', 'eventTime'],
    });

    act(() =>
      result.current.table.setColumns([
        'state.status',
        'state.error.stackTrace',
      ]),
    );

    await waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
    expect(asked(paged)).toEqual({
      include: [
        'state.id',
        'state.status',
        'state.error.stackTrace',
        'eventTime',
      ],
    });
  });

  it('switches layout without a query, since the card fields came already', async () => {
    const source = testSource();
    const { result } = open(source);
    await waitFor(() => expect(result.current.table.status).toBe('success'));

    act(() => result.current.table.setLayout('card'));

    expect(result.current.table.layout).toBe('card');
    expect(source.paged).toHaveBeenCalledTimes(1);
  });

  it('exports with the same projection the page ran with', async () => {
    const source = testSource();
    const { result } = open(source);
    await waitFor(() => expect(result.current.table.status).toBe('success'));

    await result.current.runtime!.exportRows();

    expect(asked(vi.mocked(source.paged))).toEqual({
      include: ['state.id', 'state.status', 'eventTime'],
    });
  });
});

/**
 * A card is a row folded out, so a card field over an array of objects
 * carries the same element title its column does — the card reads the
 * elements as the table does, wrapped rather than counted away.
 */
describe('an array of objects on a card', () => {
  it('carries its element title to the card field, as to the column', async () => {
    const events: DataViewDefinition = {
      id: 'events',
      title: 'Events',
      kind: 'data',
      source: 'events',
      fields: [
        {
          name: 'aggregateId',
          label: 'Aggregate',
          kind: 'string',
          sortable: true,
        },
        {
          name: 'body',
          label: 'Events',
          kind: 'elementMatch',
          elementTitle: 'name',
          elements: [{ name: 'name', label: 'Event', kind: 'string' }],
        },
      ],
      record: {
        rowKey: 'aggregateId',
        paging: 'paged',
        layouts: ['table', 'card'],
      },
    };
    const engine = new ViewEngine({
      definitions: [events],
      store: new MemoryViewStore({
        instances: [
          {
            id: 'stream',
            definitionId: 'events',
            title: 'Stream',
            scope: 'personal',
            revision: '1',
            config: recordConfig({
              table: { columns: [{ field: 'body' }] },
              card: { title: 'aggregateId', fields: ['body'] },
            }),
          },
        ],
      }),
      resolveSource: () => testSource(),
    });
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'stream');
      return useRecordTable(opened.runtime as RecordViewRuntime | null);
    });
    await waitFor(() => expect(result.current.status).toBe('success'));

    const title = { name: 'name', kind: 'string', cell: 'string' };
    expect(result.current.card.fields[0].elementTitle).toEqual(title);
    expect(result.current.columns[0].elementTitle).toEqual(title);
  });
});
