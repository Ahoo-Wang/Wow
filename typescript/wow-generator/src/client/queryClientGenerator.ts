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
import { AggregateDefinition, TagAliasAggregate } from '../aggregate';
import { GenerateContext, Generator } from '../generateContext';
import { IMPORT_WOW_PATH, ModelInfo, resolveModelInfo } from '../model';
import { addImportRefModel, camelCase } from '../utils';
import { createClientFilePath, inferPathSpecType, resolveClassName } from './utils';

/**
 * Generates TypeScript query client classes for aggregates.
 * Creates query clients that can perform state queries and event streaming.
 */
export class QueryClientGenerator implements Generator {
  private readonly domainEventTypeSuffix = 'DomainEventType';

  /**
   * Creates a new QueryClientGenerator instance.
   * @param context - The generation context containing OpenAPI spec and project details
   */
  constructor(public readonly context: GenerateContext) {
  }

  /**
   * Generates query client classes for all aggregates.
   */
  generate(): void {
    const totalAggregates = Array.from(this.context.contextAggregates.values()).reduce(
      (sum, set) => sum + set.size,
      0,
    );
    this.context.logger.info('--- Generating Query Clients ---');
    this.context.logger.progress(
      `Generating query clients for ${totalAggregates} aggregates`,
    );
    let currentIndex = 0;
    for (const [, aggregates] of this.context.contextAggregates) {
      aggregates.forEach(aggregateDefinition => {
        currentIndex++;
        this.context.logger.progressWithCount(
          currentIndex,
          totalAggregates,
          `Processing query client for aggregate: ${aggregateDefinition.aggregate.aggregateName}`,
        );
        this.processQueryClient(aggregateDefinition);
      });
    }
    this.context.logger.success('Query client generation completed');
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
      this.context.project,
      this.context.outputDir,
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
    this.context.logger.info(
      `Processing query client for aggregate: ${aggregate.aggregate.aggregateName} in context: ${aggregate.aggregate.contextAlias}`,
    );

    this.context.logger.info(
      `Adding imports from ${IMPORT_WOW_PATH}: QueryClientFactory, QueryClientOptions, ResourceAttributionPathSpec`,
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
    this.context.logger.info(
      `Creating default query client options: ${defaultClientOptionsName}`,
    );
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
    this.context.logger.info(
      `Processing ${aggregate.events.size} domain events for aggregate: ${aggregate.aggregate.aggregateName}`,
    );
    for (const event of aggregate.events.values()) {
      const eventModelInfo = resolveModelInfo(event.schema.key);
      this.context.logger.info(
        `Adding import for event model: ${eventModelInfo.name} from path: ${eventModelInfo.path}`,
      );
      addImportRefModel(queryClientFile, this.context.outputDir, eventModelInfo);
      eventModelInfos.push(eventModelInfo);
    }
    const aggregateDomainEventType = resolveClassName(aggregate.aggregate, this.domainEventTypeSuffix);
    const eventTypeUnion = eventModelInfos.map(it => it.name).join(' | ');
    this.context.logger.info(
      `Creating domain event types union: ${aggregateDomainEventType} = ${eventTypeUnion}`,
    );
    queryClientFile.addTypeAlias({
      isExported: true,
      name: aggregateDomainEventType,
      type: eventTypeUnion,
    });

    const clientFactoryName = `${camelCase(aggregate.aggregate.aggregateName)}QueryClientFactory`;
    const stateModelInfo = resolveModelInfo(aggregate.state.key);
    const fieldsModelInfo = resolveModelInfo(aggregate.fields.key);

    this.context.logger.info(
      `Adding import for state model: ${stateModelInfo.name} from path: ${stateModelInfo.path}`,
    );
    addImportRefModel(queryClientFile, this.context.outputDir, stateModelInfo);
    this.context.logger.info(
      `Adding import for fields model: ${fieldsModelInfo.name} from path: ${fieldsModelInfo.path}`,
    );
    addImportRefModel(queryClientFile, this.context.outputDir, fieldsModelInfo);

    this.context.logger.info(`Creating query client factory: ${clientFactoryName}`);
    queryClientFile.addVariableStatement({
      declarationKind: VariableDeclarationKind.Const,
      declarations: [
        {
          name: clientFactoryName,
          initializer: `new QueryClientFactory<${stateModelInfo.name}, ${fieldsModelInfo.name} | string, ${aggregateDomainEventType}>(${defaultClientOptionsName})`,
        },
      ],
      isExported: true,
    });

    this.context.logger.success(
      `Query client generation completed for aggregate: ${aggregate.aggregate.aggregateName}`,
    );
  }
}
