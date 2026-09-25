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
 * A runtime smoke test of the client against whichever example server runs:
 * the one built from this commit (the same-source contract), or a released
 * image (the legacy contract sets `WOW_SERVER_VERSION`). It sends a command
 * and reads what it produced through the snapshot client, then holds a list
 * query without a `limit` to what the compatibility page documents for that
 * server version:
 *
 * - Wow 9.1.5 and later, and the same-source server: the server's default list
 *   size applies, so the query succeeds;
 * - Wow 8.11.0 to 9.1.3: `IllegalArgument` (HTTP 400 for a list; the list
 *   stream answers 200 and ends with that error event), and the `WowError`
 *   says to pass a limit.
 *
 * It needs nothing generated and speaks only `FilterExpression`, so it runs
 * unchanged against every version from 8.11 on.
 */

import { Fetcher } from '@ahoo-wang/fetcher';
import {
  CommandHeaders,
  CommandStage,
  ErrorCodes,
  SnapshotQueryClient,
  filter,
  listQuery,
  pagedQuery,
  toWowError,
  type FilterListQuery,
} from '@ahoo-wang/wow-client';
import { beforeAll, describe, expect, it } from 'vitest';

/** The released version under test, or undefined for the same-source server. */
const version = process.env.WOW_SERVER_VERSION || undefined;
const serverURL =
  process.env.WOW_EXAMPLE_SERVER_URL ?? 'http://localhost:8080/';

type ListWithoutLimit = 'server default' | 'rejected';

/** What the compatibility page says a list query without a limit gets. */
function listWithoutLimit(serverVersion: string | undefined): ListWithoutLimit {
  if (serverVersion === undefined) return 'server default';
  const [major, minor, patch] = serverVersion.split('.').map(Number);
  const at = major * 1_000_000 + minor * 1_000 + (patch ?? 0);
  return at >= 9_001_005 ? 'server default' : 'rejected';
}

interface CartItem {
  productId: string;
  quantity: number;
}
interface CartState {
  id: string;
  items: CartItem[];
}

const ownerId = `smoke-${Date.now()}-${Math.random().toString(36).slice(2)}`;
const fetcher = new Fetcher({ baseURL: serverURL });
const snapshots = new SnapshotQueryClient<CartState>({
  fetcher,
  basePath: 'owner/{ownerId}/cart',
  urlParams: { path: { ownerId } },
});
let cartId: string;

beforeAll(async () => {
  const response = await fetch(
    new URL(`owner/${ownerId}/cart/add_cart_item`, serverURL),
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        [CommandHeaders.WAIT_STAGE]: CommandStage.SNAPSHOT,
      },
      body: JSON.stringify({ productId: 'smoke-book', quantity: 2 }),
      signal: AbortSignal.timeout(60_000),
    },
  );
  const body = (await response.json()) as {
    errorCode: string;
    aggregateId: string;
  };
  expect(response.status, JSON.stringify(body)).toBe(200);
  expect(body.errorCode).toBe(ErrorCodes.SUCCEEDED);
  cartId = body.aggregateId;
}, 90_000);

const ownCart = () => filter.aggregateId(cartId);

describe(`the client against Wow ${version ?? 'built from this commit'}`, () => {
  it('reads the state a command produced', async () => {
    const state = await snapshots.getStateById(cartId);
    expect(state.items).toEqual([{ productId: 'smoke-book', quantity: 2 }]);
  });

  it('pages states with a filter', async () => {
    const page = await snapshots.pagedState(
      pagedQuery({
        filter: ownCart(),
        pagination: { index: 1, size: 10 },
      }),
    );
    expect(page.total).toBe(1);
  });

  it('lists states with an explicit limit on every version', async () => {
    const rows = await snapshots.listState(
      listQuery({ filter: ownCart(), limit: 10 }),
    );
    expect(rows).toHaveLength(1);
  });

  describe(`a list query without a limit: ${listWithoutLimit(version)}`, () => {
    const query = (): FilterListQuery => listQuery({ filter: ownCart() });

    /**
     * The request fails with HTTP 400; the stream answers 200 and ends with
     * the error event, which carries no status.
     */
    async function expectRefusal(
      pending: Promise<unknown>,
      status: number | undefined,
    ) {
      const thrown = await pending.then(
        () => undefined,
        (error: unknown) => error,
      );
      expect(thrown, 'the server accepted the query').toBeDefined();
      const wowError = await toWowError(thrown);
      expect(wowError?.status).toBe(status);
      expect(wowError?.errorCode).toBe(ErrorCodes.ILLEGAL_ARGUMENT);
      expect(wowError?.errorMsg).toMatch(/limit\[0\] must be between 1 and/);
      expect(wowError?.message).toContain('needs Wow 9.1.5 or later');
    }

    it('listState', async () => {
      if (listWithoutLimit(version) === 'rejected') {
        await expectRefusal(snapshots.listState(query()), 400);
      } else {
        expect(await snapshots.listState(query())).toHaveLength(1);
      }
    });

    it('listStateStream', async () => {
      const read = async () => {
        const rows: CartState[] = [];
        for await (const row of await snapshots.listStateStream(query()))
          rows.push(row);
        return rows;
      };
      if (listWithoutLimit(version) === 'rejected') {
        await expectRefusal(read(), undefined);
      } else {
        expect(await read()).toHaveLength(1);
      }
    });
  });
});
