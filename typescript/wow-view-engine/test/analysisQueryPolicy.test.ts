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

import { expect, it, vi } from 'vitest';
import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import { ViewEngine } from '../src/engine/ViewEngine.js';
import { createFilterConfiguration } from '../src/filter/filterConfiguration.js';
import { analysisQueryPolicy } from '../src/analysis/analysisQueryPolicy.js';
import type {
  AnalysisSession,
  AnalysisViewInstance,
} from '../src/contracts/viewModel.js';

const instance: AnalysisViewInstance = {
  id: 'analysis',
  definitionId: 'orders',
  kind: 'analysis',
  title: 'Orders',
  scope: { type: 'personal' },
  revision: '1',
  config: {
    filters: createFilterConfiguration({
      id: 'all',
      component: { name: 'builtin' },
      operator: FilterOperator.MATCH_ALL,
      props: {},
    }),
    dimensions: [],
    metrics: [
      {
        id: 'count',
        component: { name: 'count' },
        alias: 'orders',
        title: 'Orders',
        props: {},
      },
    ],
    sort: [],
    limit: 100,
    presentation: { layout: 'table', columns: [] },
  },
};
function setup() {
  const aggregate = vi.fn().mockResolvedValue([{ orders: 2 }]);
  const engine = new ViewEngine({
    definitionId: 'orders',
    definition: {
      id: 'orders',
      title: 'Orders',
      sourceId: 'orders',
      fields: [],
      analysis: { count: true, fields: [] },
    },
    instances: {
      instances: [instance, { ...instance, id: 'other' }],
      defaultInstanceId: instance.id,
    },
    host: {
      resolveSource: () => ({ aggregate }),
      instance: {
        load: async () => ({
          ...instance,
          revision: '2',
          title: 'Remote orders',
        }),
      },
    },
  });
  return { engine, aggregate };
}

it('distinguishes manual drafts, explicit reload and automatic refresh by executed query', async () => {
  const { engine } = setup();
  try {
    await engine.load();
    const clean = engine.getSnapshot().sessions.analysis as AnalysisSession;
    expect(analysisQueryPolicy(clean, 'auto')).toBe(true);
    engine.analysis('analysis').edit(value => ({ ...value, limit: 50 }));
    const dirty = engine.getSnapshot().sessions.analysis as AnalysisSession;
    expect(analysisQueryPolicy(dirty, 'manual')).toBe(true);
    expect(analysisQueryPolicy(dirty, 'reload')).toBe(false);
    expect(analysisQueryPolicy(dirty, 'auto')).toBe(false);
    engine.analysis('analysis').restore();
    engine.analysis('analysis').edit(value => ({
      ...value,
      presentation: {
        ...value.presentation,
        columns: [{ alias: 'missing', visible: true }],
      },
    }));
    const presentation = engine.getSnapshot().sessions
      .analysis as AnalysisSession;
    expect(presentation.validation.length).toBeGreaterThan(0);
    expect(analysisQueryPolicy(presentation, 'manual')).toBe(true);
    expect(analysisQueryPolicy(presentation, 'auto')).toBe(true);
  } finally {
    engine.dispose();
  }
});

it('blocks invalid input and duplicate pending queries for every intent, and conflicts for implicit runs', async () => {
  const { engine } = setup();
  try {
    await engine.load();
    const clean = engine.getSnapshot().sessions.analysis as AnalysisSession;
    for (const intent of ['manual', 'open', 'reload', 'auto'] as const) {
      expect(analysisQueryPolicy({ ...clean, queryValid: false }, intent)).toBe(
        false,
      );
      expect(
        analysisQueryPolicy(
          {
            ...clean,
            queryStatus: 'loading',
            pendingQuery: clean.compilation.plan!,
          },
          intent,
        ),
      ).toBe(false);
    }
    const conflict = {
      editVersion: 0,
      baseline: instance,
      local: instance,
      remote: instance,
      filterDraft: instance.config.filters,
      filterValid: true,
    };
    expect(analysisQueryPolicy({ ...clean, conflict }, 'manual')).toBe(true);
    for (const intent of ['open', 'reload', 'auto'] as const) {
      expect(analysisQueryPolicy({ ...clean, conflict }, intent)).toBe(false);
      expect(
        analysisQueryPolicy({ ...clean, requiresReload: true }, intent),
      ).toBe(false);
    }
    expect(
      analysisQueryPolicy(
        { ...clean, queryStatus: 'idle', result: null },
        'open',
      ),
    ).toBe(true);
    expect(analysisQueryPolicy({ ...clean, result: null }, 'auto')).toBe(false);
    expect(
      analysisQueryPolicy({ ...clean, queryStatus: 'error' }, 'auto'),
    ).toBe(false);
    expect(
      analysisQueryPolicy({ ...clean, writeStatus: 'saving' }, 'auto'),
    ).toBe(false);
  } finally {
    engine.dispose();
  }
});

it('refresh executes only the confirmed query without a mounted view', async () => {
  const { engine, aggregate } = setup();
  try {
    await engine.load();
    const commands = engine.analysis('analysis');
    commands.edit(value => ({ ...value, limit: 50 }));
    await commands.refresh();
    expect(aggregate).toHaveBeenCalledOnce();
    await commands.run();
    await commands.refresh();
    expect(aggregate).toHaveBeenCalledTimes(3);
    commands.setFilterValidity(false);
    await commands.refresh();
    expect(aggregate).toHaveBeenCalledTimes(3);
    await expect(commands.run()).rejects.toThrow();
  } finally {
    engine.dispose();
  }
});

it('keeps unresolved remote conflicts out of refresh while allowing an explicit draft run', async () => {
  const { engine, aggregate } = setup();
  try {
    await engine.load();
    engine.setTitle('Local orders', instance.id);
    await engine.reloadInstance(instance.id);
    expect(engine.getSnapshot().sessions.analysis.conflict).toBeDefined();
    await engine.analysis(instance.id).refresh();
    expect(aggregate).toHaveBeenCalledOnce();
    await engine.analysis(instance.id).run();
    expect(aggregate).toHaveBeenCalledTimes(2);
    expect(engine.getSnapshot().sessions.analysis.conflict).toBeDefined();
  } finally {
    engine.dispose();
  }
});

it('resumes public refresh after navigating away from a pending refresh and returning', async () => {
  const { engine, aggregate } = setup();
  let finish: ((rows: { orders: number }[]) => void) | undefined;
  try {
    await engine.load();
    const confirmed = engine.getSnapshot().sessions.analysis.result;
    aggregate.mockImplementationOnce(
      () =>
        new Promise(resolve => {
          finish = resolve;
        }),
    );
    const refresh = engine.analysis(instance.id).refresh();
    await vi.waitFor(() => expect(aggregate).toHaveBeenCalledTimes(2));
    await engine.selectInstance('other');
    await refresh;
    expect(engine.getSnapshot().sessions.analysis).toMatchObject({
      queryStatus: 'success',
      queryError: null,
      pendingQuery: null,
      result: confirmed,
    });
    await engine.selectInstance(instance.id);
    expect(aggregate).toHaveBeenCalledTimes(3);
    aggregate.mockResolvedValueOnce([{ orders: 5 }]);
    await engine.analysis(instance.id).refresh();
    expect(engine.getSnapshot().sessions.analysis.result?.rows).toEqual([
      { orders: 5 },
    ]);
    finish?.([{ orders: 99 }]);
    await Promise.resolve();
    expect(engine.getSnapshot().sessions.analysis.result?.rows).toEqual([
      { orders: 5 },
    ]);
  } finally {
    finish?.([]);
    engine.dispose();
  }
});

it('keeps idle when navigating away before the first successful analysis', async () => {
  const { engine, aggregate } = setup();
  let finish: ((rows: { orders: number }[]) => void) | undefined;
  try {
    aggregate.mockImplementationOnce(
      () =>
        new Promise(resolve => {
          finish = resolve;
        }),
    );
    const loading = engine.load();
    await vi.waitFor(() => expect(aggregate).toHaveBeenCalledOnce());
    await engine.selectInstance('other');
    await loading;
    expect(engine.getSnapshot().sessions.analysis).toMatchObject({
      queryStatus: 'idle',
      queryError: null,
      pendingQuery: null,
      result: null,
    });
    await engine.selectInstance(instance.id);
    expect(engine.getSnapshot().sessions.analysis.queryStatus).toBe('success');
  } finally {
    finish?.([]);
    engine.dispose();
  }
});

it('does not overwrite a replacement query started by an abort listener', async () => {
  const { engine, aggregate } = setup();
  let replacement: Promise<void> | undefined;
  let finish: ((rows: { orders: number }[]) => void) | undefined;
  let finishReplacement: ((rows: { orders: number }[]) => void) | undefined;
  try {
    await engine.load();
    aggregate.mockImplementationOnce(
      (_query, _attributes, controller: AbortController) => {
        controller.signal.addEventListener(
          'abort',
          () => {
            const commands = engine.analysis(instance.id);
            commands.edit(config => ({ ...config, limit: 50 }));
            replacement = commands.run();
          },
          { once: true },
        );
        return new Promise(resolve => {
          finish = resolve;
        });
      },
    );
    const refresh = engine.analysis(instance.id).refresh();
    await vi.waitFor(() => expect(aggregate).toHaveBeenCalledTimes(2));
    aggregate.mockImplementationOnce(
      () =>
        new Promise(resolve => {
          finishReplacement = resolve;
        }),
    );
    await engine.selectInstance('other');
    await refresh;
    expect(engine.getSnapshot().sessions.analysis).toMatchObject({
      queryStatus: 'loading',
      pendingQuery: { query: { limit: 50 } },
    });
    finishReplacement?.([{ orders: 7 }]);
    await replacement;
    finish?.([{ orders: 99 }]);
    await Promise.resolve();
    expect(engine.getSnapshot().sessions.analysis).toMatchObject({
      queryStatus: 'success',
      pendingQuery: null,
      result: { rows: [{ orders: 7 }] },
    });
  } finally {
    finish?.([]);
    finishReplacement?.([]);
    engine.dispose();
  }
});
