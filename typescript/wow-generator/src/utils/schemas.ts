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

import { Reference, Schema, SchemaType } from '@ahoo-wang/fetcher-openapi';

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
    return true;
  }
  return PRIMITIVE_TYPES.includes(type);
}

export type EnumSchema = Schema & { enum: any[] };

/**
 * Checks if a schema represents an enum.
 * @param schema - The schema to check
 * @returns True if the schema has an enum property, false otherwise
 */
export function isEnum(schema: Schema): schema is EnumSchema {
  return Array.isArray(schema.enum) && schema.enum.length > 0;
}

export type ObjectSchema = Schema & {
  type: 'object';
  properties: Record<string, Schema | Reference>;
};

export function isObject(schema: Schema): schema is ObjectSchema {
  return schema.type === 'object' && !!schema.properties;
}

export type ArraySchema = Schema & { type: 'array'; items: Schema | Reference };

/**
 * Checks if a schema is an array type.
 * @param schema - The schema to check
 * @returns True if the schema is an array type, false otherwise
 */
export function isArray(schema: Schema): schema is ArraySchema {
  return schema.type === 'array' && !!schema.items;
}

export type AnyOfSchema = Schema & { anyOf: any[] };

/**
 * Checks if a schema is an anyOf composition.
 * @param schema - The schema to check
 * @returns True if the schema has a non-empty anyOf property, false otherwise
 */
export function isAnyOf(schema: Schema): schema is AnyOfSchema {
  return Array.isArray(schema.anyOf) && schema.anyOf.length > 0;
}

export type OneOfSchema = Schema & { oneOf: any[] };

/**
 * Checks if a schema is a oneOf composition.
 * @param schema - The schema to check
 * @returns True if the schema has a non-empty oneOf property, false otherwise
 */
export function isOneOf(schema: Schema): schema is OneOfSchema {
  return Array.isArray(schema.oneOf) && schema.oneOf.length > 0;
}

export type UnionSchema = Schema & ({ anyOf: any[] } | { oneOf: any[] });

/**
 * Checks if a schema is a union (either anyOf or oneOf).
 * @param schema - The schema to check
 * @returns True if the schema is either an anyOf or oneOf composition, false otherwise
 */
export function isUnion(schema: Schema): schema is UnionSchema {
  return isAnyOf(schema) || isOneOf(schema);
}

export type AllOfSchema = Schema & { allOf: any[] };

/**
 * Checks if a schema is an allOf composition.
 * @param schema - The schema to check
 * @returns True if the schema has a non-empty allOf property, false otherwise
 */
export function isAllOf(schema: Schema): schema is AllOfSchema {
  return Array.isArray(schema.allOf) && schema.allOf.length > 0;
}

export type CompositionSchema = AnyOfSchema | OneOfSchema | AllOfSchema;

/**
 * Checks if a schema is a composition (anyOf, oneOf, or allOf).
 * @param schema - The schema to check
 * @returns True if the schema is anyOf, oneOf, or allOf composition, false otherwise
 */
export function isComposition(schema: Schema): schema is CompositionSchema {
  return isAnyOf(schema) || isOneOf(schema) || isAllOf(schema);
}

/**
 * Converts a type string to an array type.
 * Wraps complex types (containing | or &) in parentheses before adding array notation.
 * @param type - The type string to convert to an array type
 * @returns The array type string
 */
export function toArrayType(type: string): string {
  if (type.includes('|') || type.includes('&')) {
    return `(${type})[]`;
  }
  return `${type}[]`;
}

export type MapSchema = Schema & {
  type: 'object';
  additionalProperties: boolean | Schema | Reference;
};

export function isMap(schema: Schema): schema is MapSchema {
  return (
    schema.type === 'object' &&
    !schema.properties &&
    schema.additionalProperties !== undefined
  );
}

const X_MAP_KEY_SCHEMA = 'x-map-key-schema';

export function getMapKeySchema(schema: Schema): Schema | Reference | undefined {
  return schema[X_MAP_KEY_SCHEMA];
}

/**
 * Checks if a schema represents an empty object.
 * @param schema - The schema to check
 * @returns True if the schema represents an empty object, false otherwise
 */
export function isEmptyObject(schema: Schema): boolean {
  if (schema.type !== 'object') {
    return false;
  }
  if (!schema.properties) {
    return true;
  }
  return Object.keys(schema.properties).length === 0;
}

export function isReadOnly(schema: Schema | Reference): boolean {
  return (schema as Schema).readOnly === true;
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

export function resolveOptionalFields(schema: Schema): string[] {
  if (!isObject(schema)) {
    return [];
  }
  const required = schema.required || [];
  return Object.keys(schema.properties).filter(it => !required.includes(it));
}
