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

import type { Components, Reference, Schema } from '@ahoo-wang/fetcher-openapi';
import { extractSchema } from '../openapi/components';
import { isReference } from '../openapi/references';
import {
  isAllOf,
  isComposition,
  resolvePrimitiveType,
} from '../openapi/schemas';

/**
 * What a schema generates, as far as assignability to an index signature goes.
 *
 * A primitive carries its resolved TypeScript type; `object` and `array` need
 * no further detail, since neither is ever assignable to a primitive.
 */
type SchemaKind = 'object' | 'array' | { primitive: string };

/** Tells whether two classifications describe the same generated shape. */
function sameKind(left: SchemaKind | undefined, right: SchemaKind): boolean {
  if (left === undefined) return false;
  if (typeof left === 'string' || typeof right === 'string') {
    return left === right;
  }
  return left.primitive === right.primitive;
}

/**
 * Classifies a schema, following references through the components.
 *
 * Returns undefined - undecided - for anything whose assignability cannot be
 * read off the schema: a composition, a const, a nullable schema, a type
 * union, a missing `type`, a reference that cannot be resolved and a
 * reference cycle. An enum is decided by the type it sits beside, since it
 * generates literals of exactly that type.
 *
 * @param schema - The schema to classify
 * @param components - The components a reference resolves against
 * @param seen - The references already followed, guarding against a cycle
 * @returns The schema's kind, or undefined when it cannot be decided
 */
function schemaKind(
  schema: Schema | Reference,
  components?: Components,
  seen: Set<string> = new Set(),
): SchemaKind | undefined {
  if (isReference(schema)) {
    if (!components || seen.has(schema.$ref)) return undefined;
    seen.add(schema.$ref);
    const resolved = extractSchema(schema, components);
    return resolved ? schemaKind(resolved, components, seen) : undefined;
  }
  if (schema.nullable || Array.isArray(schema.type)) {
    return undefined;
  }
  // An `allOf` narrows to whatever its branches agree on, so it is decided
  // when every one of them decides and they all say the same thing. `anyOf`
  // and `oneOf` widen instead, and stay undecided.
  if (isAllOf(schema) && schema.type === undefined) {
    const kinds = schema.allOf.map(member =>
      schemaKind(member, components, seen),
    );
    const [first] = kinds;
    return first !== undefined && kinds.every(kind => sameKind(kind, first))
      ? first
      : undefined;
  }
  if (
    isComposition(schema) ||
    schema.const !== undefined ||
    schema.type === undefined
  ) {
    return undefined;
  }
  // An enum beside a sibling type generates literals of that type, which a
  // matching primitive index accepts - `'a' | 'b'` sits beside `string`.
  if (schema.type === 'object') return 'object';
  if (schema.type === 'array') return 'array';
  return { primitive: resolvePrimitiveType(schema.type) };
}

/**
 * Tells whether a named property provably cannot sit beside the index signature.
 *
 * The index type has to resolve to a primitive. That is not about proving the
 * clash - it is what keeps the alias sound: `Record<string, T>` in a type alias
 * may not lead back to the alias itself (TS2456), and only a primitive `T` is
 * certain never to. A property may reference anything, including the model
 * itself, because an object member defers.
 *
 * Against a primitive index, an object and an array are as incompatible as a
 * different primitive is, and so is a property whose kind cannot be read off
 * the schema: a nullable property generates `T | null`, a type array and a
 * typeless enum generate a union, and none of those is assignable to a
 * primitive index (TS2411). Undecided therefore takes the intersection, which
 * has no index-assignability rule to break. It cannot be circular either
 * (TS2456), because the index resolved to a primitive before we got here, so
 * the `Record` this generates can never lead back to the alias.
 *
 * @param propSchema - The named property's schema
 * @param additionalProperties - The additional-property schema
 * @param components - The components a reference resolves against
 * @returns True when the property may not be assignable to the index type
 */
function clashesWithIndexSignature(
  propSchema: Schema | Reference,
  additionalProperties: Schema | Reference,
  components?: Components,
): boolean {
  const indexKind = schemaKind(additionalProperties, components);
  if (typeof indexKind !== 'object') return false;
  const propertyKind = schemaKind(propSchema, components);
  if (propertyKind === undefined) return true;
  return (
    typeof propertyKind === 'string' ||
    propertyKind.primitive !== indexKind.primitive
  );
}

/**
 * Chooses the intersection representation over an interface with an index
 * signature.
 *
 * An interface may only carry a named property whose type is assignable to
 * its index signature (TS2411). Every generated property is required, so
 * only a clash that can be PROVEN off the schemas moves one - see
 * {@link clashesWithIndexSignature}. Anything undecided keeps the interface,
 * which is the only form that can reference itself through an index
 * signature: an alias reaching itself through `Record` is circular (TS2456),
 * which is what a dictionary of its own type would generate.
 *
 * @param schema - The object schema to represent
 * @param components - The components a reference resolves against
 * @returns True when the schema needs the intersection form
 */
export function requiresAdditionalPropertiesIntersection(
  schema: Schema,
  components?: Components,
): boolean {
  const additionalProperties = schema.additionalProperties;
  if (typeof additionalProperties !== 'object') {
    return false;
  }
  return Object.values(schema.properties ?? {}).some(propSchema =>
    clashesWithIndexSignature(propSchema, additionalProperties, components),
  );
}
