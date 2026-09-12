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
import { deferred, session, setup } from './fixtures.js';

it('reports budget refusal and retries only the summary after the slot is released', async () => {
  const { engine, source } = setup({ maxConcurrentQueries: 1 });
  const pending = deferred<{ list: []; total: number }>();
  source.paged.mockReturnValueOnce(pending.promise);
  const loading = engine.load();
  const failure = expect(loading).rejects.toThrow('记录服务不可用');
  try {
    await vi.waitFor(() => expect(source.paged).toHaveBeenCalledOnce());
    await vi.waitFor(() => {
      expect(session(engine).allSummary.status).toBe('error');
      expect(session(engine).allSummary.error).toContain('并发');
    });
    expect(source.aggregate).not.toHaveBeenCalled();
    await expect(engine.record('mine').refreshSummary()).rejects.toMatchObject({
      code: 'BUSY',
    });
    pending.reject(new Error('记录服务不可用'));
    await failure;
    await engine.record('mine').refreshSummary();
    expect(session(engine).allSummary.status).toBe('success');
    expect(session(engine).allSummary.values.amount?.SUM).toBe(30);
    expect(source.aggregate).toHaveBeenCalledOnce();
    expect(source.paged).toHaveBeenCalledOnce();
  } finally {
    pending.reject(new Error('记录服务不可用'));
    await failure;
    engine.dispose();
  }
});

it('isolates aggregation failure and retries only the summary', async () => {
  const { engine, source } = setup();
  source.aggregate.mockRejectedValueOnce(new Error('汇总服务不可用'));
  await engine.load();
  await vi.waitFor(() =>
    expect(session(engine).allSummary.status).toBe('error'),
  );
  expect(session(engine).queryStatus).toBe('success');
  expect(session(engine).rows).toHaveLength(2);
  expect(session(engine).pageSummary.values).toEqual({ amount: { SUM: 5 } });
  expect(source.aggregate).toHaveBeenCalledTimes(1);
  await engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .refreshSummary();
  expect(session(engine).allSummary.values.amount?.SUM).toBe(30);
  expect(source.paged).toHaveBeenCalledTimes(1);
  engine.dispose();
});
