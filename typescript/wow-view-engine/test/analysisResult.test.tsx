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

import {
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/fetcher-wow';
import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import {
  AUTO_APPLY_DELAY_MS,
  MemoryViewStore,
  ViewEngine,
  type AnalysisViewConfig,
  type ViewInstance,
  type ViewRuntime,
} from '../src/index.js';
import {
  useAnalysisEditor,
  useAnalysisResult,
  useOpenView,
  useViewRuntime,
} from '../src/react/index.js';
import {
  analysisConfig,
  namedOrdersDefinition,
  ordersDefinition,
  testEnvironment,
  testSource,
} from './fixtures.js';
import { settle } from './fixtures/ui.js';

const analysisView: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'By warehouse',
  scope: 'personal',
  revision: '1',
  config: analysisConfig(),
};

/**
 * The orders fixture with a second dimension to split by: the fixture
 * declares only `warehouse` as groupable, and the one group in force is
 * never on offer.
 */
function twoDimensions() {
  return ordersDefinition({
    analysis: {
      count: true,
      fields: [
        {
          field: 'warehouse',
          groups: [AggregationGroupType.TERMS],
          functions: [],
        },
        {
          field: 'status',
          groups: [AggregationGroupType.TERMS],
          functions: [],
        },
        { field: 'amount', groups: [], functions: [AggregationFunction.SUM] },
      ],
    },
  });
}

/** The controller over an opened analysis, with the workbench's drill stubbed. */
async function opened(canDrill = true) {
  const source = testSource();
  const engine = new ViewEngine({
    definitions: [twoDimensions()],
    store: new MemoryViewStore({ instances: [analysisView] }),
    resolveSource: () => source,
  });
  const drill = vi.fn();
  const follow = vi.fn();
  const hook = renderHook(() => {
    const open = useOpenView(engine, 'orders-1');
    const runtime = open.runtime as ViewRuntime<AnalysisViewConfig> | null;
    const state = useViewRuntime(runtime);
    const analysis = useAnalysisEditor(runtime);
    return {
      runtime,
      analysis,
      result: useAnalysisResult(runtime, analysis, {
        state,
        canDrill,
        drill,
        follow,
      }),
    };
  });
  await waitFor(() => expect(hook.result.current.result.view).not.toBeNull());
  return { ...hook, source, drill, follow };
}

describe('useAnalysisResult', () => {
  it('draws nothing and offers nothing without a runtime', () => {
    const { result } = renderHook(() =>
      useAnalysisResult(null, useAnalysisEditor(null), {
        state: null,
        canDrill: true,
        drill: () => {},
        follow: () => {},
      }),
    );
    expect(result.current.view).toBeNull();
    expect(result.current.ran).toBeUndefined();
    expect(result.current.pickable).toBe(false);
    expect(result.current.followUp({ warehouse: 'CN' })).toBeNull();
  });

  it('offers the records, a split and a focus on a group, in that order', async () => {
    const { result } = await opened();
    const followUp = result.current.result.followUp({
      warehouse: 'CN',
      orders: 2,
    });
    expect(followUp?.actions.map(action => action.kind)).toEqual([
      'records',
      'split',
      'focus',
    ]);
    // Named dimension by dimension: the warehouse's column, the row's value
    // under it, and its one condition as the applied bar names it.
    expect(followUp?.groups).toHaveLength(1);
    expect(followUp?.groups[0]).toMatchObject({
      column: { alias: 'warehouse', role: 'group' },
      value: 'CN',
    });
    expect(followUp?.groups[0].conditions.map(item => item.text)).toHaveLength(
      1,
    );
    // What the two views a follow-up opens are of: the definition's records,
    // and this view.
    expect(
      followUp?.actions.map(action => [action.kind, action.subject]),
    ).toEqual([
      ['records', 'Orders'],
      ['split', 'By warehouse'],
      ['focus', 'By warehouse'],
    ]);
    // A split is by a field the result is not grouped by already.
    const split = followUp?.actions.find(action => action.kind === 'split');
    const fields =
      split?.kind === 'split' ? split.options.map(option => option.field) : [];
    expect(fields).toEqual(['status']);
  });

  it('offers no records where the workbench cannot open them', async () => {
    const { result } = await opened(false);
    expect(
      result.current.result
        .followUp({ warehouse: 'CN' })
        ?.actions.map(action => action.kind),
    ).toEqual(['split', 'focus']);
  });

  it('opens the records, follows the group, or splits it', async () => {
    const { result, source, drill, follow } = await opened();
    const actions = result.current.result.followUp({
      warehouse: 'CN',
    })!.actions;
    const run = (kind: string, field?: string) => {
      const action = actions.find(entry => entry.kind === kind)!;
      if (action.kind === 'split')
        action.run(field ?? action.options[0].field, `named ${kind}`);
      else action.run(`named ${kind}`);
    };
    const row = [expect.objectContaining({ field: 'warehouse' })];

    run('records');
    // Named by the menu, and by what the records are once the group goes.
    expect(drill).toHaveBeenCalledWith(row, 'named records', 'Orders');

    // Only this group: the question that ran, narrowed, handed to the
    // workbench as a view of its own — this one is neither edited nor run.
    const before = vi.mocked(source.aggregate).mock.calls.length;
    run('focus');
    expect(follow).toHaveBeenCalledTimes(1);
    const [config, title, conditions, subject] = follow.mock.calls[0];
    expect(title).toBe('named focus');
    expect(subject).toBe(result.current.runtime?.getSnapshot().title);
    expect(conditions).toEqual(row);
    expect(config).toMatchObject({
      kind: 'analysis',
      groups: [expect.objectContaining({ field: 'warehouse' })],
      layout: result.current.analysis.layout,
      filter: { op: 'and', children: row },
    });
    expect(result.current.runtime?.getSnapshot().dirty).toBe(false);
    expect(vi.mocked(source.aggregate).mock.calls.length).toBe(before);

    // Split: the group narrowed to, asked again by the dimension chosen —
    // beside this view too, which stays as it ran.
    run('split');
    expect(follow).toHaveBeenCalledTimes(2);
    const [split, splitTitle, splitConditions] = follow.mock.calls[1];
    expect(splitTitle).toBe('named split');
    expect(splitConditions).toEqual(row);
    expect(split).toMatchObject({
      kind: 'analysis',
      groups: [expect.objectContaining({ field: 'status' })],
      filter: { op: 'and', children: row },
      sort: [],
    });
    expect(result.current.runtime?.getSnapshot().dirty).toBe(false);
    expect(
      result.current.runtime
        ?.getSnapshot()
        .applied.groups.map(group => group.field),
    ).toEqual(['warehouse']);
    expect(vi.mocked(source.aggregate).mock.calls.length).toBe(before);

    // A field that is no dimension here opens nothing.
    const offer = actions.find(entry => entry.kind === 'split')!;
    if (offer.kind === 'split') offer.run('nowhere', 'named nothing');
    expect(follow).toHaveBeenCalledTimes(2);
  });

  it('redraws for a type picked, and never runs for it', async () => {
    const { result, source } = await opened();
    const before = vi.mocked(source.aggregate).mock.calls.length;

    result.current.result.choose('pie');
    await waitFor(() => expect(result.current.result.picked).toBe('pie'));
    expect(result.current.analysis.layout).toBe('chart');
    expect(result.current.result.chartData?.type).toBe('pie');

    result.current.result.choose('table');
    await waitFor(() => expect(result.current.result.picked).toBe('table'));
    expect(vi.mocked(source.aggregate).mock.calls.length).toBe(before);
  });

  /**
   * The picker's own path to a type, beside the editor's `setChartType`:
   * bars of the total picked as a pie are a pie of the total, not of the
   * first metric (audit P0-10).
   */
  it('keeps the metric the chart measured when a type is picked', async () => {
    const source = testSource();
    const totals: ViewInstance = {
      ...analysisView,
      id: 'orders-totals',
      config: analysisConfig({
        metrics: [
          { alias: 'orders', type: 'COUNT' },
          {
            alias: 'total',
            type: 'NUMERIC',
            function: 'SUM',
            expression: { type: 'FIELD', field: 'amount' },
          },
        ],
        layout: 'chart',
        chart: {
          type: 'bar',
          cartesian: { x: 'warehouse', series: [{ metric: 'total' }] },
        },
      }),
    };
    const engine = new ViewEngine({
      definitions: [twoDimensions()],
      store: new MemoryViewStore({ instances: [totals] }),
      resolveSource: () => source,
    });
    const { result } = renderHook(() => {
      const open = useOpenView(engine, 'orders-totals');
      const runtime = open.runtime as ViewRuntime<AnalysisViewConfig> | null;
      const state = useViewRuntime(runtime);
      const analysis = useAnalysisEditor(runtime);
      return {
        analysis,
        result: useAnalysisResult(runtime, analysis, {
          state,
          canDrill: true,
          drill: vi.fn(),
          follow: vi.fn(),
        }),
      };
    });
    await waitFor(() => expect(result.current.result.view).not.toBeNull());

    result.current.result.choose('pie');
    await waitFor(() => expect(result.current.result.picked).toBe('pie'));
    expect(result.current.analysis.chart.pie?.value).toBe('total');
  });

  it('asks for the whole once when a trend card is switched to read it', async () => {
    // A monthly analysis drawn as bars asks one query; its whole is asked
    // only when something draws it — here, a metric card over a trend.
    const source = testSource();
    const monthly: ViewInstance = {
      ...analysisView,
      id: 'orders-monthly',
      config: analysisConfig({
        groups: [
          {
            alias: 'month',
            field: 'createdAt',
            type: 'DATE_HISTOGRAM',
            unit: 'MONTH',
          },
        ],
        layout: 'chart',
        chart: {
          type: 'bar',
          cartesian: { x: 'month', series: [{ metric: 'orders' }] },
        },
      }),
    };
    const clock = testEnvironment();
    const engine = new ViewEngine({
      definitions: [namedOrdersDefinition()],
      store: new MemoryViewStore({ instances: [monthly] }),
      resolveSource: () => source,
      environment: clock.environment,
    });
    // Nothing more is on its way: past the debounce an edit would run
    // after, and past the effects and promises a run would start from.
    const quiet = async () => {
      act(() => clock.advance(AUTO_APPLY_DELAY_MS));
      await settle();
    };
    const { result } = renderHook(() => {
      const open = useOpenView(engine, 'orders-monthly');
      const runtime = open.runtime as ViewRuntime<AnalysisViewConfig> | null;
      const state = useViewRuntime(runtime);
      const analysis = useAnalysisEditor(runtime);
      return {
        analysis,
        result: useAnalysisResult(runtime, analysis, {
          state,
          canDrill: true,
          drill: vi.fn(),
          follow: vi.fn(),
        }),
      };
    });
    await waitFor(() => expect(result.current.result.view).not.toBeNull());
    const grouped = vi.mocked(source.aggregate).mock.calls.length;
    expect(result.current.result.view?.overall).toBeUndefined();

    // Read as its last period — the default — the card is one of the rows
    // that came back: it redraws, and nothing is asked.
    result.current.analysis.updateChart({
      type: 'metric',
      metric: { metric: 'orders', trend: { x: 'month' } },
    });
    await waitFor(() =>
      expect(result.current.result.chartData).toMatchObject({
        type: 'metric',
      }),
    );
    expect(result.current.result.chartData).not.toHaveProperty('whole');
    await quiet();
    expect(vi.mocked(source.aggregate).mock.calls.length).toBe(grouped);

    // Switched to the whole in the options, it runs once for it.
    result.current.analysis.updateChart({
      type: 'metric',
      metric: { metric: 'orders', trend: { x: 'month', headline: 'whole' } },
    });
    // One run, and it brought the whole.
    await waitFor(() =>
      expect(result.current.result.view?.overall).toBeDefined(),
    );
    const calls = vi.mocked(source.aggregate).mock.calls.slice(grouped);
    expect(calls.some(([query]) => query.groupBy === undefined)).toBe(true);
    expect(result.current.result.chartData).toMatchObject({
      type: 'metric',
      value: result.current.result.view?.overall?.orders,
    });

    // Asked once: the config that ran asked for the whole, so nothing runs
    // again on its own.
    const settled = vi.mocked(source.aggregate).mock.calls.length;
    await quiet();
    expect(vi.mocked(source.aggregate).mock.calls.length).toBe(settled);
  });

  /**
   * A view saved with a chart that is refused runs nothing, so there are no
   * rows to redraw. The status line opens the picker, and a pick there used
   * to do nothing at all: it is fitted to the draft's shape instead, which
   * makes the view valid, and it runs.
   */
  describe('a pick with no rows', () => {
    /** A funnel over the warehouse, saved with the stages given. */
    const funnelOf = (value: string, order: string[]) =>
      analysisConfig({
        metrics: [
          { alias: 'orders', type: 'COUNT' },
          {
            alias: 'avg',
            type: 'NUMERIC',
            function: 'AVG',
            expression: { type: 'FIELD', field: 'amount' },
          },
        ],
        layout: 'chart',
        chart: {
          type: 'funnel',
          funnel: {
            stages: { from: 'group', category: 'warehouse', value, order },
          },
        },
      });

    async function broken(config: AnalysisViewConfig) {
      const source = testSource();
      const engine = new ViewEngine({
        definitions: [
          ordersDefinition({
            analysis: {
              count: true,
              fields: [
                {
                  field: 'warehouse',
                  groups: [AggregationGroupType.TERMS],
                  functions: [],
                },
                {
                  field: 'amount',
                  groups: [],
                  functions: [AggregationFunction.SUM, AggregationFunction.AVG],
                },
              ],
            },
          }),
        ],
        store: new MemoryViewStore({
          instances: [{ ...analysisView, id: 'orders-broken', config }],
        }),
        resolveSource: () => source,
      });
      const hook = renderHook(() => {
        const open = useOpenView(engine, 'orders-broken');
        const runtime = open.runtime as ViewRuntime<AnalysisViewConfig> | null;
        const state = useViewRuntime(runtime);
        const analysis = useAnalysisEditor(runtime);
        return {
          runtime,
          analysis,
          result: useAnalysisResult(runtime, analysis, {
            state,
            canDrill: true,
            drill: vi.fn(),
            follow: vi.fn(),
          }),
        };
      });
      await waitFor(() => expect(hook.result.current.runtime).not.toBeNull());
      // Refused: nothing ran, nothing to draw.
      expect(
        hook.result.current.analysis.issues.map(found => found.code),
      ).not.toEqual([]);
      expect(source.aggregate).not.toHaveBeenCalled();
      expect(hook.result.current.result.view).toBeNull();
      return { ...hook, source };
    }

    it('offers what the draft’s shape draws, and the stages its chart names', async () => {
      const { result } = await broken(funnelOf('orders', ['CN']));
      const { fits } = result.current.result;
      expect(fits.bar).toMatchObject({ available: true, recommended: true });
      expect(fits.funnel).toEqual({
        available: false,
        reason: 'chart.fit.needs-two-stages',
      });
      expect(fits.metric.reason).toBe('chart.fit.needs-no-dimension');
    });

    it('fits a type picked to the draft, and runs', async () => {
      const { result, source } = await broken(funnelOf('orders', ['CN']));
      result.current.result.choose('bar');
      await waitFor(() => expect(result.current.result.view).not.toBeNull());
      expect(source.aggregate).toHaveBeenCalledTimes(1);
      expect(result.current.analysis.issues).toEqual([]);
      expect(result.current.result.picked).toBe('bar');
      expect(result.current.analysis.chart.cartesian).toMatchObject({
        x: 'warehouse',
      });
      // Applied: the bar is what ran.
      expect(result.current.runtime?.getSnapshot().applied.chart.type).toBe(
        'bar',
      );
    });

    it('takes the table as the way out, and leaves the chart as it was saved', async () => {
      const { result, source } = await broken(funnelOf('orders', ['CN']));
      result.current.result.choose('table');
      await waitFor(() => expect(result.current.result.view).not.toBeNull());
      expect(source.aggregate).toHaveBeenCalledTimes(1);
      expect(result.current.result.picked).toBe('table');
      // Under the table the chart is not judged (D20), so nothing had to be
      // replaced for the table to run: the funnel is still the author's.
      expect(result.current.analysis.chart.type).toBe('funnel');
      expect(result.current.analysis.issues).toEqual([]);
    });

    it('measures a funnel of averages with the count instead', async () => {
      const { result, source } = await broken(funnelOf('avg', ['CN', 'JP']));
      expect(result.current.analysis.issues.map(found => found.code)).toContain(
        'chart.funnel.not-additive',
      );
      // The count adds up, so the funnel is offered, and picked it counts.
      expect(result.current.result.fits.funnel.available).toBe(true);
      result.current.result.choose('funnel');
      await waitFor(() => expect(result.current.result.view).not.toBeNull());
      expect(source.aggregate).toHaveBeenCalledTimes(1);
      expect(result.current.analysis.chart.funnel?.stages).toMatchObject({
        value: 'orders',
        order: ['CN', 'JP'],
      });
    });

    it('leaves the run to Apply while the draft holds another edit', async () => {
      const { result, source } = await broken(funnelOf('orders', ['CN']));
      result.current.analysis.setLimit(10);
      await waitFor(() => expect(result.current.analysis.pending).toBe(true));
      result.current.result.choose('bar');
      await waitFor(() =>
        expect(result.current.analysis.chart.type).toBe('bar'),
      );
      expect(result.current.analysis.issues).toEqual([]);
      expect(source.aggregate).not.toHaveBeenCalled();
      result.current.analysis.submit();
      await waitFor(() => expect(result.current.result.view).not.toBeNull());
    });
  });
});
