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
 * `StateClient` and `OwnStateClient`: states at a version or a time, entity
 * tags, the caller's own, and traces.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { OwnStateClient, StateClient, type SystemEvent } from '../src/index.js';
import { snapshotOf } from '../src/testing.js';
import { BASE_URL, jsonResponse, stubFetch } from './fixtures/fetchStub';
import { client, PRODUCT, problemResponse } from './fixtures/clients';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('StateClient', () => {
  const ref = { type: PRODUCT, tenantId: 't', id: 'p1' };
  // The snapshot record's shape, a snapshot query's row (decision D348 ⑦).
  const snapshot = snapshotOf(ref, { name: 'n' }, { version: 2 });

  it('reads the latest state, at a version or a time, or of a minimum version', async () => {
    const { requests } = stubFetch(() => jsonResponse(snapshot));
    const states = new StateClient<{ name: string }>(PRODUCT, client());
    expect((await states.get('p1')).state.name).toBe('n');
    await states.get('p1', { version: 2 });
    await states.get('p1', { at: new Date(Date.UTC(2026, 0, 1)) });
    await states.get('p1', { minVersion: 3 });
    expect(requests.map(r => r.url)).toEqual([
      `${BASE_URL}/eshop-catalog/product/p1/state`,
      `${BASE_URL}/eshop-catalog/product/p1/state?version=2`,
      `${BASE_URL}/eshop-catalog/product/p1/state?at=2026-01-01T00%3A00%3A00.000Z`,
      `${BASE_URL}/eshop-catalog/product/p1/state?minVersion=3`,
    ]);
  });

  it('answers notModified for the entity tag held', async () => {
    const { requests } = stubFetch(
      () =>
        new Response(null, {
          status: 304,
          headers: { ETag: '"2-0123456789abcdef"' },
        }),
    );
    const states = new StateClient(PRODUCT, client());
    expect(
      await states.read('p1', { ifNoneMatch: '2-0123456789abcdef' }),
    ).toEqual({
      notModified: true,
      version: 2,
      etag: '2-0123456789abcdef',
    });
    expect(requests[0].headers.get('If-None-Match')).toBe(
      '"2-0123456789abcdef"',
    );
    await expect(states.get('p1')).rejects.toThrow('304');
  });

  it('answers a state with its entity tag', async () => {
    stubFetch(
      () =>
        new Response(JSON.stringify(snapshot), {
          headers: {
            'Content-Type': 'application/json',
            ETag: '"2-0123456789abcdef"',
          },
        }),
    );
    expect(await new StateClient(PRODUCT, client()).read('p1')).toEqual({
      notModified: false,
      snapshot,
      etag: '2-0123456789abcdef',
    });
  });

  it('rejects a missing state with its problem', async () => {
    stubFetch(() => problemResponse(404, 'wow.aggregate.not-found'));
    await expect(
      new StateClient(PRODUCT, client()).get('p1'),
    ).rejects.toMatchObject({ code: 'wow.aggregate.not-found', status: 404 });
  });

  it('finds a state, or undefined only for an aggregate that does not exist', async () => {
    let answer: () => Response = () => jsonResponse(snapshot);
    stubFetch(() => answer());
    const states = new StateClient<{ name: string }>(PRODUCT, client());
    expect((await states.find('p1'))?.state.name).toBe('n');
    answer = () => problemResponse(404, 'wow.aggregate.not-found');
    expect(await states.find('p1', { version: 2 })).toBeUndefined();
    // Another 404, a route or a query that does not exist, is a mistake.
    answer = () => problemResponse(404, 'wow.query.not-found');
    await expect(states.find('p1')).rejects.toMatchObject({ status: 404 });
    answer = () => problemResponse(403, 'wow.aggregate.forbidden');
    await expect(states.find('p1')).rejects.toMatchObject({ status: 403 });
  });

  it("reads the caller's own state and trace by no id, and by id as any other", async () => {
    let answer: () => Response = () => jsonResponse(snapshot);
    const { requests } = stubFetch(() => answer());
    const states = new OwnStateClient<{ name: string }>(PRODUCT, client());
    expect((await states.getOwn()).state.name).toBe('n');
    expect((await states.readOwn({ version: 2 })).notModified).toBe(false);
    expect((await states.findOwn({ minVersion: 3 }))?.state.name).toBe('n');
    await states.get('p1');
    answer = () => jsonResponse({ ref, commits: [] });
    await states.traceOwn({ limit: 3 });
    expect(requests.map(r => r.url)).toEqual([
      `${BASE_URL}/eshop-catalog/product/state`,
      `${BASE_URL}/eshop-catalog/product/state?version=2`,
      `${BASE_URL}/eshop-catalog/product/state?minVersion=3`,
      `${BASE_URL}/eshop-catalog/product/p1/state`,
      `${BASE_URL}/eshop-catalog/product/state/trace?limit=3`,
    ]);
    answer = () => problemResponse(404, 'wow.aggregate.not-found');
    expect(await states.findOwn()).toBeUndefined();
    answer = () => problemResponse(400, 'wow.query.malformed');
    await expect(states.getOwn()).rejects.toMatchObject({
      code: 'wow.query.malformed',
      status: 400,
    });
  });

  it('reads a trace', async () => {
    const { requests } = stubFetch(() => jsonResponse({ ref, commits: [] }));
    await new StateClient(PRODUCT, client()).trace('p1', {
      from: 2,
      to: 5,
      limit: 3,
    });
    expect(requests[0].url).toBe(
      `${BASE_URL}/eshop-catalog/product/p1/state/trace?from=2&to=5&limit=3`,
    );
  });
  it("types the system events a trace carries beside the aggregate's own", async () => {
    // The shape edge/http's HistoryRoutesTest reads from a trace after
    // `wow.lifecycle.transfer-owner`, and an event of the aggregate itself,
    // named `context.aggregate.EventName`.
    type ProductEvent =
      | {
          readonly name: 'eshop-catalog.product.ProductRenamed';
          readonly revision: number;
          readonly body: { readonly title: string };
        }
      | SystemEvent;
    const commit = (version: number, events: unknown[]) => ({
      version,
      commitTime: version * 1000,
      commandId: `c${version}`,
      requestId: `r${version}`,
      commandType: 'wow.lifecycle.TransferOwner',
      events,
      scope: { ownerId: 'bob' },
      deleted: false,
      tags: {},
      state: {},
    });
    stubFetch(() =>
      jsonResponse({
        ref,
        commits: [
          commit(4, [
            {
              name: 'wow.lifecycle.OwnerTransferred',
              revision: 1,
              body: { ownerId: 'bob' },
            },
          ]),
          commit(5, [
            {
              name: 'eshop-catalog.product.ProductRenamed',
              revision: 1,
              body: { title: 'Pear' },
            },
            { name: 'wow.ScheduleCancelled', revision: 1, body: { key: 'k' } },
          ]),
        ],
      }),
    );
    const trace = await new StateClient<unknown, ProductEvent>(
      PRODUCT,
      client(),
    ).trace('p1');
    const read = trace.commits.flatMap(c =>
      c.events.map(event => {
        switch (event.name) {
          case 'wow.lifecycle.OwnerTransferred':
            return event.body.ownerId;
          case 'eshop-catalog.product.ProductRenamed':
            return event.body.title;
          case 'wow.ScheduleCancelled':
            return event.body.key;
          default:
            return event.name;
        }
      }),
    );
    expect(read).toEqual(['bob', 'Pear', 'k']);
    const deleted: SystemEvent = {
      name: 'wow.lifecycle.AggregateDeleted',
      revision: 1,
      body: {},
    };
    const wrong: SystemEvent = {
      name: 'wow.lifecycle.SpaceTransferred',
      revision: 1,
      // @ts-expect-error a lifecycle event's body is the kernel's shape
      body: { ownerId: 'x' },
    };
    void [deleted, wrong];
  });
});
