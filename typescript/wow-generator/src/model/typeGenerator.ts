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

import type { ModelInfo } from './modelInfo';
import { resolveModelInfo, resolveReferenceModelInfo } from './modelInfo';
import type { InterfaceDeclaration, JSDocableNode, SourceFile } from 'ts-morph';
import { CodeBlockWriter, VariableDeclarationKind } from 'ts-morph';
import type { Components, Reference, Schema } from '@ahoo-wang/fetcher-openapi';
import type {
  ArraySchema,
  CompositionSchema,
  EnumSchema,
  KeySchema,
  MapSchema,
  ObjectSchema,
} from '../utils';
import {
  addImportModelInfo,
  addMainSchemaJSDoc,
  addSchemaJSDoc,
  enumMemberKey,
  extractComponentKey,
  extractSchema,
  getEnumText,
  getMapKeySchema,
  isAllOf,
  isArray,
  isComposition,
  isEnum,
  isMap,
  isObject,
  isReadOnly,
  isReference,
  jsDoc,
  quoteStringLiteral,
  resolvePrimitiveType,
  resolvePropertyName,
  schemaJSDoc,
  toArrayType,
} from '../utils';
import type { Generator } from '../generateContext';
import type { SchemaDocs } from '../api/options';

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
 * Names the members of an enum. Each value takes its UPPER_SNAKE_CASE form;
 * when another value already took that form - `in-progress`, `IN_PROGRESS`
 * and `inProgress` all give `IN_PROGRESS` - it takes its own value as a
 * quoted member name, or else a numbered form.
 *
 * @param values - The distinct values, in document order
 * @returns The member name of each value, quoted where it has to be
 */
export function uniqueEnumMemberNames(
  values: readonly string[],
): Map<string, string> {
  const used = new Set<string>();
  const names = new Map<string, string>();
  for (const value of values) {
    const preferred = enumMemberKey(value);
    const candidates = [preferred];
    // A numeric name cannot name an enum member, even quoted.
    if (!/^\d/.test(value)) candidates.push(value);
    let key = candidates.find(candidate => !used.has(candidate));
    for (let index = 2; key === undefined; index++) {
      if (!used.has(`${preferred}_${index}`)) key = `${preferred}_${index}`;
    }
    used.add(key);
    names.set(value, resolvePropertyName(key));
  }
  return names;
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

export class TypeGenerator implements Generator {
  constructor(
    private readonly modelInfo: ModelInfo,
    /** The file the types are written into, which also receives their imports. */
    readonly sourceFile: SourceFile,
    private readonly keySchema: KeySchema<Schema | Reference>,
    private readonly outputDir: string,
    private readonly components?: Components,
    private readonly schemaDocs: SchemaDocs = 'summary',
  ) {}

  generate(): void {
    const node = this.process();
    if (node) {
      addMainSchemaJSDoc(
        node,
        this.keySchema.schema,
        this.keySchema.key,
        this.schemaDocs === 'full',
      );
    }
  }

  private process(): JSDocableNode | undefined {
    const { schema } = this.keySchema;
    if (isReference(schema)) {
      return this.processTypeAlias(schema);
    }
    if (isEnum(schema)) {
      return this.processEnum(schema);
    }
    if (schema.const !== undefined) {
      return this.processTypeAlias(schema);
    }
    if (
      (schema.nullable && schema.type) ||
      (isComposition(schema) &&
        (schema.type || schema.properties || schema.required))
    ) {
      return this.processTypeAlias(schema);
    }
    if (isObject(schema)) {
      return this.processInterface(schema);
    }
    if (
      isMap(schema) &&
      typeof schema.additionalProperties === 'object' &&
      !getMapKeySchema(schema) &&
      !schema.required?.length
    ) {
      return this.processIndexSignature(schema);
    }
    if (isArray(schema)) {
      return this.processArray(schema);
    }
    if (isComposition(schema)) {
      return this.processComposition(schema);
    }
    return this.processTypeAlias(schema);
  }

  private resolveReference(schema: Reference) {
    const refModelInfo = resolveReferenceModelInfo(schema, this.components);
    const declaration = addImportModelInfo(
      this.modelInfo,
      this.sourceFile,
      this.outputDir,
      refModelInfo,
    );
    const namedImport = declaration
      ?.getNamedImports()
      .find(item => item.getName() === refModelInfo.name);
    if (!namedImport) return refModelInfo;
    const existingAlias = namedImport.getAliasNode()?.getText();
    if (existingAlias) return { ...refModelInfo, name: existingAlias };

    const reservedNames = new Set([
      ...GLOBAL_TYPE_NAMES,
      this.modelInfo.name,
      ...Object.keys(this.components?.schemas ?? {})
        .map(key => resolveModelInfo(key))
        .filter(model => model.path === this.modelInfo.path)
        .flatMap(model => [model.name, `${model.name}EnumText`]),
      ...this.sourceFile
        .getImportDeclarations()
        .flatMap(item => item.getNamedImports())
        .filter(item => item !== namedImport)
        .map(item => item.getAliasNode()?.getText() ?? item.getName()),
    ]);
    if (reservedNames.has(refModelInfo.name)) {
      let alias = `_${refModelInfo.name}`;
      while (reservedNames.has(alias)) alias = `_${alias}`;
      namedImport.setAlias(alias);
      return { ...refModelInfo, name: alias };
    }
    return refModelInfo;
  }

  private resolveAdditionalProperties(schema: Schema): string {
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

    return `[key: string]: ${this.resolveAdditionalPropertyType(schema)}`;
  }

  private resolveAdditionalPropertyType(schema: Schema): string {
    return this.resolveType(
      typeof schema.additionalProperties === 'object'
        ? schema.additionalProperties
        : {},
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
   * @returns True when the schema needs the intersection form
   */
  private requiresAdditionalPropertiesIntersection(schema: Schema): boolean {
    const additionalProperties = schema.additionalProperties;
    if (typeof additionalProperties !== 'object') {
      return false;
    }
    return Object.values(schema.properties ?? {}).some(propSchema =>
      clashesWithIndexSignature(
        propSchema,
        additionalProperties,
        this.components,
      ),
    );
  }

  private resolveRequiredAdditionalPropertyType(schema: Schema): string {
    if (schema.additionalProperties === false) return 'never';
    if (typeof schema.additionalProperties === 'object') {
      return this.resolveType(schema.additionalProperties);
    }
    return 'null | string | number | boolean | globalThis.Record<string, unknown> | readonly unknown[]';
  }

  private resolvePropertyDefinitions(schema: ObjectSchema): string[] {
    const { properties } = schema;
    return Object.entries(properties).map(([propName, propSchema]) => {
      const type = this.resolveType(propSchema);
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

  private resolveObjectType(schema: Schema): string {
    const parts: string[] = [];
    if (isObject(schema)) {
      const propertyDefs = this.resolvePropertyDefinitions(schema);
      parts.push(...propertyDefs);
    }

    for (const name of schema.required ?? []) {
      if (!Object.hasOwn(schema.properties ?? {}, name)) {
        parts.push(
          `${resolvePropertyName(name)}: ${this.resolveRequiredAdditionalPropertyType(schema)}`,
        );
      }
    }
    const mapType =
      isMap(schema) && getMapKeySchema(schema)
        ? this.resolveMapType(schema)
        : this.requiresAdditionalPropertiesIntersection(schema)
          ? `globalThis.Record<string, ${this.resolveAdditionalPropertyType(schema)}>`
          : undefined;
    const additionalProps = mapType
      ? ''
      : this.resolveAdditionalProperties(schema);
    if (additionalProps) {
      parts.push(additionalProps);
    }

    if (parts.length === 0) {
      return 'globalThis.Record<string, any>';
    }

    const objectType = `{\n  ${parts.join(';\n  ')}; \n}`;
    return mapType ? `(${objectType} & ${mapType})` : objectType;
  }

  private resolveMapValueType(schema: MapSchema): string {
    if (
      schema.additionalProperties === undefined ||
      schema.additionalProperties === false ||
      schema.additionalProperties === true
    ) {
      return 'any';
    }
    return this.resolveType(schema.additionalProperties);
  }

  private resolveMapKeyType(schema: Schema): string {
    const mapKeySchema = getMapKeySchema(schema);
    if (!mapKeySchema) {
      return 'string';
    }
    return this.resolveType(mapKeySchema);
  }

  private resolveMapType(schema: MapSchema): string {
    const keyType = this.resolveMapKeyType(schema);
    const valueType = this.resolveMapValueType(schema);
    return `globalThis.Record<${keyType},${valueType}>`;
  }

  private resolveCompositionConstraints(schema: Schema | Reference): {
    objectConstrained: boolean;
  } {
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
    return { objectConstrained: root !== undefined && constrained.has(root) };
  }

  resolveType(schema: Schema | Reference): string {
    if (isReference(schema)) {
      return this.resolveReference(schema).name;
    }
    if (Array.isArray(schema.type)) {
      return schema.type
        .map(type => {
          const resolved = this.resolveType({ ...schema, type });
          return /[|&]/.test(resolved) ? `(${resolved})` : resolved;
        })
        .join(' | ');
    }
    if (isNullableReference(schema)) {
      // OpenAPI 3.0 writes a nullable reference as {nullable, allOf: [$ref]}.
      return `(${this.resolveType({ ...schema, nullable: false })}) | null`;
    }
    if (isComposition(schema)) {
      const simple = this.resolveSimpleComposition(schema);
      if (simple !== undefined) return simple;
      const compositions = (['allOf', 'oneOf', 'anyOf'] as const).flatMap(
        keyword => {
          const schemas = schema[keyword];
          if (!schemas?.length) return [];
          const types = schemas.map(member => {
            const type = this.resolveMemberType(schema, keyword, member);
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
      const baseType = this.resolveType(base);
      const type =
        baseType === 'any' ? composed : `(${composed} & (${baseType}))`;
      const constraints = this.resolveCompositionConstraints(schema);
      // Weak object types can otherwise admit non-object branches through intersections.
      return constraints.objectConstrained
        ? `globalThis.Exclude<${type}, string | number | boolean | readonly unknown[]> & ({ readonly [globalThis.Symbol.iterator]?: never } | null)`
        : type;
    }
    if (schema.const !== undefined) {
      if (!this.matchesLiteralType(schema.const, schema)) return 'never';
      const literal = this.resolveLiteral(schema.const);
      const baseType = this.resolveType({ ...schema, const: undefined });
      return baseType === 'any' ? literal : `(${literal}) & (${baseType})`;
    }
    if (schema.nullable && schema.type && !isEnum(schema)) {
      return `(${this.resolveType({ ...schema, nullable: false })}) | null`;
    }
    if (isEnum(schema)) {
      const literal =
        schema.enum
          .filter(value => this.matchesLiteralType(value, schema))
          .map(value => this.resolveLiteral(value))
          .join(' | ') || 'never';
      const baseType = this.resolveType({ ...schema, enum: undefined });
      // Every literal already matches its primitive type, so intersecting
      // with a bare primitive adds nothing but noise.
      return baseType === 'any' || PRIMITIVE_TYPE_NAMES.has(baseType)
        ? literal
        : `(${literal}) & (${baseType})`;
    }
    if (isMap(schema) && !schema.required?.length) {
      return this.resolveMapType(schema);
    }

    if (schema.type === 'array') {
      const itemType = this.resolveType(schema.items ?? {});
      return toArrayType(itemType);
    }
    if (schema.type === 'object') {
      return this.resolveObjectType(schema);
    }
    if (!schema.type) {
      if (
        schema.required?.length ||
        Object.keys(schema.properties ?? {}).length > 0
      ) {
        // Object keywords constrain object instances without rejecting other JSON types.
        const objectType = this.resolveObjectType({
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
  private resolveSimpleComposition(schema: Schema): string | undefined {
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
      isReference(member)
        ? this.resolveMemberType(schema, keyword, member)
        : 'null',
    );
    return [...new Set(types)].join(keyword === 'allOf' ? ' & ' : ' | ');
  }

  /**
   * Resolves one member of a composition. A member of a `oneOf` or `anyOf`
   * that carries a `discriminator` is intersected with the literal the
   * discriminator property holds for it, so checking that property narrows
   * the union: `(Cat & { petType: 'cat' }) | (Dog & { petType: 'dog' })`.
   */
  private resolveMemberType(
    schema: Schema,
    keyword: 'allOf' | 'oneOf' | 'anyOf',
    member: Schema | Reference,
  ): string {
    const type = this.resolveType(member);
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

  private matchesLiteralType(value: unknown, schema: Schema): boolean {
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

  private resolveLiteral(value: unknown): string {
    if (typeof value === 'string') {
      return new CodeBlockWriter({ useSingleQuote: true })
        .quote(value)
        .toString();
    }
    if (Array.isArray(value)) {
      return `[${value.map(item => this.resolveLiteral(item)).join(', ')}]`;
    }
    if (value !== null && typeof value === 'object') {
      const properties = Object.entries(value).map(
        ([name, item]) =>
          `${resolvePropertyName(name)}: ${this.resolveLiteral(item)}`,
      );
      return properties.length
        ? `{ ${properties.join('; ')}; readonly [globalThis.Symbol.iterator]?: never }`
        : 'globalThis.Record<string, never>';
    }
    return JSON.stringify(value) ?? 'never';
  }

  private processEnum(schema: EnumSchema): JSDocableNode | undefined {
    const enumText = getEnumText(schema);
    if (enumText) {
      const textNames = uniqueEnumMemberNames(Object.keys(enumText));
      this.sourceFile.addEnum({
        name: this.modelInfo.name + 'EnumText',
        isExported: true,
        members: [...textNames].map(([name, memberName]) => {
          return {
            name: memberName,
            initializer: this.resolveLiteral(enumText[name]),
          };
        }),
      });
    }
    const stringValues = [
      ...new Set(
        schema.enum.filter(
          (value): value is string => typeof value === 'string',
        ),
      ),
    ];
    const memberNames = uniqueEnumMemberNames(stringValues);
    if (
      isComposition(schema) ||
      schema.const !== undefined ||
      (schema.type !== undefined && schema.type !== 'string') ||
      schema.enum.some(value => typeof value !== 'string')
    ) {
      if (stringValues.length) {
        this.sourceFile.addVariableStatement({
          declarationKind: VariableDeclarationKind.Const,
          isExported: true,
          declarations: [
            {
              name: this.modelInfo.name,
              initializer: writer => {
                writer.inlineBlock(() => {
                  for (const value of stringValues) {
                    writer
                      .write(`${memberNames.get(value)}: `)
                      .quote(value)
                      .write(',')
                      .newLine();
                  }
                });
                writer.write(' as const');
              },
            },
          ],
        });
      }
      return this.processTypeAlias(schema);
    }
    return this.sourceFile.addEnum({
      name: this.modelInfo.name,
      isExported: true,
      members: stringValues.map(value => ({
        name: memberNames.get(value)!,
        initializer: this.resolveLiteral(value),
      })),
    });
  }

  private addPropertyToInterface(
    interfaceDeclaration: InterfaceDeclaration,
    propName: string,
    propSchema: Schema | Reference,
  ): void {
    const propType = this.resolveType(propSchema);
    const resolvedPropName = resolvePropertyName(propName);
    let propertySignature = interfaceDeclaration.getProperty(resolvedPropName);
    if (propertySignature) {
      propertySignature.setType(propType);
      propertySignature.setHasQuestionToken(false);
    } else {
      propertySignature = interfaceDeclaration.addProperty({
        name: resolvedPropName,
        type: propType,
        isReadonly: isReadOnly(propSchema),
      });
    }
    addSchemaJSDoc(propertySignature, propSchema);
  }

  private processInterface(schema: ObjectSchema): JSDocableNode | undefined {
    if (this.requiresAdditionalPropertiesIntersection(schema)) {
      return this.processTypeAlias(schema);
    }
    const interfaceDeclaration = this.sourceFile.addInterface({
      name: this.modelInfo.name,
      isExported: true,
    });

    const properties = schema.properties || {};

    Object.entries(properties).forEach(([propName, propSchema]) => {
      this.addPropertyToInterface(interfaceDeclaration, propName, propSchema);
    });

    for (const name of schema.required ?? []) {
      if (!Object.hasOwn(properties, name)) {
        interfaceDeclaration.addProperty({
          name: resolvePropertyName(name),
          type: this.resolveRequiredAdditionalPropertyType(schema),
        });
      }
    }

    if (this.resolveAdditionalProperties(schema)) {
      const indexSignature = interfaceDeclaration.addIndexSignature({
        keyName: 'key',
        keyType: 'string',
        returnType: this.resolveAdditionalPropertyType(schema),
      });
      indexSignature.addJsDoc('Additional properties');
    }
    return interfaceDeclaration;
  }

  private processArray(schema: ArraySchema): JSDocableNode | undefined {
    const itemType = this.resolveType(schema.items);
    return this.sourceFile.addTypeAlias({
      name: this.modelInfo.name,
      type: toArrayType(itemType),
      isExported: true,
    });
  }

  /**
   * A string-keyed map is an interface with an index signature rather than an
   * alias of `Record`: an alias may not reference itself through `Record`
   * (TS2456), and a map of its own type - a tree of dictionaries - does.
   */
  private processIndexSignature(schema: MapSchema): JSDocableNode | undefined {
    const interfaceDeclaration = this.sourceFile.addInterface({
      name: this.modelInfo.name,
      isExported: true,
    });
    interfaceDeclaration.addIndexSignature({
      keyName: 'key',
      keyType: 'string',
      returnType: this.resolveMapValueType(schema),
    });
    return interfaceDeclaration;
  }

  private processComposition(
    schema: CompositionSchema,
  ): JSDocableNode | undefined {
    return this.sourceFile.addTypeAlias({
      name: this.modelInfo.name,
      type: this.resolveType(schema),
      isExported: true,
    });
  }

  private processTypeAlias(
    schema: Schema | Reference,
  ): JSDocableNode | undefined {
    return this.sourceFile.addTypeAlias({
      name: this.modelInfo.name,
      type: this.resolveType(schema),
      isExported: true,
    });
  }
}
