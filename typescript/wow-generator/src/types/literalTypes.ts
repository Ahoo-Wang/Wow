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

import type { Schema } from '@ahoo-wang/fetcher-openapi';
import { CodeBlockWriter } from 'ts-morph';
import { resolvePropertyName } from '../naming/naming';

export function matchesLiteralType(value: unknown, schema: Schema): boolean {
  if (schema.type === undefined) return true;
  if (value === null && schema.nullable) return true;
  return [schema.type].flat().some(type => {
    if (type === 'null') return value === null;
    if (type === 'array') return Array.isArray(value);
    if (type === 'object') {
      return (
        value !== null && typeof value === 'object' && !Array.isArray(value)
      );
    }
    if (type === 'integer')
      return typeof value === 'number' && Number.isInteger(value);
    return typeof value === type;
  });
}

/**
 * Renders a JSON value as a TypeScript literal type: a string in single
 * quotes, escaped as ts-morph's printer escapes it, an array as a tuple, an
 * object as an object type that no array matches.
 *
 * @param value - The value from the document
 * @returns The literal type
 */
export function resolveLiteral(value: unknown): string {
  if (typeof value === 'string') {
    return new CodeBlockWriter({ useSingleQuote: true })
      .quote(value)
      .toString();
  }
  if (Array.isArray(value)) {
    return `[${value.map(item => resolveLiteral(item)).join(', ')}]`;
  }
  if (value !== null && typeof value === 'object') {
    const properties = Object.entries(value).map(
      ([name, item]) => `${resolvePropertyName(name)}: ${resolveLiteral(item)}`,
    );
    return properties.length
      ? `{ ${properties.join('; ')}; readonly [globalThis.Symbol.iterator]?: never }`
      : 'globalThis.Record<string, never>';
  }
  return JSON.stringify(value) ?? 'never';
}
