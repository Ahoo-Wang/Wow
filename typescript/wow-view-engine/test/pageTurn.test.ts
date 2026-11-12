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

import type { PagedList } from '@ahoo-wang/wow-client/dsl';
import { describe, expect, it, vi } from 'vitest';
import {
  builtinFieldKinds,
  DEFAULT_RUNTIME_LIMITS,
  type ViewSource,
} from '../src/index.js';
import {
  dataViewRuntime,
  type RecordDataViewRuntime,
} from '../src/runtime/recordRuntime.js';
import { RequestRunner } from '../src/runtime/requestRunner.js';
import { recordSource } from '../src/runtime/source.js';
import {
  deferred,
  nextTask,
  ordersDefinition,
  recordConfig,
  ROWS,
  testEnvironment,
  testLabels,
  testSource,
} from './fixtures.js';

/**
 * A page turn is a re-fetch a reader should see (D94): while another page
 * than the one on screen is on its way the runtime says so
 * (`turningPage`), and a surface keeps the rows and fades them
 * (`useStale`). A refresh reads the page on screen again and says nothing.
 */

const PAGE: PagedList<object> = { total: 45, exact: true, list: [...ROWS] };

function record(source: ViewSource): RecordDataViewRuntime {
  return dataViewRuntime({
    id: 'runtime-1',
    definition: ordersDefinition(),
    config: recordConfig(),
    title: 'Mine',
    scope: 'personal',
    saved: null,
    kinds: builtinFieldKinds,
    limits: DEFAULT_RUNTIME_LIMITS,
    environment: testEnvironment().environment,
    source: recordSource(source),
    runner: new RequestRunner(),
    labels: testLabels(),
  }) as RecordDataViewRuntime;
}

describe('a page turn', () => {
  it('is said from the moment the page is asked for until it lands', async () => {
    const second = deferred<PagedList<object>>();
    const paged = vi
      .fn()
      .mockResolvedValueOnce(PAGE)
      .mockReturnValueOnce(second.promise);
    const runtime = record(testSource({ paged }));
    runtime.apply();
    await nextTask();
    expect(runtime.turningPage).toBe(false);

    runtime.page({ index: 2 });
    expect(runtime.getSnapshot().query.status).toBe('loading');
    expect(runtime.turningPage).toBe(true);

    second.resolve(PAGE);
    await nextTask();
    expect(runtime.turningPage).toBe(false);
  });

  it('is not said by a refresh of the page on screen', async () => {
    const again = deferred<PagedList<object>>();
    const paged = vi
      .fn()
      .mockResolvedValueOnce(PAGE)
      .mockResolvedValueOnce(PAGE)
      .mockReturnValueOnce(again.promise);
    const runtime = record(testSource({ paged }));
    runtime.apply();
    await nextTask();
    runtime.page({ index: 2 });
    await nextTask();

    runtime.refresh();
    expect(runtime.getSnapshot().query.status).toBe('loading');
    expect(runtime.turningPage).toBe(false);
    again.resolve(PAGE);
    await nextTask();
  });

  it('ends when the question starts over', async () => {
    const slow = deferred<PagedList<object>>();
    const paged = vi
      .fn()
      .mockResolvedValueOnce(PAGE)
      .mockReturnValueOnce(slow.promise)
      .mockResolvedValue(PAGE);
    const runtime = record(testSource({ paged }));
    runtime.apply();
    await nextTask();
    runtime.page({ index: 2 });
    expect(runtime.turningPage).toBe(true);

    // The same question asked anew goes back to the first page.
    runtime.apply();
    expect(runtime.turningPage).toBe(false);
    await nextTask();
  });
});
