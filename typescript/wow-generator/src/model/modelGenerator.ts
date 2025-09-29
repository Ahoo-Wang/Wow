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

import { Schema, Reference } from '@ahoo-wang/fetcher-openapi';
import { InterfaceDeclaration, JSDocableNode, SourceFile } from 'ts-morph';
import { GenerateContext } from '../types';
import { ModelInfo, resolveModelInfo } from './modelInfo';
import {
  addImportModelInfo,
  addJSDoc,
  CompositionSchema,
  extractComponentKey,
  getModelFileName,
  getOrCreateSourceFile,
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
import { BaseCodeGenerator } from '../baseCodeGenerator';

/**
 * Generates TypeScript models from OpenAPI schemas.
 * Handles enum, object, union, and type alias generation.
 *
 * @property project - The ts-morph project instance
 * @property openAPI - The OpenAPI specification
 * @property outputDir - The output directory for generated files
 * @property contextAggregates - Map of aggregate definitions
 */
export class ModelGenerator extends BaseCodeGenerator {
  constructor(context: GenerateContext) {
    super(context);
  }

  private getOrCreateSourceFile(modelInfo: ModelInfo): SourceFile {
    const fileName = getModelFileName(modelInfo);
    return getOrCreateSourceFile(this.project, this.outputDir, fileName);
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
    const schemas = this.openAPI.components?.schemas;
    if (!schemas) {
      this.logger.info('No schemas found in OpenAPI specification');
      return;
    }
    const keySchemas = this.filterSchemas(schemas);
    this.logger.progress(`Generating models for ${keySchemas.length} schemas`);
    keySchemas.forEach((keySchema, index) => {
      this.logger.progressWithCount(
        index + 1,
        keySchemas.length,
        `Processing schema: ${keySchema.key}`,
        2,
      );
      this.generateKeyedSchema(keySchema);
    });
    this.logger.success('Model generation completed');
  }

  private filterSchemas(schemas: Record<string, Schema>): KeySchema[] {
    return Object.entries(schemas)
      .map(([schemaKey, schema]) => ({
        key: schemaKey,
        schema,
      }))
      .filter(keySchema => !this.isWowSchema(keySchema.key));
  }

  private isWowSchema(schemaKey: string): boolean {
    return schemaKey.startsWith('wow.')
      || this.isWowAggregatedSchema(schemaKey)
      ;
  }

  private isAggregatePrefix(schemaKey: string): boolean {
    const modelInfo = resolveModelInfo(schemaKey);
    for (let aggregates of this.contextAggregates.values()) {
      for (let aggregate of aggregates) {
        if (modelInfo.name.startsWith(pascalCase(aggregate.aggregate.aggregateName))) {
          return true;
        }
      }
    }
    return false;
  }

  private isWowAggregatedSchema(schemaKey: string): boolean {
    if (!(
      schemaKey.endsWith('AggregatedCondition')
      || schemaKey.endsWith('AggregatedDomainEventStream')
      || schemaKey.endsWith('AggregatedDomainEventStreamPagedList')
      || schemaKey.endsWith('ServerSentEventNonNullData')
      || schemaKey.endsWith('AggregatedListQuery')
      || schemaKey.endsWith('AggregatedPagedQuery')
      || schemaKey.endsWith('AggregatedSingleQuery')
      || schemaKey.endsWith('MaterializedSnapshot')
      || schemaKey.endsWith('PagedList')
      || schemaKey.endsWith('Snapshot')
      || schemaKey.endsWith('StateEvent')
    )
    ) {
      return false;
    }
    return this.isAggregatePrefix(schemaKey);
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
      addJSDoc(node, schema.title, schema.description);
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
          const refModelInfo = resolveModelInfo(
            extractComponentKey(compositionTypeSchema),
          );
          addImportModelInfo(
            modelInfo,
            sourceFile,
            this.outputDir,
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
        addJSDoc(propertySignature, propSchema.title, propSchema.description);
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
      const refModelInfo = resolveModelInfo(extractComponentKey(propSchema));
      addImportModelInfo(
        currentModelInfo,
        sourceFile,
        this.outputDir,
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
      addJSDoc(interfaceDeclaration, propSchema.title, propSchema.description);
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
        const refModelInfo = resolveModelInfo(
          extractComponentKey(compositionTypeSchema),
        );
        addImportModelInfo(
          currentModelInfo,
          sourceFile,
          this.outputDir,
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
