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

import { expect, it } from 'vitest';
import { aggregation } from '@ahoo-wang/fetcher-wow';
import type { AnalysisPlan } from '../src/analysis/analysisModel.js';
import {
  validateAnalysisResult,
  analysisRowKey,
} from '../src/analysis/analysisResult.js';
const plan: AnalysisPlan = {
  query: {
    metrics: [
      aggregation.count('orders'),
      aggregation.sum(aggregation.field('amount'), 'total'),
    ],
  },
  schema: [
    {
      id: 'c',
      alias: 'orders',
      title: 'Orders',
      role: 'metric',
      valueType: 'number',
      nullable: false,
      format: 'count',
    },
    {
      id: 's',
      alias: 'total',
      title: 'Total',
      role: 'metric',
      valueType: 'number',
      nullable: true,
    },
  ],
};
it('preserves zero, null and empty sets without extra fields', () => {
  expect(
    validateAnalysisResult([{ orders: 0, total: null, extra: 'hidden' }], plan),
  ).toEqual({ errors: [], rows: [{ orders: 0, total: null }] });
  expect(validateAnalysisResult([], plan)).toEqual({ errors: [], rows: [] });
});
it.each(
  [
    [{ orders: 0 }],
    [{ orders: -1, total: 0 }],
    [{ orders: 1.5, total: 0 }],
    [{ orders: Number.MAX_SAFE_INTEGER + 1, total: 0 }],
    [{ orders: 0, total: Infinity }],
    [{ orders: 0, total: '2' }],
    [
      { orders: 0, total: 0 },
      { orders: 0, total: 0 },
    ],
    Object.assign(Object.create({ orders: 0 }), { total: null }),
  ].map(rows => ({ rows })),
)('rejects malformed complete response $rows', ({ rows }) => {
  expect(validateAnalysisResult(rows, plan).rows).toBeUndefined();
});
it('validates full typed tuples and duplicate groups', () => {
  const grouped: AnalysisPlan = {
    ...plan,
    query: {
      ...plan.query,
      groupBy: [
        aggregation.terms('state', 'state'),
        aggregation.histogram('amount', { alias: 'bucket', interval: 1 }),
      ],
    },
    schema: [
      {
        id: 'state',
        alias: 'state',
        title: 'State',
        role: 'dimension',
        valueType: 'string',
        nullable: true,
      },
      {
        id: 'bucket',
        alias: 'bucket',
        title: 'Bucket',
        role: 'dimension',
        valueType: 'number',
        nullable: true,
      },
      ...plan.schema,
    ],
  };
  const row = { state: 'ready', bucket: 1, orders: 0, total: null };
  expect(
    validateAnalysisResult([row, { ...row, bucket: 2 }], grouped).errors,
  ).toEqual([]);
  expect(validateAnalysisResult([row, row], grouped).rows).toBeUndefined();
  expect(
    validateAnalysisResult([{ ...row, bucket: '1' }], grouped).rows,
  ).toBeUndefined();
  expect(
    validateAnalysisResult(
      [
        Object.assign(Object.create({ state: 'ready' }), {
          bucket: 1,
          orders: 0,
          total: null,
        }),
      ],
      grouped,
    ).rows,
  ).toBeUndefined();
});

it('uses full typed tuple keys instead of the first group or string coercion', () => {
  const dimensions = [
    { ...plan.schema[0], alias: 'a', role: 'dimension' as const },
    { ...plan.schema[0], alias: 'b', role: 'dimension' as const },
  ];
  expect(analysisRowKey({ a: 1, b: 'x' }, dimensions)).not.toBe(
    analysisRowKey({ a: '1', b: 'x' }, dimensions),
  );
  expect(analysisRowKey({ a: 1, b: 'x' }, dimensions)).not.toBe(
    analysisRowKey({ a: 1, b: 'y' }, dimensions),
  );
});
it('keeps row identity stable when presentation reorders dimensions', () => {
  const dimensions = [
    { ...plan.schema[0], alias: 'a', role: 'dimension' as const },
    { ...plan.schema[0], alias: 'b', role: 'dimension' as const },
  ];
  expect(analysisRowKey({ a: 1, b: 'x' }, dimensions)).toBe(
    analysisRowKey({ a: 1, b: 'x' }, [...dimensions].reverse()),
  );
});
it('enforces count semantics even without a display format and admits scalar ANY values', () => {
  const semanticPlan: AnalysisPlan = {
    query: {
      metrics: [aggregation.count('count'), aggregation.any('state', 'sample')],
    },
    schema: [
      {
        id: 'count',
        alias: 'count',
        title: 'Count',
        role: 'metric',
        valueType: 'number',
        nullable: false,
        aggregation: 'COUNT',
      },
      {
        id: 'sample',
        alias: 'sample',
        title: 'Sample',
        role: 'metric',
        valueType: 'string',
        nullable: true,
        aggregation: 'ANY',
      },
    ],
  };
  expect(
    validateAnalysisResult([{ count: -1, sample: 'x' }], semanticPlan).rows,
  ).toBeUndefined();
  for (const sample of ['', null, 'x'])
    expect(
      validateAnalysisResult([{ count: 0, sample }], semanticPlan).errors,
    ).toEqual([]);
  expect(
    validateAnalysisResult([{ count: 1, sample: {} }], semanticPlan).rows,
  ).toBeUndefined();
});
