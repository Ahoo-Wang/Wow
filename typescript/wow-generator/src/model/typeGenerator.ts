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

import { ModelInfo, resolveReferenceModelInfo } from './modelInfo';
import { InterfaceDeclaration, JSDocableNode, SourceFile } from 'ts-morph';
import { Reference, Schema } from '@ahoo-wang/fetcher-openapi';
import {
  addImportModelInfo,
  addSchemaJSDoc,
  AllOfSchema,
  ArraySchema,
  CompositionSchema,
  EnumSchema,
  isAllOf,
  isArray,
  isComposition,
  isEnum,
  isMap,
  isObject,
  isReference,
  jsDoc,
  KeySchema,
  MapSchema,
  ObjectSchema,
  resolvePrimitiveType,
  schemaJSDoc,
  toArrayType,
  upperSnakeCase,
} from '../utils';
import { Generator } from '../generateContext';

export class TypeGenerator implements Generator {
  constructor(
    private readonly modelInfo: ModelInfo,
    private readonly sourceFile: SourceFile,
    private readonly keySchema: KeySchema,
    private readonly outputDir: string,
  ) {
  }

  generate(): void {
    const node = this.process();
    if (node) {
      addSchemaJSDoc(node, this.keySchema.schema, this.keySchema.key);
    }
  }

  private process(): JSDocableNode | undefined {
    const { schema } = this.keySchema;
    if (isEnum(schema)) {
      return this.processEnum(schema);
    }
    if (isObject(schema)) {
      return this.processInterface(schema);
    }
    if (isArray(schema)) {
      return this.processArray(schema);
    }
    if (isAllOf(schema)) {
      return this.processIntersection(schema);
    }
    if (isComposition(schema)) {
      return this.processComposition(schema);
    }
    return this.processTypeAlias(schema);
  }

  private resolveReference(schema: Reference) {
    const refModelInfo = resolveReferenceModelInfo(schema);
    addImportModelInfo(
      this.modelInfo,
      this.sourceFile,
      this.outputDir,
      refModelInfo,
    );
    return refModelInfo;
  }

  private resolveAdditionalProperties(schema: Schema): string {
    if (
      schema.additionalProperties === undefined ||
      schema.additionalProperties === false
    ) {
      return '';
    }

    if (schema.additionalProperties === true) {
      return '[key: string]: any';
    }

    const valueType = this.resolveType(
      schema.additionalProperties,
    );
    return `[key: string]: ${valueType}`;
  }

  private resolvePropertyDefinitions(schema: ObjectSchema): string[] {
    const { properties } = schema;
    return Object.entries(properties).map(([propName, propSchema]) => {
      const type = this.resolveType(propSchema);
      if (!isReference(propSchema)) {
        const jsDocDescriptions = schemaJSDoc(propSchema);
        const doc = jsDoc(jsDocDescriptions, '\n * ');
        if (doc) {
          return `
          /**
           * ${doc}
           */
          ${propName}: ${type}
          `;
        }
      }
      return `${propName}: ${type}`;
    });
  }

  private resolveObjectType(schema: ObjectSchema): string {
    const parts: string[] = [];

    const propertyDefs = this.resolvePropertyDefinitions(schema);
    parts.push(...propertyDefs);

    const additionalProps = this.resolveAdditionalProperties(schema);
    if (additionalProps) {
      parts.push(additionalProps);
    }

    if (parts.length === 0) {
      return 'Record<string, any>';
    }

    return `{\n  ${parts.join(';\n')} \n}`;
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

  resolveType(schema: Schema | Reference): string {
    if (isReference(schema)) {
      return this.resolveReference(schema).name;
    }
    if (isMap(schema)) {
      const valueType = this.resolveMapValueType(schema);
      return `Record<string,${valueType}>`;
    }
    if (schema.const) {
      return `'${schema.const}'`;
    }
    if (isEnum(schema)) {
      return schema.enum.map(val => `'${val}'`).join(' | ');
    }

    if (isComposition(schema)) {
      const schemas = schema.oneOf || schema.anyOf || schema.allOf || [];
      const types = schemas.map(s => this.resolveType(s));
      const separator = isAllOf(schema) ? ' & ' : ' | ';
      return `(${types.join(separator)})`;
    }

    if (isArray(schema)) {
      const itemType = this.resolveType(schema.items);
      return toArrayType(itemType);
    }
    if (isObject(schema)) {
      return this.resolveObjectType(schema);
    }
    if (!schema.type) {
      return 'any';
    }
    return resolvePrimitiveType(schema.type);
  }

  private processEnum(schema: EnumSchema): JSDocableNode | undefined {
    return this.sourceFile.addEnum({
      name: this.modelInfo.name,
      isExported: true,
      members: schema.enum
        .filter(value => typeof value === 'string' && value.length > 0)
        .map(value => ({
          name: upperSnakeCase(value),
          initializer: `'${value}'`,
        })),
    });
  }

  private addPropertyToInterface(
    interfaceDeclaration: InterfaceDeclaration,
    propName: string,
    propSchema: Schema | Reference,
  ): void {
    const propType = this.resolveType(propSchema);
    let propertySignature = interfaceDeclaration.getProperty(propName);
    if (propertySignature) {
      propertySignature.setType(propType);
    } else {
      propertySignature = interfaceDeclaration.addProperty({
        name: propName,
        type: propType,
      });
    }
    if (!isReference(propSchema)) {
      addSchemaJSDoc(propertySignature, propSchema);
    }
  }

  private processInterface(schema: ObjectSchema): JSDocableNode | undefined {
    const interfaceDeclaration = this.sourceFile.addInterface({
      name: this.modelInfo.name,
      isExported: true,
    });

    const properties = schema.properties || {};

    Object.entries(properties).forEach(([propName, propSchema]) => {
      this.addPropertyToInterface(interfaceDeclaration, propName, propSchema);
    });

    if (schema.additionalProperties) {
      const indexSignature = interfaceDeclaration.addIndexSignature({
        keyName: 'key',
        keyType: 'string',
        returnType: this.resolveType(
          schema.additionalProperties === true
            ? {}
            : (schema.additionalProperties as Schema | Reference),
        ),
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

  private processIntersection(schema: AllOfSchema): JSDocableNode | undefined {
    const interfaceDeclaration = this.sourceFile.addInterface({
      name: this.modelInfo.name,
      isExported: true,
    });
    schema.allOf.forEach(allOfSchema => {
      if (isReference(allOfSchema)) {
        const resolvedType = this.resolveType(allOfSchema);
        interfaceDeclaration.addExtends(resolvedType);
        return;
      }
      if (isObject(allOfSchema)) {
        Object.entries(allOfSchema.properties).forEach(
          ([propName, propSchema]) => {
            this.addPropertyToInterface(
              interfaceDeclaration,
              propName,
              propSchema,
            );
          },
        );
      }
    });

    return interfaceDeclaration;
  }

  private processTypeAlias(schema: Schema): JSDocableNode | undefined {
    return this.sourceFile.addTypeAlias({
      name: this.modelInfo.name,
      type: this.resolveType(schema),
      isExported: true,
    });
  }
}
