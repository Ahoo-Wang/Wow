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
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
  FilterOperator,
  PagingMode,
  SearchMode,
  type QueryModelDescriptor,
} from '@ahoo-wang/wow-client';
import {
  builtinFieldKinds,
  searchFieldOf,
  type DataViewDefinition,
  type FieldDefinition,
  type Issue,
} from '../src/index.js';
import { narrowDefinition } from '../src/capabilities/index.js';
import { ordersDefinition } from './fixtures.js';
import { describedField, ordersDescriptor } from './fixtures/descriptor.js';

function narrow(
  definition: DataViewDefinition,
  descriptor: QueryModelDescriptor,
): { definition: DataViewDefinition; findings: Issue[] } {
  return narrowDefinition(definition, descriptor, builtinFieldKinds);
}

/** The descriptor with one field replaced. */
function withField(
  path: string,
  overrides: Parameters<typeof describedField>[1],
  base: QueryModelDescriptor = ordersDescriptor(),
): QueryModelDescriptor {
  return {
    ...base,
    fields: base.fields.map(field =>
      field.path === path ? describedField(path, overrides) : field,
    ),
  };
}

function field(
  definition: DataViewDefinition,
  name: string,
): FieldDefinition | undefined {
  return definition.fields.find(entry => entry.name === name);
}

function codes(findings: readonly Issue[]): string[] {
  return findings.map(found => `${found.severity} ${found.code}`);
}

describe('narrowing a definition to a descriptor', () => {
  it('changes no field and finds nothing where the descriptor admits everything', () => {
    const declared = ordersDefinition();
    const { definition, findings } = narrow(declared, ordersDescriptor());

    expect(findings).toEqual([]);
    expect(definition.fields).toEqual(declared.fields);
    // Only the bounds a source says are written in.
    expect(definition.record).toEqual({ ...declared.record, maxSortFields: 7 });
    expect(definition.analysis?.fields).toEqual(declared.analysis?.fields);
  });

  it('adds nothing the definition does not declare', () => {
    const declared = ordersDefinition();
    const { definition } = narrow(declared, ordersDescriptor());

    expect(definition.fields.map(entry => entry.name)).toEqual(
      declared.fields.map(entry => entry.name),
    );
    // `warehouse` declares no operators and no sort; the descriptor's every
    // operator does not become a declared list, nor its sort a sortable.
    expect(field(definition, 'warehouse')).toEqual(
      field(declared, 'warehouse'),
    );
  });
});

describe('4.1 fields', () => {
  it('keeps a field the descriptor does not list on screen, without a query capability', () => {
    const descriptor = ordersDescriptor();
    descriptor.fields = descriptor.fields.filter(
      entry => entry.path !== 'amount',
    );
    const { definition, findings } = narrow(ordersDefinition(), descriptor);

    expect(field(definition, 'amount')).toEqual({
      name: 'amount',
      label: 'Amount',
      kind: 'number',
      operators: [],
      sortable: false,
    });
    expect(findings).toContainEqual({
      code: 'capability.field.unknown',
      severity: 'warning',
      path: ['fields', 3],
      params: { field: 'amount' },
    });
    // Nor is it aggregated.
    expect(definition.analysis?.fields.map(entry => entry.field)).toEqual([
      'warehouse',
    ]);
  });

  it('matches a key under a dynamic pattern, its excluded keys left out', () => {
    const declared = ordersDefinition({
      fields: [
        ...ordersDefinition().fields,
        { name: 'tags.color', label: 'Colour', kind: 'string' },
        { name: 'tags.size', label: 'Size', kind: 'string' },
      ],
    });
    const descriptor = ordersDescriptor({
      dynamic: [
        {
          pattern: 'tags.{key}',
          types: ['STRING'],
          kind: 'SCALAR' as never,
          filter: { operators: [FilterOperator.EQ] },
          excludedKeys: ['size'],
        },
        {
          pattern: 'tags.{key}',
          types: ['STRING'],
          kind: 'ARRAY' as never,
          filter: { operators: [FilterOperator.IN] },
        },
      ],
    });
    const { definition, findings } = narrow(declared, descriptor);

    expect(field(definition, 'tags.color')?.operators).toEqual(['EQ', 'IN']);
    // `size` is excluded from the first pattern, and only the second admits it.
    expect(field(definition, 'tags.size')?.operators).toEqual(['IN']);
    expect(codes(findings)).toEqual([
      'warning capability.field.operators-narrowed',
      'warning capability.field.operators-narrowed',
    ]);
  });

  it('checks a declared time storage against the one the descriptor names, as an error', () => {
    const declared = ordersDefinition({
      fields: [
        ...ordersDefinition().fields,
        { name: 'createdAt', label: 'Created', kind: 'datetime' },
        {
          name: 'shippedAt',
          label: 'Shipped',
          kind: 'datetime',
          temporal: { type: 'epoch', timeUnit: 'SECONDS' },
        },
        {
          name: 'dueOn',
          label: 'Due',
          kind: 'date',
          temporal: { type: 'date' },
        },
      ],
    });
    const base = ordersDescriptor();
    const descriptor = {
      ...base,
      fields: [
        ...base.fields,
        // Milliseconds, as declared by default: no finding.
        describedField('createdAt', {
          semantic: { type: 'TEMPORAL_EPOCH' },
        }),
        // Declared seconds, kept as milliseconds.
        describedField('shippedAt', {
          semantic: {
            type: 'TEMPORAL_EPOCH',
            timeUnit: 'MILLISECONDS' as never,
          },
        }),
        // Declared a date, kept as text.
        describedField('dueOn', {
          semantic: { type: 'TEMPORAL_FORMATTED', pattern: 'yyyy-MM-dd' },
        }),
      ],
    };
    const { findings } = narrow(declared, descriptor);

    expect(findings).toEqual([
      {
        code: 'capability.field.temporal-mismatch',
        severity: 'error',
        path: ['fields', 5],
        params: {
          field: 'shippedAt',
          declared: 'epoch SECONDS',
          described: 'epoch MILLISECONDS',
        },
      },
      {
        code: 'capability.field.temporal-mismatch',
        severity: 'error',
        path: ['fields', 6],
        params: {
          field: 'dueOn',
          declared: 'date',
          described: 'text yyyy-MM-dd',
        },
      },
    ]);
  });

  it('keeps an option the descriptor does not list, and says so', () => {
    const declared = ordersDefinition({
      fields: [
        ...ordersDefinition().fields.filter(entry => entry.name !== 'status'),
        {
          name: 'status',
          label: 'Status',
          kind: 'enum',
          options: [
            { value: 'PENDING', label: '待发' },
            { value: 'LEGACY', label: '旧状态' },
          ],
        },
      ],
    });
    const descriptor = withField('status', {
      enum: [{ value: 'PENDING' }, { value: 'SHIPPED' }],
    });
    const { definition, findings } = narrow(declared, descriptor);

    // Neither `SHIPPED` added nor `LEGACY` dropped: labels are the definition's.
    expect(field(definition, 'status')?.options?.map(o => o.value)).toEqual([
      'PENDING',
      'LEGACY',
    ]);
    expect(findings).toEqual([
      expect.objectContaining({
        code: 'capability.field.options-undescribed',
        severity: 'warning',
        params: { field: 'status', values: 'LEGACY' },
      }),
    ]);
  });
});

describe('4.2 conditions', () => {
  it("cuts a field's operators to the ones its path admits", () => {
    const descriptor = withField('warehouse', {
      filter: { operators: [FilterOperator.EQ, FilterOperator.IN] },
    });
    const { definition, findings } = narrow(ordersDefinition(), descriptor);

    expect(field(definition, 'warehouse')?.operators).toEqual(['EQ', 'IN']);
    expect(findings).toEqual([
      expect.objectContaining({
        code: 'capability.field.operators-narrowed',
        params: expect.objectContaining({ field: 'warehouse' }),
      }),
    ]);
  });

  it('narrows a declared operator list and never widens it', () => {
    const declared = ordersDefinition();
    declared.fields[1] = { ...declared.fields[1], operators: ['EQ', 'NE'] };
    const descriptor = withField('warehouse', {
      filter: { operators: [FilterOperator.EQ, FilterOperator.IN] },
    });

    expect(
      field(narrow(declared, descriptor).definition, 'warehouse')?.operators,
    ).toEqual(['EQ']);
  });

  it('offers no condition on a field whose path admits none', () => {
    const descriptor = withField('status', { filter: { operators: [] } });
    const { definition, findings } = narrow(ordersDefinition(), descriptor);

    expect(field(definition, 'status')?.operators).toEqual([]);
    expect(codes(findings)).toEqual(['warning capability.field.unfilterable']);
  });

  it('takes STARTS_WITH off a case-insensitive field where the source matches a prefix only by case', () => {
    const declared = ordersDefinition();
    declared.fields[2] = {
      ...declared.fields[2],
      stringComparison: 'CASE_SENSITIVE',
    };
    const descriptor = ordersDescriptor({
      constraints: [{ type: 'STARTS_WITH_REQUIRES_PREFIX' }],
    });
    const { definition } = narrow(declared, descriptor);

    expect(field(definition, 'warehouse')?.operators).not.toContain(
      'STARTS_WITH',
    );
    expect(field(definition, 'id')?.operators).not.toContain('STARTS_WITH');
    // `status` compares case-sensitively and keeps it.
    expect(field(definition, 'status')?.operators).toBeUndefined();
  });

  it('checks a metadata condition against the root operators', () => {
    const declared = ordersDefinition({
      fields: [
        ...ordersDefinition().fields,
        { name: '@tenantId', label: 'Tenant', kind: 'tenantId' },
        { name: '@deleted', label: 'Deleted', kind: 'deletion' },
      ],
    });
    const descriptor = ordersDescriptor();
    descriptor.record = {
      ...descriptor.record,
      rootOperators: [FilterOperator.ID, FilterOperator.DELETION],
    };
    const { definition, findings } = narrow(declared, descriptor);

    expect(field(definition, '@tenantId')?.operators).toEqual([]);
    expect(field(definition, '@deleted')?.operators).toBeUndefined();
    expect(findings).toEqual([
      expect.objectContaining({
        code: 'capability.field.unfilterable',
        params: { field: '@tenantId' },
      }),
    ]);
  });

  describe('an element array', () => {
    const items: FieldDefinition = {
      name: 'items',
      label: 'Items',
      kind: 'elementMatch',
      elements: [
        { name: 'sku', label: 'SKU', kind: 'string' },
        { name: 'qty', label: 'Qty', kind: 'number' },
      ],
    };
    const declared = ordersDefinition({
      fields: [...ordersDefinition().fields, items],
    });
    const described = (filter: boolean): QueryModelDescriptor => {
      const base = ordersDescriptor();
      return {
        ...base,
        fields: [
          ...base.fields,
          describedField('items'),
          describedField('items.sku', { scope: 'items' }),
          describedField('items.qty', {
            scope: 'items',
            filter: { operators: [FilterOperator.GT] },
          }),
        ],
        elements: [{ path: 'items', filter, aggregate: true }],
      };
    };

    it('narrows the fields inside it by their scope', () => {
      const { definition } = narrow(declared, described(true));
      const narrowed = field(definition, 'items');

      expect(narrowed?.operators).toBeUndefined();
      expect(narrowed?.elements?.[1].operators).toEqual(['GT']);
    });

    it('offers no element condition where the elements cannot be filtered', () => {
      const { definition, findings } = narrow(declared, described(false));

      expect(field(definition, 'items')?.operators).toEqual([]);
      expect(findings).toContainEqual(
        expect.objectContaining({
          code: 'capability.field.unfilterable',
          path: ['fields', 4],
        }),
      );
    });
  });
});

describe('4.2 search (G15)', () => {
  /** The compensation console's 「搜索错误」, as batch 1 declared it. */
  const errorSearch: FieldDefinition = {
    name: 'keyword',
    label: 'Search errors',
    kind: 'search',
    searchFields: ['state.error.errorMsg', 'state.error.stackTrace'],
    searchMode: 'PHRASE',
  };
  const declared = ordersDefinition({
    fields: [...ordersDefinition().fields, errorSearch],
  });
  const searching = (
    search: QueryModelDescriptor['record']['search'],
  ): QueryModelDescriptor => {
    const base = ordersDescriptor();
    return { ...base, record: { ...base.record, search } };
  };
  const both = [SearchMode.TERMS, SearchMode.PHRASE];
  const errorFields = ['state.error.errorMsg', 'state.error.stackTrace'];

  it('keeps the phrase search where the model searches by phrase (Elasticsearch)', () => {
    const { definition, findings } = narrow(
      declared,
      searching({ modes: both, fields: errorFields }),
    );

    expect(findings).toEqual([]);
    expect(searchFieldOf(definition.fields)).toEqual(errorSearch);
  });

  it('draws no search box where the model has no full-text search (MongoDB without a text index)', () => {
    const { definition, findings } = narrow(declared, searching(undefined));

    expect(searchFieldOf(definition.fields)).toBeNull();
    expect(findings).toEqual([
      {
        code: 'capability.search.unavailable',
        severity: 'warning',
        path: ['fields', 4],
        params: { field: 'keyword' },
      },
    ]);
  });

  it("draws no search box where the model's search modes lack both", () => {
    const { definition } = narrow(
      declared,
      searching({ modes: [], fields: errorFields }),
    );

    expect(searchFieldOf(definition.fields)).toBeNull();
  });

  it('searches a phrase as words where the model matches words only, and notes it', () => {
    const { definition, findings } = narrow(
      declared,
      searching({ modes: [SearchMode.TERMS], fields: errorFields }),
    );

    expect(searchFieldOf(definition.fields)?.searchMode).toBe('TERMS');
    expect(codes(findings)).toEqual(['note capability.search.as-terms']);
  });

  it('never narrows words into a phrase', () => {
    const words = ordersDefinition({
      fields: [
        ...ordersDefinition().fields,
        { ...errorSearch, searchMode: 'TERMS' },
      ],
    });
    const { definition } = narrow(
      words,
      searching({ modes: [SearchMode.PHRASE], fields: errorFields }),
    );

    expect(searchFieldOf(definition.fields)).toBeNull();
  });

  it('searches only the fields the model searches in', () => {
    const { definition, findings } = narrow(
      declared,
      searching({ modes: both, fields: ['state.error.errorMsg'] }),
    );

    expect(searchFieldOf(definition.fields)?.searchFields).toEqual([
      'state.error.errorMsg',
    ]);
    expect(findings).toEqual([
      expect.objectContaining({
        code: 'capability.search.fields-narrowed',
        params: { field: 'keyword', fields: 'state.error.stackTrace' },
      }),
    ]);
    // None in common: no search at all.
    expect(
      searchFieldOf(
        narrow(declared, searching({ modes: both, fields: ['other'] }))
          .definition.fields,
      ),
    ).toBeNull();
  });
});

describe('4.3 sort and paging', () => {
  it('turns off a sort the path does not take, by the paging the view reads', () => {
    const descriptor = withField('amount', {
      sort: { paged: false, cursor: true },
    });
    const paged = narrow(ordersDefinition(), descriptor);
    const cursor = narrow(
      ordersDefinition({
        record: { rowKey: 'id', paging: 'cursor', layouts: ['table'] },
      }),
      descriptor,
    );

    expect(field(paged.definition, 'amount')?.sortable).toBe(false);
    expect(codes(paged.findings)).toEqual([
      'warning capability.field.unsortable',
    ]);
    expect(field(cursor.definition, 'amount')?.sortable).toBe(true);
  });

  it('refuses paging the source does not offer', () => {
    const descriptor = ordersDescriptor();
    descriptor.record = { ...descriptor.record, paging: [PagingMode.CURSOR] };

    expect(narrow(ordersDefinition(), descriptor).findings).toEqual([
      {
        code: 'capability.record.paging',
        severity: 'error',
        path: ['record', 'paging'],
        params: { paging: 'paged' },
      },
    ]);
  });

  it('refuses a cursor the source ends on another field than the row key', () => {
    const cursor = ordersDefinition({
      record: { rowKey: 'id', paging: 'cursor', layouts: ['table'] },
    });
    const ended = (appended: string) =>
      narrow(
        cursor,
        ordersDescriptor({
          constraints: [{ type: 'CURSOR_UNIQUE_SORT', appended }],
        }),
      ).findings;

    expect(ended('id')).toEqual([]);
    expect(codes(ended('aggregateId'))).toEqual([
      'error capability.record.cursor-appended',
    ]);
  });

  it('asks a paged view for a condition where the source counts only what one narrows (Q3)', () => {
    const counted = ordersDescriptor({
      constraints: [{ type: 'COUNT_REQUIRES_FILTER' }],
    });

    expect(
      narrow(ordersDefinition(), counted).definition.record?.requiresFilter,
    ).toBe(true);
    // A cursor counts nothing.
    expect(
      narrow(
        ordersDefinition({
          record: { rowKey: 'id', paging: 'cursor', layouts: ['table'] },
        }),
        counted,
      ).definition.record,
    ).not.toHaveProperty('requiresFilter');
  });

  it('refuses a row key the source cannot sort by', () => {
    const descriptor = withField('id', {
      sort: { paged: false, cursor: false },
    });

    expect(codes(narrow(ordersDefinition(), descriptor).findings)).toEqual([
      'warning capability.field.unsortable',
      'error capability.record.row-key-unsortable',
    ]);
  });

  it("bounds the sort by the source's, the row key taking one place", () => {
    const descriptor = ordersDescriptor();
    descriptor.limits = { ...descriptor.limits, maxSortFields: 3 };
    const declared = ordersDefinition();

    expect(narrow(declared, descriptor).definition.record?.maxSortFields).toBe(
      2,
    );
    // A lower bound the definition declares stands.
    declared.record = { ...declared.record!, maxSortFields: 1 };
    expect(narrow(declared, descriptor).definition.record?.maxSortFields).toBe(
      1,
    );
  });

  it("cuts a column's summaries to the metrics its path feeds", () => {
    const declared = ordersDefinition();
    declared.fields[3] = { ...declared.fields[3], summary: ['SUM', 'COUNT'] };
    const descriptor = ordersDescriptor();
    descriptor.analysis = {
      ...descriptor.analysis,
      metrics: [AggregationMetricType.COUNT],
    };
    const { definition, findings } = narrow(declared, descriptor);

    expect(field(definition, 'amount')?.summary).toEqual(['COUNT']);
    expect(findings).toContainEqual(
      expect.objectContaining({
        code: 'capability.field.summary-narrowed',
        params: { field: 'amount', summaries: 'SUM' },
      }),
    );
  });
});

describe('4.4 analysis', () => {
  it("cuts a field's groups and functions to what its path feeds", () => {
    const declared = ordersDefinition();
    declared.analysis!.fields[0] = {
      field: 'warehouse',
      groups: [AggregationGroupType.TERMS, AggregationGroupType.HISTOGRAM],
      functions: [],
    };
    const descriptor = withField('warehouse', {
      aggregate: {
        ...describedField('warehouse').aggregate!,
        groups: [AggregationGroupType.TERMS],
      },
    });
    const { definition, findings } = narrow(declared, descriptor);

    expect(definition.analysis?.fields[0].groups).toEqual(['TERMS']);
    expect(findings).toEqual([
      expect.objectContaining({
        code: 'capability.analysis.field-narrowed',
        params: { field: 'warehouse', dropped: 'HISTOGRAM' },
      }),
    ]);
  });

  it('buckets dates by the units both declare, and offers no date histogram with none in common (#3489)', () => {
    const declared = ordersDefinition({
      fields: [
        ...ordersDefinition().fields,
        { name: 'createdAt', label: 'Created', kind: 'datetime' },
      ],
    });
    declared.analysis!.fields.push({
      field: 'createdAt',
      groups: [AggregationGroupType.DATE_HISTOGRAM, AggregationGroupType.TERMS],
      functions: [],
      dateUnits: [
        AggregationDateUnit.MONTH,
        AggregationDateUnit.DAY,
        AggregationDateUnit.HOUR,
      ],
    });
    const base = ordersDescriptor();
    const described = (units: AggregationDateUnit[]): QueryModelDescriptor => ({
      ...base,
      fields: [...base.fields, describedField('createdAt')],
      analysis: { ...base.analysis, dateUnits: units },
    });

    const some = narrow(
      declared,
      described([AggregationDateUnit.DAY, AggregationDateUnit.MONTH]),
    );
    expect(some.definition.analysis?.fields[2]).toMatchObject({
      groups: ['DATE_HISTOGRAM', 'TERMS'],
      dateUnits: ['MONTH', 'DAY'],
    });
    expect(some.findings).toEqual([
      expect.objectContaining({
        code: 'capability.analysis.field-narrowed',
        params: { field: 'createdAt', dropped: 'HOUR' },
      }),
    ]);

    const none = narrow(declared, described([AggregationDateUnit.YEAR]));
    expect(none.definition.analysis?.fields[2]).toMatchObject({
      groups: ['TERMS'],
      dateUnits: [],
    });
  });

  it('takes a metric type the model does not offer off every field', () => {
    const declared = ordersDefinition();
    declared.analysis!.fields[1] = {
      field: 'amount',
      groups: [],
      functions: [AggregationFunction.SUM],
      distinctCount: true,
      percentile: true,
      any: true,
    };
    const descriptor = ordersDescriptor();
    descriptor.analysis = {
      ...descriptor.analysis,
      metrics: [AggregationMetricType.COUNT, AggregationMetricType.ANY],
    };
    const { definition } = narrow(declared, descriptor);

    expect(definition.analysis?.fields[1]).toEqual({
      field: 'amount',
      groups: [],
      functions: [],
      distinctCount: false,
      percentile: false,
      any: true,
    });
  });

  it('takes away the count, formulas and having where the model has none', () => {
    const declared = ordersDefinition();
    declared.analysis = {
      ...declared.analysis!,
      expressions: true,
      having: true,
    };
    const descriptor = ordersDescriptor();
    descriptor.analysis = {
      ...descriptor.analysis,
      metrics: [AggregationMetricType.NUMERIC],
      expressions: false,
      having: { metrics: [] },
    };
    const { definition, findings } = narrow(declared, descriptor);

    expect(definition.analysis).toMatchObject({
      count: false,
      expressions: false,
      having: false,
    });
    expect(codes(findings)).toEqual([
      'warning capability.analysis.count',
      'warning capability.analysis.expressions',
      'warning capability.analysis.having',
    ]);
  });

  it('takes an element away where the model cannot aggregate over it, and narrows the rest by scope', () => {
    const declared = ordersDefinition({
      fields: [
        ...ordersDefinition().fields,
        {
          name: 'items',
          label: 'Items',
          kind: 'elementMatch',
          elements: [{ name: 'sku', label: 'SKU', kind: 'string' }],
        },
        {
          name: 'lines',
          label: 'Lines',
          kind: 'elementMatch',
          elements: [{ name: 'qty', label: 'Qty', kind: 'number' }],
        },
      ],
    });
    declared.analysis = {
      ...declared.analysis!,
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
        {
          path: 'lines',
          aggregations: [
            {
              field: 'qty',
              groups: [],
              functions: [AggregationFunction.SUM, AggregationFunction.AVG],
            },
          ],
        },
      ],
    };
    const base = ordersDescriptor();
    const descriptor: QueryModelDescriptor = {
      ...base,
      fields: [
        ...base.fields,
        describedField('items'),
        describedField('items.sku', { scope: 'items' }),
        describedField('lines'),
        describedField('lines.qty', {
          scope: 'lines',
          aggregate: {
            ...describedField('x').aggregate!,
            functions: [AggregationFunction.SUM],
          },
        }),
      ],
      elements: [
        { path: 'items', filter: true, aggregate: false },
        { path: 'lines', filter: true, aggregate: true },
      ],
    };
    const { definition, findings } = narrow(declared, descriptor);

    expect(definition.analysis?.elements).toEqual([
      {
        path: 'lines',
        aggregations: [{ field: 'qty', groups: [], functions: ['SUM'] }],
      },
    ]);
    expect(codes(findings)).toEqual([
      'warning capability.analysis.element-unavailable',
      'warning capability.analysis.field-narrowed',
    ]);
  });

  it("lowers the aggregation sizes to the entry's", () => {
    const declared = ordersDefinition();
    declared.analysis = { ...declared.analysis!, limits: { maxGroups: 2 } };
    const { definition } = narrow(declared, ordersDescriptor());

    expect(definition.analysis?.limits).toEqual({
      maxGroups: 2,
      maxMetrics: 16,
      maxElements: 4,
    });
  });

  it('takes the analysis away, as a warning, when not one metric is left', () => {
    const descriptor = ordersDescriptor();
    descriptor.analysis = { ...descriptor.analysis, metrics: [] };
    const { definition, findings } = narrow(ordersDefinition(), descriptor);

    expect(definition.analysis).toBeUndefined();
    expect(findings[findings.length - 1]).toEqual({
      code: 'capability.analysis.unavailable',
      severity: 'warning',
      path: ['analysis'],
    });
    expect(findings.every(found => found.severity !== 'error')).toBe(true);
  });
});
