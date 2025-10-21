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

import { Reference, Schema } from '@ahoo-wang/fetcher-openapi';
import { InterfaceDeclaration, JSDocableNode, SourceFile } from 'ts-morph';

import { GenerateContext, Generator } from '../generateContext';
import {
  addImportModelInfo,
  addSchemaJSDoc, ArraySchema,
  CompositionSchema, EnumSchema,
  getModelFileName, isAllOf,
  isArray,
  isComposition,
  isEnum, isObject,
  isPrimitive,
  isReference,
  isUnion,
  KeySchema, ObjectSchema,
  pascalCase,
  resolvePrimitiveType,
  toArrayType, UnionSchema,
  upperSnakeCase,
} from '../utils';
import { ModelInfo, resolveModelInfo, resolveReferenceModelInfo } from './modelInfo';

/**
 * Generates TypeScript models from OpenAPI schemas.
 * Handles enum, object, union, and type alias generation.
 *
 * @property project - The ts-morph project instance
 * @property openAPI - The OpenAPI specification
 * @property outputDir - The output directory for generated files
 * @property contextAggregates - Map of aggregate definitions
 */
export class ModelGenerator implements Generator {
  constructor(public readonly context: GenerateContext) {
  }

  private getOrCreateSourceFile(modelInfo: ModelInfo): SourceFile {
    const fileName = getModelFileName(modelInfo);
    return this.context.getOrCreateSourceFile(fileName);
  }

  /**
   * Generates models for all schemas in the OpenAPI specification.
   * Skips schemas with keys starting with 'wow.'.
   *
   * @remarks
   * This method iterates through all schemas in the OpenAPI specification
   * and generates corresponding TypeScript models for each one.
   */
  generate() {
    const schemas = this.context.openAPI.components?.schemas;
    if (!schemas) {
      this.context.logger.info('No schemas found in OpenAPI specification');
      return;
    }
    const stateAggregatedTypeNames = this.stateAggregatedTypeNames();
    const keySchemas = this.filterSchemas(schemas, stateAggregatedTypeNames);
    this.context.logger.progress(`Generating models for ${keySchemas.length} schemas`);
    keySchemas.forEach((keySchema, index) => {
      this.context.logger.progressWithCount(
        index + 1,
        keySchemas.length,
        `Processing schema: ${keySchema.key}`,
        2,
      );
      this.generateKeyedSchema(keySchema);
    });
    this.context.logger.success('Model generation completed');
  }

  private filterSchemas(schemas: Record<string, Schema>, aggregatedTypeNames: Set<string>): KeySchema[] {
    return Object.entries(schemas)
      .map(([schemaKey, schema]) => ({
        key: schemaKey,
        schema,
      }))
      .filter(keySchema => !this.isWowSchema(keySchema.key, aggregatedTypeNames));
  }

  private isWowSchema(schemaKey: string, stateAggregatedTypeNames: Set<string>): boolean {
    if (
      schemaKey !== 'wow.api.query.PagedList'
      && schemaKey.startsWith('wow.api.query.')
      && (schemaKey.endsWith('PagedList'))) {
      return false;
    }

    if (schemaKey.startsWith('wow.')
      || schemaKey.endsWith('AggregatedCondition')
      || schemaKey.endsWith('AggregatedDomainEventStream')
      || schemaKey.endsWith('AggregatedDomainEventStreamPagedList')
      || schemaKey.endsWith('AggregatedDomainEventStreamServerSentEventNonNullData')
      || schemaKey.endsWith('AggregatedListQuery')
      || schemaKey.endsWith('AggregatedPagedQuery')
      || schemaKey.endsWith('AggregatedSingleQuery')
    ) {
      return true;
    }
    const modelInfo = resolveModelInfo(schemaKey);
    return stateAggregatedTypeNames.has(modelInfo.name);
  }

  private aggregatedSchemaSuffix = [
    'MaterializedSnapshot',
    'MaterializedSnapshotPagedList',
    'MaterializedSnapshotServerSentEventNonNullData',
    'PagedList',
    'ServerSentEventNonNullData',
    'Snapshot',
    'StateEvent',
  ];

  private stateAggregatedTypeNames() {
    const typeNames = new Set<string>;
    for (const aggregates of this.context.contextAggregates.values()) {
      for (const aggregate of aggregates) {
        this.aggregatedSchemaSuffix.forEach(
          suffix => {
            const modelInfo = resolveModelInfo(aggregate.state.key);
            const typeName = pascalCase(modelInfo.name) + suffix;
            typeNames.add(typeName);
          },
        );
      }
    }
    return typeNames;
  }

  /**
   * Generates a model for a specific schema key.
   * Processes enums, objects, unions, and type aliases in order.
   *
   * @param schemaKey - The key of the schema to generate
   * @param schema - The schema definition
   *
   * @remarks
   * The generation process follows this order:
   * 1. Enum processing
   * 2. Object processing
   * 3. Union processing
   * 4. Type alias processing
   */
  generateKeyedSchema({ key, schema }: KeySchema) {
    const modelInfo = resolveModelInfo(key);
    const sourceFile = this.getOrCreateSourceFile(modelInfo);
    const node = this.process(modelInfo, sourceFile, schema);
    if (node) {
      addSchemaJSDoc(node, schema);
    }
  }

  private resolveReference(currentModelInfo: ModelInfo, sourceFile: SourceFile, schema: Reference) {
    const refModelInfo = resolveReferenceModelInfo(schema);
    addImportModelInfo(
      currentModelInfo,
      sourceFile,
      this.context.outputDir,
      refModelInfo,
    );
    return refModelInfo;
  }

  private resolveAdditionalProperties(currentModelInfo: ModelInfo,
                                      sourceFile: SourceFile, schema: ObjectSchema): string {
    if (schema.additionalProperties === undefined || schema.additionalProperties === false) {
      return '';
    }

    if (schema.additionalProperties === true) {
      return '[key: string]: any';
    }

    const valueType = this.resolveType(currentModelInfo, sourceFile, schema.additionalProperties);
    return `[key: string]: ${valueType}`;
  }

  private resolvePropertyDefinitions(currentModelInfo: ModelInfo,
                                     sourceFile: SourceFile, schema: ObjectSchema): string {
    const { properties } = schema;
    const propStrings = Object.entries(properties).map(([propName, propSchema]) => {
      const type = this.resolveType(currentModelInfo, sourceFile, propSchema);
      return `${propName}: ${type}`;
    });

    return `{\n  ${propStrings.join(';\n  ')}\n}`;
  }

  private resolveObjectType(currentModelInfo: ModelInfo,
                            sourceFile: SourceFile, schema: ObjectSchema): string {
    const parts: string[] = [];

    if (schema.properties && Object.keys(schema.properties).length > 0) {
      const propertyDefs = this.resolvePropertyDefinitions(currentModelInfo, sourceFile, schema);
      parts.push(propertyDefs);
    }

    const additionalProps = this.resolveAdditionalProperties(currentModelInfo, sourceFile, schema);
    if (additionalProps) {
      parts.push(additionalProps);
    }

    if (parts.length === 0) {
      return schema.additionalProperties ? 'Record<string, any>' : '{}';
    }

    return parts.length === 1 ? parts[0] : `{ ${parts.join('; ')} }`;
  }

  private resolveType(currentModelInfo: ModelInfo,
                      sourceFile: SourceFile, schema: Schema | Reference): string {

    if (isReference(schema)) {
      return this.resolveReference(currentModelInfo, sourceFile, schema).name;
    }

    if (isEnum(schema)) {
      return schema.enum.map(val => `'${val}'`).join(' | ');
    }

    if (isComposition(schema)) {
      const schemas = schema.oneOf || schema.anyOf || schema.allOf || [];
      const types = schemas.map(s => this.resolveType(currentModelInfo, sourceFile, s));
      if (types.length === 1) {
        return types[0];
      }
      if (isAllOf(schema)) {
        return types.join(' & ');
      }
      return types.join(' | ');
    }

    if (isArray(schema)) {
      const itemType = this.resolveType(currentModelInfo, sourceFile, schema.items);
      return toArrayType(itemType);
    }
    if (isObject(schema)) {
      return this.resolveObjectType(currentModelInfo, sourceFile, schema);
    }
    if (!schema.type) {
      return 'any';
    }
    return resolvePrimitiveType(schema.type);
  }

  private processEnum(
    modelInfo: ModelInfo,
    sourceFile: SourceFile,
    schema: EnumSchema,
  ): JSDocableNode | undefined {
    return sourceFile.addEnum({
      name: modelInfo.name,
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
    currentModelInfo: ModelInfo,
    sourceFile: SourceFile,
    interfaceDeclaration: InterfaceDeclaration,
    propName: string,
    propSchema: Schema | Reference,
    isRequired: boolean,
  ): void {
    const property = interfaceDeclaration.addProperty({
      name: propName,
      type: this.resolveType(currentModelInfo, sourceFile, propSchema),
      hasQuestionToken: !isRequired,
    });
    if (!isReference(propSchema)) {
      addSchemaJSDoc(property, propSchema);
    }
  }

  private processInterface(
    modelInfo: ModelInfo,
    sourceFile: SourceFile,
    schema: ObjectSchema,
  ): JSDocableNode | undefined {
    const interfaceDeclaration = sourceFile.addInterface({
      name: modelInfo.name,
      isExported: true,
    });

    const properties = schema.properties || {};
    const required = new Set(schema.required || []);

    Object.entries(properties).forEach(([propName, propSchema]) => {
      const isRequired = required.has(propName);
      this.addPropertyToInterface(
        modelInfo,
        sourceFile,
        interfaceDeclaration,
        propName,
        propSchema,
        isRequired,
      );
    });

    if (schema.additionalProperties) {
      const indexSignature = interfaceDeclaration.addIndexSignature({
        keyName: 'key',
        keyType: 'string',
        returnType: this.resolveType(
          modelInfo,
          sourceFile,
          schema.additionalProperties === true
            ? {}
            : schema.additionalProperties as Schema | Reference,
        ),
      });
      indexSignature.addJsDoc('Additional properties');
    }
    return interfaceDeclaration;
  }

  private processArray(
    modelInfo: ModelInfo,
    sourceFile: SourceFile,
    schema: ArraySchema,
  ): JSDocableNode | undefined {
    const itemType = this.resolveType(modelInfo, sourceFile, schema.items);
    return sourceFile.addTypeAlias({
      name: modelInfo.name,
      type: `Array<${itemType}>`,
      isExported: true,
    });
  }

  private processComposition(
    modelInfo: ModelInfo,
    sourceFile: SourceFile,
    schema: CompositionSchema,
  ): JSDocableNode | undefined {
    return sourceFile.addTypeAlias({
      name: modelInfo.name,
      type: this.resolveType(modelInfo, sourceFile, schema),
      isExported: true,
    });
  }

  private processTypeAlias(
    modelInfo: ModelInfo,
    sourceFile: SourceFile,
    schema: Schema,
  ): JSDocableNode | undefined {
    return sourceFile.addTypeAlias({
      name: modelInfo.name,
      type: this.resolveType(modelInfo, sourceFile, schema),
      isExported: true,
    });
  }

  private process(
    modelInfo: ModelInfo,
    sourceFile: SourceFile,
    schema: Schema,
  ): JSDocableNode | undefined {
    if (isEnum(schema)) {
      return this.processEnum(modelInfo, sourceFile, schema);
    }
    if (isObject(schema)) {
      return this.processInterface(modelInfo, sourceFile, schema);
    }
    if (isArray(schema)) {
      return this.processArray(modelInfo, sourceFile, schema);
    }
    if (isComposition(schema)) {
      return this.processComposition(modelInfo, sourceFile, schema);
    }
    return this.processTypeAlias(modelInfo, sourceFile, schema);
  }
}
