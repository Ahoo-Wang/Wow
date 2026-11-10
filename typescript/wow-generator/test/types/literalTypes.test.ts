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
 * A `const` as the generated model types it: its literal, when the value
 * fits the schema's type; `never`, when it does not.
 */

import type { Schema } from '@ahoo-wang/fetcher-openapi';
import { describe, expect, it } from 'vitest';
import { generateModel } from '../support/models';

/** The generated type of a required property of schema `property`. */
function typeOf(property: Schema): string {
  const { file, diagnostics } = generateModel({
    type: 'object',
    required: ['value'],
    properties: { value: property },
  });
  expect(diagnostics).toEqual([]);
  return file
    .getInterfaceOrThrow('Model')
    .getPropertyOrThrow('value')
    .getTypeNodeOrThrow()
    .getText();
}

describe('a const as a literal type', () => {
  it('takes any value when the schema names no type', () => {
    expect(typeOf({ const: 'a' })).toBe("'a'");
  });

  it('takes null when the schema is nullable', () => {
    expect(typeOf({ type: 'string', nullable: true, const: null })).toBe(
      '(null) & ((string) | null)',
    );
  });

  it('is never when the value does not fit the type', () => {
    expect(typeOf({ type: 'string', const: null })).toBe('never');
  });

  it.each([
    [{ type: 'null', const: null }, '(null) & (null)'],
    [{ type: 'null', const: 'a' }, 'never'],
    [
      { type: 'array', items: { type: 'string' }, const: ['a'] },
      "(['a']) & (string[])",
    ],
    [{ type: 'array', items: { type: 'string' }, const: 'a' }, 'never'],
  ] as [Schema, string][])('types %j as %s', (property, type) => {
    expect(typeOf(property)).toBe(type);
  });
});
