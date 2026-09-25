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

import { describe, expect, it } from 'vitest';
import {
  drillConditions,
  drillFilter,
  drillGroups,
} from '../src/analysis/index.js';
import { drillGap, drillSpan } from '../src/analysis/drill.js';
import { builtinFieldKinds, validateFilter } from '../src/filter/index.js';
import type {
  AnalysisGroup,
  FieldDefinition,
  FilterTree,
} from '../src/model/index.js';
import { analysisConfig } from './fixtures.js';

/**
 * A group of an analysis over expanded elements, followed back to the
 * records it was counted from (D38): the records are root documents, so the
 * group's conditions are asked of one element of the array — an element
 * match — together with the gate that element was counted under.
 */

const ITEMS: FieldDefinition = {
  name: 'items',
  label: 'Items',
  kind: 'elementMatch',
  elements: [
    { name: 'sku', label: 'SKU', kind: 'string' },
    { name: 'qty', label: 'Qty', kind: 'number' },
    { name: 'placedAt', label: 'Placed', kind: 'datetime' },
    {
      name: 'batches',
      label: 'Batches',
      kind: 'elementMatch',
      elements: [{ name: 'lot', label: 'Lot', kind: 'string' }],
    },
  ],
};
const FIELDS: FieldDefinition[] = [
  { name: 'warehouse', label: 'Warehouse', kind: 'string' },
  ITEMS,
];
const context = { timeZone: 'Asia/Shanghai' };

const SKU: AnalysisGroup = { alias: 'sku', field: 'items.sku', type: 'TERMS' };
const QTY: AnalysisGroup = {
  alias: 'qty',
  field: 'items.qty',
  type: 'HISTOGRAM',
  interval: 10,
};
const GATE: FilterTree = {
  op: 'and',
  children: [{ field: 'items.qty', operator: 'GTE', value: 2 }],
};

describe('a group counted over one level of elements', () => {
  it('is one element match: its gate and every dimension, asked of one element', () => {
    const config = analysisConfig({
      elements: [{ path: 'items', filter: GATE }],
      groups: [SKU, QTY],
    });
    const conditions = drillConditions(
      config,
      FIELDS,
      builtinFieldKinds,
      { sku: 'A-1', qty: 20 },
      context,
    );
    // The gate bounds a field a band bounds too, which one group may not say
    // twice, so the gate is a group of its own inside the one match.
    expect(conditions).toEqual([
      {
        field: 'items',
        operator: 'ELEMENT_MATCH',
        value: {
          op: 'and',
          children: [
            GATE,
            { field: 'items.sku', operator: 'EQ', value: 'A-1' },
            { field: 'items.qty', operator: 'GTE', value: 20 },
            { field: 'items.qty', operator: 'LT', value: 30 },
          ],
        },
      },
    ]);
    // A condition the record view admits as it stands: the drilled view
    // opens on it, not on a finding.
    expect(
      validateFilter(
        FIELDS,
        drillFilter({ op: 'and', children: [] }, conditions!),
        builtinFieldKinds,
      ),
    ).toEqual([]);
  });

  it('reads the gate and the groups as one "all of" where they bound different fields', () => {
    expect(
      drillConditions(
        analysisConfig({
          elements: [{ path: 'items', filter: GATE }],
          groups: [SKU],
        }),
        FIELDS,
        builtinFieldKinds,
        { sku: 'A-1' },
        context,
      ),
    ).toEqual([
      {
        field: 'items',
        operator: 'ELEMENT_MATCH',
        value: {
          op: 'and',
          children: [
            { field: 'items.qty', operator: 'GTE', value: 2 },
            { field: 'items.sku', operator: 'EQ', value: 'A-1' },
          ],
        },
      },
    ]);
  });

  it('keeps each dimension named as the element names it, for the menu', () => {
    const drilled = drillGroups(
      analysisConfig({ elements: [{ path: 'items' }], groups: [SKU] }),
      FIELDS,
      builtinFieldKinds,
      { sku: 'A-1' },
      context,
    );
    expect(
      drilled?.map(entry => [entry.group.alias, entry.conditions]),
    ).toEqual([
      ['sku', [{ field: 'items.sku', operator: 'EQ', value: 'A-1' }]],
    ]);
  });

  it('reads an element with no gate as the dimensions alone', () => {
    expect(
      drillConditions(
        analysisConfig({ elements: [{ path: 'items' }], groups: [SKU] }),
        FIELDS,
        builtinFieldKinds,
        { sku: null },
        context,
      ),
    ).toEqual([
      {
        field: 'items',
        operator: 'ELEMENT_MATCH',
        value: {
          op: 'and',
          children: [{ field: 'items.sku', operator: 'IS_NULL', value: null }],
        },
      },
    ]);
  });

  it('spans a stretch of an element’s time inside the one match', () => {
    const DAYS: AnalysisGroup = {
      alias: 'day',
      field: 'items.placedAt',
      type: 'DATE_HISTOGRAM',
      unit: 'DAY',
    };
    const first = Date.parse('2026-09-01T00:00:00+08:00');
    const last = Date.parse('2026-09-03T00:00:00+08:00');
    const drilled = drillSpan(
      analysisConfig({ elements: [{ path: 'items' }], groups: [DAYS] }),
      FIELDS,
      builtinFieldKinds,
      { day: first },
      { day: last },
      context,
    );
    expect(drilled?.[0]?.conditions).toEqual([
      {
        field: 'items.placedAt',
        operator: 'BETWEEN',
        value: {
          type: 'absolute',
          from: '2026-08-31T16:00:00.000Z',
          to: '2026-09-03T15:59:59.999Z',
          timeZone: 'Asia/Shanghai',
        },
      },
    ]);
  });

  it('says nothing where the array is not one a condition can match into', () => {
    const plain: FieldDefinition[] = [{ ...ITEMS, kind: 'array' }];
    expect(
      drillConditions(
        analysisConfig({ elements: [{ path: 'items' }], groups: [SKU] }),
        plain,
        builtinFieldKinds,
        { sku: 'A-1' },
        context,
      ),
    ).toBeNull();
    // Nor where the definition narrows it to other questions.
    expect(
      drillConditions(
        analysisConfig({ elements: [{ path: 'items' }], groups: [SKU] }),
        [{ ...ITEMS, operators: ['IS_EMPTY'] }],
        builtinFieldKinds,
        { sku: 'A-1' },
        context,
      ),
    ).toBeNull();
  });
});

describe('a group counted over elements of elements', () => {
  const config = analysisConfig({
    elements: [{ path: 'items' }, { path: 'batches' }],
    groups: [{ alias: 'lot', field: 'items.batches.lot', type: 'TERMS' }],
  });

  it('is not followed to records, and says why', () => {
    expect(drillGap(config)).toBe('nested-elements');
    expect(
      drillConditions(
        config,
        FIELDS,
        builtinFieldKinds,
        { lot: 'L-7' },
        context,
      ),
    ).toBeNull();
  });

  it('still names the group, as the innermost element names it', () => {
    expect(
      drillGroups(
        config,
        FIELDS,
        builtinFieldKinds,
        { lot: 'L-7' },
        context,
      )?.flatMap(entry => entry.conditions),
    ).toEqual([{ field: 'items.batches.lot', operator: 'EQ', value: 'L-7' }]);
  });

  it('has no gap over one level, or none', () => {
    expect(drillGap(analysisConfig())).toBeUndefined();
    expect(
      drillGap(analysisConfig({ elements: [{ path: 'items' }] })),
    ).toBeUndefined();
  });
});
