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

import { AggregationGroupType } from '@ahoo-wang/wow-client/dsl';
import { describe, expect, it, vi } from 'vitest';
import { builtinFieldKinds } from '../src/filter/index.js';
import type {
  AnalysisViewConfig,
  FieldDefinition,
  ViewRuntime,
} from '../src/index.js';
import { followUpOf, type FollowUpSource } from '../src/react/followUp.js';
import { analysisConfig } from './fixtures.js';

const FIELDS: FieldDefinition[] = [
  { name: 'warehouse', label: 'Warehouse', kind: 'string' },
  { name: 'customer', label: 'Customer', kind: 'reference', remote: 'people' },
];

/** Grouped by the customer, a reference: its id goes in by its name. */
const ran = analysisConfig({
  groups: [
    {
      alias: 'customer',
      field: 'customer',
      type: AggregationGroupType.TERMS,
    },
  ],
});

/** The parts of a runtime a follow-up reads, and nothing else. */
const runtime = {
  environment: { timeZone: 'Asia/Shanghai' },
  labels: {
    text: (remote: string, id: string | number) => `${remote}:${id}`,
  },
  fields: FIELDS,
  kinds: builtinFieldKinds,
  definition: { title: 'Orders' },
} as unknown as ViewRuntime<AnalysisViewConfig>;

function source(overrides: Partial<FollowUpSource> = {}): FollowUpSource {
  return {
    pickable: true,
    ran,
    runtime,
    workbench: {
      state: null,
      canDrill: true,
      drill: vi.fn(),
      follow: vi.fn(),
    },
    analysis: { fields: [], layout: 'table' },
    chart: ran.chart,
    gap: undefined,
    expanded: false,
    moments: new Set(),
    ...overrides,
  };
}

describe('followUpOf', () => {
  it('names a reference group by its label, read off the runtime', () => {
    const from = source();
    const followUp = followUpOf(from, { customer: 'c-1' });
    const records = followUp?.actions.find(action => action.kind === 'records');
    if (records?.kind !== 'records' || records.gap) throw new Error('records');
    records.run('named records');
    expect(from.workbench.drill).toHaveBeenCalledWith(
      [
        expect.objectContaining({
          field: 'customer',
          value: { items: [{ id: 'c-1', label: 'people:c-1' }] },
        }),
      ],
      'named records',
      'Orders',
    );
  });

  it('heads a view with no screen state by no subject', () => {
    const followUp = followUpOf(source(), { customer: 'c-1' });
    // Nothing else is groupable here, so no split: the records and the focus.
    expect(
      followUp?.actions.map(action => [
        action.kind,
        'subject' in action ? action.subject : undefined,
      ]),
    ).toEqual([
      ['records', 'Orders'],
      ['focus', ''],
    ]);
  });

  it('offers only the records over expanded elements, or nothing', () => {
    const expanded = followUpOf(source({ expanded: true }), {
      customer: 'c-1',
    });
    expect(expanded?.actions.map(action => action.kind)).toEqual(['records']);
    expect(expanded?.groups).toHaveLength(1);

    const workbench = { ...source().workbench, canDrill: false };
    expect(
      followUpOf(source({ expanded: true, workbench }), { customer: 'c-1' }),
    ).toBeNull();
  });
});
