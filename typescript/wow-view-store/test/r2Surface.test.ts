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
 * Round two of the deep review: the public surface of wow-view-store
 * against what its TSDoc promises. Each test fails on `370ac57604` for the
 * issue it names, and passes once that defect is fixed.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { WowViewStore } from '../src/index.js';
import { actingAs, installFake, WRITER_ROLES } from './support.js';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('r2: WowViewStoreOptions.pageSize', () => {
  // `pageSize`: "The most views one page of `list` answers". The system
  // audience is read whole (`routes.systemViews`) and put on the first page
  // whatever room the page has left (wowViewStore.ts, `listAudience`).
  it('bounds a page that starts with the system views', async () => {
    installFake({
      systemViews: ['a', 'b', 'c'].map(id => ({
        id: `configured-${id}`,
        definitionId: 'orders',
        title: id,
        config: { kind: 'record' },
      })),
    });
    const store = new WowViewStore({
      fetcher: actingAs({
        user: 'alice',
        tenant: 't1',
        app: 'shop',
        roles: WRITER_ROLES,
      }),
      pageSize: 2,
    });
    const first = await store.list('orders');
    expect(first.items.length).toBeLessThanOrEqual(2);
  });
});
