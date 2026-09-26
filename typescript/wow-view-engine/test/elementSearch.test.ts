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
  filter,
  SearchMode,
  type QueryModelDescriptor,
  type SearchDescriptor,
} from '@ahoo-wang/wow-client';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  compileFilter,
  describeFilter,
  validateAnalysis,
  validateDefinition,
  validateFilter,
  type AnalysisViewConfig,
  type FieldDefinition,
  type FilterTree,
} from '../src/index.js';
import { narrowDefinition } from '../src/capabilities/index.js';
import { ordersDefinition } from './fixtures.js';
import {
  analysisCapability,
  analysisDefinition,
  analysisKernelConfig,
  errorCodes,
} from './fixtures/analysis.js';
import { describedField, ordersDescriptor } from './fixtures/descriptor.js';

/**
 * N4: a search inside an element, where the source offers one. Declared as a
 * `search` field among the array's `elements`, its `searchFields` naming the
 * element's own fields; it compiles to a `SEARCH` with those fields inside
 * the `ELEMENT_MATCH`, relative to the element.
 */
const lineSearch: FieldDefinition = {
  name: 'q',
  label: 'Search lines',
  kind: 'search',
  searchFields: ['title', 'sku'],
  searchMode: 'PHRASE',
};

const lines: FieldDefinition = {
  name: 'lines',
  label: 'Lines',
  kind: 'elementMatch',
  elements: [
    { name: 'sku', label: 'SKU', kind: 'string' },
    { name: 'title', label: 'Title', kind: 'string' },
    lineSearch,
  ],
};

const context = { now: new Date('2026-09-25T00:00:00Z'), timeZone: 'UTC' };

function inLines(...children: FilterTree['children']): FilterTree {
  return {
    op: 'and',
    children: [
      {
        field: 'lines',
        operator: 'ELEMENT_MATCH',
        value: { op: 'and', children } as never,
      },
    ],
  };
}

const searching = (text: string) =>
  inLines({ field: 'lines.q', operator: 'SEARCH', value: text });

describe('a search inside an element (N4)', () => {
  it('is admitted inside the element match when it names the element fields', () => {
    expect(
      validateFilter([lines], searching('blue tea'), builtinFieldKinds),
    ).toEqual([]);
  });

  it('compiles to a SEARCH on the element fields, relative to the element', () => {
    expect(
      compileFilter(
        [lines],
        searching('  blue tea '),
        builtinFieldKinds,
        context,
      ),
    ).toEqual(
      filter.elementMatch(
        'lines',
        filter.search('blue tea', {
          fields: ['title', 'sku'],
          mode: SearchMode.PHRASE,
        }),
      ),
    );
  });

  it('sits beside the other conditions on the same entry', () => {
    expect(
      compileFilter(
        [lines],
        inLines(
          { field: 'lines.sku', operator: 'EQ', value: 'TEA-01' },
          { field: 'lines.q', operator: 'SEARCH', value: 'green' },
        ),
        builtinFieldKinds,
        context,
      ),
    ).toEqual(
      filter.elementMatch(
        'lines',
        filter.and([
          filter.eq('sku', 'TEA-01'),
          filter.search('green', {
            fields: ['title', 'sku'],
            mode: SearchMode.PHRASE,
          }),
        ]),
      ),
    );
  });

  it('searches an element of an element relative to the inner one', () => {
    const batches: FieldDefinition = {
      name: 'batches',
      label: 'Batches',
      kind: 'elementMatch',
      elements: [
        { name: 'lot', label: 'Lot', kind: 'string' },
        {
          name: 'q',
          label: 'Search lots',
          kind: 'search',
          searchFields: ['lot'],
        },
      ],
    };
    const nested: FieldDefinition = { ...lines, elements: [batches] };
    const tree = inLines({
      field: 'lines.batches',
      operator: 'ELEMENT_MATCH',
      value: {
        op: 'and',
        children: [
          { field: 'lines.batches.q', operator: 'SEARCH', value: 'L7' },
        ],
      } as never,
    });

    expect(validateFilter([nested], tree, builtinFieldKinds)).toEqual([]);
    expect(compileFilter([nested], tree, builtinFieldKinds, context)).toEqual(
      filter.elementMatch(
        'lines',
        filter.elementMatch(
          'batches',
          filter.search('L7', { fields: ['lot'], mode: SearchMode.TERMS }),
        ),
      ),
    );
  });

  it('still refuses a search that names no field, which asks about the whole record', () => {
    const anywhere: FieldDefinition = {
      ...lines,
      elements: [{ name: 'q', label: 'Search', kind: 'search' }],
    };

    expect(
      validateFilter([anywhere], searching('blue'), builtinFieldKinds).map(
        found => found.code,
      ),
    ).toContain('filter.element.root-filter');
  });

  it('is said in the summary of the element match', () => {
    const [item] = describeFilter(
      [lines],
      searching('blue'),
      builtinFieldKinds,
    );

    expect(item.text).toBe('Lines has an entry where Search lines blue');
    expect(item.items?.map(inner => inner.text)).toEqual(['Search lines blue']);
  });
});

describe('declaring an element search', () => {
  const definitionCodes = (fields: FieldDefinition[]) =>
    validateDefinition(
      ordersDefinition({ fields: [...ordersDefinition().fields, ...fields] }),
      builtinFieldKinds,
    ).map(found => found.code);

  it('admits one that names the element fields', () => {
    expect(definitionCodes([lines])).toEqual([]);
  });

  it('refuses one that names no field', () => {
    const anywhere: FieldDefinition = {
      ...lines,
      elements: [{ name: 'q', label: 'Search', kind: 'search' }],
    };
    const issues = validateDefinition(
      ordersDefinition({ fields: [...ordersDefinition().fields, anywhere] }),
      builtinFieldKinds,
    );

    expect(issues).toContainEqual(
      expect.objectContaining({
        code: 'definition.field.element-search-fields-required',
        severity: 'error',
        path: ['fields', 4, 'elements', 0, 'searchFields'],
      }),
    );
  });

  it('refuses one that names a field the element does not declare', () => {
    const astray: FieldDefinition = {
      ...lines,
      elements: [
        { name: 'sku', label: 'SKU', kind: 'string' },
        { ...lineSearch, searchFields: ['sku', 'amount'] },
      ],
    };

    expect(definitionCodes([astray])).toEqual([
      'definition.field.search-fields-unknown',
    ]);
  });

  it("keeps a record's search to the record's own fields (D39)", () => {
    expect(
      definitionCodes([
        lines,
        {
          name: 'keyword',
          label: 'Search',
          kind: 'search',
          searchFields: ['lines.title'],
        },
      ]),
    ).toEqual(['definition.field.search-fields-unknown']);
  });
});

describe('narrowing an element search to the descriptor', () => {
  const declared = ordersDefinition({
    fields: [...ordersDefinition().fields, lines],
  });
  /** Elasticsearch searches nested text; MongoDB (`search` absent) none. */
  const described = (search?: SearchDescriptor): QueryModelDescriptor => {
    const base = ordersDescriptor();
    return {
      ...base,
      fields: [
        ...base.fields,
        describedField('lines'),
        describedField('lines.sku', { scope: 'lines' }),
        describedField('lines.title', { scope: 'lines' }),
      ],
      elements: [
        {
          path: 'lines',
          filter: true,
          aggregate: true,
          ...(search ? { search } : {}),
        },
      ],
    };
  };
  const both = [SearchMode.TERMS, SearchMode.PHRASE];
  const narrowed = (descriptor: QueryModelDescriptor) => {
    const { definition, findings } = narrowDefinition(
      declared,
      descriptor,
      builtinFieldKinds,
    );
    const element = definition.fields
      .find(entry => entry.name === 'lines')
      ?.elements?.find(entry => entry.name === 'q');
    return { definition, element, findings };
  };

  it('keeps it where the element searches those fields (Elasticsearch)', () => {
    const { element, findings } = narrowed(
      described({ modes: both, fields: ['lines.sku', 'lines.title'] }),
    );

    expect(element).toEqual(lineSearch);
    expect(findings).toEqual([]);
  });

  it('takes it away where the element offers no search (MongoDB)', () => {
    const { definition, element, findings } = narrowed(described());

    expect(element?.operators).toEqual([]);
    expect(findings).toEqual([
      {
        code: 'capability.search.unavailable',
        severity: 'warning',
        path: ['fields', 4, 'elements', 2],
        params: { field: 'lines.q' },
      },
    ]);
    // A view saved where it was offered is no longer admitted (Q2).
    expect(
      validateFilter(definition.fields, searching('blue'), builtinFieldKinds)
        .length,
    ).toBeGreaterThan(0);
  });

  it('searches a phrase as words where the element matches words only', () => {
    const { element, findings } = narrowed(
      described({
        modes: [SearchMode.TERMS],
        fields: ['lines.sku', 'lines.title'],
      }),
    );

    expect(element?.searchMode).toBe('TERMS');
    expect(findings.map(found => `${found.severity} ${found.code}`)).toEqual([
      'note capability.search.as-terms',
    ]);
  });

  it('never narrows words into a phrase', () => {
    const words = ordersDefinition({
      fields: [
        ...ordersDefinition().fields,
        {
          ...lines,
          elements: [
            ...(lines.elements ?? []).slice(0, 2),
            { ...lineSearch, searchMode: 'TERMS' },
          ],
        },
      ],
    });
    const { definition } = narrowDefinition(
      words,
      described({
        modes: [SearchMode.PHRASE],
        fields: ['lines.sku', 'lines.title'],
      }),
      builtinFieldKinds,
    );

    expect(
      definition.fields.find(entry => entry.name === 'lines')?.elements?.[2]
        .operators,
    ).toEqual([]);
  });

  it('searches only the element fields the source searches, still relative', () => {
    const { element, findings } = narrowed(
      described({ modes: both, fields: ['lines.title'] }),
    );

    expect(element?.searchFields).toEqual(['title']);
    expect(findings).toEqual([
      expect.objectContaining({
        code: 'capability.search.fields-narrowed',
        params: { field: 'lines.q', fields: 'lines.sku' },
      }),
    ]);
    // None in common: no search at all.
    expect(
      narrowed(described({ modes: both, fields: ['lines.other'] })).element
        ?.operators,
    ).toEqual([]);
  });

  it("reads an element field named by an alias as the element's path", () => {
    const aliased = described({ modes: both, fields: ['lines.name'] });
    aliased.fields = aliased.fields.map(entry =>
      entry.path === 'lines.title'
        ? describedField('lines.name', {
            scope: 'lines',
            aliases: ['lines.title'],
          })
        : entry,
    );
    const { element } = narrowed(aliased);

    expect(element?.searchFields).toEqual(['name']);
  });

  it('never grants a record search to an element, nor the reverse', () => {
    const base = described();
    const { element } = narrowed({
      ...base,
      record: {
        ...base.record,
        search: { modes: both, fields: ['lines.title', 'lines.sku'] },
      },
    });

    expect(element?.operators).toEqual([]);
  });
});

describe("an analysis element's gate", () => {
  // Wow reads an aggregation element's filter with no `SEARCH` at all.
  const batches: FieldDefinition = {
    name: 'batches',
    label: 'Batches',
    kind: 'elementMatch',
    elements: [
      { name: 'lot', label: 'Lot', kind: 'string' },
      {
        name: 'q',
        label: 'Search lots',
        kind: 'search',
        searchFields: ['lot'],
      },
    ],
  };
  const withElements = analysisDefinition({
    fields: [
      ...analysisDefinition().fields,
      {
        name: 'items',
        label: 'Items',
        kind: 'array',
        elements: [
          { name: 'sku', label: 'SKU', kind: 'string' },
          {
            name: 'q',
            label: 'Search items',
            kind: 'search',
            searchFields: ['sku'],
          },
          batches,
        ],
      },
    ],
    analysis: {
      ...analysisCapability,
      elements: [
        {
          path: 'items',
          aggregations: [
            {
              field: 'sku',
              groups: [AggregationGroupType.TERMS],
              functions: [],
            },
          ],
        },
      ],
    },
  });
  const gated = (gate: FilterTree): AnalysisViewConfig =>
    analysisKernelConfig({
      elements: [{ path: 'items', filter: gate }],
      groups: [{ type: 'TERMS', field: 'items.sku', alias: 'sku' }],
      chart: {
        type: 'bar',
        cartesian: { x: 'sku', series: [{ metric: 'orders' }] },
      },
    });

  it('refuses a search', () => {
    expect(
      errorCodes(
        validateAnalysis(
          withElements,
          gated({
            op: 'and',
            children: [{ field: 'items.q', operator: 'SEARCH', value: 'tea' }],
          }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['analysis.elementFilter.search']);
  });

  it('refuses a search inside an element match it holds', () => {
    const issues = validateAnalysis(
      withElements,
      gated({
        op: 'and',
        children: [
          {
            field: 'items.batches',
            operator: 'ELEMENT_MATCH',
            value: {
              op: 'and',
              children: [
                { field: 'items.batches.q', operator: 'SEARCH', value: 'L7' },
              ],
            } as never,
          },
        ],
      }),
      builtinFieldKinds,
    );

    expect(errorCodes(issues)).toEqual(['analysis.elementFilter.search']);
    expect(issues[0].params).toEqual({ field: 'items.batches.q' });
  });
});
