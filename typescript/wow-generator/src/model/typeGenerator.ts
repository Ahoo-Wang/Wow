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
  extractSchema,
  getEnumText,
  getMapKeySchema,
  isArray,
  isComposition,
  isEnum,
  isMap,
  isObject,
  isReadOnly,
  isReference,
  jsDoc,
  resolveEnumMemberName,
  resolvePrimitiveType,
  resolvePropertyName,
  schemaJSDoc,
  toArrayType,
} from '../utils';
import type { Generator } from '../generateContext';

export class TypeGenerator implements Generator {
  constructor(
    private readonly modelInfo: ModelInfo,
    private readonly sourceFile: SourceFile,
    private readonly keySchema: KeySchema<Schema | Reference>,
    private readonly outputDir: string,
    private readonly components?: Components,
  ) {}

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

  private requiresAdditionalPropertiesIntersection(schema: Schema): boolean {
    return (
      typeof schema.additionalProperties === 'object' &&
      Object.keys(schema.properties ?? {}).some(
        name => !schema.required?.includes(name),
      )
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
        resolvePropertyName(propName) +
        (schema.required?.includes(propName) ? '' : '?');
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

    Object.entries(properties).forEach(([propName, propSchema]) => {
      this.addPropertyToInterface(
        interfaceDeclaration,
        propName,
        propSchema,
        schema.required?.includes(propName) ?? false,
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
