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
import { Project } from 'ts-morph';
import type { Schema } from '@ahoo-wang/fetcher-openapi';
import { TypeGenerator } from '../src/model';

function generate(schemas: Record<string, Schema>, assignments: string) {
  const project = new Project({
    useInMemoryFileSystem: true,
    compilerOptions: {
      strict: true,
      skipLibCheck: true,
      lib: ['lib.es2020.d.ts', 'lib.dom.d.ts', 'lib.dom.iterable.d.ts'],
    },
  });
  const file = project.createSourceFile('/models.ts', '');
  for (const [name, schema] of Object.entries(schemas)) {
    new TypeGenerator({ name, path: '/' }, file, { key: name, schema }, '/', {
      schemas,
    }).generate();
  }
  file.addStatements(assignments);
  return project
    .getPreEmitDiagnostics()
    .map(diagnostic => diagnostic.getMessageText());
}

describe('reviewed object schema constraints', () => {
  it('keeps typeless required objects open by default', () => {
    expect(
      generate(
        {
          Model: {
            properties: { id: { type: 'string' } },
            required: ['id'],
          },
        },
        `
      const valid: Model = { id: '1', extra: 2 };
      const nonObjects: Model[] = [null, 'text', 1, true, []];
      const tuple = [1, 'text'] as const;
      const readonlyArray: Model = tuple;
      void readonlyArray;
      // @ts-expect-error object instances must supply id
      const missing: Model = { extra: 2 };
      // @ts-expect-error present id must match its schema
      const wrong: Model = { id: 1 };
    `,
      ),
    ).toEqual([]);
  });

  it('retains nonempty typeless root properties beside composition', () => {
    expect(
      generate(
        {
          Model: {
            oneOf: [{ type: 'object', properties: {} }],
            properties: { id: { type: 'string' } },
          },
        },
        `
      const valid: Model[] = [{}, { id: '1', extra: 2 }];
      // @ts-expect-error root property constraints still apply
      const wrong: Model = { id: 1 };
    `,
      ),
    ).toEqual([]);
  });

  it('uses the global Record type when a component shadows its name', () => {
    expect(
      generate(
        {
          Record: { type: 'object', properties: { label: { type: 'string' } } },
          Model: {
            type: 'object',
            properties: { name: { type: 'string' } },
            additionalProperties: { type: 'string' },
          },
          Empty: { type: 'object' },
          Dictionary: {
            type: 'object',
            additionalProperties: { type: 'number' },
          },
        },
        `
      const valid: Model[] = [{}, { name: 'yes', extra: 'allowed' }];
      const empty: Empty = {};
      const dictionary: Dictionary = { count: 1 };
      // @ts-expect-error additional properties retain their schema type
      const wrong: Model = { extra: 2 };
    `,
      ),
    ).toEqual([]);
  });

  it.each([undefined, true])(
    'does not let prototype methods satisfy required JSON keys (additionalProperties: %s)',
    additionalProperties => {
      expect(
        generate(
          {
            Model: {
              type: 'object',
              required: ['toString', 'constructor'],
              additionalProperties,
            },
          },
          `
      const valid: Model[] = [
        { toString: 'text', constructor: null },
        { toString: {}, constructor: [] },
        { toString: 1, constructor: false },
      ];
      // @ts-expect-error inherited functions cannot satisfy required JSON values
      const missing: Model = {};
      // @ts-expect-error inherited constructor is not a JSON value
      const partial: Model = { toString: 'text' };
      // @ts-expect-error a supplied function is not a JSON value either
      const callable: Model = { toString: () => 'text', constructor: null };
    `,
        ),
      ).toEqual([]);
    },
  );
});
