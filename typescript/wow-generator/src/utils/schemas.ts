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

import type {
  Components,
  Reference,
  Schema,
  SchemaType,
} from '@ahoo-wang/fetcher-openapi';
import { extractSchema } from './components';
import { isReference } from './references';

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

export type EnumText = Record<string, string>;
const ENUM_TEXT_NAME = 'x-enum-text';

export function getEnumText(schema: EnumSchema): EnumText | undefined {
  return schema[ENUM_TEXT_NAME];
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

export function getMapKeySchema(
  schema: Schema,
): Schema | Reference | undefined {
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
 * Checks whether a schema admits `null`.
 *
 * A schema admits null only when every applicable keyword does, so each is
 * checked in turn rather than accepting the first `null` that turns up:
 *
 * - `type` must include `null`, or the OpenAPI 3.0 `nullable` flag must be set.
 *   An absent `type` constrains nothing and admits null.
 * - `const` must be null, and `enum` must contain it.
 * - `anyOf` / `oneOf` need one branch that admits null; `allOf` needs all of
 *   them, since an intersection is only as permissive as its narrowest
 *   conjunct.
 *
 * `{ type: 'string', enum: ['a', null] }` is therefore not nullable: the
 * sibling `type` rejects the null member, exactly as `resolveType` does when it
 * drops the literal from the generated union.
 *
 * References are followed when components are supplied.
 *
 * @param schema - The schema or reference to check
 * @param components - Components used to resolve references
 * @param visited - Schemas already visited, guarding against reference cycles
 * @returns True if the schema admits null, false otherwise
 */
export function isNullableSchema(
  schema: Schema | Reference,
  components?: Components,
  visited: Set<Schema | Reference> = new Set(),
): boolean {
  if (visited.has(schema)) {
    return false;
  }
  visited.add(schema);
  if (isReference(schema)) {
    if (!components) {
      return false;
    }
    const resolved = extractSchema(schema, components);
    return resolved ? isNullableSchema(resolved, components, visited) : false;
  }
  const typeAdmitsNull =
    schema.nullable === true ||
    schema.type === undefined ||
    [schema.type].flat().includes('null');
  if (!typeAdmitsNull) {
    return false;
  }
  if (schema.const !== undefined && schema.const !== null) {
    return false;
  }
  if (Array.isArray(schema.enum) && !schema.enum.includes(null)) {
    return false;
  }
  // Each branch gets its own visited set: sibling branches must be judged
  // independently, while the copy still accumulates along a path so reference
  // cycles terminate.
  const isNullableMember = (member: Schema | Reference) =>
    isNullableSchema(member, components, new Set(visited));
  // anyOf and oneOf are separate assertions that must both hold, so they are
  // never merged into one check.
  if (schema.anyOf?.length && !schema.anyOf.some(isNullableMember)) {
    return false;
  }
  // oneOf admits null only when EXACTLY one branch does: a value matching two
  // branches fails the keyword.
  if (
    schema.oneOf?.length &&
    schema.oneOf.filter(isNullableMember).length !== 1
  ) {
    return false;
  }
  if (schema.allOf?.length && !schema.allOf.every(isNullableMember)) {
    return false;
  }
  // `not` rejects whatever its subschema accepts, so a nullable subschema
  // makes the whole schema non-nullable.
  if (schema.not !== undefined && isNullableMember(schema.not)) {
    return false;
  }
  return true;
}

/**
 * Checks whether a schema is request-only.
 *
 * A `writeOnly` property belongs to requests, so a response may omit it however
 * the document declares its type. References are followed when components are
 * supplied, since a property is commonly a bare `$ref` to the component that
 * carries the flag.
 *
 * @param schema - The schema or reference to check
 * @param components - Components used to resolve references
 * @param visited - Schemas already visited, guarding against reference cycles
 * @returns True if the schema is write-only, false otherwise
 */
export function isWriteOnly(
  schema: Schema | Reference,
  components?: Components,
  visited: Set<Schema | Reference> = new Set(),
): boolean {
  if (visited.has(schema)) {
    return false;
  }
  visited.add(schema);
  if (isReference(schema)) {
    if (!components) {
      return false;
    }
    const resolved = extractSchema(schema, components);
    return resolved ? isWriteOnly(resolved, components, visited) : false;
  }
  return schema.writeOnly === true;
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
