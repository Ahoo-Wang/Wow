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
 * 「每个〈维度〉只留前 N 个」 (D89): the per-group top N, compiled into a
 * hidden dense `RANK` within the outer dimension and a having that keeps
 * the ranks up to N beside 「只保留」; admitted only where the source ranks,
 * over two dimensions or more; followed by the editor when what it names
 * leaves; and edited as one sentence in the result slot.
 */

import {
  AggregationFunction,
  AggregationGroupType,
  DerivedExpressionType,
  type DerivedAggregationMetric,
} from '@ahoo-wang/wow-client';
import {
  act,
  cleanup,
  fireEvent,
  render,
  renderHook,
  screen,
  waitFor,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import {
  narrowsGroups,
  topRankAlias,
  validateAnalysis,
} from '../src/analysis/index.js';
import { withoutFirstUnavailable } from '../src/runtime/failure/unavailable.js';
import {
  MemoryViewStore,
  ViewEngine,
  builtinFieldKinds,
  compileAnalysis,
  type AnalysisTop,
  type AnalysisViewConfig,
  type DataViewDefinition,
} from '../src/index.js';
import { useAnalysisEditor, useOpenView } from '../src/react/index.js';
import { DataWorkbench } from '../src/ui/index.js';
import { defaultMessages } from '../src/ui/kit/messages.js';
import {
  analysisConfig,
  ordersDefinition,
  resourcesOf,
  testSource,
} from './fixtures.js';
import { analysisContext as context, errorCodes } from './fixtures/analysis.js';
import { openTray } from './fixtures/workbench.js';

afterEach(cleanup);

function definition(
  windows: ('RANK' | 'TOTAL')[] = ['RANK'],
  having = true,
): DataViewDefinition {
  return ordersDefinition({
    analysis: {
      count: true,
      expressions: true,
      ...(having ? { having: true } : {}),
      windows,
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
        {
          field: 'amount',
          groups: [],
          functions: [AggregationFunction.SUM],
          any: true,
        },
      ],
    },
  });
}

const TOP: AnalysisTop = { within: 'warehouse', metric: 'amount', count: 3 };

function config(
  overrides: Partial<AnalysisViewConfig> = {},
): AnalysisViewConfig {
  return analysisConfig({
    groups: [
      { alias: 'warehouse', field: 'warehouse', type: 'TERMS' },
      { alias: 'status', field: 'status', type: 'TERMS' },
    ],
    metrics: [
      { alias: 'orders', type: 'COUNT' },
      {
        alias: 'amount',
        type: 'NUMERIC',
        function: 'SUM',
        expression: { type: 'FIELD', field: 'amount' },
      },
      { alias: 'sample', type: 'ANY', field: 'amount' },
    ],
    top: TOP,
    ...overrides,
  });
}

const compiled = (value: AnalysisViewConfig, source = definition()) =>
  compileAnalysis(source, value, builtinFieldKinds, context);

const codes = (value: AnalysisViewConfig, source = definition()) =>
  errorCodes(validateAnalysis(source, value, builtinFieldKinds));

describe('compiling the top N', () => {
  it('asks for a dense rank within the outer dimension and keeps the first N', () => {
    const query = compiled(config());
    const rank = query.metrics[3] as DerivedAggregationMetric;
    expect(rank.alias).toBe('amount__rank');
    expect(rank.expression).toEqual({
      type: DerivedExpressionType.RANK,
      metric: 'amount',
      within: ['warehouse'],
      direction: 'DESC',
    });
    expect(query.having).toEqual({
      type: 'CONDITION',
      metric: 'amount__rank',
      operator: 'LTE',
      value: 3,
    });
  });

  it('ranks lowest first when asked', () => {
    const query = compiled(config({ top: { ...TOP, direction: 'ASC' } }));
    const rank = query.metrics[3] as DerivedAggregationMetric;
    expect(rank.expression).toMatchObject({ direction: 'ASC' });
  });

  it('keeps the ranks beside 「只保留」, both holding', () => {
    const query = compiled(
      config({
        having: {
          type: 'CONDITION',
          metric: 'orders',
          operator: 'GT',
          value: 10,
        },
      }),
    );
    expect(query.having).toEqual({
      type: 'AND',
      operands: [
        { type: 'CONDITION', metric: 'orders', operator: 'GT', value: 10 },
        {
          type: 'CONDITION',
          metric: 'amount__rank',
          operator: 'LTE',
          value: 3,
        },
      ],
    });
  });

  it('names the rank around an alias the config already holds', () => {
    const taken = config();
    const metrics = [
      ...taken.metrics,
      { alias: 'amount__rank', type: 'COUNT' },
      { alias: 'amount__rank2', type: 'COUNT' },
    ] as AnalysisViewConfig['metrics'];
    expect(topRankAlias({ groups: taken.groups, metrics }, TOP)).toBe(
      'amount__rank3',
    );
  });

  it('adds nothing without one', () => {
    const query = compiled(config({ top: undefined }));
    expect(query.metrics).toHaveLength(3);
    expect(query.having).toBeUndefined();
  });

  it('narrows the groups as 「只保留」 does', () => {
    expect(narrowsGroups(config())).toBe(true);
    expect(narrowsGroups(config({ top: undefined }))).toBe(false);
  });
});

describe('admitting the top N', () => {
  it('admits one over two dimensions where the source ranks', () => {
    expect(codes(config())).toEqual([]);
  });

  it('is refused where the source does not rank or keep groups', () => {
    expect(codes(config(), definition(['TOTAL']))).toEqual([
      'analysis.top.unsupported',
    ]);
    expect(codes(config(), definition(['RANK'], false))).toEqual([
      'analysis.top.unsupported',
    ]);
  });

  it('needs a second dimension to rank within the first', () => {
    expect(
      codes(
        config({
          groups: [{ alias: 'warehouse', field: 'warehouse', type: 'TERMS' }],
        }),
      ),
    ).toEqual(['analysis.top.requires-groups']);
  });

  it('names the dimension, the metric and the count it is refused for', () => {
    expect(codes(config({ top: { ...TOP, within: 'region' } }))).toEqual([
      'analysis.top.unknown-within',
    ]);
    expect(codes(config({ top: { ...TOP, metric: 'sample' } }))).toEqual([
      'analysis.top.unknown-metric',
    ]);
    expect(codes(config({ top: { ...TOP, metric: 'missing' } }))).toEqual([
      'analysis.top.unknown-metric',
    ]);
    for (const count of [0, 2.5, 101])
      expect(codes(config({ top: { ...TOP, count } }))).toEqual([
        'analysis.top.count-out-of-range',
      ]);
    expect(
      codes(config({ top: { ...TOP, direction: 'UP' as never } })),
    ).toEqual(['analysis.top.malformed']);
  });

  it('refuses a top N that is no object at all', () => {
    expect(codes(config({ top: 3 as never }))).toEqual([
      'analysis.config.malformed',
    ]);
  });

  it('is taken out whole when the source cannot have it', () => {
    const value = config();
    const issues = validateAnalysis(
      definition(['TOTAL']),
      value,
      builtinFieldKinds,
    );
    const next = withoutFirstUnavailable(value, issues);
    expect(next && 'top' in next).toBe(false);
  });
});

async function editor(overrides: Partial<AnalysisViewConfig> = {}) {
  const store = new MemoryViewStore({
    instances: [
      {
        id: 'orders-1',
        definitionId: 'orders',
        title: 'By warehouse and status',
        scope: 'personal',
        revision: '1',
        config: config(overrides),
      },
    ],
  });
  const engine = new ViewEngine({
    resources: resourcesOf([definition()], () => testSource()),
    store,
  });
  const { result } = renderHook(() => {
    const opened = useOpenView(engine, 'orders-1');
    return { opened, analysis: useAnalysisEditor(opened.runtime) };
  });
  await waitFor(() => expect(result.current.opened.runtime).not.toBeNull());
  const draft = () =>
    result.current.opened.runtime!.getSnapshot().draft as AnalysisViewConfig;
  return { analysis: () => result.current.analysis, draft };
}

describe('the top N following the question', () => {
  it('is offered where the source ranks and keeps groups', async () => {
    const { analysis } = await editor();
    expect(analysis().topAllowed).toBe(true);
    expect(analysis().top).toEqual(TOP);
    expect(analysis().issues).toEqual([]);
  });

  it('goes with the dimension it ranks within', async () => {
    const { analysis, draft } = await editor();
    act(() => analysis().removeGroup(0));
    expect('top' in draft()).toBe(false);
    expect(analysis().issues).toEqual([]);
  });

  it('goes with the second dimension, within the only one every group is first', async () => {
    const { analysis, draft } = await editor();
    act(() => analysis().removeGroup(1));
    expect('top' in draft()).toBe(false);
  });

  it('goes with the metric it ranks by', async () => {
    const { analysis, draft } = await editor();
    act(() => analysis().removeMetric(1));
    expect('top' in draft()).toBe(false);
    expect(analysis().issues).toEqual([]);
  });

  it('stays when another metric leaves', async () => {
    const { analysis, draft } = await editor();
    act(() => analysis().removeMetric(0));
    expect(draft().top).toEqual(TOP);
  });

  it('is set and cleared by the editor', async () => {
    const { analysis, draft } = await editor({ top: undefined });
    act(() => analysis().setTop(TOP));
    expect(draft().top).toEqual(TOP);
    act(() => analysis().setTop(undefined));
    expect('top' in draft()).toBe(false);
  });
});

async function workbench(overrides: Partial<AnalysisViewConfig> = {}) {
  const store = new MemoryViewStore({
    instances: [
      {
        id: 'orders-1',
        definitionId: 'orders',
        title: 'By warehouse and status',
        scope: 'personal',
        revision: '1',
        config: config(overrides),
      },
    ],
  });
  const engine = new ViewEngine({
    resources: resourcesOf([definition()], () => testSource()),
    store,
  });
  render(
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId="orders-1"
      viewKinds={['analysis']}
    />,
  );
  await openTray();
  const draft = () =>
    engine.openRuntimes()[0].getSnapshot().draft as AnalysisViewConfig;
  return { draft };
}

const slot = (name: string) =>
  document.querySelector<HTMLElement>(`[data-slot="${name}"]`);

describe('the top N in the result slot', () => {
  it('starts as the first three of each value of the first dimension', async () => {
    const { draft } = await workbench({ top: undefined });
    expect(slot('analysis-top')).toBeNull();
    fireEvent.click(slot('add-top')!);
    expect(draft().top).toEqual({
      within: 'warehouse',
      metric: 'orders',
      count: 3,
    });
    expect(slot('add-top')).toBeNull();
    expect(slot('analysis-top')).not.toBeNull();
  });

  it('is not offered over one dimension', async () => {
    await workbench({
      top: undefined,
      groups: [{ alias: 'warehouse', field: 'warehouse', type: 'TERMS' }],
    });
    expect(slot('add-top')).toBeNull();
  });

  it('writes a count the source takes and holds one it does not', async () => {
    const { draft } = await workbench();
    const box = screen.getByRole('textbox', {
      name: defaultMessages['label.analysis.top-count'],
    });
    fireEvent.change(box, { target: { value: '5' } });
    expect(draft().top?.count).toBe(5);
    fireEvent.change(box, { target: { value: '0' } });
    expect(draft().top?.count).toBe(5);
    expect(slot('top-invalid')).not.toBeNull();
  });

  it('is taken away whole', async () => {
    const { draft } = await workbench();
    fireEvent.click(
      screen.getByRole('button', {
        name: defaultMessages['label.analysis.remove-top'],
      }),
    );
    expect('top' in draft()).toBe(false);
    expect(slot('add-top')).not.toBeNull();
  });

  it('ranks the other way and back', async () => {
    const user = userEvent.setup();
    const { draft } = await workbench();
    const order = () =>
      screen.getByLabelText(defaultMessages['label.analysis.top-direction']);
    await user.click(order());
    await user.click(
      await screen.findByRole('option', {
        name: defaultMessages['label.analysis.top-direction.ASC'],
      }),
    );
    await waitFor(() =>
      expect(draft().top).toEqual({ ...TOP, direction: 'ASC' }),
    );
    await user.click(order());
    await user.click(
      await screen.findByRole('option', {
        name: defaultMessages['label.analysis.top-direction.DESC'],
      }),
    );
    await waitFor(() => expect(draft().top).toEqual(TOP));
  });

  it('shows a stored one naming a dimension the lists no longer offer', async () => {
    await workbench({ top: { ...TOP, within: 'region' } });
    expect(slot('analysis-top')?.textContent).toContain('region');
  });

  it('is said in the reading of the result once it ran', async () => {
    await workbench({ top: { ...TOP, direction: 'ASC' } });
    await waitFor(() =>
      expect(slot('analysis-reading')?.textContent).toContain(
        `Top 3 in each Warehouse by Sum of Amount (${defaultMessages['label.analysis.top-direction.ASC']})`,
      ),
    );
  });
});
