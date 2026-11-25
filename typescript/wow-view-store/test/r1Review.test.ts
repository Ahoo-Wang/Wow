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
 * Round 1 of the deep review of `@ahoo-wang/wow-view-store`: each case
 * states what the store owes the port (or what its documentation promises)
 * and fails on `main` for the defect it names.
 */

import { describe, expect, it, vi } from 'vitest';
import { WowHeaders } from '@ahoo-wang/wow-client';
import type { ViewConfig, WriteOptions } from '@ahoo-wang/wow-view-engine';
import { WowViewStore } from '../src/index.js';
import { listAll } from './conformance/wowViewStoreService.js';
import { problem } from './support/fakeViewStore.js';
import {
  actingAs,
  BASE_URL,
  installFake,
  WRITER_ROLES,
  type Caller,
} from './support.js';

const ALICE: Caller = {
  user: 'alice',
  tenant: 't1',
  roles: WRITER_ROLES,
  app: 'console',
};

const config = {
  kind: 'record',
  pageSize: 20,
} as unknown as ViewConfig;

function storeOf(caller: Caller = ALICE): WowViewStore {
  return new WowViewStore({ fetcher: actingAs(caller) });
}

function write(requestId = `r1-${crypto.randomUUID()}`): WriteOptions {
  return { requestId };
}

describe('r1 review: WowViewStore', () => {
  it('r1 a write at a stored system view’s current revision lands after another tab changed it and changed it back', async () => {
    installFake();
    const mine = storeOf();
    const made = await mine.create(
      { definitionId: 'orders', title: 'Everyone', scope: 'system', config },
      write(),
    );
    // Another tab renames it and renames it back: its revision, a hash of
    // its content, is the one this store read, at a later version.
    const other = storeOf();
    const renamed = await other.rename(
      made.id,
      'Renamed',
      made.revision,
      write(),
    );
    const back = await other.rename(
      made.id,
      'Everyone',
      renamed.revision,
      write(),
    );
    expect(back.revision).toBe(made.revision);

    // The view is at the revision this store holds: nothing to conflict with.
    const retitled = await mine.rename(
      made.id,
      'For all',
      made.revision,
      write(),
    );
    expect(retitled).toMatchObject({
      id: made.id,
      title: 'For all',
      scope: 'system',
    });
  });

  it('r1 a retried save answers its first outcome after the view moved and the store learned where it went', async () => {
    const fake = installFake();
    const served = fake.fetch;
    // Over the fake, as the service answers a retry: the kernel answers a
    // duplicate only to a caller that reaches the aggregate with the owner
    // its first attempt was sent to (KernelAnswers.duplicate,
    // CommandReach.retries under the view's access policy); from another
    // owner it is `wow.aggregate.not-found`. The fake answers a duplicate to
    // any owner.
    const sentFrom = new Map<string, string | null>();
    let loseNextSave = false;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
        const path = new URL(String(input), BASE_URL).pathname;
        const headers = new Headers(init.headers);
        const requestId = headers.get(WowHeaders.REQUEST_ID);
        const scope = headers.get(WowHeaders.SCOPE);
        const saving = requestId !== null && path.endsWith('/save-view');
        if (
          saving &&
          sentFrom.has(requestId) &&
          sentFrom.get(requestId) !== scope
        )
          return problem(
            404,
            'wow.aggregate.not-found',
            `${path} does not exist.`,
          );
        const response = await served(input, init);
        if (saving && response.ok && !sentFrom.has(requestId))
          sentFrom.set(requestId, scope);
        if (saving && loseNextSave) {
          // Landed, but the answer never came back.
          loseNextSave = false;
          throw new TypeError('Failed to fetch');
        }
        return response;
      }),
    );
    const mine = storeOf();
    const made = await mine.create(
      { definitionId: 'orders', title: 'Mine', scope: 'personal', config },
      write(),
    );
    const saving = write();
    const larger = { ...config, pageSize: 50 } as ViewConfig;
    loseNextSave = true;
    await expect(
      mine.save(made.id, larger, made.revision, saving),
    ).rejects.toMatchObject({ code: 'UNAVAILABLE' });

    // Another tab shares it, and this one lists it where it went.
    await storeOf().changeAudience(made.id, 'shared', '2', write());
    await listAll(mine, 'orders');

    // The retry of a save that landed is answered, not refused as missing.
    const again = await mine.save(made.id, larger, made.revision, saving);
    expect(again).toMatchObject({ id: made.id, config: { pageSize: 50 } });
  });

  it('r1 preferences written at a revision this store never issues are refused as CONFLICT', async () => {
    installFake();
    const store = storeOf();
    await store.setPreferences(
      'orders',
      { order: ['a'], defaultInstanceId: null, revision: '0' },
      write(),
    );
    // As an instance write at such a revision is (`versionOf`): nothing was
    // sent, so the outcome is known, and it is no unreachable service.
    await expect(
      store.setPreferences(
        'orders',
        { order: ['b'], defaultInstanceId: null, revision: 'not-a-version' },
        write(),
      ),
    ).rejects.toMatchObject({ code: 'CONFLICT' });
  });

  it('r1 a page size below one never makes a list without an end', async () => {
    installFake();
    let store: WowViewStore;
    try {
      store = new WowViewStore({ fetcher: actingAs(ALICE), pageSize: 0 });
    } catch (refused) {
      // Refused where the store is made: a host's mistake shows at once.
      expect(refused).toBeInstanceOf(Error);
      return;
    }
    await store.create(
      { definitionId: 'orders', title: 'Mine', scope: 'personal', config },
      write(),
    );
    // The engine reads pages until a null cursor or its limit of views; a
    // page of none that names the cursor it was asked with never gets there.
    let cursor: string | null = null;
    for (let page = 0; page < 5; page++) {
      cursor = (await store.list('orders', { cursor })).nextCursor;
      if (cursor === null) break;
    }
    expect(cursor).toBeNull();
  });

  it('r1 a view by an id no view has, a link’s `..` or nothing at all, is NOT_FOUND, not a service without a view store', async () => {
    installFake();
    const store = storeOf();
    // `GET /view-store/views/..` is `/view-store/` once the URL is resolved,
    // and `…/views/` names no route: a 404 without a code either way.
    for (const id of ['..', '.', ''])
      await expect(store.get(id)).rejects.toMatchObject({ code: 'NOT_FOUND' });
  });
});
