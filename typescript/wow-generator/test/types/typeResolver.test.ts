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
 * The type resolver, table-driven and without ts-morph: each case of
 * `typeResolverCases.ts` resolves a schema in a scope, and the text and the
 * imports of every case are held word for word in
 * `expected/type-resolver.json`. The golden was recorded from the resolver
 * the models and the API clients used before it became pure functions
 * (refactor batch B5), so it also holds that refactor to the old output.
 *
 * Accept an intentional change with `-u` and review the diff of the golden.
 */

import { join } from 'node:path';
import type { Reference, Schema } from '@ahoo-wang/fetcher-openapi';
import { describe, expect, it } from 'vitest';
import { relativeModuleSpecifier } from '../../src/emit/imports';
import { ImportRegistry } from '../../src/emit/importRegistry';
import {
  resolveModelInfo,
  resolveReferenceModelInfo,
} from '../../src/model/modelInfo';
import { documentTypeContext } from '../../src/model/typeGenerator';
import type {
  ResolvedType,
  TypeContext,
  TypeScope,
} from '../../src/types/typeResolver';
import {
  createTypeContext,
  requiresAdditionalPropertiesIntersection,
  resolveAdditionalProperties,
  resolveAdditionalPropertyType,
  resolveLiteral,
  resolveMapValueType,
  resolveRequiredAdditionalPropertyType,
  resolveType,
} from '../../src/types/typeResolver';
import type { CaseScope, Entry } from './typeResolverCases';
import { CASES, COMPONENTS, ROOT_MODEL } from './typeResolverCases';

const OUTPUT_DIR = '/out';

const ENTRIES: Record<
  Entry,
  (schema: never, scope: TypeScope) => ResolvedType
> = {
  type: resolveType,
  additionalProperties: resolveAdditionalProperties,
  additionalPropertyType: resolveAdditionalPropertyType,
  requiredAdditionalPropertyType: resolveRequiredAdditionalPropertyType,
  mapValueType: resolveMapValueType,
};

function scopeOf(
  caseScope: CaseScope,
  context: TypeContext,
): { scope: TypeScope; imports: ImportRegistry } {
  const imports = new ImportRegistry();
  imports.apply(caseScope.existing ?? []);
  return {
    imports,
    scope: {
      context,
      owner: caseScope.owner,
      specifierOf: model =>
        model.path.startsWith('@')
          ? model.path
          : relativeModuleSpecifier(
              caseScope.directory,
              join(OUTPUT_DIR, model.path, 'types.ts'),
            ),
      imports,
    },
  };
}

describe('TypeResolver', () => {
  it('resolves every case as the golden records', async () => {
    const context = documentTypeContext(COMPONENTS);
    const results: Record<string, unknown> = {};
    for (const testCase of CASES) {
      const { scope, imports } = scopeOf(testCase.scope ?? ROOT_MODEL, context);
      const resolve = ENTRIES[testCase.entry ?? 'type'];
      const texts: string[] = [];
      for (let time = 0; time < (testCase.times ?? 1); time++) {
        const resolved = resolve(testCase.schema as never, scope);
        imports.apply(resolved.imports);
        texts.push(resolved.text);
      }
      results[testCase.name] = {
        text: texts.length === 1 ? texts[0] : texts,
        imports: imports.structures(),
      };
    }
    await expect(`${JSON.stringify(results, null, 2)}\n`).toMatchFileSnapshot(
      '../../expected/type-resolver.json',
    );
  });

  it('never changes the imports of the scope it reads', () => {
    const { scope, imports } = scopeOf(
      {
        owner: { name: 'Model', path: '/shop' },
        directory: '/out/shop',
        existing: [{ moduleSpecifier: '../types.js', name: 'Response' }],
      },
      documentTypeContext(COMPONENTS),
    );
    const resolved = resolveType(
      { $ref: '#/components/schemas/Response' },
      scope,
    );
    expect(resolved).toEqual({
      text: '_Response',
      imports: [
        {
          moduleSpecifier: '../types.js',
          name: 'Response',
          alias: '_Response',
        },
      ],
    });
    expect(imports.entries()).toEqual([
      { moduleSpecifier: '../types.js', name: 'Response' },
    ]);
  });

  it('reads the model names of each path once', () => {
    let reads = 0;
    const context = createTypeContext(COMPONENTS, {
      ofKey: key => {
        reads++;
        return resolveModelInfo(key);
      },
      ofReference: resolveReferenceModelInfo,
    });
    const { scope } = scopeOf(
      { owner: { name: 'Model', path: '/shop' }, directory: '/out/shop' },
      context,
    );
    const reference: Reference = { $ref: '#/components/schemas/Item' };
    for (let time = 0; time < 5; time++) resolveType(reference, scope);
    expect(reads).toBe(Object.keys(COMPONENTS.schemas!).length);
    expect(context.namesAt('/shop')).toEqual([
      'Order',
      'OrderEnumText',
      'Item',
      'ItemEnumText',
      'Status',
      'StatusEnumText',
    ]);
    expect(context.namesAt('/nowhere')).toEqual([]);
  });

  it('reads no model names without components', () => {
    expect(documentTypeContext().namesAt('/')).toEqual([]);
  });

  it('tells when a property clashes with the index signature', () => {
    const schema: Schema = {
      type: 'object',
      properties: { count: { type: 'integer' } },
      additionalProperties: { type: 'string' },
    };
    expect(requiresAdditionalPropertiesIntersection(schema)).toBe(true);
    expect(
      requiresAdditionalPropertiesIntersection({
        ...schema,
        additionalProperties: true,
      }),
    ).toBe(false);
    expect(
      requiresAdditionalPropertiesIntersection(
        {
          ...schema,
          properties: { name: { $ref: '#/components/schemas/Name' } },
        },
        COMPONENTS,
      ),
    ).toBe(false);
  });

  it.each([
    ['plain', "'plain'"],
    ["it's", "'it\\'s'"],
    [['a', 1], "['a', 1]"],
    [{}, 'globalThis.Record<string, never>'],
    [undefined, 'never'],
  ])('renders %j as a literal type', (value, literal) => {
    expect(resolveLiteral(value)).toBe(literal);
  });
});
