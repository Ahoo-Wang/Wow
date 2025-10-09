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
  addSchemaJSDoc,
  CompositionSchema,
  getModelFileName,
  isArray,
  isComposition,
  isEnum,
  isPrimitive,
  isReference,
  isUnion,
  KeySchema,
  pascalCase,
  resolvePrimitiveType,
  toArrayType,
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

  private process(
    modelInfo: ModelInfo,
    sourceFile: SourceFile,
    schema: Schema,
  ): JSDocableNode | undefined {
    if (isEnum(schema)) {
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
    if (isArray(schema)) {
      if (isReference(schema.items)) {
        const refModelInfo = resolveReferenceModelInfo(schema.items);
        addImportModelInfo(
          modelInfo,
          sourceFile,
          this.context.outputDir,
          refModelInfo,
        );
        return sourceFile.addTypeAlias({
          name: modelInfo.name,
          type: toArrayType(refModelInfo.name),
          isExported: true,
        });
      }
    }

    const interfaceDeclaration = sourceFile.addInterface({
      name: modelInfo.name,
      isExported: true,
    });
    if (schema.type === 'object' && schema.properties) {
      return this.processInterface(
        sourceFile,
        modelInfo,
        schema,
        interfaceDeclaration,
      );
    }


    if (isComposition(schema)) {
      const compositionTypes = schema.anyOf || schema.oneOf || schema.allOf;
      compositionTypes!.forEach(compositionTypeSchema => {
        if (isReference(compositionTypeSchema)) {
          const refModelInfo = resolveReferenceModelInfo(compositionTypeSchema);
          addImportModelInfo(
            modelInfo,
            sourceFile,
            this.context.outputDir,
            refModelInfo,
          );
          interfaceDeclaration.addExtends(refModelInfo.name);
          return;
        }
        this.processInterface(
          sourceFile,
          modelInfo,
          compositionTypeSchema,
          interfaceDeclaration,
        );
      });
    }
    return interfaceDeclaration;
  }

  private processObject(
    sourceFile: SourceFile,
    modelInfo: ModelInfo,
    schema: Schema,
  ) {
    const interfaceDeclaration = sourceFile.addInterface({
      name: modelInfo.name,
      isExported: true,
    });
    return this.processInterface(
      sourceFile,
      modelInfo,
      schema,
      interfaceDeclaration,
    );
  }

  private processInterface(
    sourceFile: SourceFile,
    modelInfo: ModelInfo,
    schema: Schema,
    interfaceDeclaration: InterfaceDeclaration,
  ) {
    for (const [propName, propSchema] of Object.entries(schema.properties!)) {
      const propType: string = this.resolvePropertyType(
        modelInfo,
        sourceFile,
        propName,
        propSchema,
      );
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
    return interfaceDeclaration;
  }

  private resolvePropertyType(
    currentModelInfo: ModelInfo,
    sourceFile: SourceFile,
    propName: string,
    propSchema: Schema | Reference,
  ): string {
    if (isReference(propSchema)) {
      const refModelInfo = resolveReferenceModelInfo(propSchema);
      addImportModelInfo(
        currentModelInfo,
        sourceFile,
        this.context.outputDir,
        refModelInfo,
      );
      return refModelInfo.name;
    }
    if (propSchema.const) {
      return `'${propSchema.const}'`;
    }
    if (isArray(propSchema)) {
      const itemsType = this.resolvePropertyType(
        currentModelInfo,
        sourceFile,
        propName,
        propSchema.items!,
      );
      return toArrayType(itemsType);
    }
    if (propSchema.type && isPrimitive(propSchema.type)) {
      return resolvePrimitiveType(propSchema.type!);
    }
    if (isComposition(propSchema)) {
      return this.resolvePropertyCompositionType(
        currentModelInfo,
        sourceFile,
        propSchema,
      );
    }
    /**
     * handle object
     */
    if (propSchema.type === 'object' && propSchema.properties) {
      const propModelInfo: ModelInfo = {
        path: currentModelInfo.path,
        name: `${currentModelInfo.name}${pascalCase(propName)}`,
      };
      const interfaceDeclaration = this.processObject(
        sourceFile,
        propModelInfo,
        propSchema,
      );
      addSchemaJSDoc(interfaceDeclaration, propSchema);
      return propModelInfo.name;
    }
    return 'any';
  }

  private resolvePropertyCompositionType(
    currentModelInfo: ModelInfo,
    sourceFile: SourceFile,
    schema: CompositionSchema,
  ): string {
    const compositionTypes = schema.anyOf || schema.oneOf || schema.allOf;
    const types: Set<string> = new Set<string>();
    compositionTypes!.forEach(compositionTypeSchema => {
      if (isReference(compositionTypeSchema)) {
        const refModelInfo = resolveReferenceModelInfo(compositionTypeSchema);
        addImportModelInfo(
          currentModelInfo,
          sourceFile,
          this.context.outputDir,
          refModelInfo,
        );
        types.add(refModelInfo.name);
        return;
      }
      types.add(resolvePrimitiveType(compositionTypeSchema.type ?? 'string'));
    });
    const separator = isUnion(schema) ? '|' : '&';
    return Array.from(types).join(separator);
  }
}
