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
import { describe, expect, expectTypeOf, it } from 'vitest';
import {
  type BindingError,
  ErrorCodes,
  type KnownQueryErrorCode,
  type QueryErrorCode,
  QueryErrorCodes,
  toWowError,
  WowError,
} from '../../src';

/** The string `const val`s of the Kotlin QueryErrorCodes, in source order. */
function kotlinQueryErrorCodes(): Record<string, string> {
  const source = readFileSync(
    new URL(
      '../../../../wow-api/src/main/kotlin/me/ahoo/wow/api/query/QueryErrorCodes.kt',
      import.meta.url,
    ),
    'utf8',
  );
  return Object.fromEntries(
    [...source.matchAll(/const val (\w+) = "([^"]*)"/g)].map(
      ([, name, value]) => [name, value],
    ),
  );
}

describe('QueryErrorCodes', () => {
  it('mirrors the Kotlin QueryErrorCodes, name for name and in order', () => {
    const kotlin = kotlinQueryErrorCodes();
    expect(Object.keys(kotlin)).toHaveLength(29);
    expect(Object.entries(QueryErrorCodes)).toEqual(Object.entries(kotlin));
  });

  it('is frozen, its codes are literal types, and the union is open', () => {
    expect(Object.isFrozen(QueryErrorCodes)).toBe(true);
    expectTypeOf(
      QueryErrorCodes.UNKNOWN_FIELD,
    ).toEqualTypeOf<'UNKNOWN_FIELD'>();
    expectTypeOf<'UNKNOWN_FIELD'>().toMatchTypeOf<KnownQueryErrorCode>();
    // A code a newer server added is still a QueryErrorCode.
    expectTypeOf<'SOME_FUTURE_CODE'>().toMatchTypeOf<QueryErrorCode>();
    expectTypeOf<BindingError['code']>().toEqualTypeOf<
      QueryErrorCode | undefined
    >();
  });
});

describe('WowError.violation', () => {
  it('reads an admission rejection: the logical field path', () => {
    const errorMsg =
      'Field [state.items.sku] requires its declared element scope.';
    const error = new WowError(
      {
        errorCode: ErrorCodes.QUERY_SCHEMA_VALIDATION,
        errorMsg,
        bindingErrors: [
          {
            name: 'state.items.sku',
            msg: errorMsg,
            code: QueryErrorCodes.ELEMENT_SCOPE_REQUIRED,
          },
        ],
      },
      { status: 400 },
    );
    expect(error.violation).toEqual({
      code: 'ELEMENT_SCOPE_REQUIRED',
      path: 'state.items.sku',
      message: errorMsg,
    });
  });

  it('reads a decoding rejection: the JSON path', () => {
    const errorMsg = 'Unknown type [NOPE] at [metrics[0]].';
    const error = new WowError({
      errorCode: ErrorCodes.ILLEGAL_ARGUMENT,
      errorMsg,
      bindingErrors: [
        { name: 'metrics[0]', msg: errorMsg, code: 'UNKNOWN_TYPE' },
      ],
    });
    expect(error.violation).toEqual({
      code: QueryErrorCodes.UNKNOWN_TYPE,
      path: 'metrics[0]',
      message: errorMsg,
    });
  });

  it('keeps a model-level path empty and a code it does not know', () => {
    const error = new WowError({
      errorCode: ErrorCodes.QUERY_SCHEMA_VALIDATION,
      errorMsg: 'A rule of a newer server.',
      bindingErrors: [
        {
          name: '',
          msg: 'A rule of a newer server.',
          code: 'SOME_FUTURE_CODE',
        },
      ],
    });
    expect(error.violation).toEqual({
      code: 'SOME_FUTURE_CODE',
      path: '',
      message: 'A rule of a newer server.',
    });
  });

  it('takes the first binding error that carries a code', () => {
    const error = new WowError({
      errorCode: ErrorCodes.ILLEGAL_ARGUMENT,
      errorMsg: 'Request body is empty.',
      bindingErrors: [
        { name: 'other', msg: 'no code' },
        { name: 'body', msg: 'Request body is empty.', code: 'EMPTY_BODY' },
      ],
    });
    expect(error.violation?.path).toBe('body');
  });

  it('falls back to errorMsg and an empty path for a partial body', () => {
    const error = new WowError({
      errorCode: ErrorCodes.ILLEGAL_ARGUMENT,
      errorMsg: 'Request body is empty.',
      bindingErrors: [{ code: 'EMPTY_BODY' } as BindingError],
    });
    expect(error.violation).toEqual({
      code: 'EMPTY_BODY',
      path: '',
      message: 'Request body is empty.',
    });
  });

  it('is undefined without a coded binding error', () => {
    // A budget rejection: text only.
    expect(
      new WowError({
        errorCode: ErrorCodes.ILLEGAL_ARGUMENT,
        errorMsg: 'HTTP list query limit[5000] must be between 1 and 1000.',
      }).violation,
    ).toBeUndefined();
    // A command's validation errors carry no code.
    expect(
      new WowError({
        errorCode: ErrorCodes.COMMAND_VALIDATION,
        errorMsg: 'quantity must be positive',
        bindingErrors: [{ name: 'quantity', msg: 'must be positive' }],
      }).violation,
    ).toBeUndefined();
  });

  it('survives toWowError from a failed response', async () => {
    const body = {
      errorCode: 'QuerySchemaValidation',
      errorMsg: 'Unknown logical field [state.missing].',
      bindingErrors: [
        {
          name: 'state.missing',
          msg: 'Unknown logical field [state.missing].',
          code: 'UNKNOWN_FIELD',
        },
      ],
    };
    const response = new Response(JSON.stringify(body), {
      status: 400,
      headers: { 'Content-Type': 'application/json' },
    });
    const error = await toWowError(
      Object.assign(new Error('Request failed'), { exchange: { response } }),
    );
    expect(error?.violation).toEqual({
      code: QueryErrorCodes.UNKNOWN_FIELD,
      path: 'state.missing',
      message: body.errorMsg,
    });
  });
});
