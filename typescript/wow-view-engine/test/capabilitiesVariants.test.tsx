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
import type {
  FieldDescriptor,
  QueryModelDescriptor,
} from '@ahoo-wang/wow-client';
import {
  builtinFieldKinds,
  compileFilter,
  MemoryViewStore,
  ViewEngine,
  type DataViewDefinition,
  type FieldDefinition,
  type FilterTree,
  type ViewInstance,
} from '../src/index.js';
import { narrowDefinition } from '../src/capabilities/index.js';
import { variantGroups, writeValue } from '../src/filter/index.js';
import { DataWorkbench } from '../src/ui/index.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';
import {
  describedField,
  ordersDescriptor,
  read,
} from './fixtures/descriptor.js';

afterEach(cleanup);

/** An event stream: `body` holds events, whose payload depends on `bodyType`. */
const body: FieldDefinition = {
  name: 'body',
  label: 'Events',
  kind: 'elementMatch',
  elements: [
    {
      name: 'bodyType',
      label: 'Event',
      kind: 'enum',
      options: [
        { value: 'Created', label: 'Created' },
        { value: 'Failed', label: 'Failed' },
      ],
    },
    { name: 'body.orderId', label: 'Order', kind: 'string' },
    { name: 'body.error', label: 'Error', kind: 'string' },
  ],
};

const stream: DataViewDefinition = ordersDefinition({
  fields: [...ordersDefinition().fields, body],
});

function variantField(path: string): FieldDescriptor {
  return { ...describedField(path), aliases: [] };
}

function streamDescriptor(): QueryModelDescriptor {
  const base = ordersDescriptor();
  return {
    ...base,
    model: 'EVENT_STREAM',
    fields: [
      ...base.fields,
      describedField('body'),
      describedField('body.bodyType', { scope: 'body' }),
    ],
    elements: [{ path: 'body', filter: true, aggregate: true }],
    variants: {
      element: 'body',
      discriminator: 'bodyType',
      values: [
        {
          value: 'Created',
          fields: [variantField('body.orderId')],
        },
        {
          value: 'Failed',
          fields: [variantField('body.orderId'), variantField('body.error')],
        },
      ],
    },
  };
}

const context = { now: new Date('2026-09-25T00:00:00Z'), timeZone: 'UTC' };

describe('an element whose fields differ by variant (#3519)', () => {
  it('finds a payload field in the variants that have it', () => {
    const { definition, findings } = narrowDefinition(
      stream,
      streamDescriptor(),
      builtinFieldKinds,
    );
    const events = definition.fields.find(field => field.name === 'body');

    expect(findings).toEqual([]);
    expect(events?.variantKey).toBe('bodyType');
    expect(events?.elements?.map(element => element.variants)).toEqual([
      undefined,
      ['Created', 'Failed'],
      ['Failed'],
    ]);
  });

  it('holds a condition on a payload field to the variants that have it', () => {
    const { definition } = narrowDefinition(
      stream,
      streamDescriptor(),
      builtinFieldKinds,
    );
    const tree = (children: FilterTree['children']): FilterTree => ({
      op: 'and',
      children: [
        {
          field: 'body',
          operator: 'ELEMENT_MATCH',
          value: writeValue({ op: 'and', children }),
        },
      ],
    });

    expect(
      compileFilter(
        definition.fields,
        tree([{ field: 'body.body.error', operator: 'EQ', value: 'x' }]),
        builtinFieldKinds,
        context,
      ),
    ).toEqual({
      op: 'ELEMENT_MATCH',
      field: 'body',
      predicate: {
        op: 'AND',
        operands: [
          { op: 'EQ', field: 'body.error', value: 'x' },
          { op: 'IN', field: 'bodyType', values: ['Failed'] },
        ],
      },
    });
    // The variant said already, or no variant's field: as written.
    expect(
      compileFilter(
        definition.fields,
        tree([
          { field: 'body.body.error', operator: 'EQ', value: 'x' },
          { field: 'body.bodyType', operator: 'IN', value: ['Failed'] },
        ]),
        builtinFieldKinds,
        context,
      ),
    ).toMatchObject({ predicate: { op: 'AND', operands: [{}, {}] } });
    expect(
      compileFilter(
        stream.fields,
        tree([{ field: 'body.body.error', operator: 'EQ', value: 'x' }]),
        builtinFieldKinds,
        context,
      ),
    ).toEqual({
      op: 'ELEMENT_MATCH',
      field: 'body',
      predicate: { op: 'EQ', field: 'body.error', value: 'x' },
    });
  });

  it('lists a payload field under the one variant that has it', () => {
    const { definition } = narrowDefinition(
      stream,
      streamDescriptor(),
      builtinFieldKinds,
    );
    const events = definition.fields.find(field => field.name === 'body')!;

    expect(variantGroups(events)).toEqual([
      { id: 'variant:Failed', label: 'Failed', fields: ['body.body.error'] },
    ]);
    expect(variantGroups(body)).toEqual([]);
  });

  it('groups the predicate picker by variant in the workbench', async () => {
    const saved: ViewInstance = {
      id: 'events',
      definitionId: 'orders',
      title: 'Events',
      scope: 'personal',
      revision: '1',
      config: recordConfig({
        filterMode: 'advanced',
        filter: {
          op: 'and',
          children: [
            {
              field: 'body',
              operator: 'ELEMENT_MATCH',
              value: { op: 'and', children: [] },
            },
          ],
        },
      }),
    };
    const descriptor = streamDescriptor();
    const engine = new ViewEngine({
      definitions: [stream],
      store: new MemoryViewStore({ instances: [saved] }),
      resolveSource: () =>
        testSource({ describe: () => Promise.resolve(read(descriptor)) }),
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="events"
      />,
    );
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: /^Filter/ }));
    // The predicate's own, named for the events condition it sits in.
    await user.click(
      await screen.findByRole('button', { name: 'Events Add condition' }),
    );

    const picker = await screen.findByRole('dialog');
    expect(within(picker).getByText('Failed')).toBeTruthy();
    expect(
      within(picker).getByRole('checkbox', { name: 'Error' }),
    ).toBeTruthy();
  });
});
