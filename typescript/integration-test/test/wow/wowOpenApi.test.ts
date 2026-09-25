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

import { beforeAll, describe, expect, it } from 'vitest';
import {
  AggregationDatePart,
  AggregationDateUnit,
  AggregationExpressionOperator,
  AggregationExpressionType,
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
  ComparisonOperator,
  DeletionState,
  DerivedExpressionType,
  filter,
  FilterOperator,
  HavingExpressionType,
  PagingMode,
  QueryErrorCodes,
  QueryValueKind,
  SearchMode,
  SensitivityLevel,
  SortDirection,
  StringComparison,
  TimeUnit,
} from '@ahoo-wang/wow-client';
import type {
  AggregationLimitsDescriptor,
  AnalysisDescriptor,
  AnalysisSortDescriptor,
  ConstraintDescriptor,
  DatePartAggregationGroup,
  DynamicFieldDescriptor,
  ElementDescriptor,
  EnumValueDescriptor,
  FieldAggregateDescriptor,
  FieldDescriptor,
  FieldFilterDescriptor,
  FieldSortDescriptor,
  HavingDescriptor,
  LimitsDescriptor,
  QueryDeprecation,
  QueryModelDescriptor,
  QuerySemanticType,
  RecordDescriptor,
  SearchDescriptor,
  SensitivityDescriptor,
  VariantDescriptor,
  VariantsDescriptor,
} from '@ahoo-wang/wow-client';
import { exampleFetcher } from '../../src/wow';

/**
 * Holds every value this package puts on the wire to the Wow server's own
 * description of the protocol.
 *
 * The server writes `/v3/api-docs` from its own types, with constants resolved
 * and Jackson's annotations applied, so it states exactly what Wow accepts: a
 * value sent here that it does not list is refused with a 400. Renovate bumps
 * the example server this runs against, so a Wow release that changes the
 * protocol fails that pull request.
 *
 * `Operator` is not here: it belongs to the deprecated Condition API, which the
 * document does not describe.
 */
describe('Wow OpenAPI document', () => {
  let doc: any;
  beforeAll(async () => {
    const response = await exampleFetcher.get('v3/api-docs');
    doc = await response.json();
  });

  const query = (name: string) =>
    doc.components.schemas[`wow.api.query.${name}`];
  const filters = () => query('FilterExpression').definitions;

  /** The schema a `$ref` points at, through as many references as it takes. */
  const deref = (schema: any): any => {
    while (schema.$ref)
      schema = schema.$ref
        .slice(2)
        .split('/')
        .reduce((node: any, key: string) => node[key], doc);
    return schema;
  };

  /** The value each member of a union names itself by on the wire. */
  const discriminators = (members: any[], property: string): string[] =>
    members.map(member => {
      const pinned = deref(member).properties[property];
      return pinned.const ?? pinned.enum[0];
    });

  const mapped = (name: string) =>
    Object.keys(query(name).discriminator.mapping);

  const WIRE: [string, Record<string, string>, () => string[]][] = [
    [
      'FilterOperator',
      FilterOperator,
      () => discriminators(filters().filterExpression.oneOf, 'op'),
    ],
    [
      'StringComparison',
      StringComparison,
      () => filters().stringComparison.enum,
    ],
    ['SearchMode', SearchMode, () => filters().search.properties.mode.enum],
    ['TimeUnit', TimeUnit, () => filters().timeUnit.enum],
    [
      'DeletionState',
      DeletionState,
      () => filters().deletion.properties.state.enum,
    ],
    ['SortDirection', SortDirection, () => query('Sort.Direction').enum],
    [
      'AggregationGroupType',
      AggregationGroupType,
      () =>
        discriminators(
          query('AggregationQuery').properties.groupBy.items.anyOf,
          'type',
        ),
    ],
    [
      'AggregationMetricType',
      AggregationMetricType,
      () => mapped('AggregationMetric'),
    ],
    [
      'AggregationExpressionType',
      AggregationExpressionType,
      () => mapped('AggregationExpression'),
    ],
    [
      'AggregationExpressionOperator',
      AggregationExpressionOperator,
      () => query('AggregationExpressionOperator').enum,
    ],
    [
      'AggregationDateUnit',
      AggregationDateUnit,
      () => query('AggregationDateUnit').enum,
    ],
    [
      'AggregationDatePart',
      AggregationDatePart,
      () => query('AggregationDatePart').enum,
    ],
    [
      'AggregationFunction',
      AggregationFunction,
      () => query('AggregationFunction').enum,
    ],
    [
      'DerivedExpressionType',
      DerivedExpressionType,
      () => mapped('DerivedExpression'),
    ],
    [
      'HavingExpressionType',
      HavingExpressionType,
      () => mapped('HavingExpression'),
    ],
    [
      'ComparisonOperator',
      ComparisonOperator,
      () => query('ComparisonOperator').enum,
    ],
    ['PagingMode', PagingMode, () => query('PagingMode').enum],
    ['QueryValueKind', QueryValueKind, () => query('QueryValueKind').enum],
    [
      'SensitivityLevel',
      SensitivityLevel,
      () => query('SensitivityLevel').enum,
    ],
  ];

  it.each(WIRE)('%s sends exactly what Wow accepts', (_name, local, wire) => {
    expect(Object.values(local).sort()).toEqual([...wire()].sort());
  });

  it('sends a DATE_PART group with exactly the properties Wow declares', () => {
    const declared: Record<keyof DatePartAggregationGroup, true> = {
      type: true,
      field: true,
      alias: true,
      part: true,
      timeZone: true,
      dense: true,
    };
    const group = query('AggregationGroup.DatePart');
    expect(Object.keys(declared).sort()).toEqual(
      Object.keys(group.properties).sort(),
    );
    expect([...group.required].sort()).toEqual([
      'alias',
      'field',
      'part',
      'type',
    ]);
    expect(deref(group.properties.part)).toBe(query('AggregationDatePart'));
  });

  // The codes come back rather than go out: a rejected query's
  // BindingError.code. The list is open on the server's side (codes are
  // added, never renamed), so a new one fails here until wow-client knows it.
  it('QueryErrorCodes knows exactly the codes Wow answers with', () => {
    const code = doc.components.schemas['wow.api.BindingError'].properties.code;
    expect(Object.values(QueryErrorCodes)).toEqual(code.enum);
  });

  describe('the query capability descriptor', () => {
    /**
     * Each descriptor type's properties, as wow-client declares them. A
     * `Record` over the type's keys, so a property the type gains or loses
     * fails to compile until it is listed here, and the list is held to the
     * document's.
     */
    type Keys<T> = Record<keyof T, true>;
    const keys = <T>(record: Keys<T>) => Object.keys(record).sort();
    const SHAPES: [string, string[]][] = [
      [
        'QueryModelDescriptor',
        keys<QueryModelDescriptor>({
          model: true,
          version: true,
          timeZone: true,
          record: true,
          limits: true,
          analysis: true,
          fields: true,
          elements: true,
          dynamic: true,
          constraints: true,
          variants: true,
        }),
      ],
      [
        'VariantsDescriptor',
        keys<VariantsDescriptor>({
          element: true,
          discriminator: true,
          values: true,
        }),
      ],
      [
        'VariantDescriptor',
        keys<VariantDescriptor>({
          value: true,
          fields: true,
          description: true,
        }),
      ],
      [
        'RecordDescriptor',
        keys<RecordDescriptor>({
          identity: true,
          paging: true,
          defaultScope: true,
          rootOperators: true,
          search: true,
        }),
      ],
      [
        'SearchDescriptor',
        keys<SearchDescriptor>({ modes: true, fields: true }),
      ],
      [
        'LimitsDescriptor',
        keys<LimitsDescriptor>({
          maxListSize: true,
          defaultListSize: true,
          maxPageSize: true,
          maxPageWindow: true,
          maxFilterNodes: true,
          maxFilterValues: true,
          maxSortFields: true,
          aggregation: true,
        }),
      ],
      [
        'AggregationLimitsDescriptor',
        keys<AggregationLimitsDescriptor>({
          maxGroups: true,
          maxMetrics: true,
          maxElements: true,
          maxLimit: true,
          maxExpressionDepth: true,
          maxExpressionNodes: true,
        }),
      ],
      [
        'AnalysisDescriptor',
        keys<AnalysisDescriptor>({
          metrics: true,
          approximate: true,
          expressions: true,
          having: true,
          sort: true,
          dense: true,
          dateUnits: true,
          dateParts: true,
        }),
      ],
      ['HavingDescriptor', keys<HavingDescriptor>({ metrics: true })],
      [
        'AnalysisSortDescriptor',
        keys<AnalysisSortDescriptor>({ groups: true, metrics: true }),
      ],
      [
        'FieldDescriptor',
        keys<FieldDescriptor>({
          path: true,
          role: true,
          types: true,
          kind: true,
          nullable: true,
          semantic: true,
          enum: true,
          description: true,
          sensitivity: true,
          project: true,
          filter: true,
          sort: true,
          aggregate: true,
          scope: true,
          deprecated: true,
          aliases: true,
        }),
      ],
      ['QueryDeprecation', keys<QueryDeprecation>({ message: true })],
      [
        'EnumValueDescriptor',
        keys<EnumValueDescriptor>({ value: true, description: true }),
      ],
      [
        'SensitivityDescriptor',
        keys<SensitivityDescriptor>({ level: true, comparable: true }),
      ],
      [
        'FieldFilterDescriptor',
        keys<FieldFilterDescriptor>({ operators: true }),
      ],
      [
        'FieldSortDescriptor',
        keys<FieldSortDescriptor>({ paged: true, cursor: true }),
      ],
      [
        'FieldAggregateDescriptor',
        keys<FieldAggregateDescriptor>({
          groups: true,
          missingKey: true,
          functions: true,
          distinctCount: true,
          percentile: true,
          any: true,
          expressionInput: true,
          inMetricFilter: true,
        }),
      ],
      [
        'ElementDescriptor',
        keys<ElementDescriptor>({ path: true, filter: true, aggregate: true }),
      ],
      [
        'DynamicFieldDescriptor',
        keys<DynamicFieldDescriptor>({
          pattern: true,
          types: true,
          kind: true,
          filter: true,
          excludedKeys: true,
        }),
      ],
      [
        'ConstraintDescriptor',
        keys<ConstraintDescriptor>({
          type: true,
          appended: true,
          fields: true,
        }),
      ],
    ];

    it.each(SHAPES)(
      '%s has exactly the properties Wow sends',
      (name, local) => {
        expect(local).toEqual(Object.keys(query(name).properties).sort());
      },
    );

    /** The schema of `path` inside the descriptor type `name`, dereferenced. */
    const at = (name: string, ...path: string[]) =>
      deref(
        path.reduce(
          (schema: any, key) =>
            key === '[]' ? deref(schema).items : deref(schema).properties[key],
          query(name),
        ),
      );
    /** The one schema of a nullable property that is not `null`. */
    const nonNull = (schema: any) =>
      deref(schema.anyOf?.find((it: any) => it.type !== 'null') ?? schema);

    // A closed set is an enum wow-client holds above; an open one is a plain
    // string, which wow-client types as its known values plus `string & {}`.
    it.each([
      ['QueryModelDescriptor', 'model'],
      ['FieldDescriptor', 'types', '[]'],
      ['DynamicFieldDescriptor', 'types', '[]'],
      ['FieldAggregateDescriptor', 'groups', '[]'],
      ['FieldAggregateDescriptor', 'functions', '[]'],
      ['AnalysisDescriptor', 'metrics', '[]'],
      ['AnalysisDescriptor', 'approximate', '[]'],
      ['HavingDescriptor', 'metrics', '[]'],
      ['ConstraintDescriptor', 'type'],
    ])('%s.%s is an open string', (name, ...path) => {
      const schema = at(name, ...path);
      expect(schema.type).toBe('string');
      expect(schema.enum).toBeUndefined();
    });

    it('names the role of a system field with an open string', () => {
      const role = nonNull(query('FieldDescriptor').properties.role);
      expect(role.type).toBe('string');
      expect(role.enum).toBeUndefined();
    });

    it.each([
      ['FieldFilterDescriptor', ['operators', '[]'], 'FilterOperator'],
      ['RecordDescriptor', ['rootOperators', '[]'], 'FilterOperator'],
      ['RecordDescriptor', ['paging', '[]'], 'PagingMode'],
      ['SearchDescriptor', ['modes', '[]'], 'SearchMode'],
      ['FieldDescriptor', ['kind'], 'QueryValueKind'],
      ['DynamicFieldDescriptor', ['kind'], 'QueryValueKind'],
      ['AnalysisDescriptor', ['dateUnits', '[]'], 'AggregationDateUnit'],
      ['AnalysisDescriptor', ['dateParts', '[]'], 'AggregationDatePart'],
      ['SensitivityDescriptor', ['level'], 'SensitivityLevel'],
    ])('%s.%s is the closed enum %s', (name, path, target) => {
      expect(at(name, ...path)).toBe(query(target));
    });

    it('describes each variant with the FieldDescriptor of a field', () => {
      expect(nonNull(query('QueryModelDescriptor').properties.variants)).toBe(
        query('VariantsDescriptor'),
      );
      expect(at('VariantsDescriptor', 'values', '[]')).toBe(
        query('VariantDescriptor'),
      );
      expect(at('VariantDescriptor', 'fields', '[]')).toBe(
        query('FieldDescriptor'),
      );
    });

    it('scopes a record by the DeletionState wow-client sends', () => {
      const scope = nonNull(query('RecordDescriptor').properties.defaultScope);
      expect([...scope.enum].sort()).toEqual(
        Object.values(DeletionState).sort(),
      );
    });

    it('knows every semantic type Wow describes', () => {
      const known: Keys<Record<QuerySemanticType['type'], true>> = {
        TEMPORAL_DATE: true,
        TEMPORAL_EPOCH: true,
        TEMPORAL_FORMATTED: true,
      };
      expect(Object.keys(known).sort()).toEqual(
        mapped('QuerySemanticType').sort(),
      );
      const epoch = query('Temporal.Epoch').properties.timeUnit;
      expect([...deref(epoch).enum].sort()).toEqual(
        Object.values(TimeUnit).sort(),
      );
    });

    // Kotlin writes `null` for an unlimited limit, and leaves a null out of
    // every other descriptor class (`@JsonInclude(NON_NULL)`): wow-client
    // types the former `number | null` and the latter optional.
    it('marks the same properties nullable as wow-client leaves optional or null', () => {
      const nullable = (name: string) =>
        Object.entries(query(name).properties)
          .filter(([, schema]: [string, any]) =>
            schema.anyOf?.some((it: any) => it.type === 'null'),
          )
          .map(([key]) => key)
          .sort();
      expect(nullable('QueryModelDescriptor')).toEqual(['variants']);
      expect(nullable('VariantsDescriptor')).toEqual([]);
      expect(nullable('VariantDescriptor')).toEqual(['description']);
      expect(nullable('FieldDescriptor')).toEqual([
        'aggregate',
        'deprecated',
        'description',
        'enum',
        'role',
        'scope',
        'semantic',
        'sensitivity',
      ]);
      expect(nullable('RecordDescriptor')).toEqual(['defaultScope', 'search']);
      expect(nullable('DynamicFieldDescriptor')).toEqual(['excludedKeys']);
      expect(nullable('ConstraintDescriptor')).toEqual(['appended', 'fields']);
      expect(nullable('EnumValueDescriptor')).toEqual(['description']);
      expect(nullable('QueryDeprecation')).toEqual(['message']);
      expect(query('FieldDescriptor').properties.aliases.items.type).toBe(
        'string',
      );
      expect(nullable('AnalysisDescriptor')).toEqual([]);
      expect(query('AnalysisDescriptor').required).toEqual(
        expect.arrayContaining(['approximate', 'dateUnits', 'dateParts']),
      );
      expect(nullable('LimitsDescriptor')).toEqual([
        'defaultListSize',
        'maxFilterNodes',
        'maxFilterValues',
        'maxListSize',
        'maxPageSize',
        'maxPageWindow',
      ]);
    });

    it('answers GET {aggregate}/{snapshot|event}/schema with it', () => {
      const routes = Object.entries(doc.paths).filter(([path]) =>
        /\/(snapshot|event)\/schema$/.test(path),
      );
      expect(routes.length).toBeGreaterThan(0);
      for (const [, route] of routes as [string, any][])
        expect(
          route.get.responses['200'].content['application/json'].schema.$ref,
        ).toBe('#/components/schemas/wow.api.query.QueryModelDescriptor');
    });

    // QueryDescriptorClient revalidates with If-None-Match and reads the
    // version back from the ETag, on a 200 and on a 304 alike.
    it('declares the conditional GET QueryDescriptorClient relies on', () => {
      const routes = Object.entries(doc.paths).filter(([path]) =>
        /\/(snapshot|event)\/schema$/.test(path),
      );
      expect(routes.length).toBeGreaterThan(0);
      for (const [, route] of routes as [string, any][]) {
        const { parameters, responses } = route.get;
        expect(parameters.map(deref)).toContainEqual(
          expect.objectContaining({
            in: 'header',
            name: 'If-None-Match',
            required: false,
          }),
        );
        for (const status of ['200', '304'])
          expect(deref(responses[status].headers.ETag).schema.type).toBe(
            'string',
          );
        expect(responses['304'].content).toBeUndefined();
      }
    });
  });

  // ELEMENT_MATCH takes a subset of the filters, listed as `elementPredicate`;
  // filter.elementMatch must refuse the rest and nothing more.
  it.each(Object.values(FilterOperator))(
    'ELEMENT_MATCH takes %s exactly when Wow does',
    op => {
      const scoped = discriminators(filters().elementPredicate.oneOf, 'op');
      const check = () =>
        filter.elementMatch('items', {
          op,
          operands: [{ op: FilterOperator.MATCH_ALL }],
          predicate: { op: FilterOperator.MATCH_ALL },
        } as never);
      if (scoped.includes(op)) expect(check).not.toThrow();
      else expect(check).toThrow('cannot contain root filters');
    },
  );
});
