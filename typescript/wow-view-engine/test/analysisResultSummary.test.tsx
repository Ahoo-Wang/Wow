/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { AnalysisResultSummary } from '../src/analysis/AnalysisResultSummary.js';
import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import type { AnalysisSession } from '../src/contracts/viewModel.js';
it('describes a compiled unconditional element scope as all records', () => {
  const all = {
    mode: 'simple' as const,
    root: {
      id: 'all',
      component: { name: 'builtin' },
      operator: FilterOperator.AND,
      children: [],
      props: {},
    },
  };
  const result = {
    config: {
      filters: all,
      dimensions: [],
      metrics: [],
      sort: [],
      limit: 10,
      scope: { id: 'items', filters: [all] },
      presentation: { layout: 'table', columns: [] },
    },
    plan: { query: { metrics: [], elements: [{ path: 'items' }] }, schema: [] },
    rows: [],
    receivedAt: 0,
  } as unknown as NonNullable<AnalysisSession['result']>;
  const html = renderToStaticMarkup(
    <AnalysisResultSummary
      result={result}
      definition={{ id: 'd', title: 'D', sourceId: 's', fields: [] }}
      compilers={{}}
    />,
  );
  expect(html).toContain('元素 1：全部记录');
});

it('describes executed metric filters and HAVING even when supplied by a compiler', () => {
  const result = {
    config: {
      filters: {
        mode: 'simple',
        root: {
          id: 'root',
          component: { name: 'builtin' },
          operator: FilterOperator.MATCH_ALL,
          props: {},
        },
      },
      dimensions: [],
      metrics: [
        {
          id: 'orders',
          alias: 'orders',
          title: '订单数',
          component: { name: 'custom' },
          props: {},
        },
      ],
      sort: [],
      limit: 100,
      presentation: { layout: 'table', columns: [] },
    },
    plan: {
      query: {
        metrics: [
          {
            type: 'COUNT',
            alias: 'orders',
            filter: { op: 'EQ', field: 'status', value: 'PAID' },
          },
        ],
        having: {
          type: 'CONDITION',
          metric: 'orders',
          operator: 'GTE',
          value: 100,
        },
      },
      schema: [
        {
          id: 'orders',
          alias: 'orders',
          title: '订单数',
          role: 'metric',
          valueType: 'number',
          nullable: false,
          aggregation: 'COUNT',
        },
      ],
    },
    rows: [],
    receivedAt: 0,
  } as unknown as NonNullable<AnalysisSession['result']>;
  const html = renderToStaticMarkup(
    <AnalysisResultSummary
      result={result}
      definition={{
        id: 'd',
        title: 'D',
        sourceId: 's',
        fields: [
          {
            field: 'status',
            label: '支付状态',
            type: 'string',
            options: [{ value: 'PAID', label: '已支付' }],
          },
        ],
      }}
      compilers={{}}
    />,
  );
  expect(html).toContain('订单数 统计条件：支付状态 等于 已支付');
  expect(html).toContain('结果筛选：订单数 大于等于 100');
  const nested = {
    ...result,
    plan: {
      ...result.plan,
      query: {
        ...result.plan.query,
        having: {
          type: 'AND',
          operands: [
            { type: 'BETWEEN', metric: 'orders', lower: 1, upper: 5 },
            {
              type: 'OR',
              operands: [
                { type: 'IN', metric: 'orders', values: [1, 3] },
                { type: 'IS_NULL', metric: 'orders' },
                { type: 'IS_NULL', metric: 'orders', negated: true },
              ],
            },
          ],
        },
      },
    },
  } as unknown as NonNullable<AnalysisSession['result']>;
  const nestedHtml = renderToStaticMarkup(
    <AnalysisResultSummary
      result={nested}
      definition={{ id: 'd', title: 'D', sourceId: 's', fields: [] }}
      compilers={{}}
    />,
  );
  for (const text of [
    '订单数 介于 1 至 5',
    '订单数 属于 [1、3]',
    '订单数 为空值',
    '订单数 非空值',
  ])
    expect(nestedHtml).toContain(text);
});

it('describes executed percentile and formula parameters rather than same-title drafts', () => {
  const result = {
    config: {
      filters: {
        mode: 'simple',
        root: {
          id: 'all',
          component: { name: 'builtin' },
          operator: FilterOperator.MATCH_ALL,
          props: {},
        },
      },
      dimensions: [],
      metrics: [
        {
          id: 'p',
          alias: 'p',
          title: '分位数',
          component: { name: 'percentile' },
          props: { percentile: 99 },
        },
        {
          id: 'r',
          alias: 'r',
          title: '计算值',
          component: { name: 'derived' },
          props: {},
          derivedExpression: { type: 'CONSTANT', value: 999 },
        },
      ],
      sort: [],
      limit: 10,
      presentation: { layout: 'table', columns: [] },
    },
    plan: {
      query: {
        metrics: [
          {
            type: 'PERCENTILE',
            alias: 'p',
            percentile: 95,
            expression: { type: 'FIELD', field: 'amount' },
          },
          {
            type: 'DERIVED',
            alias: 'r',
            expression: {
              type: 'BINARY',
              operator: 'DIVIDE',
              left: { type: 'METRIC_REF', metric: 'p' },
              right: {
                type: 'BINARY',
                operator: 'SUBTRACT',
                left: { type: 'CONSTANT', value: 3 },
                right: { type: 'CONSTANT', value: 1 },
              },
            },
          },
        ],
      },
      schema: [
        {
          id: 'p',
          alias: 'p',
          title: '分位数',
          role: 'metric',
          valueType: 'number',
          nullable: true,
          aggregation: 'PERCENTILE',
        },
        {
          id: 'r',
          alias: 'r',
          title: '计算值',
          role: 'metric',
          valueType: 'number',
          nullable: true,
          aggregation: 'DERIVED',
        },
      ],
    },
    rows: [],
    receivedAt: 0,
  } as unknown as NonNullable<AnalysisSession['result']>;
  const definition = {
    id: 'd',
    title: 'D',
    sourceId: 's',
    fields: [{ field: 'amount', label: '金额', type: 'number' as const }],
  };
  const html = renderToStaticMarkup(
    <AnalysisResultSummary
      result={result}
      definition={definition}
      compilers={{}}
    />,
  );
  expect(html).toContain('分位数 P95：金额');
  expect(html).toContain('计算值 公式：(分位数 ÷ (3 − 1))');
  expect(html).not.toContain('P99');
  expect(html).not.toContain('999');
  const changed = {
    ...result,
    plan: {
      ...result.plan,
      query: {
        ...result.plan.query,
        metrics: result.plan.query.metrics.map(metric =>
          metric.type === 'PERCENTILE' ? { ...metric, percentile: 50 } : metric,
        ),
      },
    },
  };
  const next = renderToStaticMarkup(
    <AnalysisResultSummary
      result={changed}
      definition={definition}
      compilers={{}}
    />,
  );
  expect(next).toContain('分位数 P50：金额');
  expect(next).not.toBe(html);
});

it('describes bucket options from the executed plan rather than dimension drafts', () => {
  const result = {
    config: {
      filters: {
        mode: 'simple',
        root: {
          id: 'all',
          component: { name: 'builtin' },
          operator: FilterOperator.MATCH_ALL,
          props: {},
        },
      },
      dimensions: [
        {
          id: 'g',
          alias: 'g',
          title: '分组',
          component: { name: 'terms' },
          props: { missingKey: '草稿' },
        },
      ],
      metrics: [],
      sort: [],
      limit: 10,
      presentation: { layout: 'table', columns: [] },
    },
    plan: {
      query: {
        metrics: [],
        groupBy: [
          { type: 'TERMS', field: 'channel', alias: 'g', missingKey: '未知' },
        ],
      },
      schema: [{ alias: 'g', title: '渠道' }],
    },
    rows: [],
    receivedAt: 0,
  } as unknown as NonNullable<AnalysisSession['result']>;
  const definition = { id: 'd', title: 'D', sourceId: 's', fields: [] };
  const html = renderToStaticMarkup(
    <AnalysisResultSummary
      result={result}
      definition={definition}
      compilers={{}}
    />,
  );
  expect(html).toContain('渠道 缺失值归入：未知');
  expect(html).not.toContain('草稿');
  const excluded = {
    ...result,
    plan: {
      ...result.plan,
      query: {
        metrics: [],
        groupBy: [{ type: 'TERMS', field: 'channel', alias: 'g' }],
      },
    },
  } as unknown as typeof result;
  expect(
    renderToStaticMarkup(
      <AnalysisResultSummary
        result={excluded}
        definition={definition}
        compilers={{}}
      />,
    ),
  ).toContain('缺失值：不参与分组');

  const date = {
    ...result,
    plan: {
      ...result.plan,
      query: {
        metrics: [],
        groupBy: [
          {
            type: 'DATE_HISTOGRAM',
            field: 'createdAt',
            alias: 'g',
            unit: 'DAY',
            timeZone: 'UTC',
            dense: true,
          },
        ],
      },
    },
  } as unknown as typeof result;
  const dense = renderToStaticMarkup(
    <AnalysisResultSummary
      result={date}
      definition={definition}
      compilers={{}}
    />,
  );
  expect(dense).toContain('日期空桶补齐：开启');
  const sparse = {
    ...date,
    plan: {
      ...date.plan,
      query: {
        ...date.plan.query,
        groupBy: [{ ...date.plan.query.groupBy![0], dense: false }],
      },
    },
  } as unknown as typeof result;
  expect(
    renderToStaticMarkup(
      <AnalysisResultSummary
        result={sparse}
        definition={definition}
        compilers={{}}
      />,
    ),
  ).toContain('日期空桶补齐：关闭');
});
