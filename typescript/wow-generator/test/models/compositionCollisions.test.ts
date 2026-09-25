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
 * Compositions combined in one schema, and models named like the globals the
 * generated types use.
 */

import { describe, expect, it } from 'vitest';
import type { Schema } from '@ahoo-wang/fetcher-openapi';
import {
  diagnosticsOf,
  generateModel,
  runModels,
  writeModels,
} from '../support/models';

describe('simultaneous composition and declaration collisions', () => {
  it.each(['oneOf', 'anyOf'] as const)(
    'intersects allOf with the complete %s union',
    composition => {
      expect(
        generateModel(
          {
            allOf: [
              {
                type: 'object',
                required: ['id'],
                properties: { id: { type: 'string' } },
              },
            ],
            [composition]: ['a', 'b'].map(kind => ({
              type: 'object',
              required: ['kind'],
              properties: { kind: { const: kind } },
            })),
          },
          `
            const valid: Model[] = [{ id: '1', kind: 'a' }, { id: '2', kind: 'b' }];
            // @ts-expect-error allOf still requires id
            const missingId: Model = { kind: 'a' };
            // @ts-expect-error the union still requires kind
            const missingKind: Model = { id: '1' };
            // @ts-expect-error allOf still constrains the id type
            const wrongId: Model = { id: 1, kind: 'a' };
          `,
        ).diagnostics,
      ).toEqual([]);
    },
  );

  it('intersects simultaneous oneOf and anyOf unions', () => {
    expect(
      generateModel(
        {
          oneOf: [{ const: 'a' }, { const: 'b' }],
          anyOf: [{ const: 'b' }, { const: 'c' }],
        },
        `
          const valid: Model = 'b';
          // @ts-expect-error a does not satisfy anyOf
          const excludedByAnyOf: Model = 'a';
          // @ts-expect-error c does not satisfy oneOf
          const excludedByOneOf: Model = 'c';
        `,
      ).diagnostics,
    ).toEqual([]);
  });

  it('keeps the global Exclude available beside a model of the same name', () => {
    const schemas: Record<string, Schema> = {
      Exclude: { type: 'string' },
      Model: {
        allOf: [
          { type: 'object', properties: { id: { type: 'string' } } },
          { required: ['id'] },
        ],
      },
    };
    const file = writeModels(schemas, { schemas });
    file.addStatements(`
      const component: Exclude = 'local model';
      const valid: Model = { id: '1' };
      // @ts-expect-error id remains required
      const missing: Model = {};
      // @ts-expect-error required-only branches cannot admit a string
      const primitive: Model = 'x';
      // @ts-expect-error required-only branches cannot admit an array
      const array: Model = [];
    `);
    expect(diagnosticsOf(file)).toEqual([]);
  });

  it.each([false, true])(
    'keeps empty and separator enum members distinct (composed: %s)',
    composed => {
      const { diagnostics, file } = generateModel(
        {
          type: 'string',
          enum: ['', '-', '_', 'ON'],
          'x-enum-text': {
            '': 'Empty',
            '-': 'Dash',
            _: 'Underscore',
            ON: 'On',
          },
          ...(composed ? { allOf: [{ type: 'string' as const }] } : {}),
        },
        `
          export const values: Model[] = [Model[''], Model['-'], Model['_'], Model.ON];
          export const labels = [ModelEnumText[''], ModelEnumText['-'], ModelEnumText['_'], ModelEnumText.ON];
        `,
      );
      expect(diagnostics).toEqual([]);
      expect(runModels(file)).toMatchObject({
        Model: { '': '', '-': '-', _: '_', ON: 'ON' },
        ModelEnumText: { '': 'Empty', '-': 'Dash', _: 'Underscore', ON: 'On' },
        values: ['', '-', '_', 'ON'],
        labels: ['Empty', 'Dash', 'Underscore', 'On'],
      });
    },
  );
});
