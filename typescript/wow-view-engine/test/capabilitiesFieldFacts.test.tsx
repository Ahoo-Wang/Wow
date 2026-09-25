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
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
import {
  builtinFieldKinds,
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  validateFilter,
  validateRecord,
  ViewEngine,
  type ViewInstance,
} from '../src/index.js';
import { narrowDefinition } from '../src/capabilities/index.js';
import { validateDataConfig } from '../src/runtime/execute.js';
import { DataWorkbench } from '../src/ui/index.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';
import {
  describedField,
  ordersDescriptor,
  read,
} from './fixtures/descriptor.js';

afterEach(cleanup);

function withFields(
  fields: QueryModelDescriptor['fields'],
  overrides: Partial<QueryModelDescriptor> = {},
): QueryModelDescriptor {
  return ordersDescriptor({ fields, ...overrides });
}

const narrow = (descriptor: QueryModelDescriptor) =>
  narrowDefinition(ordersDefinition(), descriptor, builtinFieldKinds);

describe('a protected field (#3519)', () => {
  it('keeps its column, loses every comparison, and is said once as protected', () => {
    const { definition, findings } = narrow(
      withFields([
        ...ordersDescriptor().fields.filter(field => field.path !== 'status'),
        describedField('status', {
          sensitivity: { level: 'CONFIDENTIAL' as never, comparable: false },
          filter: { operators: [] },
          sort: { paged: false, cursor: true },
        }),
      ]),
    );

    const status = definition.fields.find(field => field.name === 'status');
    expect(status?.operators).toEqual([]);
    expect(findings.map(found => found.code)).toEqual([
      'capability.field.protected',
    ]);
  });
});

describe('a deprecated field (#3519)', () => {
  const deprecated = (message?: string) =>
    withFields(
      ordersDescriptor().fields.map(field =>
        field.path === 'warehouse'
          ? describedField('warehouse', {
              deprecated: message === undefined ? {} : { message },
            })
          : field,
      ),
    );

  it('is still offered and queried, and carries the reason', () => {
    const plain = narrow(deprecated());
    const because = narrow(deprecated('Use region.'));

    expect(
      plain.definition.fields.find(field => field.name === 'warehouse')
        ?.deprecated,
    ).toEqual({});
    expect(plain.findings.map(found => found.code)).toEqual([
      'capability.field.deprecated',
    ]);
    expect(because.findings).toEqual([
      expect.objectContaining({
        code: 'capability.field.deprecated-because',
        params: { field: 'warehouse', reason: 'Use region.' },
      }),
    ]);
  });

  it('warns a view that uses it, without holding its query', () => {
    const { definition } = narrow(deprecated('Use region.'));
    const context = {
      definition,
      kinds: builtinFieldKinds,
      limits: DEFAULT_RUNTIME_LIMITS,
    };
    const using = recordConfig({
      filter: {
        op: 'and',
        children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
      },
    });

    expect(validateDataConfig(context, using)).toEqual([
      {
        code: 'view.field.deprecated-because',
        severity: 'warning',
        path: [],
        params: { field: 'Warehouse', reason: 'Use region.' },
      },
    ]);
    expect(validateDataConfig(context, recordConfig())).toEqual([]);
  });

  it('wears a badge in the condition picker', async () => {
    const saved: ViewInstance = {
      id: 'mine',
      definitionId: 'orders',
      title: 'Mine',
      scope: 'personal',
      revision: '1',
      config: recordConfig(),
    };
    const descriptor = deprecated();
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [saved] }),
      resolveSource: () =>
        testSource({ describe: () => Promise.resolve(read(descriptor)) }),
    });
    render(
      <DataWorkbench engine={engine} definitionId="orders" instanceId="mine" />,
    );
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: /^Filter/ }));
    await user.click(await screen.findByRole('button', { name: 'Add' }));
    const picker = await screen.findByRole('dialog');
    const box = within(picker).getByRole('checkbox', { name: /Warehouse/ });
    expect(box.closest('[data-slot="field"]')?.textContent).toContain(
      'Deprecated',
    );
  });
});

describe('the storage facts of #3515', () => {
  it('notes a presence condition on a field whose empty value reads as missing', () => {
    const { definition } = narrow(
      ordersDescriptor({
        constraints: [
          { type: 'NULL_OR_EMPTY_AS_MISSING', fields: ['warehouse'] },
        ],
      }),
    );
    expect(
      definition.fields.find(field => field.name === 'warehouse')
        ?.emptyIsMissing,
    ).toBe(true);

    const tree = {
      op: 'and' as const,
      children: [
        { field: 'warehouse', operator: 'IS_NOT_NULL' as const, value: null },
        { field: 'status', operator: 'IS_NULL' as const, value: null },
      ],
    };
    expect(validateFilter(definition.fields, tree, builtinFieldKinds)).toEqual([
      {
        code: 'filter.presence.empty-is-missing',
        severity: 'note',
        path: ['children', 0],
        params: { field: 'warehouse' },
      },
    ]);
  });

  it('refuses a sort by two arrays the source cannot order together', () => {
    const declared = ordersDefinition({
      fields: [
        ...ordersDefinition().fields,
        { name: 'tags', label: 'Tags', kind: 'array', sortable: true },
        { name: 'codes', label: 'Codes', kind: 'array', sortable: true },
      ],
    });
    const base = ordersDescriptor();
    const { definition } = narrowDefinition(
      declared,
      {
        ...base,
        fields: [
          ...base.fields,
          describedField('tags'),
          describedField('codes'),
        ],
        constraints: [
          { type: 'PARALLEL_ARRAY_SORT', fields: ['tags', 'codes'] },
        ],
      },
      builtinFieldKinds,
    );

    const codes = (sort: { field: string; direction: 'ASC' | 'DESC' }[]) =>
      validateRecord(definition, recordConfig({ sort }), builtinFieldKinds).map(
        found => found.code,
      );
    expect(
      codes([
        { field: 'tags', direction: 'ASC' },
        { field: 'amount', direction: 'DESC' },
        { field: 'codes', direction: 'ASC' },
      ]),
    ).toContain('record.sort.parallel-arrays');
    expect(
      codes([
        { field: 'tags', direction: 'ASC' },
        { field: 'amount', direction: 'DESC' },
      ]),
    ).not.toContain('record.sort.parallel-arrays');
  });

  it('never compares a whole array for equality (ARRAY_EQUALITY)', () => {
    // An array is matched by its elements; no built-in kind of a list
    // offers EQ or NE, so the engine never writes what the constraint refuses.
    for (const id of ['array', 'elementMatch'])
      expect(builtinFieldKinds.get(id)?.operators).not.toEqual(
        expect.arrayContaining(['EQ']),
      );
  });
});
