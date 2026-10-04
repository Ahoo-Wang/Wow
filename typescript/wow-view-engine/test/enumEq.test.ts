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

import { FilterOperator } from '@ahoo-wang/wow-client';
import { describe, expect, it, vi } from 'vitest';
import {
  builtinFieldKinds,
  compileFilter,
  describeFilter,
  MemoryViewStore,
  validateFilter,
  ViewEngine,
  type DataViewDefinition,
  type FieldDefinition,
  type FilterLeaf,
  type FilterTree,
  type Issue,
  type ViewInstance,
} from '../src/index.js';
import { readFilter, withFieldKinds } from '../src/filter/index.js';
import {
  analysisConfig,
  NOW,
  nextTask,
  ordersDefinition,
  recordConfig,
  requireRecordConfig,
  resourcesOf,
  testEnvironment,
  testSource,
} from './fixtures.js';

/**
 * Todo G: a field that was a string when a view was saved, and is an enum
 * now. Its saved `EQ` conditions are read as the `IN` of their one value
 * (`enumFieldKind.readLeaf`), so they still admit and compile rather than
 * being refused for an operator an enum does not offer.
 */
const STATUS: FieldDefinition = {
  name: 'status',
  label: 'Status',
  kind: 'enum',
  options: [
    { value: 'PENDING', label: 'Pending' },
    { value: 'SHIPPED', label: 'Shipped' },
  ],
};

const fields: FieldDefinition[] = [
  { name: 'id', label: 'Order', kind: 'string' },
  STATUS,
];

function tree(...children: FilterLeaf[]): FilterTree {
  return { op: 'and', children };
}

const errors = (found: Issue[]) =>
  found.filter(entry => entry.severity === 'error').map(entry => entry.code);

describe('an EQ saved on a field since made an enum', () => {
  const eq = (value: unknown): FilterLeaf => ({
    field: 'status',
    operator: 'EQ',
    value: value as FilterLeaf['value'],
  });

  it('is admitted', () => {
    expect(
      errors(validateFilter(fields, tree(eq('PENDING')), builtinFieldKinds)),
    ).toEqual([]);
  });

  it('compiles as the IN of its one value', () => {
    expect(
      compileFilter(fields, tree(eq('PENDING')), builtinFieldKinds, {
        now: NOW,
        timeZone: 'UTC',
      }),
    ).toMatchObject({
      op: FilterOperator.IN,
      field: 'status',
      values: ['PENDING'],
    });
  });

  it('is summarised as that IN', () => {
    const [item] = describeFilter(
      fields,
      tree(eq('PENDING')),
      builtinFieldKinds,
    );
    expect(item).toMatchObject({ operator: 'IN', text: 'Status IN Pending' });
  });

  it('is still judged by the enum: a value it does not list is refused', () => {
    expect(
      errors(validateFilter(fields, tree(eq('GONE')), builtinFieldKinds)),
    ).toEqual(['filter.value.unknown-option']);
  });

  it('left blank, stays blank and narrows nothing', () => {
    expect(
      errors(validateFilter(fields, tree(eq('')), builtinFieldKinds)),
    ).toEqual([]);
    expect(
      compileFilter(fields, tree(eq('')), builtinFieldKinds, {
        now: NOW,
        timeZone: 'UTC',
      }),
    ).toMatchObject({ op: FilterOperator.MATCH_ALL });
  });

  it('is read wherever it sits in the tree, and nothing else is copied', () => {
    const kept = { field: 'id', operator: 'EQ' as const, value: 'A' };
    const inner = { op: 'or' as const, children: [kept, eq('SHIPPED')] };
    const stored: FilterTree = { op: 'and', children: [kept, inner] };
    const read = readFilter(fields, stored, builtinFieldKinds);
    expect(read).toEqual({
      op: 'and',
      children: [
        kept,
        {
          op: 'or',
          children: [
            kept,
            { field: 'status', operator: 'IN', value: ['SHIPPED'] },
          ],
        },
      ],
    });
    expect(stored.children[1]).toBe(inner);
    expect(readFilter(fields, tree(kept), builtinFieldKinds)).toEqual(
      tree(kept),
    );
  });

  it('keeps a list written under EQ a list, for the enum to judge', () => {
    expect(
      readFilter(fields, tree(eq(['PENDING'])), builtinFieldKinds).children,
    ).toEqual([{ field: 'status', operator: 'IN', value: ['PENDING'] }]);
  });

  it('leaves a leaf as it came where a host kind cannot read it', () => {
    const kinds = withFieldKinds(builtinFieldKinds, [
      {
        ...builtinFieldKinds.get('enum')!,
        readLeaf() {
          throw new Error('boom');
        },
      },
    ]);
    const stored = tree(eq('PENDING'));
    expect(readFilter(fields, stored, kinds)).toBe(stored);
  });

  it('left null, stays blank and narrows nothing', () => {
    expect(
      readFilter(fields, tree(eq(null)), builtinFieldKinds).children,
    ).toEqual([{ field: 'status', operator: 'IN', value: [] }]);
    expect(
      errors(validateFilter(fields, tree(eq(null)), builtinFieldKinds)),
    ).toEqual([]);
    expect(
      compileFilter(fields, tree(eq(null)), builtinFieldKinds, {
        now: NOW,
        timeZone: 'UTC',
      }),
    ).toMatchObject({ op: FilterOperator.MATCH_ALL });
  });

  it('is not offered: the enum still offers no EQ to pick', () => {
    expect(builtinFieldKinds.get('enum')?.operators).not.toContain('EQ');
  });
});

describe('a saved view whose condition was written before its field became an enum', () => {
  /** What the view was saved over: `status` a string, compared with `EQ`. */
  const saved: ViewInstance = {
    id: 'pending-orders',
    definitionId: 'orders',
    title: 'Pending',
    scope: 'shared',
    revision: '1',
    config: recordConfig({
      filter: tree({ field: 'status', operator: 'EQ', value: 'PENDING' }),
    }),
  };

  /** The definition of this release: `status` is an enum now. */
  function enumOrders(): DataViewDefinition {
    const definition = ordersDefinition();
    return {
      ...definition,
      fields: definition.fields.map(field =>
        field.name === 'status' ? STATUS : field,
      ),
    };
  }

  function engineOver(instances: ViewInstance[]) {
    const source = testSource();
    const store = new MemoryViewStore({ instances });
    const issues: Issue[] = [];
    const engine = new ViewEngine({
      resources: resourcesOf([enumOrders()], () => source),
      store,
      environment: testEnvironment().environment,
      onIssue: found => issues.push(found),
    });
    return { engine, source, store, issues };
  }

  it('opens, runs as IN, reads as IN, and is not dirty', async () => {
    const { engine, source, issues } = engineOver([saved]);

    const runtime = await engine.open('pending-orders');
    await nextTask();
    const snapshot = runtime.getSnapshot();

    expect(errors(snapshot.issues)).toEqual([]);
    expect(snapshot.query.status).toBe('success');
    const [query] = vi.mocked(source.paged).mock.calls[0] ?? [];
    expect(query?.filter).toMatchObject({
      op: FilterOperator.IN,
      field: 'status',
      values: ['PENDING'],
    });
    // The editor and the bar hold the condition as the enum offers it.
    expect(requireRecordConfig(snapshot.draft).filter.children).toEqual([
      { field: 'status', operator: 'IN', value: ['PENDING'] },
    ]);
    expect(snapshot.dirty).toBe(false);
    expect(issues).toEqual([]);
  });

  it('is saved back as IN', async () => {
    const { engine, store } = engineOver([saved]);
    const runtime = await engine.open('pending-orders');
    await nextTask();

    await engine.save(runtime);

    const stored = await store.get('pending-orders');
    expect(requireRecordConfig(stored.config).filter.children).toEqual([
      { field: 'status', operator: 'IN', value: ['PENDING'] },
    ]);
  });

  it('reads an analysis metric’s own EQ as IN too, in the editor as in the query', async () => {
    const analysis: ViewInstance = {
      ...saved,
      id: 'pending-count',
      config: analysisConfig({
        metrics: [
          {
            alias: 'orders',
            type: 'COUNT',
            filter: tree({ field: 'status', operator: 'EQ', value: 'PENDING' }),
          },
        ],
      }),
    };
    const { engine, source, issues } = engineOver([analysis]);

    const runtime = await engine.open('pending-count');
    await nextTask();
    const snapshot = runtime.getSnapshot();

    expect(errors(snapshot.issues)).toEqual([]);
    expect(snapshot.query.status).toBe('success');
    expect(JSON.stringify(vi.mocked(source.aggregate).mock.calls[0])).toContain(
      '"values":["PENDING"]',
    );
    if (snapshot.draft.kind !== 'analysis') throw new Error('analysis');
    const [metric] = snapshot.draft.metrics;
    expect(metric && 'filter' in metric ? metric.filter : null).toEqual(
      tree({ field: 'status', operator: 'IN', value: ['PENDING'] }),
    );
    expect(snapshot.dirty).toBe(false);
    expect(issues).toEqual([]);
  });
});
