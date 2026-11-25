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
 * `memorySource`'s pages as a Wow query service answers them (host.md: "so a
 * test sees what production gives", #7068): a cursor it never issued is
 * refused as the service refuses one, and a paged query's `total` mode is
 * honoured.
 */

import { describe, expect, it } from 'vitest';
import { FilterOperator } from '@ahoo-wang/wow-client/dsl';
import { memorySource } from '../src/testing/index.js';
import { sourceFailure } from '../src/runtime/failure/sourceReason.js';

const documents = [1, 2, 3, 4, 5].map(n => ({
  aggregateId: `a-${n}`,
  state: { n },
}));
const all = { op: FilterOperator.MATCH_ALL } as const;

describe("memorySource's pages", () => {
  it('follow the cursors it issues, every row once', async () => {
    const source = memorySource(documents);
    const first = await source.cursor({ filter: all, size: 2 });
    expect(first.list).toHaveLength(2);
    const second = await source.cursor({
      filter: all,
      size: 2,
      cursor: first.nextCursor,
    });
    const third = await source.cursor({
      filter: all,
      size: 2,
      cursor: second.nextCursor,
    });
    expect([...first.list, ...second.list, ...third.list]).toEqual(documents);
    expect(third.nextCursor).toBeNull();
  });

  it('refuse a cursor it never issued, as the service does: 400 wow.query.invalid, invalid-cursor', async () => {
    // BackendQueries.cursor: "A token that does not decode for this model
    // and effective sort is rejected as `Invalid cursor.` before any I/O."
    const source = memorySource(documents);
    for (const cursor of ['not-a-cursor', '-2', '0', '1.5', '']) {
      const failure = await source
        .cursor({ filter: all, cursor, size: 2 })
        .catch((error: unknown) => error);
      // Shaped as the WowProblemError a query client rejects with.
      expect(failure, cursor).toMatchObject({
        status: 400,
        code: 'wow.query.invalid',
        violations: [
          {
            path: 'cursor',
            code: 'invalid-cursor',
            message: 'Invalid cursor.',
          },
        ],
        problem: { status: 400, code: 'wow.query.invalid' },
      });
      // And the engine reads it as it reads the service's own refusal.
      expect(await sourceFailure(failure), cursor).toMatchObject({
        status: 400,
        errorCode: 'wow.query.invalid',
        violation: { code: 'invalid-cursor', path: 'cursor' },
      });
    }
  });

  it('answer a paged query of total none with no total, and count any other whole', async () => {
    // PagedList: "`total` is exact when `exact`, a lower bound otherwise,
    // and null for total `none`"; the service's BackendQueries.paged.
    const source = memorySource(documents);
    const page = { index: 1, size: 2 };
    expect(
      await source.paged({ filter: all, pagination: page, total: 'none' }),
    ).toEqual({ total: null, exact: false, list: documents.slice(0, 2) });
    for (const total of ['exact', 'bounded', undefined] as const)
      expect(
        await source.paged({
          filter: all,
          pagination: page,
          ...(total === undefined ? {} : { total }),
        }),
        total,
      ).toMatchObject({ total: 5, exact: true });
  });
});
