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
import { CommandHeaders } from '../../src';

describe('CommandHttpHeaders', () => {
  // Table-driven: one entry per constant, replacing 20 near-identical
  // it('should have correct X header') blocks.
  const expectedHeaders: Record<string, string> = {
    COMMAND_HEADERS_PREFIX: 'Command-',
    TENANT_ID: 'Command-Tenant-Id',
    OWNER_ID: 'Command-Owner-Id',
    AGGREGATE_ID: 'Command-Aggregate-Id',
    AGGREGATE_VERSION: 'Command-Aggregate-Version',
    WAIT_PREFIX: 'Command-Wait-',
    WAIT_TIME_OUT: 'Command-Wait-Timeout',
    WAIT_STAGE: 'Command-Wait-Stage',
    WAIT_CONTEXT: 'Command-Wait-Context',
    WAIT_PROCESSOR: 'Command-Wait-Processor',
    WAIT_FUNCTION: 'Command-Wait-Function',
    WAIT_TAIL_PREFIX: 'Command-Wait-Tail-',
    WAIT_TAIL_STAGE: 'Command-Wait-Tail-Stage',
    WAIT_TAIL_CONTEXT: 'Command-Wait-Tail-Context',
    WAIT_TAIL_PROCESSOR: 'Command-Wait-Tail-Processor',
    WAIT_TAIL_FUNCTION: 'Command-Wait-Tail-Function',
    REQUEST_ID: 'Command-Request-Id',
    LOCAL_FIRST: 'Command-Local-First',
    COMMAND_AGGREGATE_CONTEXT: 'Command-Aggregate-Context',
    COMMAND_AGGREGATE_NAME: 'Command-Aggregate-Name',
    COMMAND_TYPE: 'Command-Type',
    COMMAND_HEADER_X_PREFIX: 'Command-Header-',
  };

  it('should expose all expected header constants with correct values', () => {
    for (const [key, expected] of Object.entries(expectedHeaders)) {
      expect(
        (CommandHeaders as Record<string, string>)[key],
        `CommandHeaders.${key}`,
      ).toBe(expected);
    }
  });
});
