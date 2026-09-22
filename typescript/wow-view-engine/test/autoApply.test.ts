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

import { describe, expect, it, vi } from 'vitest';
import {
  AUTO_APPLY_DELAY_MS,
  autoApplyDue,
  builtinFieldKinds,
  DataViewRuntime,
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  RequestRunner,
  ViewEngine,
  autoRunMembers,
  type AnalysisViewConfig,
  type ViewSource,
} from '../src/index.js';
import {
  analysisConfig,
  dashboardConfig,
  overviewDefinition,
  ordersDefinition,
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';

/** An analysis runtime on the test clock, with auto-apply switched on. */
function analysis(source: ViewSource = testSource()) {
  const clock = testEnvironment();
  const runtime = new DataViewRuntime({
    id: 'analysis-1',
    definition: ordersDefinition(),
    config: analysisConfig(),
    title: 'By warehouse',
    scope: 'personal',
    saved: null,
    kinds: builtinFieldKinds,
    limits: DEFAULT_RUNTIME_LIMITS,
    environment: clock.environment,
    source,
    runner: new RequestRunner(),
  });
  runtime.apply();
  runtime.setAutoApply(true);
  return { runtime, clock, source };
}

/** One edit to the question that the definition admits: fewer groups. */
const topFive = (): Partial<AnalysisViewConfig> => ({ limit: 5 });

describe('改了就跑: the analysis runs again on its own', () => {
  it('runs the draft a moment after its question changed, not before', () => {
    const { runtime, clock, source } = analysis();
    const runs = () => vi.mocked(source.aggregate).mock.calls.length;
    const before = runs();

    runtime.edit(topFive());
    expect(runs()).toBe(before);
    clock.advance(AUTO_APPLY_DELAY_MS - 1);
    expect(runs()).toBe(before);
    clock.advance(1);

    expect(runs()).toBe(before + 1);
    expect(runtime.getSnapshot().applied).toEqual(runtime.getSnapshot().draft);
    expect(clock.timers).toBe(0);
  });

  it('merges a burst of edits into one query', () => {
    const { runtime, clock, source } = analysis();
    const before = vi.mocked(source.aggregate).mock.calls.length;

    runtime.edit(topFive());
    clock.advance(200);
    runtime.edit({ limit: 7 });
    clock.advance(200);
    runtime.edit({ limit: 8 });
    clock.advance(AUTO_APPLY_DELAY_MS);

    expect(vi.mocked(source.aggregate).mock.calls.length).toBe(before + 1);
    expect(runtime.getSnapshot().applied).toMatchObject({ limit: 8 });
  });

  it('waits while the range changed: the conditions are Apply’s (D20)', () => {
    const { runtime, clock, source } = analysis();
    const before = vi.mocked(source.aggregate).mock.calls.length;

    runtime.edit({
      ...topFive(),
      filter: {
        op: 'and',
        children: [{ field: 'warehouse', operator: 'EQ', value: 'SH' }],
      },
    });
    clock.advance(AUTO_APPLY_DELAY_MS * 2);

    expect(vi.mocked(source.aggregate).mock.calls.length).toBe(before);
    expect(clock.timers).toBe(0);
    // Apply runs the whole draft, conditions and question together.
    runtime.apply();
    expect(vi.mocked(source.aggregate).mock.calls.length).toBe(before + 1);
  });

  it('never runs a draft the definition refuses', () => {
    const { runtime, clock, source } = analysis();
    const before = vi.mocked(source.aggregate).mock.calls.length;

    runtime.edit({
      groups: [{ type: 'TERMS', field: 'nowhere', alias: 'nowhere' }],
    });
    clock.advance(AUTO_APPLY_DELAY_MS * 2);

    expect(vi.mocked(source.aggregate).mock.calls.length).toBe(before);
  });

  it('stops when switched off, and when the draft is reverted or applied by hand', () => {
    const { runtime, clock, source } = analysis();
    const before = vi.mocked(source.aggregate).mock.calls.length;

    runtime.edit(topFive());
    runtime.setAutoApply(false);
    clock.advance(AUTO_APPLY_DELAY_MS * 2);
    expect(vi.mocked(source.aggregate).mock.calls.length).toBe(before);
    expect(runtime.getSnapshot().autoApply).toBe(false);

    // Switched back on with the edit still waiting, it runs.
    runtime.setAutoApply(true);
    clock.advance(AUTO_APPLY_DELAY_MS);
    expect(vi.mocked(source.aggregate).mock.calls.length).toBe(before + 1);

    // Applied by hand before the moment comes: nothing is due any more.
    runtime.edit({ limit: 3 });
    runtime.apply();
    expect(clock.timers).toBe(0);
    clock.advance(AUTO_APPLY_DELAY_MS);
    expect(vi.mocked(source.aggregate).mock.calls.length).toBe(before + 2);

    runtime.dispose();
  });

  it('runs nothing of a kind whose model declares no question', () => {
    // Which members run on their own is declared beside the config types
    // (`autoRunMembers`); a record view declares none, so the switch on
    // arms nothing, and its edits wait for the press as they always did.
    expect(autoRunMembers('record')).toEqual([]);
    const clock = testEnvironment();
    const source = testSource();
    const runtime = new DataViewRuntime({
      id: 'record-1',
      definition: ordersDefinition(),
      config: recordConfig(),
      title: 'Orders',
      scope: 'personal',
      saved: null,
      kinds: builtinFieldKinds,
      limits: DEFAULT_RUNTIME_LIMITS,
      environment: clock.environment,
      source,
      runner: new RequestRunner(),
    });
    runtime.apply();
    runtime.setAutoApply(true);
    const before = vi.mocked(source.paged).mock.calls.length;

    runtime.edit({ pageSize: 5 });
    clock.advance(AUTO_APPLY_DELAY_MS * 2);

    expect(vi.mocked(source.paged).mock.calls.length).toBe(before);
    expect(clock.timers).toBe(0);
    expect(runtime.getSnapshot().autoApply).toBe(true);
    runtime.dispose();
  });

  it('is a preference a dashboard keeps too, and arms nothing with', async () => {
    const store = new MemoryViewStore({ instances: [] });
    const engine = new ViewEngine({
      definitions: [ordersDefinition(), overviewDefinition()],
      store,
      resolveSource: () => testSource(),
    });
    const instance = await store.create(
      {
        definitionId: 'overview',
        title: 'Overview',
        scope: 'personal',
        config: dashboardConfig(),
      },
      { requestId: 'r' },
    );
    const runtime = await engine.open(instance.id);
    expect(runtime.getSnapshot().autoApply).toBe(false);
    runtime.setAutoApply(true);
    expect(runtime.getSnapshot().autoApply).toBe(true);
    expect(autoRunMembers('dashboard')).toEqual([]);
    runtime.dispose();
  });

  it('reads whether a draft is due from what changed', () => {
    const applied = analysisConfig();
    const due = (draft: AnalysisViewConfig, autoApply = true) =>
      autoApplyDue({ draft, applied, issues: [], autoApply });
    expect(due(applied)).toBe(false);
    expect(due(analysisConfig(topFive()))).toBe(true);
    // A layout is presentation: nothing to run.
    expect(due(analysisConfig({ layout: 'chart' }))).toBe(false);
    expect(due(analysisConfig(topFive()), false)).toBe(false);
    // A question member and the range together: the range holds it all.
    expect(
      due(
        analysisConfig({
          ...topFive(),
          filter: {
            op: 'and',
            children: [{ field: 'warehouse', operator: 'EQ', value: 'SH' }],
          },
        }),
      ),
    ).toBe(false);
    // A record draft is never due: its kind declares no question.
    expect(
      autoApplyDue({
        draft: recordConfig({ pageSize: 5 }),
        applied: recordConfig(),
        issues: [],
        autoApply: true,
      }),
    ).toBe(false);
    expect(
      autoApplyDue({
        draft: analysisConfig(topFive()),
        applied,
        issues: [{ code: 'x', severity: 'error', path: [] }],
        autoApply: true,
      }),
    ).toBe(false);
  });
});

describe('the auto-run preference', () => {
  it('is the user’s per definition, kept beside the order and the default', async () => {
    const store = new MemoryViewStore({ instances: [] });
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store,
      resolveSource: () => testSource(),
    });
    expect((await engine.preferences('orders')).autoRun).toBeUndefined();

    const off = await engine.setAutoRun('orders', false);
    expect(off.autoRun).toBe(false);
    expect((await store.getPreferences('orders')).autoRun).toBe(false);

    const on = await engine.setAutoRun('orders', true);
    expect(on).toMatchObject({
      autoRun: true,
      order: [],
      defaultInstanceId: null,
    });
  });
});
