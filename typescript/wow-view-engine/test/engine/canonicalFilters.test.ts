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

import { afterEach, expect, it } from 'vitest';
import { FilterOperator, filter } from '@ahoo-wang/fetcher-wow';
import type { ViewEngine } from '../../src/engine/ViewEngine.js';
import type { FilterConfiguration } from '../../src/filter/filterModel.js';
import { selected, setup } from './fixtures.js';
const engines: ViewEngine[] = [];
afterEach(() => engines.splice(0).forEach(engine => engine.dispose()));
const configuration: FilterConfiguration = {
  mode: 'simple',
  root: {
    id: 'amount',
    component: { name: 'builtin' },
    operator: FilterOperator.GTE,
    field: 'state.amount',
    props: { value: 20 },
  },
};
it('uses the same configuration representation for draft, applied and persisted snapshots', async () => {
  const { engine, paged } = setup();
  engines.push(engine);
  await engine.load();
  expect(selected(engine).filterDraft).toEqual(
    selected(engine).instance.config.filters,
  );
  expect('filterMode' in selected(engine)).toBe(false);
  engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .setFilterDraft(configuration);
  expect(selected(engine).filterDraft).toEqual(configuration);
  expect(selected(engine).filterPending).toBe(true);
  await engine.record(engine.getSnapshot().selectedInstanceId!).applyFilter();
  expect(paged.mock.calls.at(-1)?.[0].filter).toEqual(
    filter.gte('state.amount', 20),
  );
  expect(selected(engine).filterBaseline).toEqual(configuration);
  expect(selected(engine).instance.config.filters).toEqual(configuration);
  await engine.save();
  await engine.restore();
  expect(selected(engine).filterDraft).toEqual(configuration);
});
it('keeps configuration-only edits saveable without reapplying an unchanged query', async () => {
  const { engine, paged } = setup();
  engines.push(engine);
  await engine.load();
  engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .setFilterDraft(configuration);
  await engine.record(engine.getSnapshot().selectedInstanceId!).applyFilter();
  const calls = paged.mock.calls.length;
  engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .setFilterDraft({ ...configuration, mode: 'advanced' });
  expect(selected(engine).filterPending).toBe(false);
  expect(selected(engine).instance.config.filters.mode).toBe('advanced');
  expect(paged).toHaveBeenCalledTimes(calls);
  await engine.save();
  expect(selected(engine).baseline.config.filters.mode).toBe('advanced');
});
