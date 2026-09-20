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
  acceptsNothing,
  addImportModelInfo,
  addMainSchemaJSDoc,
  addSchemaJSDoc,
  extractSchema,
  getEnumText,
  getMapKeySchema,
  isArray,
  isComposition,
  isEnum,
  isMap,
  isNullableSchema,
  isObject,
  isReadOnly,
  isReference,
  isWriteOnly,
  jsDoc,
  resolveEnumMemberName,
  resolvePrimitiveType,
  resolvePropertyName,
  schemaJSDoc,
  toArrayType,
} from '../utils';
import type { Generator } from '../generateContext';

/**
 * What a schema generates, as far as assignability to an index signature goes.
 *
 * A primitive carries its resolved TypeScript type; `object` and `array` need
 * no further detail, since neither is ever assignable to a primitive.
 */
type SchemaKind = 'object' | 'array' | { primitive: string };

/**
 * Classifies a schema, following references through the components.
 *
 * Returns undefined - undecided - for anything whose assignability cannot be
 * read off the schema: a composition, an enum, a const, a nullable schema, a
 * type union, a missing `type`, a reference that cannot be resolved and a
 * reference cycle.
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
  if (
    isComposition(schema) ||
    isEnum(schema) ||
    schema.const !== undefined ||
    schema.nullable ||
    schema.type === undefined ||
    Array.isArray(schema.type)
  ) {
    return undefined;
  }
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
 * different primitive is. Everything undecided stays in the interface: an enum
 * narrows the primitive it sits beside (`'a' | 'b'` against `string`) and a
 * composition may admit it (`null` against `Model | null`), so calling either a
 * clash would move a schema the interface expresses perfectly well.
 *
 * @param propSchema - The named property's schema
 * @param additionalProperties - The additional-property schema
 * @param components - The components a reference resolves against
 * @returns True when the property cannot be assignable to the index type
 */
function clashesWithIndexSignature(
  propSchema: Schema | Reference,
  additionalProperties: Schema | Reference,
  components?: Components,
): boolean {
  const indexKind = schemaKind(additionalProperties, components);
  if (typeof indexKind !== 'object') return false;
  const propertyKind = schemaKind(propSchema, components);
  if (propertyKind === undefined) return false;
  return (
    typeof propertyKind === 'string' ||
    propertyKind.primitive !== indexKind.primitive
  );
}

export class TypeGenerator implements Generator {
  constructor(
    private readonly modelInfo: ModelInfo,
    private readonly sourceFile: SourceFile,
    private readonly keySchema: KeySchema<Schema | Reference>,
    private readonly outputDir: string,
    private readonly components?: Components,
    /**
     * Treats non-nullable properties as required even when the document leaves
     * them out of `required`. Set for read-model schemas only, as classified by
     * SchemaUsageResolver.
     */
    private readonly nonNullRequired: boolean = false,
  ) {}

  /**
   * Resolves the property names to generate as required.
   *
   * Everything the document declares as required always is. Under
   * {@link nonNullRequired} every non-nullable property joins them, which
   * restores response properties an exporter dropped from `required` because
   * they carry a default value.
   *
   * `writeOnly` properties are left alone: they belong to requests, so a
   * response is entitled to omit them however their type reads. So are
   * properties no value can satisfy, which a response must omit rather than
   * always carry.
   *
   * @param schema - The object schema owning the properties
   * @returns The effective required property names
   */
  private requiredProperties(schema: Schema): Set<string> {
    const required = new Set(schema.required ?? []);
    if (!this.nonNullRequired) {
      return required;
    }
    for (const [propName, propSchema] of Object.entries(
      schema.properties ?? {},
    )) {
      if (
        !isWriteOnly(propSchema, this.components) &&
        !acceptsNothing(propSchema, this.components) &&
        !isNullableSchema(propSchema, this.components)
      ) {
        required.add(propName);
      }
    }
    return required;
  }

  generate(): void {
    const node = this.process();
    if (node) {
      addMainSchemaJSDoc(node, this.keySchema.schema, this.keySchema.key);
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
   * its index signature (TS2411). An optional property never is - its
   * `undefined` alone breaks the rule - so it always takes the intersection.
   *
   * A required property may or may not be, and only a clash that can be PROVEN
   * off the schemas moves it - see {@link clashesWithIndexSignature}. Anything
   * undecided keeps the interface, which is the only form that can reference
   * itself through an index signature: an alias reaching itself through
   * `Record` is circular (TS2456), which is what a dictionary of its own type
   * would generate. Erring towards the interface also means this rule never
   * breaks a schema that compiled before it.
   *
   * @param schema - The object schema to represent
   * @returns True when the schema needs the intersection form
   */
  private requiresAdditionalPropertiesIntersection(schema: Schema): boolean {
    const additionalProperties = schema.additionalProperties;
    if (typeof additionalProperties !== 'object') {
      return false;
    }
    const declaredRequired = new Set(schema.required ?? []);
    return Object.entries(schema.properties ?? {}).some(
      ([name, propSchema]) =>
        !declaredRequired.has(name) ||
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
    const required = this.requiredProperties(schema);
    return Object.entries(properties).map(([propName, propSchema]) => {
      const type = this.resolveType(propSchema);
      const resolvedPropName =
        (isReadOnly(propSchema) ? 'readonly ' : '') +
        resolvePropertyName(propName) +
        (required.has(propName) ? '' : '?');
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
    if (isComposition(schema)) {
      const compositions = (['allOf', 'oneOf', 'anyOf'] as const).flatMap(
        keyword => {
          const schemas = schema[keyword];
          if (!schemas?.length) return [];
          const types = schemas.map(member => {
            const type = this.resolveType(member);
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
      return baseType === 'any' ? literal : `(${literal}) & (${baseType})`;
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
      this.sourceFile.addEnum({
        name: this.modelInfo.name + 'EnumText',
        isExported: true,
        members: Object.entries(enumText).map(([name, text]) => {
          return {
            name: resolveEnumMemberName(name),
            initializer: this.resolveLiteral(text),
          };
        }),
      });
    }
    const stringValues = schema.enum.filter(
      (value): value is string => typeof value === 'string',
    );
    if (
      isComposition(schema) ||
      schema.const !== undefined ||
      (schema.type !== undefined && schema.type !== 'string') ||
      stringValues.length !== schema.enum.length
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
                      .write(`${resolveEnumMemberName(value)}: `)
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
      members: schema.enum
        .filter(value => typeof value === 'string')
        .map(value => ({
          name: resolveEnumMemberName(value),
          initializer: this.resolveLiteral(value),
        })),
    });
  }

  private addPropertyToInterface(
    interfaceDeclaration: InterfaceDeclaration,
    propName: string,
    propSchema: Schema | Reference,
    required: boolean = true,
  ): void {
    const propType = this.resolveType(propSchema);
    const resolvedPropName = resolvePropertyName(propName);
    let propertySignature = interfaceDeclaration.getProperty(resolvedPropName);
    if (propertySignature) {
      propertySignature.setType(propType);
      if (required) propertySignature.setHasQuestionToken(false);
    } else {
      propertySignature = interfaceDeclaration.addProperty({
        name: resolvedPropName,
        type: propType,
        isReadonly: isReadOnly(propSchema),
        hasQuestionToken: !required,
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
    const required = this.requiredProperties(schema);

    Object.entries(properties).forEach(([propName, propSchema]) => {
      this.addPropertyToInterface(
        interfaceDeclaration,
        propName,
        propSchema,
        required.has(propName),
      );
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
      type: `Array<${itemType}>`,
      isExported: true,
    });
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
