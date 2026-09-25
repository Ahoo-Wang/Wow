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

import { describe, expect, it, vi } from 'vitest';
import { QueryErrorCodes } from '@ahoo-wang/wow-client';
import {
  MemoryViewStore,
  ViewEngine,
  type DataViewConfig,
  type Issue,
  type ViewInstance,
  type ViewRuntime,
} from '../src/index.js';
import { checksDescriptorAgain } from '../src/capabilities/index.js';
import {
  nextTask,
  ordersDefinition,
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';
import {
  describedField,
  notModified,
  ordersDescriptor,
  read,
} from './fixtures/descriptor.js';

/** A Wow rejection as fetcher's `ExchangeError` carries it. */
function rejected(errorMsg: string, code?: string, name = 'status') {
  const body = {
    errorCode: 'QuerySchemaValidation',
    errorMsg,
    ...(code === undefined
      ? {}
      : { bindingErrors: [{ name, msg: errorMsg, code }] }),
  };
  return Object.assign(new Error('Request failed with status code 400'), {
    exchange: {
      response: { status: 400 },
      extractResult: () => Promise.resolve(body),
    },
  });
}

describe('which rejections check the descriptor again (capabilities.md 7)', () => {
  it('the capability codes, not the value or decoding ones', () => {
    const says = (code: string) =>
      checksDescriptorAgain({ code, reason: '', status: 400 });

    expect(says(QueryErrorCodes.UNKNOWN_FIELD)).toBe(true);
    expect(says(QueryErrorCodes.UNSUPPORTED_CAPABILITY)).toBe(true);
    expect(says(QueryErrorCodes.MODEL_SEARCH_UNSUPPORTED)).toBe(true);
    expect(says(QueryErrorCodes.METRIC_FILTER_SEARCH)).toBe(true);
    expect(says(QueryErrorCodes.VALUE_MISMATCH)).toBe(false);
    expect(says(QueryErrorCodes.NOT_SINGLE_STRING)).toBe(false);
    expect(says(QueryErrorCodes.INVALID_JSON)).toBe(false);
    expect(says(QueryErrorCodes.UNKNOWN_PROPERTY)).toBe(false);
  });

  it('a budget the guard refused without a code, and nothing else uncoded', () => {
    const says = (reason: string, status = 400) =>
      checksDescriptorAgain({ reason, status });

    expect(
      says('HTTP list query limit[2000] must be between 1 and 1000.'),
    ).toBe(true);
    expect(says('HTTP page window[12000] must not exceed 10000.')).toBe(true);
    expect(says('HTTP query filter nodes[130] must not exceed 128.')).toBe(
      true,
    );
    expect(
      says(
        'HTTP aggregation metric sorting is disabled because expensive operators are not allowed.',
      ),
    ).toBe(true);
    expect(says('HTTP counting query must not match all documents.')).toBe(
      true,
    );
    expect(says('Something else broke.')).toBe(false);
    expect(says('HTTP page window[12000] must not exceed 10000.', 500)).toBe(
      false,
    );
  });
});

const byStatus: ViewInstance = {
  id: 'by-status',
  definitionId: 'orders',
  title: 'Pending',
  scope: 'personal',
  revision: '1',
  config: recordConfig({
    filter: {
      op: 'and',
      children: [{ field: 'status', operator: 'EQ', value: 'PENDING' }],
    },
  }),
};

function harness(refusal: () => Promise<never>) {
  const describe = vi.fn();
  const paged = vi.fn(refusal);
  describe.mockResolvedValueOnce(read(ordersDescriptor()));
  const issues: Issue[] = [];
  const source = testSource({ describe, paged });
  const engine = new ViewEngine({
    definitions: [ordersDefinition()],
    store: new MemoryViewStore({ instances: [byStatus] }),
    resolveSource: () => source,
    environment: testEnvironment().environment,
    onIssue: found => issues.push(found),
  });
  return { engine, describe, issues, source };
}

async function open(engine: ViewEngine): Promise<ViewRuntime<DataViewConfig>> {
  const runtime = await engine.open('by-status');
  if (runtime.kind === 'dashboard') throw new Error('a data view');
  return runtime;
}

describe('a capability refusal (C5)', () => {
  it('checks the descriptor again at once; a new version flags what the view asked with', async () => {
    const { engine, describe, source } = harness(() =>
      Promise.reject(
        rejected(
          'Field [status] does not support [EQ].',
          QueryErrorCodes.UNSUPPORTED_CAPABILITY,
        ),
      ),
    );
    describe.mockResolvedValueOnce(
      read({
        ...ordersDescriptor(),
        version: 'sha256:orders-2',
        fields: ordersDescriptor().fields.map(field =>
          field.path === 'status'
            ? describedField('status', { filter: { operators: [] } })
            : field,
        ),
      }),
    );

    const runtime = await open(engine);
    await nextTask();
    await nextTask();

    // Asked again at once, with the version held, though it is not stale.
    expect(describe).toHaveBeenCalledTimes(2);
    expect(describe).toHaveBeenLastCalledWith('sha256:orders-1');
    // The refusal is said, at the condition it names…
    expect(runtime.getSnapshot().query.status).toBe('error');
    // … and the view now waits: the condition is unavailable (Q2).
    expect(runtime.unavailable().map(found => found.code)).toContain(
      'filter.operator.unsupported',
    );
    runtime.refresh();
    await nextTask();
    expect(source.paged).toHaveBeenCalledTimes(1);
  });

  it('says the service and its descriptor disagree when the version is the same', async () => {
    const { engine, describe, issues } = harness(() =>
      Promise.reject(
        rejected(
          'Field [status] does not support [EQ].',
          QueryErrorCodes.UNSUPPORTED_CAPABILITY,
        ),
      ),
    );
    describe.mockResolvedValue(notModified('sha256:orders-1'));

    const runtime = await open(engine);
    await nextTask();
    await nextTask();

    expect(describe).toHaveBeenCalledTimes(2);
    expect(issues).toContainEqual({
      code: 'capability.descriptor.disagrees',
      severity: 'error',
      path: [],
      params: {
        source: 'orders',
        code: 'UNSUPPORTED_CAPABILITY',
        version: 'sha256:orders-1',
      },
    });
    expect(runtime.getSnapshot().query.status).toBe('error');
  });

  it('leaves the descriptor alone for a value refusal', async () => {
    const { engine, describe, issues } = harness(() =>
      Promise.reject(
        rejected(
          'Value of [status] does not match.',
          QueryErrorCodes.VALUE_MISMATCH,
        ),
      ),
    );

    await open(engine);
    await nextTask();
    await nextTask();

    expect(describe).toHaveBeenCalledTimes(1);
    expect(issues).toEqual([]);
  });
});
