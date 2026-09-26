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

import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  ErrorCodes,
  type ErrorInfo,
  isErrorInfo,
  RecoverableType,
  toWowError,
  WowError,
  WowHeaders,
} from '../../src';

const KOTLIN = '../../../../';

/** The string `const val`s of a Kotlin file, `${NAME}` references resolved. */
function kotlinConstants(relativePath: string): Record<string, string> {
  const source = readFileSync(
    new URL(KOTLIN + relativePath, import.meta.url),
    'utf8',
  );
  const constants: Record<string, string> = {};
  for (const [, name, value] of source.matchAll(
    /const val (\w+) = (?:"([^"]*)"|ErrorInfo\.(\w+))/g,
  ))
    constants[name] = value ?? '';
  return constants;
}

describe('ErrorCodes', () => {
  it('mirrors the codes of the Kotlin ErrorCodes', () => {
    const kotlin = kotlinConstants(
      'wow-core/src/main/kotlin/me/ahoo/wow/exception/ErrorCodes.kt',
    );
    const codes = Object.fromEntries(
      Object.entries(kotlin).filter(([name]) => !name.endsWith('_MESSAGE')),
    );
    // SUCCEEDED is ErrorInfo.SUCCEEDED on the Kotlin side.
    codes.SUCCEEDED = 'Ok';
    expect(ErrorCodes).toMatchObject(codes);
  });

  it('mirrors the query schema and batch codes the server registers', () => {
    // Each exception class of the file declares its own ERROR_CODE.
    const schema = [
      ...readFileSync(
        new URL(
          KOTLIN +
            'wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaExceptions.kt',
          import.meta.url,
        ),
        'utf8',
      ).matchAll(/const val ERROR_CODE = "([^"]+)"/g),
    ].map(([, code]) => code);
    expect(schema).toHaveLength(3);
    expect(
      [
        ErrorCodes.QUERY_SCHEMA_VALIDATION,
        ErrorCodes.QUERY_SCHEMA_CONFLICT,
        ErrorCodes.QUERY_SCHEMA_UNAVAILABLE,
      ].sort(),
    ).toEqual(schema.sort());
    expect(
      kotlinConstants(
        'wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/exception/BatchTaskException.kt',
      ).ERROR_CODE,
    ).toBe(ErrorCodes.BATCH_TASK_ERROR);
  });
});

describe('RecoverableType', () => {
  it('uses the Wow wire values', () => {
    expect(Object.values(RecoverableType)).toEqual([
      'RECOVERABLE',
      'UNKNOWN',
      'UNRECOVERABLE',
    ]);
  });
});

describe('isErrorInfo', () => {
  it('accepts the server error body', () => {
    expect(isErrorInfo({ errorCode: 'NotFound', errorMsg: 'gone' })).toBe(true);
    expect(isErrorInfo({ errorCode: 'NotFound' })).toBe(true);
  });

  it('refuses what is not one', () => {
    for (const value of [
      null,
      undefined,
      'NotFound',
      {},
      { errorCode: 404 },
      { errorCode: 'NotFound', errorMsg: 1 },
    ])
      expect(isErrorInfo(value), JSON.stringify(value)).toBe(false);
  });
});

describe('WowError', () => {
  const info: ErrorInfo = {
    errorCode: ErrorCodes.COMMAND_VALIDATION,
    errorMsg: 'quantity must be positive',
    bindingErrors: [{ name: 'quantity', msg: 'must be positive' }],
  };

  it('carries the ErrorInfo, the status and the cause', () => {
    const cause = new Error('fetcher');
    const error = new WowError(info, { status: 400, cause });
    expect(error).toBeInstanceOf(Error);
    expect(error).toBeInstanceOf(WowError);
    expect(error).toMatchObject({
      name: 'WowError',
      errorCode: 'CommandValidation',
      errorMsg: 'quantity must be positive',
      bindingErrors: info.bindingErrors,
      status: 400,
      cause,
      message: '[CommandValidation] quantity must be positive',
    });
  });

  it('defaults what the body left out', () => {
    const error = new WowError({ errorCode: 'NotFound' } as ErrorInfo);
    expect(error.message).toBe('NotFound');
    expect(error.errorMsg).toBe('');
    expect(error.bindingErrors).toEqual([]);
    expect(error.status).toBeUndefined();
    expect(error.cause).toBeUndefined();
  });

  // Wow 8.11 to 9.1.3 refuse a list query without a limit; 9.1.5 applies
  // its default list size. The client sends no default (the 9.1.5 contract),
  // so the message says what to do.
  it.each([
    'HTTP list query limit[0] must be between 1 and 1000.',
    'HTTP list query limit[0] must be between 1 and 50.',
  ])(
    'says to pass a limit when the server refuses its absence: %s',
    errorMsg => {
      const error = new WowError(
        { errorCode: ErrorCodes.ILLEGAL_ARGUMENT, errorMsg },
        { status: 400 },
      );
      expect(error.errorMsg).toBe(errorMsg);
      expect(error.message).toBe(
        `[IllegalArgument] ${errorMsg} The query has no limit: omitting it ` +
          'needs Wow 9.1.5 or later, so pass one, for example ' +
          'listQuery({ limit: 100 }).',
      );
    },
  );

  it('says to pass a limit when the refusal names SIZE_OUT_OF_RANGE', () => {
    const errorMsg = 'HTTP list query limit[0] must be between 1 and 1000.';
    const coded = (errorCode: string) =>
      new WowError({
        errorCode,
        errorMsg,
        bindingErrors: [
          { name: 'limit', msg: errorMsg, code: 'SIZE_OUT_OF_RANGE' },
        ],
      }).message;
    expect(coded(ErrorCodes.ILLEGAL_ARGUMENT)).toContain(
      'The query has no limit',
    );
    // The code alone is enough, whatever the errorCode.
    expect(coded(ErrorCodes.BAD_REQUEST)).toContain('The query has no limit');
    // A limit out of range is not a missing one.
    expect(
      new WowError({
        errorCode: ErrorCodes.ILLEGAL_ARGUMENT,
        errorMsg: 'HTTP list query limit[5000] must be between 1 and 1000.',
        bindingErrors: [{ name: 'limit', msg: 'x', code: 'SIZE_OUT_OF_RANGE' }],
      }).message,
    ).not.toContain('The query has no limit');
  });

  it.each([
    [
      ErrorCodes.ILLEGAL_ARGUMENT,
      'HTTP list query limit[5000] must be between 1 and 1000.',
    ],
    [
      ErrorCodes.ILLEGAL_ARGUMENT,
      'HTTP aggregation query limit[0] must be between 1 and 1000.',
    ],
    [
      ErrorCodes.BAD_REQUEST,
      'HTTP list query limit[0] must be between 1 and 1000.',
    ],
  ])('adds no hint to other errors: %s %s', (errorCode, errorMsg) => {
    expect(new WowError({ errorCode, errorMsg }).message).toBe(
      `[${errorCode}] ${errorMsg}`,
    );
  });
});

/** What a fetcher `ExchangeError` exposes, around a real response. */
function exchangeError(response: Response): Error {
  return Object.assign(new Error('Request failed'), { exchange: { response } });
}

describe('toWowError', () => {
  it('reads the ErrorInfo body of a failed response', async () => {
    const response = new Response(
      JSON.stringify({ errorCode: 'NotFound', errorMsg: 'no such cart' }),
      {
        status: 404,
        headers: {
          'Content-Type': 'application/json',
          [WowHeaders.ERROR_CODE]: 'NotFound',
        },
      },
    );
    const thrown = exchangeError(response);
    const error = await toWowError(thrown);
    expect(error).toBeInstanceOf(WowError);
    expect(error).toMatchObject({
      errorCode: ErrorCodes.NOT_FOUND,
      errorMsg: 'no such cart',
      status: 404,
      cause: thrown,
    });
    // The body is read from a clone, so the response is still readable.
    await expect(response.json()).resolves.toMatchObject({
      errorCode: 'NotFound',
    });
  });

  it('falls back to the Wow-Error-Code header when the body is not one', async () => {
    const response = new Response('<html>Bad gateway</html>', {
      status: 409,
      statusText: 'Conflict',
      headers: { [WowHeaders.ERROR_CODE]: 'EventVersionConflict' },
    });
    await expect(toWowError(exchangeError(response))).resolves.toMatchObject({
      errorCode: ErrorCodes.EVENT_VERSION_CONFLICT,
      errorMsg: 'Conflict',
      status: 409,
    });
  });

  it('answers undefined for a failure the Wow server did not answer', async () => {
    const proxy = new Response('<html>Bad gateway</html>', { status: 502 });
    const used = new Response('{}', { status: 500 });
    await used.text();
    for (const error of [
      exchangeError(proxy),
      exchangeError(used),
      exchangeError(new Response('{}', { status: 200 })),
      new TypeError('Failed to fetch'),
      new DOMException('aborted', 'AbortError'),
      'NotFound',
      null,
    ])
      await expect(toWowError(error)).resolves.toBeUndefined();
  });

  it('hands back a WowError it is given, or one that is the cause', async () => {
    const error = new WowError({ errorCode: 'NotFound', errorMsg: '' });
    await expect(toWowError(error)).resolves.toBe(error);
    await expect(
      toWowError(Object.assign(new Error('wrapped'), { cause: error })),
    ).resolves.toBe(error);
  });
});
