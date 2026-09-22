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
import { renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import {
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
import { analysisConfig, ordersDefinition, testSource } from './fixtures.js';

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
  const hook = renderHook(() => {
    const open = useOpenView(engine, 'orders-1');
    const runtime = open.runtime as ViewRuntime<AnalysisViewConfig> | null;
    const state = useViewRuntime(runtime);
    const analysis = useAnalysisEditor(runtime);
    return {
      runtime,
      analysis,
      result: useAnalysisResult(runtime, analysis, { state, canDrill, drill }),
    };
  });
  await waitFor(() => expect(hook.result.current.result.view).not.toBeNull());
  return { ...hook, source, drill };
}

describe('useAnalysisResult', () => {
  it('draws nothing and offers nothing without a runtime', () => {
    const { result } = renderHook(() =>
      useAnalysisResult(null, useAnalysisEditor(null), {
        state: null,
        canDrill: true,
        drill: () => {},
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
    // Named as the applied bar names it: one condition, on the warehouse.
    expect(followUp?.conditions).toHaveLength(1);
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

  it('opens the records, narrows to the group, or splits it — each a run of its own', async () => {
    const { result, source, drill } = await opened();
    const actions = result.current.result.followUp({
      warehouse: 'CN',
    })!.actions;
    const run = (kind: string, field?: string) => {
      const action = actions.find(entry => entry.kind === kind)!;
      if (action.kind === 'split') action.run(field ?? action.options[0].field);
      else action.run();
    };

    run('records');
    expect(drill).toHaveBeenCalledWith([
      expect.objectContaining({ field: 'warehouse' }),
    ]);

    const before = vi.mocked(source.aggregate).mock.calls.length;
    run('focus');
    await waitFor(() =>
      expect(vi.mocked(source.aggregate).mock.calls.length).toBe(before + 1),
    );
    expect(
      JSON.stringify(result.current.runtime?.getSnapshot().applied.filter),
    ).toContain('warehouse');

    run('split');
    await waitFor(() =>
      expect(vi.mocked(source.aggregate).mock.calls.length).toBe(before + 2),
    );
    // The group narrowed to, and asked again by the dimension chosen.
    expect(
      result.current.runtime
        ?.getSnapshot()
        .applied.groups.map(group => group.field),
    ).toEqual(['status']);
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
});
