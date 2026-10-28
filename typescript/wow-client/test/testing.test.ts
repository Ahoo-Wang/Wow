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

import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  CommandClient,
  FrameworkErrorCodes,
  readProblem,
  StateClient,
  WowHeaders,
} from '../src/index.js';
import {
  commandResultOf,
  DevelopmentCallerHeaders,
  developmentCaller,
  developmentCallerHeaders,
  problemOf,
  problemResponse,
  stateViewOf,
} from '../src/testing.js';
import { jsonResponse, stubFetch, testFetcher } from './fixtures/fetchStub.js';
import { TEST_MANIFEST } from './fixtures/manifest';

const PRODUCT = { context: 'eshop-catalog', name: 'product' };
const MUG = { type: PRODUCT, tenantId: 'acme', id: 'mug' };

afterEach(() => vi.unstubAllGlobals());

describe('the development caller', () => {
  it('names the headers the host development callers read', () => {
    // DevelopmentCallerResolver.kt, host/spring-boot.
    expect(DevelopmentCallerHeaders).toEqual({
      USER: 'Wow-User',
      OWNER: 'Wow-Owner',
      TENANT: 'Wow-Tenant',
      ROLES: 'Wow-Roles',
      APP: 'Wow-App',
    });
    expect(Object.values(DevelopmentCallerHeaders)).not.toContain(
      WowHeaders.SCOPE,
    );
  });

  it('sends only what the caller sets, the roles comma-separated', () => {
    expect(developmentCallerHeaders({ user: 'alice' })).toEqual({
      'Wow-User': 'alice',
    });
    expect(
      developmentCallerHeaders({
        user: 'alice',
        owner: 'alice',
        tenant: 'acme',
        roles: ['viewer', 'editor'],
        app: 'console',
      }),
    ).toEqual({
      'Wow-User': 'alice',
      'Wow-Owner': 'alice',
      'Wow-Tenant': 'acme',
      'Wow-Roles': 'viewer,editor',
      'Wow-App': 'console',
    });
    expect(developmentCallerHeaders({ user: 'bob', roles: [] })).toEqual({
      'Wow-User': 'bob',
    });
  });

  it('asks as its caller, or as whoever the function answers at each request', async () => {
    const { requests } = stubFetch(() =>
      jsonResponse(stateViewOf(MUG, { stock: 1 })),
    );
    const fixed = testFetcher();
    fixed.interceptors.request.use(
      developmentCaller({ user: 'alice', tenant: 'acme' }),
    );
    await new StateClient(PRODUCT, { fetcher: fixed }).get('mug');
    expect(requests[0]!.headers.get('Wow-User')).toBe('alice');
    expect(requests[0]!.headers.get('Wow-Tenant')).toBe('acme');

    let current: { user: string } | null = null;
    const switching = testFetcher();
    switching.interceptors.request.use(developmentCaller(() => current));
    const state = new StateClient(PRODUCT, { fetcher: switching });
    await state.get('mug');
    expect(requests[1]!.headers.has('Wow-User')).toBe(false);
    current = { user: 'bob' };
    await state.get('mug');
    expect(requests[2]!.headers.get('Wow-User')).toBe('bob');
  });
});

describe('the wire builders', () => {
  it('build a committed result, or a refused one with its code', () => {
    expect(commandResultOf(MUG)).toMatchObject({
      format: 1,
      ref: MUG,
      hop: { stage: 'committed', context: 'eshop-catalog' },
      function: { kind: 'command', context: 'eshop-catalog' },
      status: 'succeeded',
      version: 1,
      error: null,
    });
    expect(commandResultOf(MUG, { status: 'rejected' }).error?.code).toBe(
      FrameworkErrorCodes.COMMAND_REFUSED,
    );
    expect(commandResultOf(MUG, { status: 'failed' }).error?.code).toBe(
      FrameworkErrorCodes.COMMAND_FAILED,
    );
    const own = { code: 'eshop-catalog.product.blank-name', message: 'blank' };
    expect(
      commandResultOf(MUG, { status: 'rejected', error: own, version: 3 }),
    ).toMatchObject({ error: own, version: 3 });
  });

  it('build a problem as the service writes it, which the client reads back', async () => {
    expect(problemOf(404)).toEqual({
      type: 'about:blank',
      title: 'Not Found',
      status: 404,
    });
    const refused = problemOf(409, FrameworkErrorCodes.COMMAND_REFUSED, {
      detail: 'out of stock',
      result: commandResultOf(MUG, { status: 'rejected' }),
      extensions: { available: 0 },
    });
    expect(refused).toMatchObject({
      type: 'urn:wow:error:wow.command.refused',
      title: 'Conflict',
      code: 'wow.command.refused',
    });
    // On the wire, the extension members are members of their own.
    const response = problemResponse(refused);
    expect(response.headers.get('Content-Type')).toBe(
      'application/problem+json',
    );
    expect(await response.clone().json()).toMatchObject({ available: 0 });
    expect(await readProblem(response)).toMatchObject(refused);
  });

  it('serve a fake whose command answers reject as the service would', async () => {
    stubFetch(() =>
      problemResponse(
        problemOf(409, FrameworkErrorCodes.COMMAND_REFUSED, {
          result: commandResultOf(MUG, { status: 'rejected' }),
        }),
      ),
    );
    const commands = new CommandClient(
      { fetcher: testFetcher() },
      { manifest: TEST_MANIFEST },
    );
    await expect(
      commands.send({
        command: 'eshop-catalog.product.ChangePrice',
        id: 'mug',
        body: { price: 1 },
      }),
    ).rejects.toMatchObject({
      name: 'CommandRejectedError',
      problem: { status: 409, code: FrameworkErrorCodes.COMMAND_REFUSED },
      result: { status: 'rejected', ref: MUG },
    });
  });

  it('build a state view at version 1 unless told otherwise', () => {
    expect(stateViewOf(MUG, { stock: 7 })).toEqual({
      ref: MUG,
      version: 1,
      scope: {},
      deleted: false,
      tags: {},
      state: { stock: 7 },
    });
    expect(
      stateViewOf(MUG, { stock: 7 }, { version: 4, scope: { ownerId: 'a' } }),
    ).toMatchObject({ version: 4, scope: { ownerId: 'a' } });
  });
});
