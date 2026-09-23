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
 * A chart finding names what the reader sees — the column's header — and
 * never the alias the kernel addresses it by (「漏斗要一个可累加的指标，avg
 * 不是」). Every chart finding that names a dimension or a metric is raised
 * here, through admission as a view raises it, and said in both catalogues.
 */

import {
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/fetcher-wow';
import { render, renderHook, screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type Issue,
  type ViewRuntime,
} from '../src/index.js';
import {
  useAnalysisEditor,
  useOpenView,
  useViewRuntime,
} from '../src/react/index.js';
import { DataWorkbench, zhCN } from '../src/ui/index.js';
import { chartIssueNamer } from '../src/ui/analysis/issueNames.js';
import {
  defaultMessages,
  formatIssue,
  formatMessage,
  type ViewMessages,
} from '../src/ui/messages.js';
import type { MessageFormatters } from '../src/ui/MessagesProvider.js';
import { analysisConfig, ordersDefinition, testSource } from './fixtures.js';

const { TERMS, DATE_HISTOGRAM } = AggregationGroupType;
const { SUM, AVG, MIN, MAX } = AggregationFunction;

function definition(): DataViewDefinition {
  const base = ordersDefinition();
  return ordersDefinition({
    fields: [
      ...base.fields,
      { name: 'createdAt', label: 'Created', kind: 'datetime' },
    ],
    analysis: {
      count: true,
      fields: [
        { field: 'warehouse', groups: [TERMS], functions: [] },
        { field: 'status', groups: [TERMS], functions: [] },
        { field: 'amount', groups: [], functions: [SUM, AVG] },
        {
          field: 'createdAt',
          groups: [DATE_HISTOGRAM],
          functions: [MIN, MAX],
          dateUnits: [AggregationDateUnit.DAY],
        },
      ],
    },
  });
}

/** Aliases no header could contain, so a leak is a substring match. */
const ALIASES = ['g_wh', 'g_st', 'g_day', 'm_n', 'm_avg', 'm_last'];

const byWarehouse = {
  alias: 'g_wh',
  field: 'warehouse',
  type: 'TERMS',
} as const;
const byStatus = { alias: 'g_st', field: 'status', type: 'TERMS' } as const;
const byDay = {
  alias: 'g_day',
  field: 'createdAt',
  type: 'DATE_HISTOGRAM',
  unit: 'DAY',
} as const;
const count: AnalysisMetric = { alias: 'm_n', type: 'COUNT' };
const average: AnalysisMetric = {
  alias: 'm_avg',
  type: 'NUMERIC',
  function: 'AVG',
  expression: { type: 'FIELD', field: 'amount' },
};
const latest: AnalysisMetric = {
  alias: 'm_last',
  type: 'NUMERIC',
  function: 'MAX',
  expression: { type: 'FIELD', field: 'createdAt' },
};

/** A drawn chart over these dimensions and metrics. */
function charted(
  groups: AnalysisViewConfig['groups'],
  metrics: AnalysisMetric[],
  chart: AnalysisViewConfig['chart'],
): AnalysisViewConfig {
  return analysisConfig({
    groups,
    metrics: metrics as AnalysisViewConfig['metrics'],
    layout: 'chart',
    chart,
  });
}

/**
 * One config per chart finding that names a dimension or a metric, with the
 * header each must say instead of its alias, in English and in Chinese.
 */
const CASES: {
  code: string;
  config: AnalysisViewConfig;
  en: string;
  zh: string;
}[] = [
  {
    code: 'chart.cartesian.percent-not-additive',
    config: charted([byWarehouse], [count, average], {
      type: 'bar',
      cartesian: {
        x: 'g_wh',
        series: [{ metric: 'm_n' }, { metric: 'm_avg' }],
        percentStack: true,
      },
    }),
    en: 'Average of Amount',
    zh: '「Amount的平均」',
  },
  {
    code: 'chart.funnel.not-additive',
    config: charted([byWarehouse], [count, average], {
      type: 'funnel',
      funnel: {
        stages: {
          from: 'group',
          category: 'g_wh',
          value: 'm_avg',
          order: ['CN', 'JP'],
        },
      },
    }),
    en: 'Average of Amount',
    zh: '「Amount的平均」',
  },
  {
    code: 'chart.metric.trend-not-additive',
    config: charted([byDay], [count, average], {
      type: 'metric',
      metric: { metric: 'm_avg', trend: { x: 'g_day' } },
    }),
    en: 'Average of Amount',
    zh: '「Amount的平均」',
  },
  {
    code: 'chart.metric.trend-alias-mismatch',
    config: charted([byDay], [count], {
      type: 'metric',
      metric: { metric: 'm_n', trend: { x: 'elsewhere' } },
    }),
    en: 'Created (by day)',
    zh: '「Created（按日）」',
  },
  {
    code: 'chart.group.unconsumed',
    config: charted([byWarehouse, byStatus], [count], {
      type: 'bar',
      cartesian: { x: 'g_wh', series: [{ metric: 'm_n' }] },
    }),
    en: 'the dimension Status',
    zh: '「Status」',
  },
  {
    code: 'chart.metric.moment',
    config: charted([byWarehouse], [count, latest], {
      type: 'bar',
      cartesian: {
        x: 'g_wh',
        series: [{ metric: 'm_n' }, { metric: 'm_last' }],
      },
    }),
    en: 'Latest of Created',
    zh: '「Created的最晚」',
  },
  {
    code: 'chart.group.unknown',
    config: charted([byWarehouse], [count], {
      type: 'pie',
      pie: { category: 'g_gone', value: 'm_n' },
    }),
    en: 'a dimension this analysis does not have',
    zh: '这个分析没有的维度',
  },
  {
    code: 'chart.metric.unknown',
    config: charted([byWarehouse], [count], {
      type: 'pie',
      pie: { category: 'g_wh', value: 'm_gone' },
    }),
    en: 'a metric this analysis does not have',
    zh: '这个分析没有的指标',
  },
  {
    code: 'chart.as-table',
    config: charted([byWarehouse, byStatus, byDay], [count], {
      type: 'bar',
      cartesian: {
        x: 'g_wh',
        splitBy: 'g_st',
        series: [{ metric: 'm_n' }],
      },
    }),
    en: 'The bar chart cannot draw this result (At most two dimensions)',
    zh: '柱状图画不了这个结果（最多两个维度）',
  },
];

function formatters(catalogue: ViewMessages): MessageFormatters {
  return {
    label: (key, params) => formatMessage(catalogue, key, params),
    issue: found => formatIssue(catalogue, found),
    issues: found => found.map(each => formatIssue(catalogue, each)).join(' '),
  };
}

function engineOver(config: AnalysisViewConfig) {
  return new ViewEngine({
    definitions: [definition()],
    store: new MemoryViewStore({
      instances: [
        {
          id: 'charted',
          definitionId: 'orders',
          title: 'Charted',
          scope: 'personal',
          revision: '1',
          config,
        },
      ],
    }),
    resolveSource: () => testSource(),
  });
}

async function findings(config: AnalysisViewConfig) {
  const engine = engineOver(config);
  const hook = renderHook(() => {
    const opened = useOpenView(engine, 'charted');
    const runtime = opened.runtime as ViewRuntime<AnalysisViewConfig> | null;
    const state = useViewRuntime(runtime);
    return { state, analysis: useAnalysisEditor(runtime) };
  });
  await waitFor(() => expect(hook.result.current.state).not.toBeNull());
  const { state, analysis } = hook.result.current;
  const chart = (state?.issues ?? []).filter(found =>
    found.code.startsWith('chart.'),
  );
  return { chart, analysis };
}

function said(
  found: readonly Issue[],
  analysis: Parameters<typeof chartIssueNamer>[0],
  catalogue: ViewMessages,
): string[] {
  const messages = formatters(catalogue);
  const name = chartIssueNamer(analysis, messages);
  return found.map(each => messages.issue(name(each)));
}

describe('a chart finding says its columns as their headers do', () => {
  it.each(CASES)('$code', async ({ code, config, en, zh }) => {
    const { chart, analysis } = await findings(config);
    expect(chart.map(found => found.code)).toContain(code);
    const raised = chart.filter(found => found.code === code);

    for (const [catalogue, header] of [
      [defaultMessages, en],
      [zhCN, zh],
    ] as const) {
      const sentences = said(raised, analysis, catalogue);
      expect(sentences.join(' ')).toContain(header);
      for (const sentence of sentences)
        for (const alias of [...ALIASES, 'g_gone', 'm_gone', 'elsewhere'])
          expect(sentence).not.toContain(alias);
    }
  });

  it('passes every other finding through as it is', () => {
    const namer = chartIssueNamer(
      { fields: [], metrics: [], groups: [] },
      formatters(defaultMessages),
    );
    const sort: Issue = {
      code: 'analysis.sort.unknown-alias',
      severity: 'error',
      path: ['sort', 0],
      params: { alias: 'm_n' },
    };
    expect(namer(sort)).toBe(sort);
    // A chart type or a reason it does not know is said as the kernel said it.
    const odd: Issue = {
      code: 'chart.as-table',
      severity: 'note',
      path: ['chart', 'type'],
      params: { type: 'sunburst', reason: 'chart.fit.too-many-dimensions' },
    };
    expect(namer(odd)).toBe(odd);
  });
});

describe('the workbench’s status line', () => {
  it('names a refused funnel’s metric by its header', async () => {
    const engine = engineOver(CASES[1].config);
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="charted"
        kinds={['analysis']}
      />,
    );
    const line = await screen.findByText(
      /A funnel needs a metric that adds up/,
    );
    expect(line.textContent).toContain('Average of Amount');
    expect(line.textContent).not.toContain('m_avg');
  });

  it('says why a chart shows as the table', async () => {
    const engine = engineOver(CASES[CASES.length - 1].config);
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="charted"
        kinds={['analysis']}
      />,
    );
    const line = await screen.findByText(
      /The bar chart cannot draw this result/,
    );
    expect(line.textContent).toContain('At most two dimensions');
  });
});
