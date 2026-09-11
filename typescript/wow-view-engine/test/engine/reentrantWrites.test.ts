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
import { createFilterConfiguration } from '../../src/filter/filterCore.js';

import { filter, FilterOperator } from '@ahoo-wang/fetcher-wow';
import { expect, it, vi } from 'vitest';
import type { ViewInstance } from '../../src/contracts/viewModel.js';
import type { ViewHost } from '../../src/contracts/ViewHost.js';
import { deferred, instance, setup } from './fixtures.js';

it.each(['loaded', 'pending'] as const)(
  'keeps %s navigation triggered by save-as source cancellation newer than the created copy',
  async navigation => {
    const response = deferred<ViewInstance>();
    const destination = deferred<ViewInstance>();
    const { engine, paged } = setup({
      instances: {
        instances:
          navigation === 'loaded'
            ? [instance(), instance('third')]
            : [instance()],
        defaultInstanceId: 'mine',
      },
      host: {
        instance: {
          create: () => response.promise,
          load: () => destination.promise,
        },
      } as unknown as ViewHost,
    });
    await engine.load();
    const stalled = deferred<{ list: never[]; total: number }>();
    paged.mockReturnValueOnce(stalled.promise);
    const refreshing = engine
      .record(engine.getSnapshot().selectedInstanceId!)
      .refresh();
    await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
    const saving = engine.saveAs({
      title: 'Copy',
      scope: { type: 'personal' },
    });
    let redirected: Promise<void> | undefined;
    let redirect = true;
    const unsubscribe = engine.subscribe(() => {
      const state = engine.getSnapshot();
      if (
        redirect &&
        state.selectedInstanceId === 'mine' &&
        state.sessions.mine.queryStatus === 'idle'
      ) {
        redirect = false;
        redirected = engine.selectInstance('third');
      }
    });
    try {
      response.resolve({ ...instance('created'), title: 'Copy' });
      await saving;
      expect(redirected).toBeDefined();
      expect(engine.getSnapshot().selectedInstanceId).toBe(
        navigation === 'loaded' ? 'third' : 'mine',
      );
      expect(engine.getSnapshot().sessions.created.queryStatus).toBe('idle');
      destination.resolve(instance('third'));
      await redirected;
      expect(engine.getSnapshot().selectedInstanceId).toBe('third');
      expect(engine.getSnapshot().sessions.third.queryStatus).toBe('success');
      expect(engine.getSnapshot().sessions.mine.writeStatus).toBe('idle');
    } finally {
      unsubscribe();
      engine.dispose();
      stalled.resolve({ list: [], total: 0 });
      destination.resolve(instance('third'));
      await Promise.all([refreshing, redirected]);
    }
  },
);

it('carries edits published while canceling the source read into the selected copy', async () => {
  const response = deferred<ViewInstance>();
  const stalled = deferred<{ list: never[]; total: number }>();
  const { engine, paged } = setup({
    filterCompilers: {
      threshold: {
        compile: (props, context) =>
          filter.gte(context.field!.field, Number(props.threshold)),
      },
    },
    host: {
      instance: { create: () => response.promise },
    } as unknown as ViewHost,
  });
  await engine.load();
  paged.mockReturnValueOnce(stalled.promise);
  const refreshing = engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .refresh();
  await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
  const saving = engine.saveAs({ title: 'Copy', scope: { type: 'personal' } });
  const draft = {
    id: 'threshold',
    operator: FilterOperator.GTE,
    field: 'state.amount',
    component: { name: 'threshold' },
    props: { threshold: 20, caption: 'Twenty' },
  };
  let edited = false;
  const unsubscribe = engine.subscribe(() => {
    const source = engine.getSnapshot().sessions.mine;
    if (
      !edited &&
      source.queryStatus === 'idle' &&
      source.writeStatus === 'creating'
    ) {
      edited = true;
      engine.record('mine').setFilterDraft(createFilterConfiguration(draft));
    }
  });
  try {
    response.resolve({ ...instance('created'), title: 'Copy' });
    await saving;
    expect(edited).toBe(true);
    expect(engine.getSnapshot().selectedInstanceId).toBe('created');
    for (const id of ['mine', 'created']) {
      expect(engine.getSnapshot().sessions[id]).toMatchObject({
        filterDraft: { root: draft },
        filterPending: true,
        appliedFilter: filter.matchAll(),
      });
    }
  } finally {
    unsubscribe();
    engine.dispose();
    stalled.resolve({ list: [], total: 0 });
    await refreshing;
  }
});
