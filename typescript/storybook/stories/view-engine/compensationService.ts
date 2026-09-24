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

import type { RecordData } from '@ahoo-wang/fetcher-view-engine';
import { installRecordedWowService } from './recordedWowService.js';

/**
 * The host the console's regression stories point at. No network answers it:
 * `installRecordedCompensationService` does, in the page.
 */
export const RECORDED_COMPENSATION_HOST = 'https://compensation.example.test';

const START = Date.parse('2026-09-18T08:00:00.000Z');

/**
 * Failed executions shaped like the snapshots a compensation service returns
 * for `execution_failed`, with neutral names. `minutes` orders them in time,
 * so the default view's newest-first sort has something to decide.
 */
// Three processors, so a condition on one is picked from the values the
// service counts rather than typed: two failed in `OrderSaga`, one each in
// the other two.
export const RECORDED_EXECUTIONS: RecordData[] = [
  execution('EF-1', 'FAILED', 'UNKNOWN', 2, 1, 'OrderSaga'),
  execution('EF-2', 'PREPARED', 'RECOVERABLE', 1, 5, 'PaymentSaga', {
    errorCode: 'Timeout',
    errorMsg: 'Payment gateway timed out.',
  }),
  execution('EF-3', 'SUCCEEDED', 'RECOVERABLE', 1, 3, 'OrderSaga'),
  execution('EF-4', 'FAILED', 'UNRECOVERABLE', 3, 2, 'InventorySaga'),
  execution('EF-5', 'FAILED', 'UNKNOWN', 4, 4, 'OrderSaga'),
];

function execution(
  id: string,
  status: string,
  recoverable: string,
  retries: number,
  minutes: number,
  processorName: string,
  // What went wrong: an inventory refusal unless the execution says else.
  error = { errorCode: 'BadRequest', errorMsg: 'Inventory refused.' },
): RecordData {
  const eventTime = START + minutes * 60_000;
  return {
    aggregateId: id,
    firstEventTime: START,
    eventTime,
    state: {
      id,
      status,
      recoverable,
      isRetryable: status !== 'SUCCEEDED',
      isBelowRetryThreshold: retries < 3,
      function: {
        contextName: 'order-service',
        processorName,
        name: 'onOrderCreated',
        functionKind: 'EVENT',
      },
      eventId: {
        id: `${id}-event`,
        version: 1,
        aggregateId: {
          contextName: 'order-service',
          aggregateName: 'order',
          aggregateId: `order-${id}`,
        },
      },
      error,
      retrySpec: { maxRetries: 3, minBackoff: 180, executionTimeout: 120 },
      retryState: {
        retries,
        retryAt: eventTime,
        nextRetryAt: eventTime + 180_000,
        timeoutAt: eventTime + 120_000,
      },
    },
  };
}

/**
 * Answers what the console sends to the recorded host — the snapshot
 * queries, and the three compensation commands, which change the recorded
 * executions the way the service does. Each install starts from the recorded
 * executions afresh.
 *
 * Returns the uninstaller, as `beforeEach` expects.
 */
export function installRecordedCompensationService(): () => void {
  return installRecordedWowService({
    host: RECORDED_COMPENSATION_HOST,
    resource: 'execution_failed/snapshot',
    documents: RECORDED_EXECUTIONS,
    command: answerCommand,
  });
}

/**
 * The compensation commands on one recorded execution, with the service's
 * rules: a retry within the spec needs a retryable execution below its
 * limit, a forced one only a retryable execution; either prepares it. A
 * refusal is a command result that says why, as the service answers.
 */
function answerCommand(
  rows: RecordData[],
  path: string,
  body: { recoverable?: unknown },
): object | undefined {
  const [, aggregate, id, command] = path.split('/');
  if (aggregate !== 'execution_failed') return undefined;
  const state = rows.find(row => row.aggregateId === id)?.state as
    Record<string, unknown> | undefined;
  if (!state) return refused('NotFound', `No execution ${id}.`);
  switch (command) {
    case 'prepare_compensation':
      if (state.isRetryable !== true || state.isBelowRetryThreshold !== true)
        return refused('IllegalState', 'Retry threshold reached.');
      state.status = 'PREPARED';
      return SUCCEEDED;
    case 'force_prepare_compensation':
      if (state.isRetryable !== true)
        return refused('IllegalState', 'Not retryable.');
      state.status = 'PREPARED';
      return SUCCEEDED;
    case 'mark_recoverable':
      state.recoverable = body.recoverable;
      return SUCCEEDED;
  }
  return undefined;
}

const SUCCEEDED = { errorCode: 'Ok', errorMsg: '' };

function refused(errorCode: string, errorMsg: string) {
  return { errorCode, errorMsg };
}
