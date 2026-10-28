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

import { describe, expect, it } from 'vitest';
import {
  CommandRejectedError,
  CommandResultStatus,
  WowProblemError,
} from '@ahoo-wang/wow-client';
import { commandResultOf } from '@ahoo-wang/wow-client/testing';
import { ViewStoreError } from '@ahoo-wang/wow-view-engine';
import {
  boardTitles,
  Failure,
  failureOf,
  guard,
  request,
} from '../src/errors.js';
import { Remembered } from '../src/remembered.js';

function answered(status: number, code?: string): Failure {
  return new Failure(
    new Error('failed'),
    new WowProblemError({
      type: code ? `urn:wow:error:${code}` : 'about:blank',
      title: 'Failed',
      status,
      ...(code === undefined ? {} : { code }),
    }),
  );
}

describe('the V10 store reads a failure', () => {
  it.each([
    ['wow.aggregate.version-mismatch', 412, 'CONFLICT'],
    ['wow.command.request-id-reused', 409, 'CONFLICT'],
    ['wow.aggregate.not-found', 404, 'NOT_FOUND'],
    ['wow.query.not-found', 404, 'NOT_FOUND'],
    ['wow.aggregate.deleted', 410, 'NOT_FOUND'],
    ['view-store.not-found', 404, 'NOT_FOUND'],
    ['wow.auth.unauthenticated', 401, 'FORBIDDEN'],
    ['wow.auth.forbidden', 403, 'FORBIDDEN'],
    ['wow.aggregate.forbidden', 403, 'FORBIDDEN'],
    ['view-store.system-view-read-only', 403, 'FORBIDDEN'],
    ['view-store.invalid', 400, 'INVALID'],
    ['view-store.app-required', 400, 'INVALID'],
    ['view-store.owner-required', 400, 'INVALID'],
    ['view-store.referenced-by-shared-dashboard', 409, 'INVALID'],
    ['wow.query.malformed', 400, 'INVALID'],
    ['wow.command.contended', 409, 'UNAVAILABLE'],
    ['wow.wait.timeout', 504, 'UNAVAILABLE'],
  ])('%s (%i) as %s, by its code', (code, status, port) => {
    expect(answered(status, code).code).toBe(port);
  });

  it.each([
    [400, 'INVALID'],
    [422, 'INVALID'],
    [401, 'FORBIDDEN'],
    [403, 'FORBIDDEN'],
    [410, 'NOT_FOUND'],
    [409, 'CONFLICT'],
    [412, 'CONFLICT'],
    [500, 'UNAVAILABLE'],
    [502, 'UNAVAILABLE'],
  ])(
    'an answer without a known code by its status: %i as %s',
    (status, port) => {
      expect(answered(status).code).toBe(port);
      expect(answered(status, 'later.unknown-code').code).toBe(port);
    },
  );

  it('reads a 404 without a code as a route the service does not serve', () => {
    expect(answered(404).code).toBe('UNSUPPORTED');
    expect(answered(404, 'later.unknown-code').code).toBe('NOT_FOUND');
  });

  it('holds the problem’s code and whether the service answered', () => {
    expect(answered(503, 'wow.storage.unavailable').held()).toMatchObject({
      detail: { code: 'wow.storage.unavailable' },
      reachable: true,
    });
    const unreached = new Failure(new TypeError('Failed to fetch'));
    expect(unreached.code).toBe('UNAVAILABLE');
    expect(unreached.held()).toEqual({ cause: unreached.cause });
    expect(unreached.message).toBe(
      'The view store could not be reached: TypeError: Failed to fetch',
    );
    expect(new Failure('down').message).toBe(
      'The view store could not be reached: down',
    );
    expect(new Failure({ message: 'odd' }).message).toBe(
      'The view store could not be reached: odd',
    );
  });

  it('reads a rejection by its problem, else by its code as a conflict', async () => {
    const result = {
      ...commandResultOf(
        {
          type: { context: 'view-store', name: 'view' },
          tenantId: 't',
          id: 'v1',
        },
        { status: CommandResultStatus.REJECTED },
      ),
      error: { code: 'view-store.app-required', message: 'no app' },
    };
    const problem = answered(400, 'view-store.app-required').problem!;
    const withProblem = await failureOf(
      new CommandRejectedError(result, {
        problem: problem.problem,
        cause: problem,
      }),
    );
    expect(withProblem.problem).toBe(problem);
    expect(withProblem.code).toBe('INVALID');
    const without = await failureOf(new CommandRejectedError(result));
    expect(without.problem).toBeUndefined();
    expect(without.problemCode).toBe('view-store.app-required');
    expect(without.reached).toBe(true);
    expect(without.message).toContain('rejected the command');
  });

  it('keeps a failure it already read', async () => {
    const failure = answered(400);
    expect(await failureOf(failure)).toBe(failure);
  });

  it('passes a body that did not parse to the guard, which reads it as answered', async () => {
    const parsing = request(async () => JSON.parse('<html>'));
    await expect(parsing).rejects.toBeInstanceOf(SyntaxError);
    await expect(
      guard(() => request(async () => JSON.parse('<html>'))),
    ).rejects.toMatchObject({ code: 'UNAVAILABLE', reachable: true });
    await expect(
      guard(async () => {
        throw 'odd';
      }),
    ).rejects.toMatchObject({ code: 'UNAVAILABLE', message: 'odd' });
    const own = new ViewStoreError('FORBIDDEN', 'no');
    await expect(guard(async () => Promise.reject(own))).rejects.toBe(own);
  });
});

describe('boardTitles', () => {
  it('reads the titles of the boards the claim refusal names', () => {
    expect(
      boardTitles({
        boards: [
          { id: 'b1', title: 'Ops' },
          { id: 'b2', title: 'Daily (team) "x" (y)' },
          { id: 'b3', title: ' ' },
          { id: 'b4' },
          { title: 7 },
          null,
          'b5',
        ],
      }),
    ).toEqual(['Ops', 'Daily (team) "x" (y)', 'b3', 'b4']);
    expect(boardTitles(undefined)).toEqual([]);
    expect(boardTitles({})).toEqual([]);
    expect(boardTitles({ boards: 'Ops' })).toEqual([]);
  });
});

describe('Remembered', () => {
  it('forgets the oldest entry past its limit', () => {
    const remembered = new Remembered<number>(2);
    remembered.set('a', 1);
    remembered.set('b', 2);
    remembered.set('a', 3);
    remembered.set('c', 4);
    expect(remembered.has('b')).toBe(false);
    expect(remembered.get('a')).toBe(3);
    expect(remembered.get('c')).toBe(4);
  });
});
