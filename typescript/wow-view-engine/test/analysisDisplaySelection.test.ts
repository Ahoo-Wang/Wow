/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { expect, it } from 'vitest';
import { aggregation, AggregationFunction } from '@ahoo-wang/fetcher-wow';
import type { AnalysisPlan } from '../src/analysis/analysisModel.js';
import {
  initialDisplayMapping,
  inferCapabilities,
  resolveMappings,
  validateMapping,
} from '../src/analysis/analysisDisplaySelection.js';
import { projectAnalysis } from '../src/analysis/analysisProjection.js';
const plan: AnalysisPlan = {
  query: {
    metrics: [aggregation.sum(aggregation.field('amount'), 'amount')],
    groupBy: [
      aggregation.terms('product', 'product'),
      aggregation.terms('channel', 'channel'),
    ],
  },
  schema: [
    {
      id: 'product',
      alias: 'product',
      title: '商品',
      role: 'dimension',
      valueType: 'string',
      nullable: false,
    },
    {
      id: 'channel',
      alias: 'channel',
      title: '渠道',
      role: 'dimension',
      valueType: 'string',
      nullable: false,
    },
    {
      id: 'amount',
      alias: 'amount',
      title: '销售额',
      role: 'metric',
      valueType: 'number',
      nullable: false,
      aggregation: AggregationFunction.SUM,
    },
  ],
};
it('leaves ambiguous axes unselected while identifying usable chart types without mutating data', () => {
  const rows = [{ product: 'P1', channel: 'web', amount: 3 }];
  const next = initialDisplayMapping(
    { layout: 'table', columns: [] },
    plan,
    'bar',
  );
  expect(next).toMatchObject({ x: '', series: '', metrics: ['amount'] });
  expect(projectAnalysis(plan, rows, next).issues.length).toBeGreaterThan(0);
  expect(
    inferCapabilities({ plan, rows })
      .filter(item => item.status !== 'unavailable')
      .map(item => item.type),
  ).toContain('bar');
  expect(
    inferCapabilities({ plan, rows })
      .filter(item => item.status !== 'unavailable')
      .map(item => item.type),
  ).not.toContain('pie');
  expect(rows).toEqual([{ product: 'P1', channel: 'web', amount: 3 }]);
});
it('initializes only unique mappings and preserves an explicit saved selection', () => {
  const unique = {
    ...plan,
    schema: plan.schema.filter(column => column.alias !== 'channel'),
  };
  expect(
    initialDisplayMapping({ layout: 'table', columns: [] }, unique, 'bar'),
  ).toMatchObject({ x: 'product', metrics: ['amount'] });
  const multiple = {
    ...unique,
    schema: [
      ...unique.schema,
      { ...plan.schema[2], id: 'other', alias: 'other' },
    ],
  };
  expect(
    initialDisplayMapping({ layout: 'table', columns: [] }, multiple, 'bar')
      .metrics,
  ).toEqual([]);
  expect(
    initialDisplayMapping(
      { layout: 'table', columns: [], metrics: ['other'] },
      multiple,
      'bar',
    ).metrics,
  ).toEqual(['other']);
});

it('allows pie through a later positive SUM candidate without selecting an ambiguous metric', () => {
  const result = {
    plan: {
      ...plan,
      schema: [
        plan.schema[0],
        plan.schema[2],
        { ...plan.schema[2], id: 'profit', alias: 'profit' },
      ],
    },
    rows: [{ product: 'P1', amount: -3, profit: 5 }],
  };
  const pie = inferCapabilities(result).find(item => item.type === 'pie')!;
  expect(pie.status).not.toBe('unavailable');
  expect(
    resolveMappings('pie', result).candidates.map(item => item.metrics),
  ).toEqual([['profit']]);
  expect(
    validateMapping('pie', { x: 'product', metrics: ['amount'] }, result)
      .length,
  ).toBeGreaterThan(0);
  expect(result.rows[0].amount).toBe(-3);
});

it('finds an axis swap when the first split exceeds twelve series', () => {
  const result = {
    plan,
    rows: Array.from({ length: 13 }, (_, i) => ({
      product: 'P1',
      channel: String(i),
      amount: 1,
    })),
  };
  const mappings = resolveMappings('bar', result);
  expect(mappings.candidates).toEqual([
    { x: 'channel', series: 'product', metrics: ['amount'] },
  ]);
  expect(
    inferCapabilities(result).find(item => item.type === 'bar')?.status,
  ).toBe('recommended');
});

it('keeps ambiguous mappings selectable and returns explicit reasons for unsupported types', () => {
  const result = { plan, rows: [{ product: 'P1', channel: 'web', amount: 1 }] };
  expect(resolveMappings('bar', result).candidates).toHaveLength(2);
  const caps = inferCapabilities(result);
  expect(caps.find(item => item.type === 'bar')?.status).not.toBe(
    'unavailable',
  );
  expect(caps.find(item => item.type === 'line')).toMatchObject({
    status: 'unavailable',
  });
  expect(caps.find(item => item.type === 'line')?.reasons.join()).toContain(
    '连续',
  );
  expect(caps.map(item => item.type)).toEqual([
    'metric',
    'bar',
    'line',
    'area',
    'pie',
  ]);
});

it('retains identity, checks actual rows, and never edits a saved incompatible mapping', () => {
  const result = {
    plan: {
      ...plan,
      schema: [
        { ...plan.schema[0], valueType: 'datetime' as const },
        plan.schema[2],
      ],
    },
    rows: [{ product: null, amount: 2 }],
  };
  expect(
    inferCapabilities(result).find(item => item.type === 'line')?.status,
  ).toBe('unavailable');
  const mapping = { x: 'missing', metrics: ['amount'] };
  expect(validateMapping('bar', mapping, result).join()).toContain('missing');
  expect(mapping).toEqual({ x: 'missing', metrics: ['amount'] });
  const duplicate = {
    ...result,
    rows: [
      { product: 1, amount: 2 },
      { product: 1, amount: 3 },
    ],
  };
  expect(
    inferCapabilities(duplicate)
      .find(item => item.type === 'bar')
      ?.reasons.join(),
  ).toContain('重复分组');
});

it('does not infer a mapping from incompatible fields or combine metrics to change their meaning', () => {
  const result = {
    plan: {
      ...plan,
      schema: [
        plan.schema[0],
        { ...plan.schema[2], aggregation: AggregationFunction.AVG },
      ],
    },
    rows: [{ product: 'P1', amount: 3 }],
  };
  expect(
    inferCapabilities(result).find(item => item.type === 'pie')?.status,
  ).toBe('unavailable');
  expect(
    inferCapabilities(result).find(item => item.type === 'bar')?.status,
  ).toBe('recommended');
  expect(
    initialDisplayMapping(
      { layout: 'table', columns: [] },
      result.plan,
      'pie',
      result.rows,
    ).metrics,
  ).toEqual([]);
  const temporal = {
    ...result,
    plan: {
      ...result.plan,
      schema: [
        { ...plan.schema[0], valueType: 'datetime' as const },
        plan.schema[2],
      ],
    },
    rows: [{ product: 1, amount: 3 }],
  };
  expect(
    inferCapabilities(temporal).find(item => item.type === 'line')?.status,
  ).toBe('recommended');
  expect(
    inferCapabilities(temporal).find(item => item.type === 'bar')?.status,
  ).toBe('available');
});

it('preserves an unregistered layout during explicit mapping repair', () => {
  const value = {
    layout: 'unknown' as never,
    columns: [],
    metrics: ['amount'],
  };
  expect(initialDisplayMapping(value, plan, value.layout)).toEqual(value);
});
