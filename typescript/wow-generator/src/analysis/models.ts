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

import { GeneratorError } from '../api/errors';
import { modelFilePath } from '../naming/paths';
import type { OpenApiDocument } from '../openapi/document';
import type { ModelDeclaration } from './model';
import { resolveModelInfo } from './modelInfo';

/**
 * The models of a document: one per component schema, in the document's
 * order.
 *
 * @throws GeneratorError when two schemas generate the same model
 */
export function analyzeModels(document: OpenApiDocument): ModelDeclaration[] {
  const schemas = document.components?.schemas;
  if (!schemas) return [];
  const keys = Object.keys(schemas);
  assertUniqueModelNames(keys);
  return keys.map(key => {
    const schema = schemas[key];
    const info = resolveModelInfo(key);
    return { key, info, file: modelFilePath(info), schema };
  });
}

/**
 * Fails when two schemas generate the same model in the same file.
 *
 * Names are normalised to PascalCase, so `Foo-Bar`, `FooBar` and `foo_bar`
 * all become `FooBar`; TypeScript would silently merge three interfaces of
 * that name into one type that matches none of them.
 *
 * @throws GeneratorError listing every group of colliding schema keys
 */
function assertUniqueModelNames(keys: readonly string[]) {
  const byModel = new Map<string, string[]>();
  for (const key of keys) {
    const modelInfo = resolveModelInfo(key);
    const model = `${modelInfo.path === '/' ? '' : modelInfo.path}/${modelInfo.name}`;
    byModel.set(model, [...(byModel.get(model) ?? []), key]);
  }
  const collisions = [...byModel].filter(([, keys]) => keys.length > 1);
  if (collisions.length === 0) return;
  throw new GeneratorError(
    'specification',
    `Schemas generate the same model: ${collisions
      .map(([model, keys]) => `${keys.join(', ')} → ${model}`)
      .join('; ')}. Rename all but one of them in the document.`,
  );
}
