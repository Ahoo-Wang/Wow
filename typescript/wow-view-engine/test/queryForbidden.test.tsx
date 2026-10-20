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
 * of Wow's `…forbidden` codes — is the permission state: 「无权限」, no
 * retry offered, no clock asking again, and the problem's `code` handed to
 * the host as the service gave it.
 */

import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FrameworkErrorCodes, WowProblemError } from '@ahoo-wang/wow-client';
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
import { queryFailureIssue } from '../src/runtime/failure/queryFailure.js';
import { sourceFailure } from '../src/runtime/failure/sourceReason.js';
import { formatIssue, QueryStrip, zhCN } from '../src/ui/index.js';
import { en } from '../src/ui/messages/en.js';
import {
  dashboardConfig,
  mine,
  nextTask,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testEnvironment,
  testSource,
  resourcesOf,
} from './fixtures.js';

afterEach(cleanup);

/** A caller the service refuses the query to. */
const AUTH_FORBIDDEN = FrameworkErrorCodes.AUTH_FORBIDDEN;

/** The problem a refusal answers with. */
function problemOf(code: string, status: number, detail: string) {
  return {
    type: `urn:wow:error:${code}`,
    title: 'Forbidden',
    status,
    code,
    detail,
  };
}

/** A refusal as fetcher's `ExchangeError` carries it. */
function refused(errorCode: string | null, status = 403) {
  const body =
    errorCode === null
      ? null
      : problemOf(errorCode, status, `${errorCode}: not for you.`);
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
    FrameworkErrorCodes.AGGREGATE_FORBIDDEN,
    FrameworkErrorCodes.CALL_COMMAND_FORBIDDEN,
    AUTH_FORBIDDEN,
  ])('says %s as 无权限, not as a failed query', async errorCode => {
    const found = await issueOf(refused(errorCode));

    expect(found).toEqual({
      code: 'runtime.query.forbidden',
      severity: 'error',
      path: [],
      params: { reason: `${errorCode}: not for you.`, errorCode },
    });
    expect(formatIssue(zhCN, found)).toBe(
      '无权限查看这些数据，请联系管理员开通。',
    );
    expect(formatIssue(en, found)).toBe(
      'You do not have permission to view this data. Ask an administrator for access.',
    );
  });

  it('reads a …forbidden code whatever the status it came with', async () => {
    // A stream's WowProblemError, or a proxy that rewrote the status.
    const error = new WowProblemError(
      problemOf(AUTH_FORBIDDEN, 400, 'Tenant required.'),
    );
    await expect(issueOf(error)).resolves.toMatchObject({
      code: 'runtime.query.forbidden',
      params: { errorCode: AUTH_FORBIDDEN },
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
      issueOf(refused(FrameworkErrorCodes.AGGREGATE_DELETED, 410)),
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
      resources: resourcesOf([ordersDefinition()], () => testSource({ paged })),
      store: new MemoryViewStore({
        instances: [
          { ...mine, config: recordConfig({ refresh: { interval } }) },
        ],
      }),
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
    const error = refused(AUTH_FORBIDDEN);
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
          errorCode: AUTH_FORBIDDEN,
        },
      },
    ]);
    expect(runtime.getSnapshot().query.error?.code).toBe(
      'runtime.query.forbidden',
    );
  });

  it('asks nothing again on the clock, while a press still asks', async () => {
    const { engine, events, paged, clock } = engineOver(
      refused(FrameworkErrorCodes.AGGREGATE_FORBIDDEN),
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
      screen.getByText(
        'You do not have permission to view this data. Ask an administrator for access.',
      ),
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
      Promise.reject(refused(FrameworkErrorCodes.AGGREGATE_FORBIDDEN)),
    );
    const store = new MemoryViewStore({ instances: [pending] });
    const engine = new ViewEngine({
      resources: resourcesOf([ordersDefinition(), overviewDefinition()], () =>
        testSource({ paged }),
      ),
      store,
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
