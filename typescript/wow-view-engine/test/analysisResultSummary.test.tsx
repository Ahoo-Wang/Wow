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
