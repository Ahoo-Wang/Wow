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
  AggregationMetricType,
  FilterOperator,
  MAX_CURSOR_SORT_FIELDS,
  SortDirection,
} from '@ahoo-wang/fetcher-wow';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  compileRecord,
  compileSummaries,
  DEFAULT_RUNTIME_LIMITS,
  defaultRecordConfig,
  FIRST_PAGE,
  maxSortFields,
  pageSummaries,
  projectRecord,
  projectSummaries,
  recordCapabilityOf,
  recordValue,
  summaryAlias,
  validateRecord,
  type DataViewDefinition,
  type Issue,
  type RecordSort,
  type RecordViewConfig,
} from '../src/index.js';

const context = { now: new Date('2026-09-16T10:30:00.000Z'), timeZone: 'UTC' };

function definition(
  overrides: Partial<DataViewDefinition> = {},
): DataViewDefinition {
  return {
    id: 'orders',
    title: 'Orders',
    kind: 'data',
    source: 'orders',
    fields: [
      { name: 'id', label: 'Order', kind: 'string' },
      {
        name: 'amount',
        label: 'Amount',
        kind: 'number',
        summary: ['SUM', 'AVG'],
        numberFormat: { style: 'currency', currency: 'CNY' },
      },
      { name: 'warehouse', label: 'Warehouse', kind: 'string', cell: 'status' },
      {
        name: 'createdAt',
        label: 'Created',
        kind: 'datetime',
        sortable: true,
        // A date carries its earliest and its latest, and no maths.
        summary: ['MIN', 'MAX'],
      },
    ],
    record: { rowKey: 'id', paging: 'paged', layouts: ['table', 'card'] },
    ...overrides,
  };
}

function config(overrides: Partial<RecordViewConfig> = {}): RecordViewConfig {
  return {
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    kind: 'record',
    sort: [],
    pageSize: 20,
    layout: 'table',
    table: { columns: [{ field: 'id' }, { field: 'amount' }] },
    card: { title: 'id', fields: ['amount'] },
    ...overrides,
  };
}

const codes = (issues: Issue[]) =>
  issues.filter(i => i.severity === 'error').map(i => i.code);

describe('defaultRecordConfig', () => {
  /** How many columns a first view shows is the engine's to say, not a constant's. */
  it("takes the first view's column count from the limits", () => {
    const built = defaultRecordConfig(definition(), {
      ...DEFAULT_RUNTIME_LIMITS,
      defaultColumns: 2,
      defaultPageSize: 25,
    });
    expect(built.table.columns).toHaveLength(2);
    expect(built.pageSize).toBe(25);
  });

  it('builds a complete config from the declared capability', () => {
    const built = defaultRecordConfig(definition());
    expect(built).toMatchObject({
      kind: 'record',
      layout: 'table',
      pageSize: 20,
      card: { title: 'id' },
    });
    expect(built.table.columns.map(column => column.field)).toEqual([
      'id',
      'amount',
      'warehouse',
      'createdAt',
    ]);
    expect(validateRecord(definition(), built, builtinFieldKinds)).toEqual([]);
  });

  it('prefers the definition defaults and stays inside the page limit', () => {
    const def = definition({
      record: {
        rowKey: 'id',
        paging: 'cursor',
        layouts: ['card'],
        defaults: {
          pageSize: 1000,
          layout: 'card',
          sort: [{ field: 'createdAt', direction: 'DESC' }],
        },
      },
    });
    const built = defaultRecordConfig(def);
    expect(built.layout).toBe('card');
    expect(built.pageSize).toBe(200);
    expect(built.sort).toEqual([{ field: 'createdAt', direction: 'DESC' }]);
  });

  it('refuses a definition without the capability it needs', () => {
    const def = definition({ record: undefined });
    expect(() => defaultRecordConfig(def)).toThrow(/no record capability/);
    expect(recordCapabilityOf(def)).toBeUndefined();
    expect(
      recordCapabilityOf({ id: 'd', title: 'D', kind: 'dashboard' }),
    ).toBeUndefined();
  });
});

describe('validateRecord', () => {
  it('admits a config that matches its definition', () => {
    expect(validateRecord(definition(), config(), builtinFieldKinds)).toEqual(
      [],
    );
  });

  it('reports a layout the capability does not offer', () => {
    const def = definition({
      record: { rowKey: 'id', paging: 'paged', layouts: ['table'] },
    });
    expect(
      codes(validateRecord(def, config({ layout: 'card' }), builtinFieldKinds)),
    ).toEqual(['record.layout.unsupported']);
  });

  it('bounds the page size', () => {
    expect(
      codes(
        validateRecord(
          definition(),
          config({ pageSize: 0 }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['record.pageSize.not-positive']);
    expect(
      codes(
        validateRecord(
          definition(),
          config({ pageSize: 5000 }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['record.pageSize.too-large']);
  });

  it('sorts only by fields the definition marks sortable', () => {
    expect(
      codes(
        validateRecord(
          definition(),
          config({ sort: [{ field: 'amount', direction: 'ASC' }] }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['record.sort.not-sortable']);

    expect(
      codes(
        validateRecord(
          definition(),
          config({ sort: [{ field: 'gone', direction: 'ASC' }] }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['record.field.unknown']);
  });

  /**
   * The shape check asks a sort entry for a `field` and nothing else, so a
   * stored config may name a direction of `up`, or none at all. Said here it
   * is a finding like any other and the draft stays in the error state; left
   * unsaid it reached the sort editor as a key into a wording table and took
   * the whole workbench down with it.
   */
  it('reports a sort direction that reads as neither way round', () => {
    const bad = [
      { field: 'createdAt', direction: 'up' },
      { field: 'id' },
    ] as unknown as RecordSort[];

    const issues = validateRecord(
      definition(),
      config({ sort: bad }),
      builtinFieldKinds,
    );

    expect(codes(issues)).toEqual([
      'record.sort.direction-invalid',
      'record.sort.direction-invalid',
      'record.sort.not-sortable',
    ]);
    expect(issues[0].path).toEqual(['sort', 0, 'direction']);
  });

  it('bounds the sort fields of a cursor source with the Wow limit', () => {
    const def = definition({
      record: { rowKey: 'id', paging: 'cursor', layouts: ['table'] },
    });
    // The ceiling a control has to stop at is this one: `maxSortFields` is
    // exported so the two cannot drift into disagreeing.
    expect(maxSortFields(def)).toBe(MAX_CURSOR_SORT_FIELDS);
    expect(maxSortFields(definition())).toBe(definition().fields.length);
    const sort = Array.from({ length: 33 }, () => ({
      field: 'createdAt',
      direction: 'ASC' as const,
    }));
    expect(
      codes(validateRecord(def, config({ sort }), builtinFieldKinds)),
    ).toContain('record.sort.too-many');
  });

  it('reports columns and card fields that no longer exist', () => {
    const issues = validateRecord(
      definition(),
      config({
        table: { columns: [{ field: 'gone' }] },
        card: { title: 'missing', fields: ['amount', 'other'], image: 'nope' },
      }),
      builtinFieldKinds,
    );
    expect(codes(issues)).toEqual([
      'record.field.unknown',
      'record.field.unknown',
      'record.field.unknown',
      'record.field.unknown',
    ]);
    expect(issues[0].path).toEqual(['table', 'columns', 0, 'field']);
  });

  it('refuses a column, a sort field and a summary cell listed twice', () => {
    const issues = validateRecord(
      definition(),
      config({
        sort: [
          { field: 'createdAt', direction: 'ASC' },
          { field: 'createdAt', direction: 'DESC' },
        ],
        table: { columns: [{ field: 'id' }, { field: 'id' }] },
        summaries: [
          { field: 'amount', fn: 'SUM' },
          { field: 'amount', fn: 'SUM' },
        ],
      }),
      builtinFieldKinds,
    );
    expect(codes(issues)).toEqual([
      'record.sort.duplicate',
      'record.column.duplicate',
      'record.summary.duplicate',
    ]);
    // Reported at the repeat, so the first entry is the one that stands.
    expect(issues.map(found => found.path)).toEqual([
      ['sort', 1, 'field'],
      ['table', 'columns', 1, 'field'],
      ['summaries', 1],
    ]);
  });

  it('refuses a field-less kind on a card as well as in a column', () => {
    const def = definition({
      fields: [
        ...definition().fields,
        { name: 'q', label: 'Search', kind: 'search' },
      ],
    });
    expect(
      codes(
        validateRecord(
          def,
          config({ card: { title: 'q', fields: ['q'] } }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['record.field.not-a-column', 'record.field.not-a-column']);
  });

  it('allows only the summary functions the field declares', () => {
    expect(
      codes(
        validateRecord(
          definition(),
          config({ summaries: [{ field: 'amount', fn: 'SUM' }] }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual([]);
    expect(
      codes(
        validateRecord(
          definition(),
          config({ summaries: [{ field: 'amount', fn: 'MAX' }] }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['record.summary.unsupported']);
    expect(
      codes(
        validateRecord(
          definition(),
          config({ summaries: [{ field: 'gone', fn: 'SUM' }] }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['record.field.unknown']);
  });

  it('checks the shared config base too', () => {
    expect(
      codes(
        validateRecord(
          definition(),
          config({ refresh: { interval: 1 } }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['config.refresh.too-short']);
    expect(
      codes(
        validateRecord(
          definition(),
          config({ refresh: { interval: 999_999 } }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['config.refresh.too-long']);
    expect(
      codes(
        validateRecord(
          definition(),
          config({ refresh: { interval: 1.5 } }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['config.refresh.not-an-integer']);

    // An OR tree still runs; only the simple editor cannot show it.
    const advanced = validateRecord(
      definition(),
      config({
        filter: {
          op: 'or',
          children: [{ field: 'id', operator: 'EQ', value: 'A' }],
        },
      }),
      builtinFieldKinds,
    );
    expect(codes(advanced)).toEqual([]);
    expect(advanced.map(i => i.code)).toEqual(['config.filterMode.not-simple']);
  });

  it('reports a definition without the record capability', () => {
    expect(
      codes(
        validateRecord(
          definition({ record: undefined }),
          config(),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['record.capability.missing']);
  });
});

/**
 * A config arrives from a store, so the rules cannot read `sort`, `table`,
 * `card` or `summaries` as the shapes the type promises until something has
 * asked. Each missing or unreadable part is an Issue at its path.
 */
describe('a record config that lost its shape', () => {
  const shaped = (overrides: Record<string, unknown>) =>
    validateRecord(
      definition(),
      { ...config(), ...overrides } as RecordViewConfig,
      builtinFieldKinds,
    );

  it.each([
    ['sort is not a list', { sort: 'id' }, 'record.sort.invalid', ['sort']],
    [
      'a sort entry is null',
      { sort: [null] },
      'record.sort.invalid',
      ['sort', 0],
    ],
    [
      'a sort entry names no field',
      { sort: [{ direction: 'ASC' }] },
      'record.sort.invalid',
      ['sort', 0],
    ],
    [
      'table is absent',
      { table: undefined },
      'record.table.invalid',
      ['table'],
    ],
    ['table is null', { table: null }, 'record.table.invalid', ['table']],
    [
      'table has no columns',
      { table: {} },
      'record.table.invalid',
      ['table', 'columns'],
    ],
    [
      'a column is null',
      { table: { columns: [null] } },
      'record.table.invalid',
      ['table', 'columns', 0],
    ],
    [
      'a column names no field',
      { table: { columns: [{ width: 1 }] } },
      'record.table.invalid',
      ['table', 'columns', 0],
    ],
    ['card is absent', { card: undefined }, 'record.card.invalid', ['card']],
    [
      'card has no fields',
      { card: { title: 'id' } },
      'record.card.invalid',
      ['card', 'fields'],
    ],
    [
      'card has no title',
      { card: { fields: [] } },
      'record.card.invalid',
      ['card', 'title'],
    ],
    [
      'a card field is not a name',
      { card: { title: 'id', fields: [5] } },
      'record.card.invalid',
      ['card', 'fields', 0],
    ],
    [
      'summaries is not a list',
      { summaries: {} },
      'record.summaries.invalid',
      ['summaries'],
    ],
    [
      'a summary is null',
      { summaries: [null] },
      'record.summaries.invalid',
      ['summaries', 0],
    ],
    [
      'a summary names no function',
      { summaries: [{ field: 'amount' }] },
      'record.summaries.invalid',
      ['summaries', 0],
    ],
  ])('reports that %s instead of throwing', (_name, overrides, code, path) => {
    expect(shaped(overrides)).toEqual([{ code, severity: 'error', path }]);
  });

  it('reports a page size or layout of the wrong type by the existing rule', () => {
    expect(codes(shaped({ pageSize: '20' }))).toEqual([
      'record.pageSize.not-positive',
    ]);
    expect(shaped({ layout: null })).toEqual([
      {
        code: 'record.layout.unsupported',
        severity: 'error',
        path: ['layout'],
        params: { layout: 'null' },
      },
    ]);
  });

  it('reports a config that is not an object at all', () => {
    for (const broken of [null, undefined, 5, []])
      expect(
        validateRecord(definition(), broken as never, builtinFieldKinds),
      ).toEqual([{ code: 'config.invalid', severity: 'error', path: [] }]);
  });

  it('keeps the shared findings and stops before the rules that read through the shape', () => {
    const found = shaped({
      refresh: undefined,
      sort: null,
      table: { columns: [{ field: 'gone' }] },
    });
    expect(found.map(i => i.code)).toEqual([
      'config.refresh.missing',
      'record.sort.invalid',
    ]);
  });
});

describe('compileRecord', () => {
  it('compiles a paged query that starts at page one', () => {
    const query = compileRecord(
      definition(),
      config({ sort: [{ field: 'createdAt', direction: 'DESC' }] }),
      builtinFieldKinds,
      context,
      FIRST_PAGE.paged,
    );
    expect(query).toMatchObject({
      filter: { op: FilterOperator.MATCH_ALL },
      sort: [{ field: 'createdAt', direction: SortDirection.DESC }],
      pagination: { index: 1, size: 20 },
    });
  });

  const cursorDefinition = () =>
    definition({
      record: { rowKey: 'id', paging: 'cursor', layouts: ['table'] },
    });

  it('compiles a cursor query whose first page is a null cursor', () => {
    const query = compileRecord(
      cursorDefinition(),
      config(),
      builtinFieldKinds,
      context,
      FIRST_PAGE.cursor,
    );
    expect(query).toMatchObject({ size: 20, cursor: null });

    const next = compileRecord(
      cursorDefinition(),
      config(),
      builtinFieldKinds,
      context,
      { cursor: 'c1' },
    );
    expect(next).toMatchObject({ cursor: 'c1' });
  });

  it('takes the paging mode from the capability, not from the target', () => {
    // A cursor target against a paged source used to compile a cursor query
    // the source cannot answer, silently; the mode is the definition's.
    expect(() =>
      compileRecord(
        definition(),
        config(),
        builtinFieldKinds,
        context,
        FIRST_PAGE.cursor,
      ),
    ).toThrow(/pages by index/);
    expect(() =>
      compileRecord(
        cursorDefinition(),
        config(),
        builtinFieldKinds,
        context,
        FIRST_PAGE.paged,
      ),
    ).toThrow(/pages by cursor/);
    expect(() =>
      compileRecord(
        definition({ record: undefined }),
        config(),
        builtinFieldKinds,
        context,
        FIRST_PAGE.paged,
      ),
    ).toThrow(/no record capability/);
  });

  it('carries the applied filter into the query', () => {
    const query = compileRecord(
      definition(),
      config({
        filter: {
          op: 'and',
          children: [{ field: 'id', operator: 'EQ', value: 'A1' }],
        },
      }),
      builtinFieldKinds,
      context,
      { index: 2 },
    );
    expect(query).toMatchObject({
      filter: { op: FilterOperator.EQ, field: 'id', value: 'A1' },
      pagination: { index: 2 },
    });
  });
});

describe('compileSummaries', () => {
  it('returns null when the config asks for none', () => {
    expect(
      compileSummaries(definition(), config(), builtinFieldKinds, context),
    ).toBeNull();
  });

  it('builds one ungrouped aggregation with an alias per cell', () => {
    const query = compileSummaries(
      definition(),
      config({
        summaries: [
          { field: 'amount', fn: 'SUM' },
          { field: 'amount', fn: 'AVG' },
          { field: 'id', fn: 'COUNT' },
        ],
      }),
      builtinFieldKinds,
      context,
    );
    expect(query?.groupBy).toBeUndefined();
    expect(query?.metrics.map(metric => metric.alias)).toEqual([
      'amount_sum',
      'amount_avg',
      'id_count',
    ]);
    expect(query?.metrics[2].type).toBe(AggregationMetricType.COUNT);
  });

  it('keeps an alias to a single segment, without merging two fields', () => {
    expect(summaryAlias('address.city', 'MAX')).toBe('address_city_max');
    // `a.b` and `a_b` are two fields, so they must not share one alias: the
    // second cell would otherwise read back the first one's number.
    expect(summaryAlias('address_city', 'MAX')).toBe('address__city_max');
  });
});

describe('recordValue', () => {
  const record = {
    note: null,
    customer: { city: 'Hangzhou', region: null },
  };

  it('reads a field by its path', () => {
    expect(recordValue(record, 'customer.city')).toBe('Hangzhou');
  });

  // A renderer may show "none" for a null the backend sent and nothing at all
  // for a field that is absent, so the two must not collapse into one.
  it('keeps a null the record holds, at the root or at the end of a path', () => {
    expect(recordValue(record, 'note')).toBeNull();
    expect(recordValue(record, 'customer.region')).toBeNull();
  });

  it('is undefined only where the path is not there', () => {
    expect(recordValue(record, 'customer.street')).toBeUndefined();
    expect(recordValue(record, 'note.text')).toBeUndefined();
  });
});

describe('projectRecord', () => {
  const rows = [
    { id: 'A1', amount: 10, warehouse: 'SH' },
    { id: 'A2', amount: 30, warehouse: 'BJ' },
  ];

  it("carries an enum field's choices on its column, for a cell to name", () => {
    const options = [{ value: 'SH', label: 'Shanghai' }];
    const view = projectRecord(
      definition({
        fields: definition().fields.map(field =>
          field.name === 'warehouse'
            ? { ...field, kind: 'enum', options }
            : field,
        ),
      }),
      config({ table: { columns: [{ field: 'warehouse' }] } }),
      { total: 2, list: rows },
    );

    expect(view.columns[0]).toMatchObject({ field: 'warehouse', options });
  });

  it('resolves column semantics and the row key', () => {
    const view = projectRecord(definition(), config(), {
      total: 2,
      list: rows,
    });
    expect(view.columns).toEqual([
      {
        field: 'id',
        label: 'Order',
        kind: 'string',
        cell: 'string',
        width: undefined,
        // The row key is held on the left whatever the config says: it is
        // the column that says which record a row is, so it is the one that
        // has to stay in view while the rest scrolls sideways — and the one
        // marked `primary`, so the narrow-screen cap knows the single pin it
        // may never take back (D17-4).
        pinned: 'left',
        primary: true,
        sortable: false,
        numberFormat: undefined,
      },
      {
        field: 'amount',
        label: 'Amount',
        kind: 'number',
        cell: 'number',
        width: undefined,
        // And the last column is held on the right for the same kind of
        // reason (D13): both ends of the table stay put, so it has a frame
        // a reader can see rather than two edges that drift. `end` says it
        // is held for being last, so a host's action column can take the
        // place instead.
        pinned: 'right',
        end: true,
        sortable: false,
        numberFormat: { style: 'currency', currency: 'CNY' },
      },
    ]);
    expect(view.rows.map(row => row.key)).toEqual(['A1', 'A2']);
    expect(view.paging).toEqual({ mode: 'paged', index: 1, total: 2 });
  });

  /**
   * A definition's row key is not a preference, so the config is left alone
   * and the projection answers with the pinning the table must honour —
   * which is also what the column settings show, disabled.
   */
  it('holds the row key on the left, whatever the config asks for', () => {
    const view = projectRecord(
      definition(),
      config({
        table: { columns: [{ field: 'id', pinned: 'right' }] },
      }),
      { total: 0, list: [] },
    );

    expect(view.columns[0]).toMatchObject({ field: 'id', pinned: 'left' });
  });

  /**
   * `end` means "held right for being last", so a host's action column can
   * take that place. A column the config pinned right is the last one too —
   * the right area comes last — but it is held because the config says so,
   * and it keeps that pin beside the action column. Before, it carried
   * `end` all the same, and the moment a host added row actions every right
   * pin vanished from the table while the settings went on saying "pinned".
   */
  it('keeps a right pin the config asked for off the end flag', () => {
    const view = projectRecord(
      definition(),
      config({
        table: {
          columns: [{ field: 'id' }, { field: 'warehouse', pinned: 'right' }],
        },
      }),
      { total: 0, list: [] },
    );

    expect(view.columns[1]).toMatchObject({
      field: 'warehouse',
      pinned: 'right',
    });
    expect(view.columns[1]).not.toHaveProperty('end');
  });

  /**
   * A column pinned nowhere in particular is a finding the user can fix,
   * and a projection that says "not pinned" in the meantime — never a side
   * the table would then try to stick it to.
   */
  /**
   * Except on the row key, whose pinning the config has no opinion about:
   * the projection holds it left whatever is stored and the settings show
   * that fixed and disabled, so reporting the value would block the query
   * and the save over something no control on screen can change.
   */
  it('says nothing about the row key\u2019s own pinning', () => {
    const issues = validateRecord(
      definition(),
      config({
        table: {
          columns: [{ field: 'id', pinned: 'top' }, { field: 'amount' }],
        },
      } as unknown as Partial<RecordViewConfig>),
      builtinFieldKinds,
    );

    expect(codes(issues)).toEqual([]);
  });

  it('reports a pinning that is neither side, and projects it as none', () => {
    const config_ = config({
      table: {
        columns: [{ field: 'amount', pinned: 'top' }, { field: 'warehouse' }],
      },
    } as unknown as Partial<RecordViewConfig>);

    const issues = validateRecord(definition(), config_, builtinFieldKinds);
    expect(codes(issues)).toEqual(['record.column.pin-invalid']);
    expect(issues[0].path).toEqual(['table', 'columns', 0, 'pinned']);

    expect(
      projectRecord(definition(), config_, { total: 0, list: [] }).columns[0]
        .pinned,
    ).toBeUndefined();
  });

  /**
   * The other end of the same rule, and the other half of the exemption
   * above: the table draws `warehouse` last, so it is held on the right
   * whatever the config asked for — and a pinning nothing on screen can
   * change is not reported, exactly as the row key's is not.
   */
  it('holds the last column on the right, and says nothing about its pinning', () => {
    const config_ = config({
      table: {
        columns: [{ field: 'amount' }, { field: 'warehouse', pinned: 'top' }],
      },
    } as unknown as Partial<RecordViewConfig>);

    expect(
      codes(validateRecord(definition(), config_, builtinFieldKinds)),
    ).toEqual([]);

    const view = projectRecord(definition(), config_, { total: 0, list: [] });
    expect(view.columns.map(column => column.pinned)).toEqual([
      undefined,
      'right',
    ]);
  });

  /**
   * A table of one column is that column at both ends, and the row key wins:
   * it is the column that says which record a row is, and an end that is
   * held on both sides is held on neither.
   */
  it('leaves a lone row key on the left rather than making it the end', () => {
    const view = projectRecord(
      definition(),
      config({ table: { columns: [{ field: 'id' }] } }),
      { total: 0, list: [] },
    );

    expect(view.columns.map(column => column.pinned)).toEqual(['left']);
  });

  /**
   * A column the user switched off keeps its entry — that is its place in
   * the order, which is the whole point of the member (D17-8) — and the
   * table simply does not draw it. So it is not a column, not a cell in an
   * export, and not the end the right edge holds.
   */
  describe('a column switched off', () => {
    const hidden = config({
      table: {
        columns: [
          { field: 'id' },
          { field: 'amount', hidden: true },
          { field: 'warehouse' },
        ],
      },
    });

    it('is left out of the columns, and the rest keep their order', () => {
      const view = projectRecord(definition(), hidden, { total: 0, list: [] });

      expect(view.columns.map(column => column.field)).toEqual([
        'id',
        'warehouse',
      ]);
      // The config still says where it will come back to.
      expect(hidden.table.columns.map(column => column.field)).toEqual([
        'id',
        'amount',
        'warehouse',
      ]);
    });

    /** D13's last column is the last one *drawn*. */
    it('is never the end the table is held by', () => {
      const view = projectRecord(definition(), hidden, { total: 0, list: [] });

      expect(view.columns.map(column => column.pinned)).toEqual([
        'left',
        'right',
      ]);
      expect(view.columns[1]).toMatchObject({ field: 'warehouse', end: true });
    });

    /** It is a column like any other while it is off; only `hidden` moves. */
    it('is admitted, and so is a config that never heard of the member', () => {
      expect(
        codes(validateRecord(definition(), hidden, builtinFieldKinds)),
      ).toEqual([]);
      expect(
        codes(validateRecord(definition(), config(), builtinFieldKinds)),
      ).toEqual([]);
    });

    /** And the rules that are about the entry itself still reach it. */
    it('is still refused when it names a field that is not there', () => {
      const issues = validateRecord(
        definition(),
        config({
          table: {
            columns: [
              { field: 'id' },
              { field: 'gone', hidden: true },
              { field: 'id', hidden: true },
            ],
          },
        }),
        builtinFieldKinds,
      );

      expect(codes(issues)).toEqual([
        'record.field.unknown',
        'record.column.duplicate',
      ]);
    });

    /**
     * One checkbox writes this member and it has one value. Anything else
     * reads as shown rather than as a guess, and is reported so that the
     * checkbox which writes it properly is known to be the repair — the
     * treatment `pinned` and `width` get, for the same reason.
     */
    it('reports a switch that is not how a column is switched off', () => {
      const config_ = config({
        table: {
          columns: [
            { field: 'id' },
            { field: 'amount', hidden: 'yes' },
            { field: 'warehouse' },
          ],
        },
      } as unknown as Partial<RecordViewConfig>);

      const issues = validateRecord(definition(), config_, builtinFieldKinds);
      expect(codes(issues)).toEqual(['record.column.hidden-invalid']);
      expect(issues[0].path).toEqual(['table', 'columns', 1, 'hidden']);

      // Read as shown in the meantime, so the column is on screen and the
      // checkbox that repairs it is the one the user is looking at.
      expect(
        projectRecord(definition(), config_, { total: 0, list: [] }).columns
          .length,
      ).toBe(3);
    });
  });

  /**
   * A column the definition no longer offers is not drawn, so it is not the
   * end either: the end is the last column actually on screen.
   */
  it('passes the end over a column that cannot be drawn', () => {
    const view = projectRecord(
      definition(),
      config({
        table: {
          columns: [{ field: 'id' }, { field: 'amount' }, { field: 'gone' }],
        },
      }),
      { total: 0, list: [] },
    );

    expect(view.columns.map(column => column.field)).toEqual(['id', 'amount']);
    expect(view.columns[1].pinned).toBe('right');
  });

  /**
   * A column pinned left that is drawn second covers the one before it, so
   * where the row key goes is decided here, beside its pinning, rather than
   * by whoever wrote the config. The rest keep the order they are in.
   */
  it('leads with the row key, and leaves the rest in their order', () => {
    const view = projectRecord(
      definition(),
      config({
        table: {
          columns: [
            { field: 'amount' },
            { field: 'warehouse' },
            { field: 'id' },
          ],
        },
      }),
      { total: 0, list: [] },
    );

    expect(view.columns.map(column => column.field)).toEqual([
      'id',
      'amount',
      'warehouse',
    ]);
  });

  /**
   * `sticky` fixes an element where it already is, so a column pinned right
   * that is drawn in the middle scrolls away like any other: laying the
   * areas out is part of the same rule as pinning them, and it lives where
   * the table reads both.
   */
  it('lays the columns out in the three areas a table draws', () => {
    const view = projectRecord(
      definition(),
      config({
        table: {
          columns: [
            { field: 'amount', pinned: 'right' },
            { field: 'warehouse' },
            { field: 'id' },
            { field: 'createdAt', pinned: 'left' },
          ],
        },
      }),
      { total: 0, list: [] },
    );

    expect(view.columns.map(column => column.field)).toEqual([
      'id',
      'createdAt',
      'warehouse',
      'amount',
    ]);
    expect(view.columns.map(column => column.pinned)).toEqual([
      'left',
      'left',
      undefined,
      'right',
    ]);
  });

  it('uses the renderer key a field declares', () => {
    const view = projectRecord(
      definition(),
      config({ table: { columns: [{ field: 'warehouse', width: 120 }] } }),
      { total: 0, list: [] },
    );
    expect(view.columns[0]).toMatchObject({ cell: 'status', width: 120 });
  });

  it('drops a column whose field has disappeared', () => {
    const view = projectRecord(
      definition(),
      config({ table: { columns: [{ field: 'gone' }, { field: 'id' }] } }),
      { total: 0, list: [] },
    );
    expect(view.columns.map(column => column.field)).toEqual(['id']);
  });

  it('reports the cursor of a cursor page', () => {
    const view = projectRecord(definition(), config(), {
      list: rows,
      nextCursor: 'c2',
    });
    expect(view.paging).toEqual({ mode: 'cursor', nextCursor: 'c2' });
  });

  it('reads a nested row key', () => {
    const def = definition({
      record: { rowKey: 'meta.id', paging: 'paged', layouts: ['table'] },
    });
    const view = projectRecord(def, config(), {
      total: 1,
      list: [{ meta: { id: 'N1' } }],
    });
    expect(view.rows[0].key).toBe('N1');
  });

  it('refuses a definition without the record capability', () => {
    expect(() =>
      projectRecord(definition({ record: undefined }), config(), {
        total: 0,
        list: [],
      }),
    ).toThrow(/no record capability/);
  });
});

describe('projectSummaries', () => {
  const withSummaries = config({
    summaries: [
      { field: 'amount', fn: 'SUM' },
      { field: 'amount', fn: 'AVG' },
      { field: 'id', fn: 'COUNT' },
    ],
  });

  it('computes page totals from the rows on screen', () => {
    const row = projectSummaries(definition(), withSummaries, {
      scope: 'page',
      rows: [{ amount: 10 }, { amount: 30 }, { amount: null }],
    });
    expect(row.scope).toBe('page');
    expect(row.cells.map(cell => cell.value)).toEqual([40, 20, 3]);
    expect(row.cells[0].label).toBe('Amount');
    expect(row.cells[0].numberFormat).toEqual({
      style: 'currency',
      currency: 'CNY',
    });
  });

  it('reads range totals back by alias', () => {
    const row = projectSummaries(definition(), withSummaries, {
      scope: 'total',
      result: [{ amount_sum: 400, amount_avg: 20, id_count: 20 }],
    });
    expect(row.scope).toBe('total');
    expect(row.cells.map(cell => cell.value)).toEqual([400, 20, 20]);
  });

  it('reports a missing number rather than inventing one', () => {
    expect(
      projectSummaries(definition(), withSummaries, {
        scope: 'total',
        result: [],
      }).cells.map(cell => cell.value),
    ).toEqual([null, null, null]);

    expect(
      projectSummaries(definition(), withSummaries, {
        scope: 'page',
        rows: [{ amount: 'x' }],
      }).cells.map(cell => cell.value),
    ).toEqual([null, null, 1]);
  });

  it('handles min and max and a field the definition lost', () => {
    const row = projectSummaries(
      definition(),
      config({
        summaries: [
          { field: 'amount', fn: 'MIN' },
          { field: 'amount', fn: 'MAX' },
          { field: 'gone', fn: 'SUM' },
        ],
      }),
      { scope: 'page', rows: [{ amount: 5 }, { amount: 9 }] },
    );
    expect(row.cells.map(cell => cell.value)).toEqual([5, 9, null]);
    expect(row.cells[2].label).toBe('gone');
  });

  it('returns an empty row when the config asks for no summary', () => {
    expect(
      projectSummaries(definition(), config(), { scope: 'page', rows: [] })
        .cells,
    ).toEqual([]);
  });
});

/**
 * A date column's earliest and latest.
 *
 * They are two of that column's own cells rather than numbers about it, so
 * the kernel keeps the value the record holds and says how it reads — the
 * footer then formats it the way the column formats that cell. Everything
 * here is about that pair: what the comparison runs on, what comes back, and
 * what the two scopes have to agree about.
 */
describe('a date column summarised', () => {
  const dates = config({
    summaries: [
      { field: 'createdAt', fn: 'MIN' },
      { field: 'createdAt', fn: 'MAX' },
    ],
  });

  it('picks the earliest and the latest of the rows on screen', () => {
    const row = projectSummaries(definition(), dates, {
      scope: 'page',
      rows: [
        { createdAt: '2026-09-16T01:05:00.000Z' },
        { createdAt: '2026-09-15T02:10:00.000Z' },
        { createdAt: '2026-09-17T08:45:00.000Z' },
        { createdAt: null },
      ],
    });
    // The record's own value, not the instant it was compared as: the
    // column formats it, and a reformatted value is a second reading.
    expect(row.cells.map(cell => cell.value)).toEqual([
      '2026-09-15T02:10:00.000Z',
      '2026-09-17T08:45:00.000Z',
    ]);
    // And it says how it reads, which is how the footer knows not to run it
    // through a number format.
    expect(row.cells.map(cell => cell.cell)).toEqual(['datetime', 'datetime']);
  });

  /**
   * Text compares as text, which is the whole reason the kind's reader is
   * asked. `'2026-09-10'` sorts before `'2026-09-10T00:00:00+08:00'` as a
   * string and after it as an instant — the offset puts that one eight hours
   * earlier — and a string of epoch milliseconds is an instant `Date.parse`
   * cannot read at all.
   */
  it('orders by the instant the value names, whatever shape it came in', () => {
    const shaped = definition({
      fields: [
        ...definition().fields,
        { name: 'shipDate', label: 'Shipped', kind: 'date', summary: ['MIN'] },
      ],
    });
    const row = projectSummaries(
      shaped,
      config({
        summaries: [
          { field: 'shipDate', fn: 'MIN' },
          { field: 'createdAt', fn: 'MAX' },
        ],
      }),
      {
        scope: 'page',
        rows: [
          { shipDate: '2026-09-10', createdAt: 1789723315014 },
          { shipDate: '2026-09-10T00:00:00+08:00', createdAt: '1789723315015' },
          { shipDate: 'not a day', createdAt: 'not an instant' },
        ],
      },
    );
    expect(row.cells.map(cell => cell.value)).toEqual([
      '2026-09-10T00:00:00+08:00',
      '1789723315015',
    ]);
    expect(row.cells[0].cell).toBe('date');
  });

  it('reads the aggregated instant back as the source keeps it', () => {
    const iso = projectSummaries(definition(), dates, {
      scope: 'total',
      result: [
        {
          createdAt_min: '2026-09-15T02:10:00.000Z',
          createdAt_max: 1789723315014,
        },
      ],
    });
    // One source answers MIN on a date with an ISO instant and another with
    // epoch milliseconds; both are moments, and neither is rewritten here.
    expect(iso.cells.map(cell => cell.value)).toEqual([
      '2026-09-15T02:10:00.000Z',
      1789723315014,
    ]);
  });

  it('reports a value that is no moment as missing rather than showing it', () => {
    const row = projectSummaries(definition(), dates, {
      scope: 'total',
      result: [{ createdAt_min: 'whenever', createdAt_max: true }],
    });
    expect(row.cells.map(cell => cell.value)).toEqual([null, null]);
  });

  /**
   * The two scopes read one column one way. `pageSummaries` recomputes the
   * page from the cells the executed config named, so a date reduced to an
   * instant there and to a date here is exactly the drift the two functions
   * sit in one file to prevent.
   */
  it('reduces the page from the executed cells the same way', () => {
    const executed = projectSummaries(definition(), dates, {
      scope: 'total',
      result: [{ createdAt_min: 1789723315014 }],
    });
    const page = pageSummaries(executed.cells, [
      { key: 'A', data: { createdAt: '2026-09-16T01:05:00.000Z' } },
      { key: 'B', data: { createdAt: '2026-09-15T02:10:00.000Z' } },
    ]);
    expect(page.scope).toBe('page');
    // Both cells come back from the rows on screen — including the latest,
    // which the aggregation never answered.
    expect(page.cells.map(cell => cell.value)).toEqual([
      '2026-09-15T02:10:00.000Z',
      '2026-09-16T01:05:00.000Z',
    ]);
    expect(page.cells[0].cell).toBe('datetime');
  });

  it('counts rows under a date column rather than dating the count', () => {
    const row = projectSummaries(
      definition(),
      config({ summaries: [{ field: 'createdAt', fn: 'COUNT' }] }),
      { scope: 'page', rows: [{ createdAt: '2026-09-16' }, {}] },
    );
    // A count is a number of rows wherever it is configured, so it names no
    // reading and is not formatted as the column's cells are.
    expect(row.cells[0].value).toBe(2);
    expect(row.cells[0].cell).toBeUndefined();
  });

  it('admits the earliest and the latest, and refuses the maths', () => {
    expect(
      codes(validateRecord(definition(), dates, builtinFieldKinds)),
    ).toEqual([]);
    // Declared or not, a sum of instants answers nothing and an average of
    // them is a moment nothing happened at.
    const declared = definition({
      fields: definition().fields.map(field =>
        field.name === 'createdAt'
          ? { ...field, summary: ['MIN', 'SUM', 'AVG'] }
          : field,
      ),
    });
    expect(
      codes(
        validateRecord(
          declared,
          config({
            summaries: [
              { field: 'createdAt', fn: 'SUM' },
              { field: 'createdAt', fn: 'AVG' },
              { field: 'createdAt', fn: 'MIN' },
            ],
          }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['record.summary.unsupported', 'record.summary.unsupported']);
  });

  /**
   * A number holding an instant reads as a date under `cell: 'date'`
   * (`FieldCellId`), and the column's summary has to read as that column
   * does: its earliest, formatted as its cells are, rather than its
   * smallest thirteen-digit number.
   */
  it('follows the reading a field borrowed, not only its kind', () => {
    const borrowed = definition({
      fields: [
        ...definition().fields,
        {
          name: 'closedAt',
          label: 'Closed',
          kind: 'number',
          cell: 'date',
          summary: ['MIN', 'SUM'],
        },
      ],
    });
    const asked = config({ summaries: [{ field: 'closedAt', fn: 'MIN' }] });
    const row = projectSummaries(borrowed, asked, {
      scope: 'page',
      rows: [{ closedAt: 1789723315014 }, { closedAt: 1689723315014 }],
    });
    expect(row.cells[0].value).toBe(1689723315014);
    expect(row.cells[0].cell).toBe('date');
    expect(codes(validateRecord(borrowed, asked, builtinFieldKinds))).toEqual(
      [],
    );
    expect(
      codes(
        validateRecord(
          borrowed,
          config({ summaries: [{ field: 'closedAt', fn: 'SUM' }] }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['record.summary.unsupported']);
  });
});
