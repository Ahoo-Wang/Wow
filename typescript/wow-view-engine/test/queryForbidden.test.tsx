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
 * A source that refuses the reader rather than the query — HTTP 403, or one
 * of Wow's `IllegalAccess*` codes — is the permission state: 「无权限」, no
 * retry offered, no clock asking again, and the service's `errorCode` handed
 * to the host as it gave it.
 */

import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ErrorCodes, WowError } from '@ahoo-wang/wow-client';
import {
  MemoryViewStore,
  ViewEngine,
  type DashboardPanel,
  type DashboardViewConfig,
  type Issue,
  type RecordViewRuntime,
  type ViewErrorEvent,
  type ViewInstance,
} from '../src/index.js';
import { DashboardViewRuntime } from '../src/runtime/dashboardRuntime.js';
import { queryFailureIssue } from '../src/runtime/queryFailure.js';
import { sourceFailure } from '../src/runtime/sourceReason.js';
import { en, formatIssue, QueryStrip, zhCN } from '../src/ui/index.js';
import {
  dashboardConfig,
  mine,
  nextTask,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

/** Scope refused by the server; not in wow-client's `ErrorCodes` yet (#3485). */
const ILLEGAL_ACCESS_QUERY_SCOPE = 'IllegalAccessQueryScope';

/** A refusal as fetcher's `ExchangeError` carries it. */
function refused(errorCode: string | null, status = 403) {
  const body =
    errorCode === null
      ? null
      : { errorCode, errorMsg: `${errorCode}: not for you.` };
  return Object.assign(new Error(`Request failed with status code ${status}`), {
    exchange: {
      response: { status },
      extractResult: () => Promise.resolve(body),
    },
  });
}

async function issueOf(error: unknown): Promise<Issue> {
  return queryFailureIssue(
    await sourceFailure(error),
    ordersDefinition(),
    undefined,
  );
}

describe('a refusal of the reader is the permission state', () => {
  it.each([
    ErrorCodes.ILLEGAL_ACCESS_OWNER_AGGREGATE,
    ErrorCodes.ILLEGAL_ACCESS_SPACE_AGGREGATE,
    ILLEGAL_ACCESS_QUERY_SCOPE,
  ])('says %s as 无权限, not as a failed query', async errorCode => {
    const found = await issueOf(refused(errorCode));

    expect(found).toEqual({
      code: 'runtime.query.forbidden',
      severity: 'error',
      path: [],
      params: { reason: `${errorCode}: not for you.`, errorCode },
    });
    expect(formatIssue(zhCN, found)).toBe('无权限查看这些数据。');
    expect(formatIssue(en, found)).toBe(
      'You do not have permission to view this data.',
    );
  });

  it('reads an IllegalAccess* code whatever the status it came with', async () => {
    // A stream's WowError, or a proxy that rewrote the status.
    const error = new WowError(
      { errorCode: ILLEGAL_ACCESS_QUERY_SCOPE, errorMsg: 'Tenant required.' },
      { status: 400 },
    );
    await expect(issueOf(error)).resolves.toMatchObject({
      code: 'runtime.query.forbidden',
      params: { errorCode: ILLEGAL_ACCESS_QUERY_SCOPE },
    });
  });

  it('reads any HTTP 403, body or none', async () => {
    await expect(issueOf(refused(null))).resolves.toEqual({
      code: 'runtime.query.forbidden',
      severity: 'error',
      path: [],
      params: { reason: 'HTTP 403' },
    });
    await expect(issueOf(refused('SomeGatewayCode'))).resolves.toMatchObject({
      code: 'runtime.query.forbidden',
    });
  });

  it('leaves a deleted aggregate and other statuses to the plain failure', async () => {
    await expect(
      issueOf(refused(ErrorCodes.ILLEGAL_ACCESS_DELETED_AGGREGATE, 410)),
    ).resolves.toMatchObject({ code: 'runtime.query.failed' });
    await expect(issueOf(refused(null, 401))).resolves.toMatchObject({
      code: 'runtime.query.failed',
      params: { reason: 'HTTP 401' },
    });
  });
});

describe('the permission state in a view', () => {
  function engineOver(
    error: unknown,
    clock = testEnvironment(),
    interval: number | null = null,
  ) {
    const events: ViewErrorEvent[] = [];
    const paged = vi.fn(() => Promise.reject(error));
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({
        instances: [
          { ...mine, config: recordConfig({ refresh: { interval } }) },
        ],
      }),
      resolveSource: () => testSource({ paged }),
      environment: {
        ...clock.environment,
        onError: event => events.push(event),
      },
    });
    return { engine, events, paged, clock };
  }

  async function failed(engine: ViewEngine): Promise<RecordViewRuntime> {
    const runtime = (await engine.open('orders-1')) as RecordViewRuntime;
    await vi.waitFor(() =>
      expect(runtime.getSnapshot().query.status).toBe('error'),
    );
    return runtime;
  }

  it('hands the host the error and its code as the service gave them', async () => {
    const error = refused(ILLEGAL_ACCESS_QUERY_SCOPE);
    const { engine, events } = engineOver(error);

    const runtime = await failed(engine);

    expect(events).toEqual([
      {
        kind: 'query',
        error,
        context: {
          operation: 'query',
          definitionId: 'orders',
          instanceId: 'orders-1',
          runtimeId: runtime.id,
          errorCode: ILLEGAL_ACCESS_QUERY_SCOPE,
        },
      },
    ]);
    expect(runtime.getSnapshot().query.error?.code).toBe(
      'runtime.query.forbidden',
    );
  });

  it('asks nothing again on the clock, while a press still asks', async () => {
    const { engine, events, paged, clock } = engineOver(
      refused(ErrorCodes.ILLEGAL_ACCESS_OWNER_AGGREGATE),
      testEnvironment(),
      60,
    );
    const runtime = await failed(engine);
    const asked = paged.mock.calls.length;

    clock.advance(10 * 60_000);
    await nextTask();
    expect(paged.mock.calls.length).toBe(asked);
    expect(events).toHaveLength(1);

    runtime.refresh();
    await vi.waitFor(() => expect(paged.mock.calls.length).toBe(asked + 1));
  });

  it('keeps the clock for a failure a retry may mend', async () => {
    const { engine, paged, clock } = engineOver(
      new Error('gateway down'),
      testEnvironment(),
      60,
    );
    await failed(engine);
    const asked = paged.mock.calls.length;

    clock.advance(60_000);
    await vi.waitFor(() => expect(paged.mock.calls.length).toBe(asked + 1));
  });

  it('offers no retry, in the warning tone', () => {
    const onRetry = vi.fn();
    const forbidden: Issue = {
      code: 'runtime.query.forbidden',
      severity: 'error',
      path: [],
      params: { reason: 'x' },
    };
    const { rerender } = render(
      <QueryStrip error={forbidden} stale={false} onRetry={onRetry} />,
    );
    expect(
      screen.getByText('You do not have permission to view this data.'),
    ).toBeDefined();
    expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();

    // A plain failure keeps its retry.
    rerender(
      <QueryStrip
        error={{ ...forbidden, code: 'runtime.query.failed' }}
        stale={false}
        onRetry={onRetry}
      />,
    );
    expect(screen.getByRole('button', { name: 'Try again' })).toBeDefined();
  });
});

describe('the permission state on a board', () => {
  const pending: ViewInstance = {
    id: 'pending',
    definitionId: 'orders',
    title: 'Pending orders',
    scope: 'shared',
    revision: 'r1',
    config: recordConfig(),
  };
  const panel = {
    id: 'orders',
    kind: 'view',
    instanceId: 'pending',
    bindings: [],
    layout: { x: 0, y: 0, w: 12, h: 4 },
  } as DashboardPanel;

  it('leaves a refused panel off the board’s clock, and a press asks it', async () => {
    const clock = testEnvironment();
    const paged = vi.fn(() =>
      Promise.reject(refused(ErrorCodes.ILLEGAL_ACCESS_SPACE_AGGREGATE)),
    );
    const store = new MemoryViewStore({ instances: [pending] });
    const engine = new ViewEngine({
      definitions: [ordersDefinition(), overviewDefinition()],
      store,
      resolveSource: () => testSource({ paged }),
      environment: clock.environment,
    });
    const config: DashboardViewConfig = dashboardConfig({
      panels: [panel],
      refresh: { interval: 60 },
    });
    const instance = await store.create(
      {
        definitionId: 'overview',
        title: 'Overview',
        scope: 'personal',
        config,
      },
      { requestId: 'r' },
    );
    const board = await engine.open(instance.id);
    if (!(board instanceof DashboardViewRuntime))
      throw new Error('expected a dashboard');
    await vi.waitFor(() =>
      expect(
        board.panelRuntime('orders')?.getSnapshot().query.error?.code,
      ).toBe('runtime.query.forbidden'),
    );
    const asked = paged.mock.calls.length;

    clock.advance(10 * 60_000);
    await nextTask();
    expect(paged.mock.calls.length).toBe(asked);

    board.refresh();
    await vi.waitFor(() => expect(paged.mock.calls.length).toBe(asked + 1));
  });
});
