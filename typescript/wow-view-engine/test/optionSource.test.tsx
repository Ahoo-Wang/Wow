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

import { cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import type { OptionSource } from '../src/index.js';
import { useFilterEditor, useOpenView } from '../src/react/index.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';
import { mine } from './fixtures/ui.js';

afterEach(cleanup);

const customers: OptionSource = {
  search: () => Promise.resolve({ items: [], nextCursor: null }),
  resolve: () => Promise.resolve([]),
};

function engineWith(resolveOptions?: () => OptionSource) {
  return new ViewEngine({
    definitions: [ordersDefinition()],
    store: new MemoryViewStore({ instances: [mine] }),
    resolveSource: () => testSource(),
    ...(resolveOptions ? { resolveOptions } : {}),
  });
}

/**
 * F-04: the editor two levels down has no engine to reach, so the runtime
 * carries the host's option sources, and the controller hands them on. A
 * host that wired none is `null` all the way down — the pill then types.
 */
describe('a runtime carries its option sources', () => {
  it("hands the engine's source to the runtime, and null without one", () => {
    const wired = engineWith(() => customers).create('orders', {
      title: 'Scratch',
      scope: 'personal',
      config: recordConfig(),
    });
    expect(wired.optionSource('customers')).toBe(customers);

    const bare = engineWith().create('orders', {
      title: 'Scratch',
      scope: 'personal',
      config: recordConfig(),
    });
    expect(bare.optionSource('customers')).toBeNull();
  });

  it('reaches the filter editor bound to the runtime', async () => {
    const engine = engineWith(() => customers);
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1');
      return { opened, filter: useFilterEditor(opened.runtime) };
    });
    await waitFor(() => expect(result.current.opened.runtime).not.toBeNull());

    expect(result.current.filter.optionSource?.('customers')).toBe(customers);
  });
});
