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

import { SourceFile, VariableDeclarationKind } from 'ts-morph';
import { GenerateContext } from '../types';
import { BaseCodeGenerator } from '../baseCodeGenerator';
import { AggregateDefinition, TagAliasAggregate } from '../aggregate';
import { IMPORT_WOW_PATH, ModelInfo, resolveModelInfo } from '../model';
import { createClientFilePath, inferPathSpecType } from './utils';
import { addImportRefModel, camelCase } from '../utils';

/**
 * Generates TypeScript query client classes for aggregates.
 * Creates query clients that can perform state queries and event streaming.
 */
export class QueryClientGenerator extends BaseCodeGenerator {
  /**
   * Creates a new QueryClientGenerator instance.
   * @param context - The generation context containing OpenAPI spec and project details
   */
  constructor(context: GenerateContext) {
    super(context);
  }

  /**
   * Generates query client classes for all aggregates.
   */
  generate(): void {
    const totalAggregates = Array.from(this.contextAggregates.values()).flat()
      .length;
    this.logger.progress(
      `Generating query clients for ${totalAggregates} aggregates`,
    );
    for (const [, aggregates] of this.contextAggregates) {
      aggregates.forEach(aggregateDefinition => {
        this.logger.progress(
          `Processing query client for aggregate: ${aggregateDefinition.aggregate.aggregateName}`,
        );
        this.processQueryClient(aggregateDefinition);
      });
    }
    this.logger.success('Query client generation completed');
  }

  /**
   * Creates or retrieves a source file for client generation.
   * @param aggregate - The aggregate metadata
   * @param fileName - The name of the client file
   * @returns The source file for the client
   */
  createClientFilePath(
    aggregate: TagAliasAggregate,
    fileName: string,
  ): SourceFile {
    return createClientFilePath(
      this.project,
      this.outputDir,
      aggregate,
      fileName,
    );
  }

  /**
   * Processes and generates query client classes for an aggregate.
   * @param aggregate - The aggregate definition
   */
  processQueryClient(aggregate: AggregateDefinition) {
    const queryClientFile = this.createClientFilePath(
      aggregate.aggregate,
      'queryClient',
    );
    queryClientFile.addImportDeclaration({
      moduleSpecifier: IMPORT_WOW_PATH,
      namedImports: [
        'QueryClientFactory',
        'QueryClientOptions',
        'ResourceAttributionPathSpec',
      ],
    });
    const defaultClientOptionsName = 'DEFAULT_QUERY_CLIENT_OPTIONS';
    queryClientFile.addVariableStatement({
      declarationKind: VariableDeclarationKind.Const,
      declarations: [
        {
          name: defaultClientOptionsName,
          type: 'QueryClientOptions',
          initializer: `{
        contextAlias: '${aggregate.aggregate.contextAlias}',
        aggregateName: '${aggregate.aggregate.aggregateName}',
        resourceAttribution: ${inferPathSpecType(aggregate)},
      }`,
        },
      ],
      isExported: false,
    });
    const eventModelInfos: ModelInfo[] = [];
    for (const event of aggregate.events.values()) {
      const eventModelInfo = resolveModelInfo(event.schema.key);
      addImportRefModel(queryClientFile, this.outputDir, eventModelInfo);
      eventModelInfos.push(eventModelInfo);
    }
    const domainEventTypesName = 'DOMAIN_EVENT_TYPES';
    queryClientFile.addTypeAlias({
      name: domainEventTypesName,
      type: eventModelInfos.map(it => it.name).join(' | '),
    });
    const clientFactoryName = `${camelCase(aggregate.aggregate.aggregateName)}QueryClientFactory`;
    const stateModelInfo = resolveModelInfo(aggregate.state.key);
    const fieldsModelInfo = resolveModelInfo(aggregate.fields.key);
    addImportRefModel(queryClientFile, this.outputDir, stateModelInfo);
    addImportRefModel(queryClientFile, this.outputDir, fieldsModelInfo);
    queryClientFile.addVariableStatement({
      declarationKind: VariableDeclarationKind.Const,
      declarations: [
        {
          name: clientFactoryName,
          initializer: `new QueryClientFactory<${stateModelInfo.name}, ${fieldsModelInfo.name} | string, ${domainEventTypesName}>(${defaultClientOptionsName})`,
        },
      ],
      isExported: true,
    });
  }
}
