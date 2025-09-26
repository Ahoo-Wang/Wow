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
  it('should have correct prefix', () => {
    expect(CommandHeaders.COMMAND_HEADERS_PREFIX).toBe('Command-');
  });

  it('should have correct tenant id header', () => {
    expect(CommandHeaders.TENANT_ID).toBe('Command-Tenant-Id');
  });

  it('should have correct owner id header', () => {
    expect(CommandHeaders.OWNER_ID).toBe('Command-Owner-Id');
  });

  it('should have correct aggregate id header', () => {
    expect(CommandHeaders.AGGREGATE_ID).toBe('Command-Aggregate-Id');
  });

  it('should have correct aggregate version header', () => {
    expect(CommandHeaders.AGGREGATE_VERSION).toBe('Command-Aggregate-Version');
  });

  it('should have correct wait prefix', () => {
    expect(CommandHeaders.WAIT_PREFIX).toBe('Command-Wait-');
  });

  it('should have correct wait timeout header', () => {
    expect(CommandHeaders.WAIT_TIME_OUT).toBe('Command-Wait-Timeout');
  });

  it('should have correct wait stage header', () => {
    expect(CommandHeaders.WAIT_STAGE).toBe('Command-Wait-Stage');
  });

  it('should have correct wait context header', () => {
    expect(CommandHeaders.WAIT_CONTEXT).toBe('Command-Wait-Context');
  });

  it('should have correct wait processor header', () => {
    expect(CommandHeaders.WAIT_PROCESSOR).toBe('Command-Wait-Processor');
  });

  it('should have correct wait function header', () => {
    expect(CommandHeaders.WAIT_FUNCTION).toBe('Command-Wait-Function');
  });

  it('should have correct wait tail prefix', () => {
    expect(CommandHeaders.WAIT_TAIL_PREFIX).toBe('Command-Wait-Tail-');
  });

  it('should have correct wait tail stage header', () => {
    expect(CommandHeaders.WAIT_TAIL_STAGE).toBe('Command-Wait-Tail-Stage');
  });

  it('should have correct wait tail context header', () => {
    expect(CommandHeaders.WAIT_TAIL_CONTEXT).toBe('Command-Wait-Tail-Context');
  });

  it('should have correct wait tail processor header', () => {
    expect(CommandHeaders.WAIT_TAIL_PROCESSOR).toBe(
      'Command-Wait-Tail-Processor',
    );
  });

  it('should have correct wait tail function header', () => {
    expect(CommandHeaders.WAIT_TAIL_FUNCTION).toBe(
      'Command-Wait-Tail-Function',
    );
  });

  it('should have correct request id header', () => {
    expect(CommandHeaders.REQUEST_ID).toBe('Command-Request-Id');
  });

  it('should have correct local first header', () => {
    expect(CommandHeaders.LOCAL_FIRST).toBe('Command-Local-First');
  });

  it('should have correct command aggregate context header', () => {
    expect(CommandHeaders.COMMAND_AGGREGATE_CONTEXT).toBe(
      'Command-Aggregate-Context',
    );
  });

  it('should have correct command aggregate name header', () => {
    expect(CommandHeaders.COMMAND_AGGREGATE_NAME).toBe(
      'Command-Aggregate-Name',
    );
  });

  it('should have correct command type header', () => {
    expect(CommandHeaders.COMMAND_TYPE).toBe('Command-Type');
  });

  it('should have correct command header x prefix', () => {
    expect(CommandHeaders.COMMAND_HEADER_X_PREFIX).toBe('Command-Header-');
  });
});
