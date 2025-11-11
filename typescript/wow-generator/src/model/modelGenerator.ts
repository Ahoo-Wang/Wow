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

import { Schema } from '@ahoo-wang/fetcher-openapi';
import { SourceFile } from 'ts-morph';

import { GenerateContext, Generator } from '../generateContext';
import { getModelFileName, KeySchema, pascalCase } from '../utils';
import {
  ModelInfo,
  resolveContextDeclarationName,
  resolveModelInfo,
} from './modelInfo';
import { TypeGenerator } from './typeGenerator';

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
  constructor(public readonly context: GenerateContext) {}

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
    this.context.logger.progress(
      `Generating models for ${keySchemas.length} schemas`,
    );
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

  private filterSchemas(
    schemas: Record<string, Schema>,
    aggregatedTypeNames: Set<string>,
  ): KeySchema[] {
    return Object.entries(schemas)
      .map(([schemaKey, schema]) => ({
        key: schemaKey,
        schema,
      }))
      .filter(
        keySchema => !this.isWowSchema(keySchema.key, aggregatedTypeNames),
      );
  }

  private isWowSchema(
    schemaKey: string,
    stateAggregatedTypeNames: Set<string>,
  ): boolean {
    if (
      schemaKey !== 'wow.api.query.PagedList' &&
      schemaKey.startsWith('wow.api.query.') &&
      schemaKey.endsWith('PagedList')
    ) {
      return false;
    }

    if (
      schemaKey.startsWith('wow.') ||
      schemaKey.endsWith('AggregatedCondition') ||
      schemaKey.endsWith('AggregatedDomainEventStream') ||
      schemaKey.endsWith('AggregatedDomainEventStreamPagedList') ||
      schemaKey.endsWith(
        'AggregatedDomainEventStreamServerSentEventNonNullData',
      ) ||
      schemaKey.endsWith('AggregatedListQuery') ||
      schemaKey.endsWith('AggregatedPagedQuery') ||
      schemaKey.endsWith('AggregatedSingleQuery')
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
    const typeNames = new Set<string>();
    for (const [boundedContext, aggregates] of this.context.contextAggregates) {
      this.generateBoundedContext(boundedContext);
      for (const aggregate of aggregates) {
        this.aggregatedSchemaSuffix.forEach(suffix => {
          const modelInfo = resolveModelInfo(aggregate.state.key);
          const typeName = pascalCase(modelInfo.name) + suffix;
          typeNames.add(typeName);
        });
      }
    }
    return typeNames;
  }

  /**
   * Generates a model for a specific schema key.
   * Processes enums, objects, unions, and type aliases in order.
   *
   *
   * @remarks
   * The generation process follows this order:
   * 1. Enum processing
   * 2. Object processing
   * 3. Union processing
   * 4. Type alias processing
   */
  generateKeyedSchema(keySchema: KeySchema) {
    const modelInfo = resolveModelInfo(keySchema.key);
    const sourceFile = this.getOrCreateSourceFile(modelInfo);
    const typeGenerator = new TypeGenerator(
      modelInfo,
      sourceFile,
      keySchema,
      this.context.outputDir,
    );
    typeGenerator.generate();
  }

  generateBoundedContext(contextAlias: string) {
    const filePath = `${contextAlias}/boundedContext.ts`;
    this.context.logger.info(`Creating bounded context file: ${filePath}`);
    const file = this.context.getOrCreateSourceFile(filePath);
    const contextName = resolveContextDeclarationName(contextAlias);
    file.addStatements(`export const ${contextName} = '${contextAlias}';`);
  }
}
