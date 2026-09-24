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

import type { Reference } from '@ahoo-wang/fetcher-openapi';

export function isReference(schema: any): schema is Reference {
  return !!(schema && typeof schema === 'object' && '$ref' in schema);
}

/**
 * Resolves a local JSON pointer (`#/components/schemas/Item`) in a document.
 *
 * @param document - The document the pointer points into
 * @param ref - The `$ref` value, starting with `#`
 * @returns The value it points at, or undefined when there is none
 */
export function resolveLocalPointer(document: unknown, ref: string): unknown {
  let pointer = ref.slice(1);
  try {
    pointer = decodeURIComponent(pointer);
  } catch {
    // Not percent-encoded after all; read it as written.
  }
  if (pointer === '') return document;
  let current: unknown = document;
  for (const token of pointer.slice(1).split('/')) {
    if (current === null || typeof current !== 'object') return undefined;
    const key = token.replace(/~1/g, '/').replace(/~0/g, '~');
    if (!Object.hasOwn(current, key)) return undefined;
    current = (current as Record<string, unknown>)[key];
  }
  return current;
}

/**
 * Finds every reference to a component (`#/components/...`) that points at
 * nothing.
 *
 * A dangling schema reference would otherwise generate a type that names an
 * undeclared model, and a dangling parameter reference would silently drop
 * the parameter. Other references are left alone: a reference to another
 * document is refused where it is read, with a request to bundle it, and a
 * schema may carry JSON Schema `definitions` it references relative to
 * itself (`#/definitions/...`), as Wow 8.11 writes its filter schema.
 *
 * @param document - The parsed OpenAPI document
 * @returns Each dangling reference with the JSON path of the object holding it
 */
export function findDanglingReferences(
  document: unknown,
): { ref: string; location: string }[] {
  const dangling: { ref: string; location: string }[] = [];
  const seen = new Set<object>();
  const visit = (value: unknown, location: string) => {
    if (value === null || typeof value !== 'object' || seen.has(value)) return;
    seen.add(value);
    if (Array.isArray(value)) {
      value.forEach((item, index) => visit(item, `${location}/${index}`));
      return;
    }
    const ref = (value as Record<string, unknown>).$ref;
    if (
      typeof ref === 'string' &&
      ref.startsWith('#/components/') &&
      resolveLocalPointer(document, ref) === undefined
    ) {
      dangling.push({ ref, location: location || '/' });
    }
    for (const [key, child] of Object.entries(value)) {
      visit(
        child,
        `${location}/${key.replace(/~/g, '~0').replace(/\//g, '~1')}`,
      );
    }
  };
  visit(document, '');
  return dangling;
}
