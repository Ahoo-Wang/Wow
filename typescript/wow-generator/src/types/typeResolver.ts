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
import { CodeBlockWriter } from 'ts-morph';
import { jsDoc, schemaJSDoc } from '../emit/jsdoc';
import type { ModelInfo } from '../naming/modelInfo';
import { quoteStringLiteral, resolvePropertyName } from '../naming/naming';
import { extractComponentKey, extractSchema } from '../openapi/components';
import { isReference } from '../openapi/references';
import type { MapSchema, ObjectSchema } from '../openapi/schemas';
import {
  getMapKeySchema,
  isAllOf,
  isComposition,
  isEnum,
  isMap,
  isObject,
  isReadOnly,
  resolvePrimitiveType,
  toArrayType,
} from '../openapi/schemas';

/**
 * Schema → TypeScript type expression, as pure functions.
 *
 * Every entry point takes a schema and a {@link TypeScope} - where the type
 * is written - and returns a {@link ResolvedType}: the type's text, and the
 * imports the module needs for it. Nothing is written; the caller applies the
 * imports to its module (`ImportRegistry.apply`) before it resolves the next
 * type, so a later reference sees the names and aliases an earlier one took.
 */

/** An import a resolved type needs: a name, and the alias it goes by. */
export interface ImportRequest {
  readonly moduleSpecifier: string;
  readonly name: string;
  readonly alias?: string;
}

/** A type expression, and the imports it needs. */
export interface ResolvedType {
  readonly text: string;
  readonly imports: readonly ImportRequest[];
}

/** The imports a module already holds, as the resolver reads them. */
export interface ImportLookup {
  entries(): readonly ImportRequest[];
}

/**
 * What stays the same for every type of one document: its components, how a
 * component names its model, and the model names declared at each path.
 */
export interface TypeContext {
  readonly components?: Components;
  /** The model a reference names. */
  modelOf(reference: Reference): ModelInfo;
  /** The models declared at a path, with their `EnumText` companions. */
  namesAt(path: string): readonly string[];
}

/**
 * How a document's component keys name models: `resolveModelInfo` and
 * `resolveReferenceModelInfo` of `analysis/modelInfo.ts`.
 */
export interface ModelNaming {
  ofKey(key: string): ModelInfo;
  ofReference(reference: Reference, components?: Components): ModelInfo;
}

/**
 * Builds the context of a document. The model names of each path are read
 * once, on first use, rather than from every component for every reference.
 */
export function createTypeContext(
  components: Components | undefined,
  naming: ModelNaming,
): TypeContext {
  let namesByPath: Map<string, string[]> | undefined;
  return {
    components,
    modelOf: reference => naming.ofReference(reference, components),
    namesAt(path) {
      if (!namesByPath) {
        namesByPath = new Map();
        for (const key of Object.keys(components?.schemas ?? {})) {
          const model = naming.ofKey(key);
          let names = namesByPath.get(model.path);
          if (!names) {
            names = [];
            namesByPath.set(model.path, names);
          }
          names.push(model.name, `${model.name}EnumText`);
        }
      }
      return namesByPath.get(path) ?? [];
    },
  };
}

/** Where a type is written. */
export interface TypeScope {
  readonly context: TypeContext;
  /**
   * The declaration the type belongs to. An import never takes its name.
   * With a `path`, it is a model: the models of that path are declared beside
   * it, so a reference to one needs no import, and an import takes none of
   * their names. Without, every referenced model is imported.
   */
  readonly owner: { readonly name: string; readonly path?: string };
  /** The specifier the module imports a model by. */
  specifierOf(model: ModelInfo): string;
  /** The imports the module holds. */
  readonly imports: ImportLookup;
}

/**
 * Global names generated code relies on. A model imported under one of these
 * names would shadow the global, so the import is aliased instead: a model
 * named `Response` must not turn `Promise<Response>` into a promise of the
 * model.
 */
export const GLOBAL_TYPE_NAMES: readonly string[] = [
  'Array',
  'Blob',
  'Exclude',
  'FormData',
  'Partial',
  'Promise',
  'ReadonlyArray',
  'Record',
  'Response',
  'Symbol',
  'URLSearchParams',
];

/** The TypeScript types a primitive schema type resolves to. */
const PRIMITIVE_TYPE_NAMES = new Set(['string', 'number', 'boolean']);

/**
 * Keywords that describe a schema without constraining its instances.
 */
const ANNOTATION_KEYWORDS = new Set([
  '$comment',
  '$schema',
  'default',
  'deprecated',
  'description',
  'example',
  'examples',
  'externalDocs',
  'readOnly',
  'title',
  'writeOnly',
  'xml',
]);

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

/**
 * Tells whether a schema is the OpenAPI 3.0 idiom for a nullable reference:
 * `nullable: true` beside a composition of references alone, such as
 * `{nullable: true, allOf: [{$ref: X}]}`.
 *
 * Strictly, `nullable` only widens a sibling `type`, and a member that
 * requires an object rules null out; springdoc and Swagger nevertheless write
 * this form for a property that may be null, and mean `X | null`. An inline
 * member keeps the strict reading.
 */
function isNullableReference(schema: Schema): boolean {
  if (schema.nullable !== true || isEnum(schema) || !isComposition(schema)) {
    return false;
  }
  return [schema.allOf, schema.oneOf, schema.anyOf].every(
    members => members === undefined || members.every(isReference),
  );
}

function matchesLiteralType(value: unknown, schema: Schema): boolean {
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

/** An import as one resolution sees it: the module's, or one it added. */
interface ImportEntry {
  readonly moduleSpecifier: string;
  readonly name: string;
  alias?: string;
}

/**
 * One resolution: the scope, and the imports it has seen and asked for. It
 * starts from the module's imports and never changes them; what it asks for
 * is its result.
 */
class Resolution {
  private readonly entries: ImportEntry[];
  private readonly requested: ImportEntry[] = [];

  constructor(private readonly scope: TypeScope) {
    this.entries = scope.imports.entries().map(entry => ({ ...entry }));
  }

  get components(): Components | undefined {
    return this.scope.context.components;
  }

  /** The imports asked for, in the order first asked, with their aliases. */
  imports(): ImportRequest[] {
    return this.requested.map(({ moduleSpecifier, name, alias }) =>
      alias === undefined
        ? { moduleSpecifier, name }
        : { moduleSpecifier, name, alias },
    );
  }

  private request(entry: ImportEntry): void {
    if (!this.requested.includes(entry)) this.requested.push(entry);
  }

  /**
   * The name a reference goes by in the module: the model's own name when it
   * is declared beside the owner or imported as it is, else the alias of its
   * import. An import whose name the owner, a global, a model beside the
   * owner or another import already takes is aliased with leading `_`s.
   */
  reference(reference: Reference): string {
    const model = this.scope.context.modelOf(reference);
    const { owner } = this.scope;
    if (owner.path === model.path) return model.name;
    const moduleSpecifier = this.scope.specifierOf(model);
    let entry = this.entries.find(
      item =>
        item.moduleSpecifier === moduleSpecifier && item.name === model.name,
    );
    if (!entry) {
      entry = { moduleSpecifier, name: model.name };
      this.entries.push(entry);
    }
    this.request(entry);
    if (entry.alias) return entry.alias;
    const reservedNames = new Set([
      ...GLOBAL_TYPE_NAMES,
      owner.name,
      ...(owner.path === undefined
        ? []
        : this.scope.context.namesAt(owner.path)),
      ...this.entries
        .filter(item => item !== entry)
        .map(item => item.alias ?? item.name),
    ]);
    if (!reservedNames.has(model.name)) return model.name;
    let alias = `_${model.name}`;
    while (reservedNames.has(alias)) alias = `_${alias}`;
    entry.alias = alias;
    return alias;
  }

  additionalProperties(schema: Schema): string {
    if (
      schema.additionalProperties === false ||
      (schema.additionalProperties === undefined &&
        !schema.required?.some(
          name => !Object.hasOwn(schema.properties ?? {}, name),
        ))
    ) {
      return '';
    }

    if (
      schema.additionalProperties === true ||
      schema.additionalProperties === undefined
    ) {
      return '[key: string]: any';
    }

    return `[key: string]: ${this.additionalPropertyType(schema)}`;
  }

  additionalPropertyType(schema: Schema): string {
    return this.type(
      typeof schema.additionalProperties === 'object'
        ? schema.additionalProperties
        : {},
    );
  }

  requiredAdditionalPropertyType(schema: Schema): string {
    if (schema.additionalProperties === false) return 'never';
    if (typeof schema.additionalProperties === 'object') {
      return this.type(schema.additionalProperties);
    }
    return 'null | string | number | boolean | globalThis.Record<string, unknown> | readonly unknown[]';
  }

  private propertyDefinitions(schema: ObjectSchema): string[] {
    const { properties } = schema;
    return Object.entries(properties).map(([propName, propSchema]) => {
      const type = this.type(propSchema);
      const resolvedPropName =
        (isReadOnly(propSchema) ? 'readonly ' : '') +
        resolvePropertyName(propName);
      if (!isReference(propSchema)) {
        const jsDocDescriptions = schemaJSDoc(propSchema);
        const doc = jsDoc(jsDocDescriptions, '\n * ');
        if (doc) {
          return `
          /**
           * ${doc}
           */
          ${resolvedPropName}: ${type}
          `;
        }
      }
      return `${resolvedPropName}: ${type}`;
    });
  }

  private objectType(schema: Schema): string {
    const parts: string[] = [];
    if (isObject(schema)) {
      parts.push(...this.propertyDefinitions(schema));
    }

    for (const name of schema.required ?? []) {
      if (!Object.hasOwn(schema.properties ?? {}, name)) {
        parts.push(
          `${resolvePropertyName(name)}: ${this.requiredAdditionalPropertyType(schema)}`,
        );
      }
    }
    const mapType =
      isMap(schema) && getMapKeySchema(schema)
        ? this.mapType(schema)
        : requiresAdditionalPropertiesIntersection(schema, this.components)
          ? `globalThis.Record<string, ${this.additionalPropertyType(schema)}>`
          : undefined;
    const additionalProps = mapType ? '' : this.additionalProperties(schema);
    if (additionalProps) {
      parts.push(additionalProps);
    }

    if (parts.length === 0) {
      return 'globalThis.Record<string, any>';
    }

    const objectType = `{\n  ${parts.join(';\n  ')}; \n}`;
    return mapType ? `(${objectType} & ${mapType})` : objectType;
  }

  mapValueType(schema: MapSchema): string {
    if (
      schema.additionalProperties === undefined ||
      schema.additionalProperties === false ||
      schema.additionalProperties === true
    ) {
      return 'any';
    }
    return this.type(schema.additionalProperties);
  }

  private mapKeyType(schema: Schema): string {
    const mapKeySchema = getMapKeySchema(schema);
    if (!mapKeySchema) {
      return 'string';
    }
    return this.type(mapKeySchema);
  }

  private mapType(schema: MapSchema): string {
    const keyType = this.mapKeyType(schema);
    const valueType = this.mapValueType(schema);
    return `globalThis.Record<${keyType},${valueType}>`;
  }

  private objectConstrained(schema: Schema | Reference): boolean {
    const resolve = (member: Schema | Reference): Schema | undefined =>
      isReference(member)
        ? this.components && extractSchema(member, this.components)
        : member;
    const root = resolve(schema);
    const nodes = new Map<Schema, (Schema | undefined)[][]>();
    const constrained = new Set<Schema>();
    const pending = root ? [root] : [];
    for (const current of pending) {
      if (nodes.has(current)) continue;
      const groups = [current.allOf, current.oneOf, current.anyOf].map(
        members => members?.map(resolve) ?? [],
      );
      nodes.set(current, groups);
      for (const members of groups) {
        for (const member of members) {
          if (member && !nodes.has(member)) pending.push(member);
        }
      }
      if (
        current.type === 'object' ||
        current.type === 'null' ||
        (Array.isArray(current.type) &&
          current.type.length > 0 &&
          current.type.every(type => type === 'object' || type === 'null'))
      )
        constrained.add(current);
    }

    // ponytail: O(V * (V + E)); use a dependency worklist for very large graphs.
    let changed = true;
    while (changed) {
      changed = false;
      for (const [current, [allOf, oneOf, anyOf]] of nodes) {
        if (constrained.has(current)) continue;
        const isConstrained = (member: Schema | undefined) =>
          member !== undefined && constrained.has(member);
        if (
          allOf.some(isConstrained) ||
          [oneOf, anyOf].some(
            members => members.length > 0 && members.every(isConstrained),
          )
        ) {
          constrained.add(current);
          changed = true;
        }
      }
    }
    return root !== undefined && constrained.has(root);
  }

  type(schema: Schema | Reference): string {
    if (isReference(schema)) {
      return this.reference(schema);
    }
    if (Array.isArray(schema.type)) {
      return schema.type
        .map(type => {
          const resolved = this.type({ ...schema, type });
          return /[|&]/.test(resolved) ? `(${resolved})` : resolved;
        })
        .join(' | ');
    }
    if (isNullableReference(schema)) {
      // OpenAPI 3.0 writes a nullable reference as {nullable, allOf: [$ref]}.
      return `(${this.type({ ...schema, nullable: false })}) | null`;
    }
    if (isComposition(schema)) {
      const simple = this.simpleComposition(schema);
      if (simple !== undefined) return simple;
      const compositions = (['allOf', 'oneOf', 'anyOf'] as const).flatMap(
        keyword => {
          const schemas = schema[keyword];
          if (!schemas?.length) return [];
          const types = schemas.map(member => {
            const type = this.memberType(schema, keyword, member);
            return type === 'any'
              ? 'unknown'
              : /[|&]/.test(type)
                ? `(${type})`
                : type;
          });
          return [`(${types.join(keyword === 'allOf' ? ' & ' : ' | ')})`];
        },
      );
      const composed =
        compositions.length === 1
          ? compositions[0]
          : `(${compositions.join(' & ')})`;
      const base = {
        ...schema,
        oneOf: undefined,
        anyOf: undefined,
        allOf: undefined,
      };
      const baseType = this.type(base);
      const type =
        baseType === 'any' ? composed : `(${composed} & (${baseType}))`;
      // Weak object types can otherwise admit non-object branches through intersections.
      return this.objectConstrained(schema)
        ? `globalThis.Exclude<${type}, string | number | boolean | readonly unknown[]> & ({ readonly [globalThis.Symbol.iterator]?: never } | null)`
        : type;
    }
    if (schema.const !== undefined) {
      if (!matchesLiteralType(schema.const, schema)) return 'never';
      const literal = resolveLiteral(schema.const);
      const baseType = this.type({ ...schema, const: undefined });
      return baseType === 'any' ? literal : `(${literal}) & (${baseType})`;
    }
    if (schema.nullable && schema.type && !isEnum(schema)) {
      return `(${this.type({ ...schema, nullable: false })}) | null`;
    }
    if (isEnum(schema)) {
      const literal =
        schema.enum
          .filter(value => matchesLiteralType(value, schema))
          .map(value => resolveLiteral(value))
          .join(' | ') || 'never';
      const baseType = this.type({ ...schema, enum: undefined });
      // Every literal already matches its primitive type, so intersecting
      // with a bare primitive adds nothing but noise.
      return baseType === 'any' || PRIMITIVE_TYPE_NAMES.has(baseType)
        ? literal
        : `(${literal}) & (${baseType})`;
    }
    if (isMap(schema) && !schema.required?.length) {
      return this.mapType(schema);
    }

    if (schema.type === 'array') {
      return toArrayType(this.type(schema.items ?? {}));
    }
    if (schema.type === 'object') {
      return this.objectType(schema);
    }
    if (!schema.type) {
      if (
        schema.required?.length ||
        Object.keys(schema.properties ?? {}).length > 0
      ) {
        // Object keywords constrain object instances without rejecting other JSON types.
        const objectType = this.objectType({
          ...schema,
          type: 'object',
          additionalProperties: schema.additionalProperties ?? true,
        });
        return `(${objectType} | null | string | number | boolean | readonly unknown[])`;
      }
      return 'any';
    }
    return resolvePrimitiveType(schema.type);
  }

  /**
   * Resolves a composition of references alone - `allOf: [$ref]`,
   * `anyOf: [$ref, {type: null}]`, a discriminated `oneOf` - to the plain
   * union or intersection of its members.
   *
   * With no sibling keyword that constrains the instance, the members say
   * everything, and none of the guards the general form needs applies.
   *
   * @returns The type, or undefined when the composition is not that simple
   */
  private simpleComposition(schema: Schema): string | undefined {
    const keywords = (['allOf', 'oneOf', 'anyOf'] as const).filter(
      keyword => schema[keyword]?.length,
    );
    if (keywords.length !== 1) return undefined;
    const [keyword] = keywords;
    const constraining = Object.keys(schema).filter(
      key =>
        key !== keyword &&
        key !== 'discriminator' &&
        !key.startsWith('x-') &&
        !ANNOTATION_KEYWORDS.has(key),
    );
    if (constraining.length > 0) return undefined;
    const members: (Schema | Reference)[] = schema[keyword]!;
    const isNullMember = (member: Schema) =>
      keyword !== 'allOf' &&
      [member.type].flat().every(type => type === 'null') &&
      member.type !== undefined &&
      Object.keys(member).every(
        key => key === 'type' || ANNOTATION_KEYWORDS.has(key),
      );
    if (!members.every(member => isReference(member) || isNullMember(member))) {
      return undefined;
    }
    const types = members.map(member =>
      isReference(member) ? this.memberType(schema, keyword, member) : 'null',
    );
    return [...new Set(types)].join(keyword === 'allOf' ? ' & ' : ' | ');
  }

  /**
   * Resolves one member of a composition. A member of a `oneOf` or `anyOf`
   * that carries a `discriminator` is intersected with the literal the
   * discriminator property holds for it, so checking that property narrows
   * the union: `(Cat & { petType: 'cat' }) | (Dog & { petType: 'dog' })`.
   */
  private memberType(
    schema: Schema,
    keyword: 'allOf' | 'oneOf' | 'anyOf',
    member: Schema | Reference,
  ): string {
    const type = this.type(member);
    const discriminator = schema.discriminator;
    if (
      keyword === 'allOf' ||
      !discriminator?.propertyName ||
      !isReference(member)
    ) {
      return type;
    }
    const componentKey = extractComponentKey(member);
    const mapped = Object.entries(discriminator.mapping ?? {})
      .filter(([, target]) => target === member.$ref || target === componentKey)
      .map(([value]) => value);
    const literal = (mapped.length > 0 ? mapped : [componentKey])
      .map(value => quoteStringLiteral(value))
      .join(' | ');
    return `(${type} & { ${resolvePropertyName(discriminator.propertyName)}: ${literal} })`;
  }
}

function resolveWith(
  scope: TypeScope,
  resolve: (resolution: Resolution) => string,
): ResolvedType {
  const resolution = new Resolution(scope);
  const text = resolve(resolution);
  return { text, imports: resolution.imports() };
}

/**
 * The type a schema generates.
 *
 * @param schema - The schema, or a reference to a component
 * @param scope - Where the type is written
 * @returns The type expression and the imports it needs
 */
export function resolveType(
  schema: Schema | Reference,
  scope: TypeScope,
): ResolvedType {
  return resolveWith(scope, resolution => resolution.type(schema));
}

/**
 * The index signature an object schema's additional properties generate, as
 * `[key: string]: T`, or `''` when it admits none beyond the ones it names.
 */
export function resolveAdditionalProperties(
  schema: Schema,
  scope: TypeScope,
): ResolvedType {
  return resolveWith(scope, resolution =>
    resolution.additionalProperties(schema),
  );
}

/** The type of an object schema's additional properties; `any` when open. */
export function resolveAdditionalPropertyType(
  schema: Schema,
  scope: TypeScope,
): ResolvedType {
  return resolveWith(scope, resolution =>
    resolution.additionalPropertyType(schema),
  );
}

/**
 * The type of a property an object schema requires without declaring it: its
 * additional-property type, `never` when it admits none, else any JSON value.
 */
export function resolveRequiredAdditionalPropertyType(
  schema: Schema,
  scope: TypeScope,
): ResolvedType {
  return resolveWith(scope, resolution =>
    resolution.requiredAdditionalPropertyType(schema),
  );
}

/** The value type of a map schema; `any` when its values are open. */
export function resolveMapValueType(
  schema: MapSchema,
  scope: TypeScope,
): ResolvedType {
  return resolveWith(scope, resolution => resolution.mapValueType(schema));
}
