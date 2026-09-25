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

/**
 * Lists the property names a command body may omit.
 *
 * A command type wraps its body in `PartialBy<Command, ...>` built from this
 * list, which is where a request's declared optionality lives: generated model
 * properties are always required. The walk follows `allOf` branches and
 * references, because a command that inherits a base schema declares its
 * properties there - reading only the top level would demand fields the
 * document leaves optional.
 *
 * A property is optional when no branch requires it. `anyOf` and `oneOf` are
 * not followed: a branch an instance need not match says nothing about the
 * properties a command carries.
 *
 * @param schema - The command body schema, or a reference to it
 * @param components - The components a reference resolves against
 * @returns The declared property names absent from every `required` list
 */
export function resolveOptionalFields(
  schema: Schema | Reference,
  components?: Components,
): string[] {
  const declared: string[] = [];
  const required = new Set<string>();
  const visited = new Set<Schema | Reference>();
  // A schema that admits null generates `T | null`, whose `keyof` is `never`:
  // naming a field of it would make `PartialBy<Model, 'field'>` violate
  // `K extends keyof T` (TS2344). Such a command takes no PartialBy at all.
  let admitsNull = false;
  const walk = (current: Schema | Reference | undefined): void => {
    if (!current || visited.has(current) || admitsNull) {
      return;
    }
    visited.add(current);
    if (isReference(current)) {
      // A reference the document does not carry contributes nothing rather
      // than making every property of the command look required.
      walk(components && extractSchema(current, components));
      return;
    }
    if (current.nullable || Array.isArray(current.type)) {
      admitsNull = true;
      return;
    }
    for (const name of current.required ?? []) {
      required.add(name);
    }
    for (const name of Object.keys(current.properties ?? {})) {
      if (!declared.includes(name)) {
        declared.push(name);
      }
    }
    current.allOf?.forEach(walk);
  };
  walk(schema);
  return admitsNull ? [] : declared.filter(name => !required.has(name));
}
