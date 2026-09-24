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

import type { RecordData } from '@ahoo-wang/wow-view-engine';
import { installRecordedWowService } from './recordedWowService.js';

/**
 * The host the event console's regression stories point at. No network
 * answers it: `installRecordedEventStreamService` does, in the page.
 */
export const RECORDED_EVENT_STREAM_HOST = 'https://event-stream.example.test';

const START = Date.parse('2026-09-18T08:00:00.000Z');

const API = 'me.ahoo.wow.compensation.api';

/**
 * The events of three executions, shaped like the streams a compensation
 * service returns for `execution_failed`, with neutral names: one that failed
 * and was retried until it succeeded, one an operator marked by hand, and one
 * that has only failed. `minutes` orders them in time.
 */
export const RECORDED_EVENT_STREAMS: RecordData[] = [
  stream('EF-1', 1, 'ExecutionFailedCreated', 0, {
    error: { errorCode: 'BadRequest', errorMsg: 'Inventory refused.' },
    recoverable: 'UNKNOWN',
  }),
  stream('EF-1', 2, 'CompensationPrepared', 3, { retryState: { retries: 1 } }),
  stream('EF-1', 3, 'ExecutionFailedApplied', 4, {
    error: { errorCode: 'BadRequest', errorMsg: 'Inventory refused.' },
  }),
  stream('EF-1', 4, 'CompensationPrepared', 7, { retryState: { retries: 2 } }),
  stream('EF-1', 5, 'ExecutionSuccessApplied', 8, {}),
  stream('EF-2', 1, 'ExecutionFailedCreated', 1, {
    error: { errorCode: 'Timeout', errorMsg: 'Payment gateway timed out.' },
    recoverable: 'UNKNOWN',
  }),
  stream('EF-2', 2, 'RecoverableMarked', 6, { recoverable: 'UNRECOVERABLE' }),
  stream('EF-3', 1, 'ExecutionFailedCreated', 5, {
    error: { errorCode: 'BadRequest', errorMsg: 'Address missing.' },
    recoverable: 'UNKNOWN',
  }),
];

/** Wow names an event by its type, in snake case. */
function eventName(type: string): string {
  return type.replace(/(?<!^)([A-Z])/g, '_$1').toLowerCase();
}

function stream(
  aggregateId: string,
  version: number,
  type: string,
  minutes: number,
  payload: Record<string, unknown>,
): RecordData {
  const id = `${aggregateId}-v${version}`;
  return {
    id,
    contextName: 'compensation-service',
    aggregateName: 'execution_failed',
    header: { upstream_name: eventName(type) },
    aggregateId,
    tenantId: '(0)',
    ownerId: '',
    spaceId: '',
    commandId: `${id}-command`,
    requestId: `${id}-command`,
    version,
    body: [
      {
        id: `${id}-event`,
        name: eventName(type),
        revision: '0.0.1',
        bodyType: `${API}.${type}`,
        body: payload,
      },
    ],
    createTime: START + minutes * 60_000,
  };
}

/**
 * Answers the event stream queries the console sends to the recorded host.
 * The stream is read-only, so there is no command to answer.
 *
 * Returns the uninstaller, as `beforeEach` expects.
 */
export function installRecordedEventStreamService(): () => void {
  return installRecordedWowService({
    host: RECORDED_EVENT_STREAM_HOST,
    resource: 'execution_failed/event',
    documents: RECORDED_EVENT_STREAMS,
  });
}
