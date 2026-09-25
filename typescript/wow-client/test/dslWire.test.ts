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

/**
 * The wire protocol of the query DSL, builder by builder.
 *
 * Every builder the `/dsl` entry exports — each `filter.*`, each
 * `aggregation.*`, and the query, sort, projection and pagination factories —
 * runs here on fixed input, and what it returns is compared, as the JSON the
 * clients send, with `golden/dsl-wire.json`. A refactor of the DSL must leave
 * that file unchanged; a change to it is a change to what reaches the server
 * and is made on purpose with `pnpm exec vitest run test/dslWire.test.ts -u`.
 *
 * A builder without a case fails the completeness check, so a new builder
 * arrives with its wire shape recorded.
 */

import { describe, expect, it } from 'vitest';
import * as dsl from '../src/dsl.js';
import {
  AggregationDatePart,
  AggregationDateUnit,
  AggregationExpressionOperator,
  ComparisonOperator,
  DeletionState,
  DerivedExpressionType,
  HavingExpressionType,
  SearchMode,
  StringComparison,
  TimeUnit,
  aggregation,
  asc,
  cursorQuery,
  desc,
  filter,
  listQuery,
  pagedList,
  pagedQuery,
  pagination,
  projection,
  singleQuery,
} from '../src/dsl.js';
import { canonicalJson } from './fixtures/canonicalJson.js';

const zoned = { zoneId: 'Asia/Shanghai' } as const;
const patterned = {
  zoneId: '+08:00',
  datePattern: 'yyyy-MM-dd',
  timeUnit: TimeUnit.SECONDS,
} as const;
const revenue = aggregation.field('state.amount');
const paid = filter.eq('state.status', 'PAID');

/**
 * `builder` or `builder (variant)` → the call. The part before the first space
 * names the builder the completeness check looks for.
 */
const CASES: Record<string, () => unknown> = {
  // filter: match and metadata
  'filter.matchAll': () => filter.matchAll(),
  'filter.matchNone': () => filter.matchNone(),
  'filter.id': () => filter.id('order-1'),
  'filter.ids': () => filter.ids(['order-1', 'order-2']),
  'filter.aggregateId': () => filter.aggregateId('order-1'),
  'filter.aggregateIds': () => filter.aggregateIds(['order-1', 'order-2']),
  'filter.tenantId': () => filter.tenantId('tenant-1'),
  'filter.ownerId': () => filter.ownerId('owner-1'),
  'filter.spaceId': () => filter.spaceId('space-1'),
  // filter: logical
  'filter.and': () => filter.and([paid, filter.gt('state.amount', 10)]),
  'filter.or': () => filter.or([paid, filter.isNull('state.paidAt')]),
  'filter.nor': () => filter.nor([paid]),
  // filter: equality and comparison
  'filter.eq': () => filter.eq('state.status', 'PAID'),
  'filter.eq (null)': () => filter.eq('state.paidAt', null),
  'filter.eq (boolean)': () => filter.eq('state.archived', false),
  'filter.ne': () => filter.ne('state.status', 'CANCELLED'),
  'filter.ne (number)': () => filter.ne('state.version', 0),
  'filter.gt': () => filter.gt('state.amount', 100),
  'filter.gte': () => filter.gte('state.amount', 100.5),
  'filter.lt': () => filter.lt('state.createTime', 1700000000000),
  'filter.lte': () => filter.lte('state.name', 'M'),
  // filter: strings
  'filter.contains': () => filter.contains('state.name', 'wow'),
  'filter.contains (case-insensitive)': () =>
    filter.contains('state.name', 'wow', StringComparison.CASE_INSENSITIVE),
  'filter.startsWith': () => filter.startsWith('state.name', 'w'),
  'filter.startsWith (case-sensitive)': () =>
    filter.startsWith('state.name', 'w', StringComparison.CASE_SENSITIVE),
  'filter.endsWith': () => filter.endsWith('state.name', 'w'),
  'filter.endsWith (case-insensitive)': () =>
    filter.endsWith('state.name', 'W', StringComparison.CASE_INSENSITIVE),
  // filter: collections and ranges
  'filter.isIn': () => filter.isIn('state.status', ['PAID', 'SHIPPED']),
  'filter.isIn (mixed)': () => filter.isIn('state.code', [1, 'two', true]),
  'filter.notIn': () => filter.notIn('state.status', ['CANCELLED']),
  'filter.containsAll': () => filter.containsAll('state.tags', ['a', 'b']),
  'filter.between': () => filter.between('state.amount', 10, 20),
  'filter.between (strings)': () =>
    filter.between('state.date', '2026-01-01', '2026-12-31'),
  // filter: presence
  'filter.isEmpty': () => filter.isEmpty('state.items'),
  'filter.isEmptyString': () => filter.isEmptyString('state.name'),
  'filter.isNotEmptyString': () => filter.isNotEmptyString('state.name'),
  'filter.isNull': () => filter.isNull('state.paidAt'),
  'filter.isNotNull': () => filter.isNotNull('state.paidAt'),
  'filter.exists': () => filter.exists('state.address'),
  'filter.notExists': () => filter.notExists('state.address'),
  // filter: deletion, elements and search
  'filter.deletion (active)': () => filter.deletion(DeletionState.ACTIVE),
  'filter.deletion (deleted)': () => filter.deletion(DeletionState.DELETED),
  'filter.deletion (all)': () => filter.deletion(DeletionState.ALL),
  'filter.elementMatch': () =>
    filter.elementMatch('state.items', filter.gt('quantity', 1)),
  'filter.elementMatch (logical)': () =>
    filter.elementMatch(
      'state.items',
      filter.and([
        filter.eq('sku', 'A'),
        filter.elementMatch('lots', filter.isNotNull('expiry')),
      ]),
    ),
  'filter.elementMatch (search)': () =>
    filter.elementMatch(
      'state.items',
      filter.search('usb cable', { fields: ['productName'] }),
    ),
  'filter.search': () => filter.search('event sourcing'),
  'filter.search (options)': () =>
    filter.search('event sourcing', {
      fields: ['state.title', 'state.description'],
      mode: SearchMode.PHRASE,
    }),
  // filter: relative time
  'filter.today': () => filter.today('state.createTime'),
  'filter.today (zone)': () => filter.today('state.createTime', zoned),
  'filter.today (pattern)': () => filter.today('state.date', patterned),
  'filter.beforeToday': () => filter.beforeToday('state.createTime', '12:00'),
  'filter.beforeToday (pattern)': () =>
    filter.beforeToday('state.date', '08:30:15', patterned),
  'filter.tomorrow': () => filter.tomorrow('state.dueTime', zoned),
  'filter.yesterday': () => filter.yesterday('state.createTime', zoned),
  'filter.thisWeek': () => filter.thisWeek('state.createTime', zoned),
  'filter.nextWeek': () => filter.nextWeek('state.createTime', zoned),
  'filter.lastWeek': () => filter.lastWeek('state.createTime', zoned),
  'filter.thisMonth': () => filter.thisMonth('state.createTime', zoned),
  'filter.nextMonth': () => filter.nextMonth('state.createTime', zoned),
  'filter.lastMonth': () => filter.lastMonth('state.createTime', zoned),
  'filter.thisYear': () => filter.thisYear('state.createTime', zoned),
  'filter.nextYear': () => filter.nextYear('state.createTime', zoned),
  'filter.lastYear': () => filter.lastYear('state.createTime', zoned),
  'filter.recentDays': () => filter.recentDays('state.createTime', 7),
  'filter.recentDays (pattern)': () =>
    filter.recentDays('state.date', 30, patterned),
  'filter.earlierDays': () => filter.earlierDays('state.createTime', 7, zoned),
  'filter.beforeNow': () => filter.beforeNow('state.timeoutAt'),
  'filter.beforeNow (pattern)': () =>
    filter.beforeNow('state.date', 'P1DT2H', patterned),
  'filter.afterNow': () => filter.afterNow('state.createTime', '-PT30M', zoned),

  // aggregation: elements and expressions
  'aggregation.element': () => aggregation.element('state.items'),
  'aggregation.element (predicate)': () =>
    aggregation.element('state.items', filter.gt('quantity', 0)),
  'aggregation.field': () => aggregation.field('state.amount'),
  'aggregation.constant': () => aggregation.constant(1.5),
  'aggregation.add': () => aggregation.add(revenue, aggregation.constant(1)),
  'aggregation.subtract': () =>
    aggregation.subtract(revenue, aggregation.field('state.discount')),
  'aggregation.multiply': () =>
    aggregation.multiply(
      aggregation.add(revenue, aggregation.constant(1)),
      aggregation.constant(2),
    ),
  'aggregation.divide': () =>
    aggregation.divide(revenue, aggregation.field('state.quantity')),
  // aggregation: groups
  'aggregation.terms': () => aggregation.terms('state.status', 'status'),
  'aggregation.terms (missingKey)': () =>
    aggregation.terms('state.status', 'status', { missingKey: 'NONE' }),
  'aggregation.histogram': () =>
    aggregation.histogram('state.amount', 'amountBucket', { interval: 100 }),
  'aggregation.dateHistogram': () =>
    aggregation.dateHistogram('state.createTime', 'day', {
      unit: AggregationDateUnit.DAY,
    }),
  'aggregation.dateHistogram (options)': () =>
    aggregation.dateHistogram('state.createTime', 'month', {
      unit: AggregationDateUnit.MONTH,
      timeZone: 'Asia/Shanghai',
      dense: true,
    }),
  'aggregation.datePart': () =>
    aggregation.datePart('state.createTime', 'weekday', {
      part: AggregationDatePart.DAY_OF_WEEK,
    }),
  'aggregation.datePart (options)': () =>
    aggregation.datePart('state.createTime', 'hour', {
      part: AggregationDatePart.HOUR_OF_DAY,
      timeZone: 'Asia/Shanghai',
      dense: true,
    }),
  // aggregation: metrics
  'aggregation.count': () => aggregation.count('orders'),
  'aggregation.count (filter)': () =>
    aggregation.count('paidOrders', { filter: paid }),
  'aggregation.any': () => aggregation.any('state.currency', 'currency'),
  'aggregation.any (filter)': () =>
    aggregation.any('state.currency', 'currency', { filter: paid }),
  'aggregation.sum': () => aggregation.sum(revenue, 'revenue'),
  'aggregation.sum (filter)': () =>
    aggregation.sum(revenue, 'paidRevenue', { filter: paid }),
  'aggregation.avg': () => aggregation.avg(revenue, 'avgAmount'),
  'aggregation.min': () => aggregation.min(revenue, 'minAmount'),
  'aggregation.max': () => aggregation.max(revenue, 'maxAmount'),
  'aggregation.stddev': () => aggregation.stddev(revenue, 'stddevAmount'),
  'aggregation.variance': () => aggregation.variance(revenue, 'varAmount'),
  'aggregation.distinctCount': () =>
    aggregation.distinctCount(aggregation.field('state.customerId'), 'buyers'),
  'aggregation.distinctCount (filter)': () =>
    aggregation.distinctCount(aggregation.field('state.customerId'), 'buyers', {
      filter: paid,
    }),
  'aggregation.percentile': () =>
    aggregation.percentile(revenue, 'p95', { percentile: 0.95 }),
  'aggregation.percentile (filter)': () =>
    aggregation.percentile(revenue, 'p50', { percentile: 0.5, filter: paid }),
  'aggregation.derived': () =>
    aggregation.derived(
      {
        type: DerivedExpressionType.BINARY,
        operator: AggregationExpressionOperator.DIVIDE,
        left: { type: DerivedExpressionType.METRIC_REF, metric: 'revenue' },
        right: {
          type: DerivedExpressionType.BINARY,
          operator: AggregationExpressionOperator.ADD,
          left: { type: DerivedExpressionType.METRIC_REF, metric: 'orders' },
          right: { type: DerivedExpressionType.CONSTANT, value: 1 },
        },
      },
      'aov',
    ),
  'aggregation.derived (callback)': () =>
    aggregation.derived(
      d =>
        d.divide(
          d.subtract(d.ref('revenue'), d.ref('refunds')),
          d.add(d.multiply(d.ref('orders'), d.constant(2)), d.constant(1)),
        ),
      'net',
    ),
  // aggregation.having
  'aggregation.having.eq': () => aggregation.having.eq('orders', 1),
  'aggregation.having.ne': () => aggregation.having.ne('orders', 1),
  'aggregation.having.gt': () => aggregation.having.gt('orders', 1),
  'aggregation.having.gte': () => aggregation.having.gte('orders', 1),
  'aggregation.having.lt': () => aggregation.having.lt('orders', 1),
  'aggregation.having.lte': () => aggregation.having.lte('orders', 1),
  'aggregation.having.between': () =>
    aggregation.having.between('revenue', 10, 1000),
  'aggregation.having.isIn': () => aggregation.having.isIn('orders', [2, 3]),
  'aggregation.having.isNull': () => aggregation.having.isNull('aov'),
  'aggregation.having.isNotNull': () => aggregation.having.isNotNull('aov'),
  'aggregation.having.and': () =>
    aggregation.having.and([
      aggregation.having.gt('orders', 1),
      aggregation.having.isNotNull('aov'),
    ]),
  'aggregation.having.or': () =>
    aggregation.having.or([
      aggregation.having.lt('orders', 1),
      aggregation.having.gt('revenue', 1000),
    ]),
  // aggregation: the whole query
  'aggregation.query': () =>
    aggregation.query({ metrics: [aggregation.count('orders')] }),
  'aggregation.query (full)': () =>
    aggregation.query({
      filter: paid,
      elements: [aggregation.element('state.items', filter.gt('quantity', 0))],
      groupBy: [
        aggregation.terms('state.items.sku', 'sku'),
        aggregation.dateHistogram('state.createTime', 'day', {
          unit: AggregationDateUnit.DAY,
          timeZone: 'Asia/Shanghai',
        }),
      ],
      metrics: [
        aggregation.count('orders'),
        aggregation.sum(aggregation.field('state.items.price'), 'revenue'),
        aggregation.derived(
          {
            type: DerivedExpressionType.BINARY,
            operator: AggregationExpressionOperator.DIVIDE,
            left: { type: DerivedExpressionType.METRIC_REF, metric: 'revenue' },
            right: { type: DerivedExpressionType.METRIC_REF, metric: 'orders' },
          },
          'aov',
        ),
      ],
      having: {
        type: HavingExpressionType.AND,
        operands: [
          {
            type: HavingExpressionType.CONDITION,
            metric: 'orders',
            operator: ComparisonOperator.GT,
            value: 1,
          },
          {
            type: HavingExpressionType.BETWEEN,
            metric: 'revenue',
            lower: 10,
            upper: 1000,
          },
          { type: HavingExpressionType.IN, metric: 'orders', values: [2, 3] },
          { type: HavingExpressionType.IS_NULL, metric: 'aov', negated: true },
        ],
      },
      sort: [desc('revenue'), asc('sku')],
      limit: 50,
    }),

  // queries, sort, projection, pagination
  singleQuery: () => singleQuery(),
  'singleQuery (options)': () =>
    singleQuery({
      filter: paid,
      projection: projection({ include: ['state.status'] }),
      sort: [desc('state.createTime')],
    }),
  listQuery: () => listQuery(),
  'listQuery (options)': () =>
    listQuery({ filter: paid, sort: [asc('state.name')], limit: 20 }),
  pagedQuery: () => pagedQuery(),
  'pagedQuery (options)': () =>
    pagedQuery({
      filter: paid,
      projection: projection({ exclude: ['state.secret'] }),
      pagination: pagination({ index: 2, size: 50 }),
    }),
  cursorQuery: () => cursorQuery({ filter: paid }),
  'cursorQuery (options)': () =>
    cursorQuery({
      filter: paid,
      cursor: 'order-9',
      size: 25,
      sort: [asc('aggregateId')],
      projection: projection({ include: ['aggregateId'] }),
    }),
  asc: () => asc('state.name'),
  desc: () => desc('state.name'),
  projection: () => projection(),
  'projection (include)': () =>
    projection({ include: ['state.name'], exclude: ['state.secret'] }),
  pagination: () => pagination(),
  'pagination (partial)': () => pagination({ index: 3 }),
  pagedList: () => pagedList(),
  'pagedList (rows)': () => pagedList({ list: [{ id: 'a' }], total: 1 }),
};

/** Every builder name the `/dsl` entry offers, as `CASES` spells it. */
function builderNames(): string[] {
  const names: string[] = [];
  for (const [name, value] of Object.entries(dsl)) {
    // Capitalized functions are the static name tables, not builders.
    if (typeof value === 'function' && /^[a-z]/.test(name)) names.push(name);
    else if (name === 'filter' || name === 'aggregation')
      members(name, value as object);
  }
  return names.sort();

  // A namespace nests builders: `aggregation.having.gt`.
  function members(path: string, namespace: object): void {
    for (const [key, member] of Object.entries(namespace))
      if (typeof member === 'function') names.push(`${path}.${key}`);
      else if (typeof member === 'object' && member !== null)
        members(`${path}.${key}`, member);
  }
}

describe('the DSL wire protocol', () => {
  it('has a case for every builder', () => {
    const covered = new Set(Object.keys(CASES).map(key => key.split(' ')[0]));
    expect(builderNames().filter(name => !covered.has(name))).toEqual([]);
    expect([...covered].filter(name => !builderNames().includes(name))).toEqual(
      [],
    );
  });

  it('sends what golden/dsl-wire.json records', async () => {
    const sent = Object.fromEntries(
      Object.entries(CASES).map(([name, build]) => [
        name,
        canonicalJson(build()),
      ]),
    );
    await expect(JSON.stringify(sent, null, 2) + '\n').toMatchFileSnapshot(
      'golden/dsl-wire.json',
    );
  });
});
