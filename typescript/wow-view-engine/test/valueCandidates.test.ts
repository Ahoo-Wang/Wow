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
  AggregationGroupType,
  type AggregationQuery,
} from '@ahoo-wang/fetcher-wow';
import { describe, expect, it, vi } from 'vitest';
import {
  builtinFieldKinds,
  compileAnalysis,
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  narrowValueCandidates,
  readValueCandidates,
  validateAnalysis,
  valueCandidateField,
  valueCandidateNarrowing,
  valueCandidatesConfig,
  ViewEngine,
  type DataViewDefinition,
  type FieldDefinition,
  type RecordData,
  type ViewSource,
} from '../src/index.js';
import {
  NOW,
  ordersDefinition,
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';

const kinds = builtinFieldKinds;
const CONTEXT = { now: NOW, timeZone: 'UTC' };

function fieldOf(definition: DataViewDefinition, name: string) {
  const field = definition.fields.find(entry => entry.name === name);
  if (!field) throw new Error(`No field ${name}`);
  return field;
}

/** Orders with a few more fields, each missing one reason to be offered. */
function catalogue(): DataViewDefinition {
  const base = ordersDefinition();
  return {
    ...base,
    fields: [
      ...base.fields,
      {
        name: 'channel',
        label: 'Channel',
        kind: 'enum',
        options: [{ value: 'web', label: 'Web' }],
      },
      { name: 'customer', label: 'Customer', kind: 'reference', remote: 'c' },
    ],
    analysis: {
      ...base.analysis!,
      fields: [
        ...base.analysis!.fields,
        ...['channel', 'customer', 'amount'].map(field => ({
          field,
          groups: [AggregationGroupType.TERMS],
          functions: [],
        })),
      ],
    },
  };
}

describe('value candidates in the analysis kernel', () => {
  it('offers the values of a text field the data can group by value, and of no other', () => {
    const definition = catalogue();
    const offered = (name: string) =>
      valueCandidateField(definition, name, kinds)?.name ?? null;

    expect(offered('warehouse')).toBe('warehouse');
    // Declared text, but never grouped by value.
    expect(offered('status')).toBeNull();
    // A closed set lists itself; a remote one is the host's to search.
    expect(offered('channel')).toBeNull();
    expect(offered('customer')).toBeNull();
    // A number is compared as one, not picked as a word.
    expect(offered('amount')).toBeNull();
    expect(offered('nowhere')).toBeNull();
    // Records the definition does not let be counted list nothing.
    expect(
      valueCandidateField(
        { ...definition, analysis: { ...definition.analysis!, count: false } },
        'warehouse',
        kinds,
      ),
    ).toBeNull();
    expect(
      valueCandidateField(
        { ...definition, analysis: undefined },
        'warehouse',
        kinds,
      ),
    ).toBeNull();
  });

  it('compiles the candidates as a count by value, most frequent first, one row past the limit', () => {
    const definition = ordersDefinition();
    const config = valueCandidatesConfig(
      definition,
      fieldOf(definition, 'warehouse'),
      kinds,
    );
    expect(validateAnalysis(definition, config, kinds)).toEqual([]);

    const query = compileAnalysis(definition, config, kinds, CONTEXT);
    expect(query).toEqual({
      filter: { op: 'MATCH_ALL' },
      groupBy: [{ type: 'TERMS', field: 'warehouse', alias: 'value' }],
      metrics: [{ type: 'COUNT', alias: 'count' }],
      sort: [
        { field: 'count', direction: 'DESC' },
        { field: 'value', direction: 'ASC' },
      ],
      // Fifty asked for, and the probe row that says whether there are more.
      limit: 51,
    });
  });

  it("stays under the definition's own ceiling", () => {
    const base = ordersDefinition();
    const definition = {
      ...base,
      analysis: { ...base.analysis!, limits: { maxLimit: 20 } },
    };
    const config = valueCandidatesConfig(
      definition,
      fieldOf(definition, 'warehouse'),
      kinds,
    );
    expect(config.limit).toBe(20);
    // On the ceiling there is no row left to probe with.
    expect(compileAnalysis(definition, config, kinds, CONTEXT).limit).toBe(20);
    expect(
      valueCandidatesConfig(
        definition,
        fieldOf(definition, 'warehouse'),
        kinds,
        '',
        { ...DEFAULT_RUNTIME_LIMITS, maxAnalysisRows: 5 },
      ).limit,
    ).toBe(5);
  });

  it('narrows at the source by substring, else by prefix, else not at all', () => {
    const definition = ordersDefinition();
    const warehouse = fieldOf(definition, 'warehouse');
    const narrowed = (field: FieldDefinition, query: string) =>
      compileAnalysis(
        definition,
        valueCandidatesConfig(definition, field, kinds, query),
        kinds,
        CONTEXT,
      ).filter;

    expect(narrowed(warehouse, '  ber ')).toEqual({
      op: 'CONTAINS',
      field: 'warehouse',
      value: 'ber',
      stringComparison: 'CASE_INSENSITIVE',
    });
    expect(narrowed(warehouse, '   ')).toEqual({ op: 'MATCH_ALL' });

    const prefixOnly: FieldDefinition = {
      ...warehouse,
      operators: ['EQ', 'STARTS_WITH'],
    };
    expect(valueCandidateNarrowing(prefixOnly, kinds)).toBe('STARTS_WITH');
    expect(narrowed(prefixOnly, 'ber')).toMatchObject({ op: 'STARTS_WITH' });

    const exactOnly: FieldDefinition = {
      ...warehouse,
      operators: ['EQ', 'IN'],
    };
    expect(valueCandidateNarrowing(exactOnly, kinds)).toBeNull();
    expect(narrowed(exactOnly, 'ber')).toEqual({ op: 'MATCH_ALL' });
  });

  it('reads the probe row as more values, and leaves out what cannot be compared with', () => {
    const definition = ordersDefinition();
    const config = {
      ...valueCandidatesConfig(
        definition,
        fieldOf(definition, 'warehouse'),
        kinds,
      ),
      limit: 3,
    };
    const rows: RecordData[] = [
      { value: 'CN', count: 5 },
      { value: '', count: 4 },
      { value: 7, count: 3 },
      { value: 'JP', count: 2 },
    ];
    expect(readValueCandidates(definition, config, rows)).toEqual({
      values: [{ value: 'CN', count: 5 }],
      complete: false,
    });
    expect(readValueCandidates(definition, config, rows.slice(0, 3))).toEqual({
      values: [{ value: 'CN', count: 5 }],
      complete: true,
    });
    // A count that did not come back as a number is no count at all.
    expect(
      readValueCandidates(definition, config, [{ value: 'CN' }]).values,
    ).toEqual([{ value: 'CN', count: 0 }]);
  });

  it('narrows candidates in hand the way the source would', () => {
    const definition = ordersDefinition();
    const warehouse = fieldOf(definition, 'warehouse');
    const values = [
      { value: 'Berlin', count: 3 },
      { value: 'Alberta', count: 2 },
    ];
    expect(
      narrowValueCandidates(values, warehouse, kinds, 'BER').map(v => v.value),
    ).toEqual(['Berlin', 'Alberta']);
    expect(
      narrowValueCandidates(
        values,
        { ...warehouse, operators: ['EQ', 'STARTS_WITH'] },
        kinds,
        'ber',
      ).map(v => v.value),
    ).toEqual(['Berlin']);
    expect(
      narrowValueCandidates(
        values,
        { ...warehouse, stringComparison: 'CASE_SENSITIVE' },
        kinds,
        'BER',
      ),
    ).toEqual([]);
    expect(narrowValueCandidates(values, warehouse, kinds, ' ')).toEqual(
      values,
    );
  });
});

function answering(rows: (query: AggregationQuery) => RecordData[]) {
  const aggregate = vi.fn(
    (query: AggregationQuery, _attributes?: unknown, abort?: AbortController) =>
      Promise.resolve(rows(query)).then(answer => {
        abort?.signal.throwIfAborted();
        return answer;
      }),
  );
  return { source: testSource({ aggregate }) as ViewSource, aggregate };
}

function openOn(source: ViewSource, definition = ordersDefinition()) {
  const engine = new ViewEngine({
    definitions: [definition],
    store: new MemoryViewStore({ instances: [] }),
    resolveSource: () => source,
    environment: testEnvironment().environment,
  });
  return engine.create('orders', {
    title: 'Scratch',
    scope: 'personal',
    config: recordConfig(),
  });
}

describe('a view runtime offers value candidates', () => {
  it('asks once per question within the injected scope, and keeps the answer for the life of the view', async () => {
    const { source, aggregate } = answering(() => [
      { value: 'CN', count: 2 },
      { value: 'JP', count: 1 },
    ]);
    const runtime = openOn(source);
    const warehouse = runtime.valueCandidates('warehouse');
    // One source per field, so an editor may key an effect on it.
    expect(runtime.valueCandidates('warehouse')).toBe(warehouse);
    expect(runtime.valueCandidates('status')).toBeNull();

    await expect(warehouse!.search('')).resolves.toEqual({
      values: [
        { value: 'CN', count: 2 },
        { value: 'JP', count: 1 },
      ],
      complete: true,
    });
    await warehouse!.search('  ');
    // The whole list is known: narrowing it asks nothing more.
    await expect(warehouse!.search('j')).resolves.toEqual({
      values: [{ value: 'JP', count: 1 }],
      complete: true,
    });
    expect(aggregate).toHaveBeenCalledTimes(1);

    // Another scope is another set of records: asked again, under it.
    runtime.setScopeFilter({
      op: 'and',
      children: [{ field: 'status', operator: 'EQ', value: 'PENDING' }],
    });
    const before = aggregate.mock.calls.length;
    await warehouse!.search('');
    expect(aggregate.mock.calls.length).toBe(before + 1);
    expect(
      aggregate.mock.calls[aggregate.mock.calls.length - 1][0].filter,
    ).toEqual({
      op: 'EQ',
      field: 'status',
      value: 'PENDING',
    });
    runtime.dispose();
  });

  /**
   * The values offered are the data's, and a refresh is the data read
   * again: kept past it, the list would offer values the rows no longer
   * hold, with counts they no longer have.
   */
  it('asks again after the view is refreshed', async () => {
    const { source, aggregate } = answering(() => [{ value: 'CN', count: 2 }]);
    const runtime = openOn(source);
    const warehouse = runtime.valueCandidates('warehouse')!;

    await warehouse.search('');
    await warehouse.search('');
    expect(aggregate).toHaveBeenCalledTimes(1);

    runtime.refresh();
    await warehouse.search('');
    expect(aggregate).toHaveBeenCalledTimes(2);
    runtime.dispose();
  });

  it('asks the source to narrow a list it could not answer whole', async () => {
    const { source, aggregate } = answering(query =>
      query.filter?.op === 'CONTAINS'
        ? [{ value: 'Berlin', count: 1 }]
        : Array.from({ length: 51 }, (_, at) => ({
            value: `W${at}`,
            count: 100 - at,
          })),
    );
    const runtime = openOn(source);
    const warehouse = runtime.valueCandidates('warehouse')!;

    const top = await warehouse.search('');
    expect(top.values).toHaveLength(50);
    expect(top.complete).toBe(false);

    await expect(warehouse.search('ber')).resolves.toEqual({
      values: [{ value: 'Berlin', count: 1 }],
      complete: true,
    });
    await warehouse.search('ber');
    expect(aggregate).toHaveBeenCalledTimes(2);
    runtime.dispose();
  });

  it('narrows in hand a field the source cannot narrow', async () => {
    const base = ordersDefinition();
    const definition = {
      ...base,
      fields: base.fields.map(field =>
        field.name === 'warehouse'
          ? { ...field, operators: ['EQ' as const, 'IN' as const] }
          : field,
      ),
    };
    const { source, aggregate } = answering(() =>
      Array.from({ length: 51 }, (_, at) => ({ value: `W${at}`, count: 1 })),
    );
    const runtime = openOn(source, definition);
    const found = await runtime.valueCandidates('warehouse')!.search('w49');
    expect(found).toEqual({
      values: [{ value: 'W49', count: 1 }],
      complete: false,
    });
    expect(aggregate).toHaveBeenCalledTimes(1);
    expect(aggregate.mock.calls[0][0].filter).toEqual({ op: 'MATCH_ALL' });
    runtime.dispose();
  });

  it("stops with the caller's signal and keeps nothing it did not finish", async () => {
    const { source, aggregate } = answering(() => [{ value: 'CN', count: 1 }]);
    const runtime = openOn(source);
    const warehouse = runtime.valueCandidates('warehouse')!;
    const controller = new AbortController();
    const asked = warehouse.search('', controller.signal);
    controller.abort();
    await expect(asked).rejects.toThrow();
    await warehouse.search('');
    expect(aggregate).toHaveBeenCalledTimes(2);
    runtime.dispose();
  });

  it('refuses a question the definition cannot take, and passes on what the source threw', async () => {
    const failing = testSource({
      aggregate: vi.fn(() => Promise.reject(new Error('down'))),
    });
    const runtime = openOn(failing);
    await expect(
      runtime.valueCandidates('warehouse')!.search(''),
    ).rejects.toThrow('down');
    runtime.dispose();

    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [] }),
      resolveSource: () => testSource(),
      limits: { ...DEFAULT_RUNTIME_LIMITS, maxAnalysisRows: 0 },
    });
    const starved = engine.create('orders', {
      title: 'Scratch',
      scope: 'personal',
      config: recordConfig(),
    });
    await expect(
      starved.valueCandidates('warehouse')!.search(''),
    ).rejects.toThrow('The values of warehouse cannot be asked for');
    starved.dispose();
  });
});
