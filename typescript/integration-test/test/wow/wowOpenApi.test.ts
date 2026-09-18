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
  AggregationDateUnit,
  AggregationExpressionOperator,
  AggregationExpressionType,
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
  ComparisonOperator,
  DeletionState,
  DerivedExpressionType,
  FilterOperator,
  HavingExpressionType,
  requireElementScopedFilter,
  SearchMode,
  SortDirection,
  StringComparison,
  TimeUnit,
} from '@ahoo-wang/fetcher-wow';
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
  ];

  it.each(WIRE)('%s sends exactly what Wow accepts', (_name, local, wire) => {
    expect(Object.values(local).sort()).toEqual([...wire()].sort());
  });

  // ELEMENT_MATCH takes a subset of the filters, listed as `elementPredicate`;
  // requireElementScopedFilter must refuse the rest and nothing more.
  it.each(Object.values(FilterOperator))(
    'ELEMENT_MATCH takes %s exactly when Wow does',
    op => {
      const scoped = discriminators(filters().elementPredicate.oneOf, 'op');
      const check = () =>
        requireElementScopedFilter(
          {
            op,
            operands: [{ op: FilterOperator.MATCH_ALL }],
            predicate: { op: FilterOperator.MATCH_ALL },
          } as never,
          'ELEMENT_MATCH predicate',
        );
      if (scoped.includes(op)) expect(check).not.toThrow();
      else expect(check).toThrow('cannot contain root filters');
    },
  );
});
