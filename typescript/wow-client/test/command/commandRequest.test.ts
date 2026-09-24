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

import { describe, expect, expectTypeOf, it } from 'vitest';
import {
  CommandHeaders,
  commandHeaders,
  type CommandRequestHeaders,
  CommandStage,
  waitStrategy,
} from '../../src';

describe('CommandRequestHeaders', () => {
  it('types each command header by what the server parses', () => {
    const headers: CommandRequestHeaders = {
      [CommandHeaders.WAIT_STAGE]: CommandStage.SNAPSHOT,
      [CommandHeaders.WAIT_TAIL_STAGE]: 'PROCESSED',
      [CommandHeaders.WAIT_TIME_OUT]: '30000',
      [CommandHeaders.AGGREGATE_VERSION]: '3',
      [CommandHeaders.LOCAL_FIRST]: 'false',
      Authorization: 'Bearer token',
      'Command-Header-Trace': 'abc',
    };
    void headers;

    // @ts-expect-error A stage the server does not know.
    const typo: CommandRequestHeaders = { 'Command-Wait-Stage': 'PROCESSSED' };
    // @ts-expect-error The server reads a timeout in whole milliseconds.
    const seconds: CommandRequestHeaders = { 'Command-Wait-Timeout': '30s' };
    // @ts-expect-error Local-First is a boolean.
    const yes: CommandRequestHeaders = { 'Command-Local-First': 'yes' };
    void [typo, seconds, yes];

    expectTypeOf(
      CommandHeaders.WAIT_STAGE,
    ).toEqualTypeOf<'Command-Wait-Stage'>();
    expect(Object.isFrozen(CommandHeaders)).toBe(true);
  });
});

describe('commandHeaders', () => {
  it('sends a header for each option given, and none for the rest', () => {
    expect(
      commandHeaders({
        tenantId: 't-1',
        ownerId: 'u-1',
        spaceId: 's-1',
        aggregateId: 'a-1',
        aggregateVersion: 0,
        requestId: 'r-1',
        localFirst: false,
      }),
    ).toEqual({
      'Command-Tenant-Id': 't-1',
      'Command-Owner-Id': 'u-1',
      'Wow-Space-Id': 's-1',
      'Command-Aggregate-Id': 'a-1',
      'Command-Aggregate-Version': '0',
      'Command-Request-Id': 'r-1',
      'Command-Local-First': 'false',
    });
    expect(commandHeaders({})).toEqual({});
    expect(commandHeaders({ localFirst: true })).toEqual({
      'Command-Local-First': 'true',
    });
  });

  it('refuses a version that is not a non-negative integer', () => {
    for (const aggregateVersion of [-1, 1.5, Number.NaN, 2 ** 53])
      expect(() => commandHeaders({ aggregateVersion })).toThrowError(
        TypeError,
      );
  });
});

describe('waitStrategy', () => {
  it('waits for one stage', () => {
    expect(
      waitStrategy({
        stage: CommandStage.PROJECTED,
        context: 'example',
        processor: 'CartProjector',
        function: 'onItemAdded',
        timeoutMs: 5_000,
      }),
    ).toEqual({
      'Command-Wait-Stage': 'PROJECTED',
      'Command-Wait-Context': 'example',
      'Command-Wait-Processor': 'CartProjector',
      'Command-Wait-Function': 'onItemAdded',
      'Command-Wait-Timeout': '5000',
    });
    expect(waitStrategy({})).toEqual({});
    expect(waitStrategy({ stage: 'SENT' })).toEqual({
      'Command-Wait-Stage': 'SENT',
    });
  });

  it('waits for a saga and then the command it sends', () => {
    expect(
      waitStrategy({
        stage: 'SAGA_HANDLED',
        processor: 'TransferSaga',
        tail: { stage: CommandStage.PROCESSED, context: 'account' },
      }),
    ).toEqual({
      'Command-Wait-Stage': 'SAGA_HANDLED',
      'Command-Wait-Processor': 'TransferSaga',
      'Command-Wait-Tail-Stage': 'PROCESSED',
      'Command-Wait-Tail-Context': 'account',
    });
  });

  it('refuses what the server would ignore or reject', () => {
    expect(() =>
      waitStrategy({
        stage: 'PROCESSED',
        // @ts-expect-error Only SAGA_HANDLED has a tail.
        tail: { stage: 'PROCESSED' },
      }),
    ).toThrowError('A wait tail needs the SAGA_HANDLED stage.');
    expect(() => waitStrategy({ stage: 'PROCESSSED' as never })).toThrowError(
      /stage must be one of SENT, PROCESSED/,
    );
    expect(() =>
      waitStrategy({
        stage: 'SAGA_HANDLED',
        tail: { stage: 'DONE' as never },
      }),
    ).toThrowError(/tail\.stage must be one of/);
    for (const timeoutMs of [0, -1, 1.5, Number.POSITIVE_INFINITY])
      expect(() => waitStrategy({ timeoutMs })).toThrowError(TypeError);
  });
});
