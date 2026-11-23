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

import {
  pascalCase,
  resolvePropertyName,
  toTypeIdentifier,
} from '../naming/naming';
import { type JsonSchema, isObject, schemaKey, typesOf } from './document';
import type { PropertyModel } from './model';
import { TEXT_NUMBER_FORMATS } from './textNumbers';
import { compound, join, literal } from './typeText';

/** The deepest `state.*` path listed as a query field. */
const MAX_FIELD_DEPTH = 4;

/**
 * Where a type is written: `output`, what the service writes (a state, an
 * event, an answer), or `input`, what a caller sends (a command's body, a
 * route's request). A read-only property, a computed one the service writes
 * and never reads back, is required where the schema says in an output and
 * never in an input, at any depth: it may be handed back, never has to be.
 */
export type Position = 'output' | 'input';

/**
 * Turns the schemas of a Manifest into types: names for `$defs` keys and
 * type text for any schema.
 */
export class SchemaTypes {
  readonly names = new Map<string, string>();
  /** The input types' names, of the keys whose input differs from their output ([nameInputs]). */
  readonly inputNames = new Map<string, string>();
  readonly warnings: string[] = [];

  constructor(readonly defs: Readonly<Record<string, JsonSchema>>) {}

  /**
   * Whether `key` is a parameterized type's, such as
   * `java.util.Map(java.lang.String,java.lang.Object)`: its schema is written
   * in place (`Record<string, unknown>`), never declared, since a name taken
   * from its last type argument would shadow a global such as `Object`.
   */
  inlined(key: string): boolean {
    return key.includes('(');
  }

  /** Names each key after its simple class name, qualified as far as needed to be unique and not `reserved`. */
  name(keys: readonly string[], reserved: ReadonlySet<string>): void {
    const pending = new Set(keys);
    for (let depth = 0; pending.size > 0; depth++) {
      const byName = new Map<string, string[]>();
      for (const key of pending.keys()) {
        const segments = key.split('.');
        const simple = segments[segments.length - 1].split('$');
        const qualifier = segments.slice(
          Math.max(0, segments.length - 1 - depth),
          segments.length - 1,
        );
        const name = toTypeIdentifier(
          [...qualifier, ...simple].map(part =>
            /^[a-z]/.test(part) ? pascalCase(part) : part,
          ),
        );
        byName.set(name, [...(byName.get(name) ?? []), key]);
      }
      const taken = new Set([...reserved, ...this.names.values()]);
      for (const [name, owners] of byName) {
        // A key whose packages are used up takes the name, numbered when it
        // is taken; the others take it alone, or qualify one package more.
        const exhausted = owners.filter(
          key => key.split('.').length - 1 <= depth,
        );
        const assigned =
          owners.length === 1 && !taken.has(name) ? owners : exhausted;
        for (const key of assigned) {
          let unique = name;
          for (let index = 2; taken.has(unique); index++) {
            unique = `${name}${index}`;
          }
          taken.add(unique);
          this.names.set(key, unique);
          pending.delete(key);
        }
      }
    }
  }

  /**
   * Names the input type of each of `keys` `<Name>Input`, numbered when the
   * name is taken: by a type of `$defs`, an input named before or `reserved`.
   */
  nameInputs(keys: readonly string[], reserved: ReadonlySet<string>): void {
    const taken = new Set([...reserved, ...this.names.values()]);
    for (const key of keys) {
      const name = `${this.names.get(key)}Input`;
      let unique = name;
      for (let index = 2; taken.has(unique); index++) {
        unique = `${name}${index}`;
      }
      taken.add(unique);
      this.inputNames.set(key, unique);
    }
  }

  /**
   * Of `keys`, those whose input type differs from their output type: the
   * keys whose schema holds a read-only property, in itself or through the
   * keys it refers to.
   */
  inputsOf(keys: Iterable<string>): string[] {
    const holding = new Set(
      Object.keys(this.defs).filter(key => readOnlyWithin(this.schemaOf(key))),
    );
    for (let grown = true; grown;) {
      grown = false;
      for (const key of Object.keys(this.defs)) {
        if (holding.has(key)) continue;
        if (
          [...this.references(this.schemaOf(key))].some(ref => holding.has(ref))
        ) {
          holding.add(key);
          grown = true;
        }
      }
    }
    return [...keys].filter(key => holding.has(key));
  }

  /** The schema of a key, or undefined with a warning. */
  schemaOf(key: string): JsonSchema | undefined {
    const schema = this.defs[key];
    return isObject(schema) ? schema : undefined;
  }

  /** The schema a reference resolves to, through `$ref`s. */
  resolve(schema: JsonSchema, seen = new Set<string>()): JsonSchema {
    const ref = schema.$ref;
    if (typeof ref !== 'string') return schema;
    const key = schemaKey(ref);
    if (key === undefined || seen.has(key)) return schema;
    const target = this.schemaOf(key);
    return target ? this.resolve(target, new Set([...seen, key])) : schema;
  }

  /** The keys a schema refers to, directly. */
  references(schema: unknown, into: Set<string> = new Set()): Set<string> {
    if (Array.isArray(schema)) {
      schema.forEach(item => this.references(item, into));
    } else if (isObject(schema)) {
      for (const [member, value] of Object.entries(schema)) {
        if (member === '$ref' && typeof value === 'string') {
          const key = schemaKey(value);
          if (key !== undefined) into.add(key);
        } else if (member !== 'enum' && member !== 'const') {
          this.references(value, into);
        }
      }
    }
    return into;
  }

  /** The keys a schema refers to by name: an inlined key's own, in its place. */
  namedReferences(schema: unknown): Set<string> {
    const named = new Set<string>();
    for (const key of this.references(schema)) {
      const target = this.schemaOf(key);
      if (target !== undefined && this.inlined(key)) {
        this.namedReferences(target).forEach(inner => named.add(inner));
      } else {
        named.add(key);
      }
    }
    return named;
  }

  /** Every key reachable from `roots`. */
  closure(roots: Iterable<string>): string[] {
    const reached = new Set<string>();
    const pending = [...roots];
    while (pending.length > 0) {
      const key = pending.pop()!;
      if (reached.has(key)) continue;
      reached.add(key);
      const schema = this.schemaOf(key);
      if (schema) pending.push(...this.references(schema));
    }
    return [...reached];
  }

  /** The type of a reference to `key`, at `position`. */
  refType(key: string, where: string, position: Position = 'output'): string {
    const schema = this.schemaOf(key);
    if (schema !== undefined && this.inlined(key)) {
      return this.type(schema, where, position);
    }
    const name =
      (position === 'input' ? this.inputNames.get(key) : undefined) ??
      this.names.get(key);
    if (name !== undefined && this.schemaOf(key)) return name;
    this.warnings.push(
      `${where} refers to the schema ${key}, which the Manifest's $defs does not hold; it is typed as unknown.`,
    );
    return 'unknown';
  }

  /** The type text of a schema, at `position`. */
  type(schema: unknown, where: string, position: Position = 'output'): string {
    if (schema === true || schema === undefined) return 'unknown';
    if (schema === false) return 'never';
    if (!isObject(schema)) return 'unknown';
    if (typeof schema.$ref === 'string') {
      const key = schemaKey(schema.$ref);
      return key === undefined ? 'unknown' : this.refType(key, where, position);
    }
    if ('const' in schema) return literal(schema.const);
    if (Array.isArray(schema.enum)) {
      return schema.enum.length === 0
        ? 'never'
        : join(schema.enum.map(literal), ' | ');
    }
    for (const [member, separator] of [
      ['oneOf', ' | '],
      ['anyOf', ' | '],
      ['allOf', ' & '],
    ] as const) {
      const options = schema[member];
      if (Array.isArray(options) && options.length > 0) {
        return join(
          options.map(option => this.type(option, where, position)),
          separator,
        );
      }
    }
    const types = typesOf(schema);
    if (types.length === 0) {
      return isObject(schema.properties)
        ? this.objectType(schema, where, position)
        : 'unknown';
    }
    return join(
      types.map(type => this.primitive(type, schema, where, position)),
      ' | ',
    );
  }

  private primitive(
    type: string,
    schema: JsonSchema,
    where: string,
    position: Position,
  ): string {
    switch (type) {
      case 'string':
        return 'string';
      case 'integer':
      case 'number':
        return typeof schema.format === 'string' &&
          TEXT_NUMBER_FORMATS.has(schema.format)
          ? 'string'
          : 'number';
      case 'boolean':
        return 'boolean';
      case 'null':
        return 'null';
      case 'array': {
        const item =
          schema.items === undefined
            ? 'unknown'
            : this.type(schema.items, where, position);
        return compound(item) ? `(${item})[]` : `${item}[]`;
      }
      case 'object':
        return this.objectType(schema, where, position);
      default:
        return 'unknown';
    }
  }

  /** An inline object type. */
  private objectType(
    schema: JsonSchema,
    where: string,
    position: Position,
  ): string {
    const properties = this.properties(schema, where, position);
    const extra = this.additional(schema, where, position);
    if (properties.length === 0) {
      return `Record<string, ${extra ?? 'unknown'}>`;
    }
    const members = properties.map(
      property =>
        `${property.readonly ? 'readonly ' : ''}${property.name}${property.optional ? '?' : ''}: ${property.type}`,
    );
    // A typed index signature would have to admit every property's type.
    if (extra !== undefined) members.push('[key: string]: unknown');
    return `{ ${members.join('; ')} }`;
  }

  /** The properties of an object schema at `position`, in their order. */
  properties(
    schema: JsonSchema,
    where: string,
    position: Position = 'output',
  ): PropertyModel[] {
    if (!isObject(schema.properties)) return [];
    const required = this.required(schema, position);
    return Object.entries(schema.properties).map(([name, property]) => {
      const description = isObject(property) ? property.description : undefined;
      return {
        name: resolvePropertyName(name),
        type: this.type(property, `${where}.${name}`, position),
        optional: !required.has(name),
        ...(readOnly(property) ? { readonly: true } : {}),
        ...(typeof description === 'string' ? { description } : {}),
      };
    });
  }

  /**
   * The properties an object schema requires at `position`: what `required`
   * lists, but for a read-only property in an input.
   */
  required(schema: JsonSchema, position: Position): Set<string> {
    const listed = Array.isArray(schema.required)
      ? (schema.required as string[])
      : [];
    if (position === 'output') return new Set(listed);
    const properties = isObject(schema.properties) ? schema.properties : {};
    return new Set(listed.filter(name => !readOnly(properties[name])));
  }

  /** The type of an object's additional properties, if it allows a typed set. */
  additional(
    schema: JsonSchema,
    where: string,
    position: Position = 'output',
  ): string | undefined {
    const extra = schema.additionalProperties;
    if (extra === undefined || extra === false) return undefined;
    return this.type(extra, where, position);
  }

  /** Whether a schema is an object with properties, so it names an interface. */
  isInterface(schema: JsonSchema): boolean {
    const types = typesOf(schema);
    return (
      isObject(schema.properties) &&
      (types.length === 0 || (types.length === 1 && types[0] === 'object')) &&
      schema.$ref === undefined &&
      schema.allOf === undefined &&
      schema.oneOf === undefined &&
      schema.anyOf === undefined
    );
  }

  /** The `state.*` paths of a state schema a query can filter and sort on. */
  fields(
    schema: JsonSchema,
    prefix: string,
    depth = 1,
    seen = new Set<string>(),
  ): string[] {
    const resolved = this.resolve(schema);
    const ref =
      typeof schema.$ref === 'string' ? schemaKey(schema.$ref) : undefined;
    if (ref !== undefined && seen.has(ref)) return [];
    const nextSeen = ref === undefined ? seen : new Set([...seen, ref]);
    if (!isObject(resolved.properties)) return [];
    const paths: string[] = [];
    for (const [name, property] of Object.entries(resolved.properties)) {
      const path = `${prefix}.${name}`;
      paths.push(path);
      if (depth < MAX_FIELD_DEPTH && isObject(property)) {
        const target = this.resolve(property);
        if (!typesOf(target).includes('array')) {
          paths.push(...this.fields(property, path, depth + 1, nextSeen));
        }
      }
    }
    return paths;
  }
}

/** Whether a property's schema is read-only: computed, written by the service and never read back. */
function readOnly(property: unknown): boolean {
  return isObject(property) && property.readOnly === true;
}

/** Whether a schema holds a read-only property anywhere within it, its references not followed. */
function readOnlyWithin(schema: unknown): boolean {
  if (Array.isArray(schema)) return schema.some(readOnlyWithin);
  if (!isObject(schema)) return false;
  if (
    isObject(schema.properties) &&
    Object.values(schema.properties).some(readOnly)
  ) {
    return true;
  }
  return Object.entries(schema).some(
    ([member, value]) =>
      member !== 'enum' && member !== 'const' && readOnlyWithin(value),
  );
}
