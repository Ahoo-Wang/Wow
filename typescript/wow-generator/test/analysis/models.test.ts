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
 * Which component schemas become models, and where: the models of the
 * generation model.
 */

import type { OpenAPI } from '@ahoo-wang/fetcher-openapi';
import { describe, expect, it } from 'vitest';
import { GeneratorError } from '../../src/api/errors';
import { analyzeDocument, openAPIDocument } from '../support/emission';

function modelsOf(openAPI: OpenAPI) {
  return analyzeDocument(openAPI).model.models;
}

describe('models', () => {
  it('declares a model per component schema, in document order, in the types.ts of its package', () => {
    const models = modelsOf(
      openAPIDocument({
        components: {
          schemas: {
            TestModel: {
              type: 'object',
              properties: { id: { type: 'string' } },
            },
            'wow.TestWow': { type: 'string' },
            'com.example.TestEnum': { type: 'string', enum: ['a', 'b'] },
          },
        },
      }),
    );
    expect(models.map(({ key, info, file }) => ({ key, info, file }))).toEqual([
      {
        key: 'TestModel',
        info: { name: 'TestModel', path: '/' },
        file: '/types.ts',
      },
      {
        key: 'wow.TestWow',
        info: { name: 'TestWow', path: '/wow' },
        file: '/wow/types.ts',
      },
      {
        key: 'com.example.TestEnum',
        info: { name: 'TestEnum', path: '/com/example' },
        file: '/com/example/types.ts',
      },
    ]);
  });

  it('declares none for a document without schemas', () => {
    expect(modelsOf(openAPIDocument({ components: {} }))).toEqual([]);
    expect(modelsOf(openAPIDocument({}))).toEqual([]);
  });

  it('fails on schemas that generate the same model', () => {
    const analysis = () =>
      modelsOf(
        openAPIDocument({
          components: {
            schemas: {
              'Foo-Bar': { type: 'object' },
              FooBar: { type: 'object' },
              'a.Item': { type: 'object' },
              'a.item': { type: 'string' },
              Unique: { type: 'object' },
            },
          },
        }),
      );
    expect(analysis).toThrow(GeneratorError);
    expect(analysis).toThrow(
      'Schemas generate the same model: Foo-Bar, FooBar → /FooBar; a.Item, a.item → /a/Item. Rename all but one of them in the document.',
    );
  });

});
