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
 * The table is always a way out (D20): how a result is looked at is not the
 * question, so a chart that cannot draw the shape never keeps the question
 * from running — not under the table, and not under a chart either, which
 * then shows the table and says why.
 */

import { AggregationGroupType } from '@ahoo-wang/fetcher-wow';
import { renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  builtinFieldKinds,
  projectAnalysis,
  validateAnalysis,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type ViewRuntime,
} from '../src/index.js';
import {
  useAnalysisEditor,
  useAnalysisResult,
  useOpenView,
  useViewRuntime,
} from '../src/react/index.js';
import { analysisConfig, ordersDefinition, testSource } from './fixtures.js';

/** The orders fixture with three dimensions to group by. */
function threeDimensions(): DataViewDefinition {
  const base = ordersDefinition();
  return ordersDefinition({
    fields: [
      ...base.fields,
      { name: 'channel', label: 'Channel', kind: 'string' },
    ],
    analysis: {
      count: true,
      fields: ['warehouse', 'status', 'channel'].map(field => ({
        field,
        groups: [AggregationGroupType.TERMS],
        functions: [],
      })),
    },
  });
}

const THREE_GROUPS: AnalysisViewConfig['groups'] = [
  { alias: 'warehouse', field: 'warehouse', type: 'TERMS' },
  { alias: 'status', field: 'status', type: 'TERMS' },
  { alias: 'channel', field: 'channel', type: 'TERMS' },
];

/**
 * Three dimensions under the chart the tray fitted them to: bars along the
 * first, split by the second — a cartesian chart takes two at most, so the
 * third is one it cannot place.
 */
function threeWay(layout: AnalysisViewConfig['layout']): AnalysisViewConfig {
  return analysisConfig({
    groups: THREE_GROUPS,
    layout,
    chart: {
      type: 'bar',
      cartesian: {
        x: 'warehouse',
        splitBy: 'status',
        series: [{ metric: 'orders' }],
      },
    },
  });
}

const ROWS = [
  { warehouse: 'CN', status: 'PAID', channel: 'web', orders: 2 },
  { warehouse: 'CN', status: 'PAID', channel: 'app', orders: 1 },
];

describe('a shape no chart can draw', () => {
  it('runs as a table: the chart is not judged under it', () => {
    const issues = validateAnalysis(
      threeDimensions(),
      threeWay('table'),
      builtinFieldKinds,
    );
    expect(issues).toEqual([]);
  });

  it('runs under a chart too, drawn as the table, and says so', () => {
    const issues = validateAnalysis(
      threeDimensions(),
      threeWay('chart'),
      builtinFieldKinds,
    );
    expect(issues).toEqual([
      {
        code: 'chart.as-table',
        severity: 'note',
        path: ['chart', 'type'],
        params: { type: 'bar', reason: 'chart.fit.too-many-dimensions' },
      },
    ]);
    // Nothing is shaped for a chart that cannot draw the rows: a panel reads
    // an absent chart as its table.
    const view = projectAnalysis(threeDimensions(), threeWay('chart'), ROWS);
    expect(view.chart).toBeUndefined();
    expect(view.rows).toHaveLength(2);
  });

  it('still judges a chart that can draw the shape', () => {
    // Two dimensions fit a bar; a bar that forgot the second is refused.
    const config = analysisConfig({
      groups: THREE_GROUPS.slice(0, 2),
      layout: 'chart',
    });
    const codes = validateAnalysis(
      threeDimensions(),
      config,
      builtinFieldKinds,
    ).map(found => [found.code, found.severity]);
    expect(codes).toEqual([['chart.group.unconsumed', 'error']]);
    // Under the table the same config runs.
    expect(
      validateAnalysis(
        threeDimensions(),
        { ...config, layout: 'table' },
        builtinFieldKinds,
      ),
    ).toEqual([]);
  });

  it('names a chart type it does not know, under a chart and only there', () => {
    const unknown = {
      ...threeWay('chart'),
      chart: { type: 'sunburst' },
    } as unknown as AnalysisViewConfig;
    const codes = (config: AnalysisViewConfig) =>
      validateAnalysis(threeDimensions(), config, builtinFieldKinds).map(
        found => found.code,
      );
    expect(codes(unknown)).toEqual(['chart.type.unknown']);
    expect(codes({ ...unknown, layout: 'table' })).toEqual([]);
  });
});

async function open(config: AnalysisViewConfig) {
  const source = testSource({
    aggregate: vi.fn(() => Promise.resolve([...ROWS])),
  });
  const engine = new ViewEngine({
    definitions: [threeDimensions()],
    store: new MemoryViewStore({
      instances: [
        {
          id: 'three-way',
          definitionId: 'orders',
          title: 'Three ways',
          scope: 'personal',
          revision: '1',
          config,
        },
      ],
    }),
    resolveSource: () => source,
  });
  const hook = renderHook(() => {
    const opened = useOpenView(engine, 'three-way');
    const runtime = opened.runtime as ViewRuntime<AnalysisViewConfig> | null;
    const state = useViewRuntime(runtime);
    const analysis = useAnalysisEditor(runtime);
    return {
      runtime,
      state,
      analysis,
      result: useAnalysisResult(runtime, analysis, {
        state,
        canDrill: true,
        drill: vi.fn(),
        follow: vi.fn(),
      }),
    };
  });
  await waitFor(() => expect(hook.result.current.result.view).not.toBeNull());
  return { ...hook, source };
}

describe('three dimensions in the workbench', () => {
  it('open as a table, run, and save', async () => {
    const { result, source } = await open(threeWay('table'));
    expect(source.aggregate).toHaveBeenCalledTimes(1);
    expect(result.current.analysis.issues).toEqual([]);
    expect(result.current.result.picked).toBe('table');
    expect(result.current.result.view?.rows).toHaveLength(2);
  });

  it('grey every chart in the picker, each with its reason', async () => {
    const { result } = await open(threeWay('table'));
    const { fits } = result.current.result;
    expect(Object.values(fits).every(fit => !fit.available)).toBe(true);
    expect(fits.bar.reason).toBe('chart.fit.too-many-dimensions');
    expect(fits.pie.reason).toBe('chart.fit.needs-one-dimension');
    expect(fits.heatmap.reason).toBe('chart.fit.needs-two-dimensions');
    expect(fits.metric.reason).toBe('chart.fit.needs-no-dimension');
  });

  it('switched to the chart, still show the table and say why', async () => {
    const { result, source } = await open(threeWay('table'));
    result.current.analysis.setLayout('chart');
    await waitFor(() => expect(result.current.analysis.layout).toBe('chart'));
    // A redraw, never a run; and nothing refused, so nothing blocks a save.
    expect(source.aggregate).toHaveBeenCalledTimes(1);
    expect(result.current.result.picked).toBe('table');
    expect(result.current.result.chartData).toBeUndefined();
    expect(
      result.current.state?.issues.filter(found => found.severity === 'error'),
    ).toEqual([]);
    expect(result.current.analysis.issues.map(found => found.code)).toEqual([
      'chart.as-table',
    ]);
  });

  it('turned to the chart, fit a chart saved under the table to the shape', async () => {
    // Saved under the table, where the chart is not judged: its bars name
    // a dimension this analysis no longer has.
    const { result, source } = await open(
      analysisConfig({
        groups: THREE_GROUPS.slice(0, 2),
        chart: {
          type: 'bar',
          cartesian: { x: 'gone', series: [{ metric: 'orders' }] },
        },
      }),
    );
    expect(result.current.analysis.issues).toEqual([]);
    result.current.analysis.setLayout('chart');
    await waitFor(() => expect(result.current.result.picked).toBe('bar'));
    expect(result.current.analysis.chart.cartesian).toMatchObject({
      x: 'warehouse',
      splitBy: 'status',
    });
    expect(result.current.analysis.issues).toEqual([]);
    expect(source.aggregate).toHaveBeenCalledTimes(1);
  });

  it('a third dimension added under a chart runs, and the chart comes back when it goes', async () => {
    const { result, source } = await open(
      analysisConfig({
        groups: THREE_GROUPS.slice(0, 2),
        layout: 'chart',
        chart: {
          type: 'bar',
          cartesian: {
            x: 'warehouse',
            splitBy: 'status',
            series: [{ metric: 'orders' }],
          },
        },
      }),
    );
    expect(result.current.result.picked).toBe('bar');
    result.current.analysis.addGroup(THREE_GROUPS[2]);
    result.current.analysis.submit();
    await waitFor(() => expect(source.aggregate).toHaveBeenCalledTimes(2));
    await waitFor(() =>
      expect(result.current.result.ran?.groups).toHaveLength(3),
    );
    expect(result.current.result.picked).toBe('table');
    expect(
      result.current.state?.issues.filter(found => found.severity === 'error'),
    ).toEqual([]);

    result.current.analysis.removeGroup(2);
    result.current.analysis.submit();
    await waitFor(() =>
      expect(result.current.result.ran?.groups).toHaveLength(2),
    );
    expect(result.current.result.picked).toBe('bar');
    expect(result.current.analysis.issues).toEqual([]);
  });
});
