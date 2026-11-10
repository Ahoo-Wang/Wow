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
import { jsDoc, schemaJSDoc } from '../emit/jsdoc';
import { quoteStringLiteral, resolvePropertyName } from '../naming/naming';
import { extractComponentKey, extractSchema } from '../openapi/components';
import { isReference } from '../openapi/references';
import type { MapSchema, ObjectSchema } from '../openapi/schemas';
import {
  getMapKeySchema,
  isComposition,
  isEnum,
  isMap,
  isObject,
  isReadOnly,
  resolvePrimitiveType,
  toArrayType,
} from '../openapi/schemas';
import { requiresAdditionalPropertiesIntersection } from './indexSignature';
import { matchesLiteralType, resolveLiteral } from './literalTypes';
import type { ImportRequest, ResolvedType, TypeScope } from './typeContext';
import { GLOBAL_TYPE_NAMES } from './typeContext';

/**
 * Schema → TypeScript type expression, as pure functions.
 *
 * Every entry point takes a schema and a {@link TypeScope} - where the type
 * is written - and returns a {@link ResolvedType}: the type's text, and the
 * imports the module needs for it. Nothing is written; the caller applies the
 * imports to its module (`ImportRegistry.apply`) before it resolves the next
 * type, so a later reference sees the names and aliases an earlier one took.
 */

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
