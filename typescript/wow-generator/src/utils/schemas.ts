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

import { Schema, SchemaType } from '@ahoo-wang/fetcher-openapi';

/** List of primitive schema types */
const PRIMITIVE_TYPES: SchemaType[] = [
  'string',
  'number',
  'integer',
  'boolean',
  'null',
];

/**
 * Checks if a schema type is primitive.
 * @param type - The schema type to check
 * @returns True if the type is primitive, false otherwise
 */
export function isPrimitive(type: SchemaType | SchemaType[]): boolean {
  if (Array.isArray(type)) {
    return false;
  }
  return PRIMITIVE_TYPES.includes(type);
}

/**
 * Checks if a schema represents an enum.
 * @param schema - The schema to check
 * @returns True if the schema has an enum property, false otherwise
 */
export function isEnum(schema: Schema): schema is Schema & { enum: any[] } {
  return Array.isArray(schema.enum);
}

/**
 * Resolves a schema type to its TypeScript equivalent.
 * @param type - The schema type(s) to resolve
 * @returns The TypeScript type string
 */
export function resolvePrimitiveType(type: SchemaType | SchemaType[]): string {
  if (Array.isArray(type)) {
    return type.map(it => resolvePrimitiveType(it)).join(' | ');
  }
  switch (type) {
    case 'string':
      return 'string';
    case 'number':
    case 'integer':
      return 'number';
    case 'boolean':
      return 'boolean';
    case 'null':
      return 'null';
    default:
      return 'any';
  }
}
