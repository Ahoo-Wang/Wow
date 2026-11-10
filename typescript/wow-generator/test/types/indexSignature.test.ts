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
 * The index-signature rule as the generated model shows it: a property whose
 * kind is read off its schema as the index's primitive stays in an interface
 * beside `[key: string]`; one that cannot be read so, or reads as another
 * kind, takes the intersection form.
 */

import type { Components, Reference, Schema } from '@ahoo-wang/fetcher-openapi';
import { describe, expect, it } from 'vitest';
import { generateModels } from '../support/models';

const ref = (name: string): Reference => ({
  $ref: `#/components/schemas/${name}`,
});

/**
 * The models a property may refer to, declared beside `Model`: `Missing` is
 * left out of the components a reference resolves against.
 */
const MODELS: Record<string, Schema> = {
  Name: { type: 'string' },
  Missing: { type: 'string' },
};

const COMPONENTS: Components = { schemas: { Name: MODELS.Name } };

/**
 * A cycle that runs through `allOf`, which reference resolution alone does
 * not refuse. Its two aliases refer to each other, which TypeScript refuses
 * (TS2456), so a document holding it never compiles; the rule still decides.
 */
const CYCLE: Record<string, Schema> = {
  Left: { allOf: [ref('Right')] },
  Right: { allOf: [ref('Left')] },
};

/**
 * The generated `Model` of an object with `property` beside a string index,
 * written with `models` and type-checked to `diagnostics`.
 */
function modelOf(
  property: Schema | Reference,
  components?: Components,
  models: Record<string, Schema> = MODELS,
  diagnostics: unknown[] = [],
) {
  const generated = generateModels(
    {
      Model: {
        type: 'object',
        required: ['value'],
        properties: { value: property },
        additionalProperties: { type: 'string' },
      },
      ...models,
    },
    '',
    components,
  );
  expect(generated.diagnostics).toEqual(diagnostics);
  return generated.file;
}

/** Whether the generated `Model` is an interface with its index signature. */
function keepsInterface(file: ReturnType<typeof modelOf>): boolean {
  const model = file.getInterface('Model');
  if (model) {
    expect(model.getText()).toContain('[key: string]: string;');
    return true;
  }
  expect(file.getTypeAliasOrThrow('Model').getText()).toContain(
    '& globalThis.Record<string, string>',
  );
  return false;
}

describe('the index signature beside a property', () => {
  it('keeps the interface for an allOf whose parts all read as the index primitive', () => {
    expect(
      keepsInterface(
        modelOf({ allOf: [{ type: 'string' }, { type: 'string' }] }),
      ),
    ).toBe(true);
  });

  it('takes the intersection for an allOf with a part whose kind cannot be read', () => {
    expect(
      keepsInterface(
        modelOf({
          allOf: [{ type: 'string' }, { type: 'string', nullable: true }],
        }),
      ),
    ).toBe(false);
  });

  it.each([
    ['objects', { allOf: [{ type: 'object' }, { type: 'object' }] }],
    [
      'arrays',
      {
        allOf: [
          { type: 'array', items: {} },
          { type: 'array', items: {} },
        ],
      },
    ],
    [
      'an object, then a string',
      { allOf: [{ type: 'object' }, { type: 'string' }] },
    ],
    [
      'a string, then an object',
      { allOf: [{ type: 'string' }, { type: 'object' }] },
    ],
  ] as [string, Schema][])(
    'takes the intersection for an allOf of %s, which no primitive index admits',
    (_, property) => {
      expect(keepsInterface(modelOf(property))).toBe(false);
    },
  );

  it('keeps the interface for a reference that resolves to the index primitive', () => {
    expect(keepsInterface(modelOf(ref('Name'), COMPONENTS))).toBe(true);
  });

  it.each([
    ['without components to resolve it against', ref('Name'), undefined],
    ['to a schema the components do not hold', ref('Missing'), COMPONENTS],
  ] as [string, Reference, Components | undefined][])(
    'takes the intersection for a reference %s',
    (_, property, components) => {
      expect(keepsInterface(modelOf(property, components))).toBe(false);
    },
  );

  it('takes the intersection for a reference that comes back to itself through allOf', () => {
    const file = modelOf(ref('Left'), { schemas: CYCLE }, CYCLE, [
      "Type alias 'Left' circularly references itself.",
      "Type alias 'Right' circularly references itself.",
    ]);
    expect(keepsInterface(file)).toBe(false);
  });
});
